package de.abilas.gxtube.twitch

import android.util.Log
import de.abilas.gxtube.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Ein Stück einer Chat-Nachricht: Text oder Emote (Bild). */
sealed interface ChatPart {
    data class Text(val text: String) : ChatPart
    data class Emote(val name: String, val url: String) : ChatPart
}

data class ChatMessage(
    val id: String,
    val login: String = "",
    val name: String = "",
    val color: String? = null,
    val badges: List<String> = emptyList(),
    val parts: List<ChatPart>,
    val action: Boolean = false,
    val system: Boolean = false,
    val highlight: Boolean = false,
    val mention: Boolean = false,
    val self: Boolean = false,
    val deleted: Boolean = false,
)

/**
 * Twitch-Chat über IRC (WebSocket), wie twitch.tv. Ohne Anmeldung nur lesen, mit Twitch-Konto
 * auch schreiben. Emotes von Twitch plus 7TV, BTTV und FFZ.
 */
object TwitchChat {
    private const val TAG = "TwitchChat"
    private const val URL = "wss://irc-ws.chat.twitch.tv:443"
    private const val MAX = 200

    enum class State { CLOSED, CONNECTING, OPEN }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _state = MutableStateFlow(State.CLOSED)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _canSend = MutableStateFlow(false)
    val canSend: StateFlow<Boolean> = _canSend.asStateFlow()

    private var ws: WebSocket? = null
    private var channel: String? = null
    private var auth: Pair<String, String>? = null
    private var backoff = 1000L
    private var reconnectJob: Job? = null
    @Volatile private var emotes: Map<String, String> = emptyMap()
    private var roomId: String? = null
    private var me: Triple<String, String?, List<String>>? = null // Name, Farbe, Badges

    /** Kanal betreten (bei gleichem Kanal und Zugang passiert nichts). */
    fun join(login: String) {
        val l = login.lowercase()
        val a = TwitchAccount.chatAuth()
        if (l == channel && a == auth && ws != null) return
        close()
        channel = l
        auth = a
        backoff = 1000
        _messages.value = listOf(system("Willkommen im Chat von $l!"))
        connect()
    }

    fun close() {
        reconnectJob?.cancel()
        val w = ws
        ws = null
        channel = null
        roomId = null
        emotes = emptyMap()
        w?.close(1000, null)
        _state.value = State.CLOSED
        _canSend.value = false
    }

    private fun connect() {
        val ch = channel ?: return
        _state.value = State.CONNECTING
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                val a = auth
                if (a != null) {
                    webSocket.send("PASS oauth:${a.second}")
                    webSocket.send("NICK ${a.first}")
                } else webSocket.send("NICK justinfan${10000 + Random.nextInt(80000)}")
                webSocket.send("JOIN #$ch")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                text.split("\r\n").filter { it.isNotEmpty() }.forEach { line(webSocket, it) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        }
        ws = Http.client.newWebSocket(Request.Builder().url(URL).build(), listener)
    }

    private fun lost(webSocket: WebSocket) {
        if (webSocket !== ws) return
        ws = null
        _state.value = State.CLOSED
        _canSend.value = false
        // Neu verbinden, mit wachsender Pause
        reconnectJob = scope.launch {
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000)
            if (channel != null && ws == null) connect()
        }
    }

    private fun line(webSocket: WebSocket, raw: String) {
        val m = Irc.parse(raw)
        when (m.command) {
            "PING" -> webSocket.send("PONG :${m.text ?: "tmi.twitch.tv"}")
            "RECONNECT" -> webSocket.close(1000, null)
            "JOIN" -> {
                backoff = 1000
                _state.value = State.OPEN
                _canSend.value = auth != null
            }
            "ROOMSTATE" -> m.tags["room-id"]?.takeIf { it != roomId }?.let { room ->
                roomId = room
                scope.launch { runCatching { ThirdPartyEmotes.load(room) }.onSuccess { if (roomId == room) emotes = it } }
            }
            "USERSTATE", "GLOBALUSERSTATE" -> me = Triple(m.tags["display-name"] ?: auth?.first.orEmpty(), m.tags["color"]?.takeIf { it.isNotEmpty() }, Irc.badges(m.tags["badges"]))
            "PRIVMSG" -> add(message(m))
            "USERNOTICE" -> {
                m.tags["system-msg"]?.let { add(system(it)) }
                if (!m.text.isNullOrEmpty()) add(message(m).copy(highlight = true))
            }
            "CLEARCHAT" -> {
                val user = m.text?.lowercase()
                if (user == null) _messages.value = listOf(system("Der Chat wurde von einem Moderator geleert."))
                else _messages.update { list -> list.map { if (it.login == user) it.copy(deleted = true) else it } }
            }
            "CLEARMSG" -> m.tags["target-msg-id"]?.let { id -> _messages.update { list -> list.map { if (it.id == id) it.copy(deleted = true) else it } } }
            "NOTICE" -> {
                val text = m.text.orEmpty()
                if (Regex("authentication failed|improperly formatted auth|invalid nick", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    add(system("Twitch hat die Anmeldung für den Chat abgelehnt – du liest weiter mit. Melde dich in den Einstellungen neu bei Twitch an."))
                    TwitchAccount.markExpired()
                    val ch = channel
                    scope.launch { withContext(Dispatchers.Main) { if (ch != null) join(ch) } }
                } else if (text.isNotEmpty()) add(system(text))
            }
        }
    }

    private fun system(text: String) = ChatMessage(id = "sys-${System.nanoTime()}", parts = listOf(ChatPart.Text(text)), system = true)

    private fun message(m: Irc.Line): ChatMessage {
        var text = m.text.orEmpty()
        val action = Regex("^\u0001ACTION (.*)\u0001$").find(text)
        if (action != null) text = action.groupValues[1]
        val login = m.nick.orEmpty()
        val mine = auth?.first
        return ChatMessage(
            id = m.tags["id"] ?: "m-${System.nanoTime()}",
            login = login,
            name = m.tags["display-name"]?.takeIf { it.isNotEmpty() } ?: login,
            color = m.tags["color"]?.takeIf { it.isNotEmpty() },
            badges = Irc.badges(m.tags["badges"]),
            parts = Irc.parts(text, m.tags["emotes"], emotes),
            action = action != null,
            mention = mine != null && Regex("(^|\\W)@?${Regex.escape(mine)}(\\W|$)", RegexOption.IGNORE_CASE).containsMatchIn(text),
        )
    }

    private fun add(msg: ChatMessage) = _messages.update { list -> if (list.any { it.id == msg.id }) list else (list + msg).takeLast(MAX) }

    /** Nachricht schreiben (Twitch schickt die eigene nicht zurück – deshalb gleich anzeigen). */
    fun send(text: String): Boolean {
        val msg = text.replace(Regex("[\r\n]+"), " ").trim().take(500)
        val w = ws
        val a = auth
        if (msg.isEmpty() || w == null || a == null || !_canSend.value) return false
        w.send("PRIVMSG #$channel :$msg")
        val action = Regex("^/me (.*)$").find(msg)
        val (name, color, badges) = me ?: Triple(a.first, null, emptyList())
        add(ChatMessage("self-${System.nanoTime()}", a.first, name, color, badges, Irc.parts(action?.groupValues?.get(1) ?: msg, null, emotes), action = action != null, self = true))
        return true
    }
}

/** IRC-Zeilen von Twitch lesen. */
object Irc {
    data class Line(val tags: Map<String, String>, val prefix: String?, val nick: String?, val command: String, val params: List<String>, val text: String?)

    private fun unescape(v: String) = Regex("\\\\(.)").replace(v) { m ->
        when (val c = m.groupValues[1]) {
            "s" -> " "
            ":" -> ";"
            "\\" -> "\\"
            "r" -> "\r"
            "n" -> "\n"
            else -> c
        }
    }

    fun parse(line: String): Line {
        var rest = line
        val tags = HashMap<String, String>()
        if (rest.startsWith("@")) {
            val end = rest.indexOf(' ').let { if (it < 0) rest.length else it }
            for (part in rest.substring(1, end).split(';')) {
                val eq = part.indexOf('=')
                if (eq < 0) tags[part] = "" else tags[part.substring(0, eq)] = unescape(part.substring(eq + 1))
            }
            rest = rest.substring((end + 1).coerceAtMost(rest.length))
        }
        var prefix: String? = null
        if (rest.startsWith(":")) {
            val end = rest.indexOf(' ').let { if (it < 0) rest.length else it }
            prefix = rest.substring(1, end)
            rest = rest.substring((end + 1).coerceAtMost(rest.length))
        }
        var text: String? = null
        val colon = rest.indexOf(" :")
        if (colon >= 0) {
            text = rest.substring(colon + 2)
            rest = rest.substring(0, colon)
        } else if (rest.startsWith(":")) {
            text = rest.substring(1)
            rest = ""
        }
        val words = rest.split(' ').filter { it.isNotEmpty() }
        val nick = prefix?.takeIf { '!' in it }?.substringBefore('!')
        return Line(tags, prefix, nick, words.firstOrNull().orEmpty(), words.drop(1), text)
    }

    data class EmoteAt(val id: String, val start: Int, val end: Int)

    /** "25:0-4,12-16/1902:6-10" → Positionen (Code Points). */
    fun emoteTag(tag: String?): List<EmoteAt> {
        if (tag.isNullOrEmpty()) return emptyList()
        val out = ArrayList<EmoteAt>()
        for (part in tag.split('/')) {
            val id = part.substringBefore(':', "")
            val ranges = part.substringAfter(':', "")
            if (id.isEmpty() || ranges.isEmpty()) continue
            for (r in ranges.split(',')) {
                val a = r.substringBefore('-').toIntOrNull() ?: continue
                val b = r.substringAfter('-').toIntOrNull() ?: continue
                if (b >= a) out += EmoteAt(id, a, b)
            }
        }
        return out.sortedBy { it.start }
    }

    fun badges(tag: String?): List<String> = tag.orEmpty().split(',').map { it.substringBefore('/') }.filter { it.isNotEmpty() }

    /** Nachricht in Text und Emotes zerlegen. [extra]: Name → Bild (7TV/BTTV/FFZ). */
    fun parts(text: String, emoteTag: String?, extra: Map<String, String>): List<ChatPart> {
        val cps = text.codePoints().toArray()
        fun sub(a: Int, b: Int) = String(cps, a, b - a)
        val out = ArrayList<ChatPart>()
        fun pushText(s: String) {
            if (s.isEmpty()) return
            if (extra.isEmpty()) {
                out += ChatPart.Text(s)
                return
            }
            for (piece in Regex("(?<=\\s)|(?=\\s)").split(s)) {
                if (piece.isEmpty()) continue
                val url = extra[piece]
                val last = out.lastOrNull()
                when {
                    url != null -> out += ChatPart.Emote(piece, url)
                    last is ChatPart.Text -> out[out.lastIndex] = ChatPart.Text(last.text + piece)
                    else -> out += ChatPart.Text(piece)
                }
            }
        }
        var pos = 0
        for (e in emoteTag(emoteTag)) {
            if (e.start < pos || e.end >= cps.size) continue
            pushText(sub(pos, e.start))
            out += ChatPart.Emote(sub(e.start, e.end + 1), "https://static-cdn.jtvnw.net/emoticons/v2/${e.id}/default/dark/2.0")
            pos = e.end + 1
        }
        pushText(sub(pos, cps.size))
        return out
    }
}

/** Emotes von 7TV, BTTV und FFZ (global und für den Kanal). */
object ThirdPartyEmotes {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Map<String, String>>()

    private fun get(url: String): JsonElement? = runCatching {
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) json.parseToJsonElement(r.body.string()) else null
        }
    }.getOrNull()

    private val JsonElement?.obj get() = this as? JsonObject
    private val JsonElement?.arr get() = this as? JsonArray
    private val JsonElement?.str get() = (this as? JsonPrimitive)?.contentOrNull

    private fun seventv(map: MutableMap<String, String>, emotes: JsonElement?) {
        emotes.arr?.forEach { e ->
            val name = e.obj?.get("name").str ?: return@forEach
            val host = e.obj?.get("data").obj?.get("host").obj ?: return@forEach
            val base = host["url"].str ?: return@forEach
            val files = host["files"].arr?.mapNotNull { it.obj?.get("name").str }.orEmpty()
            val file = files.firstOrNull { it == "2x.webp" } ?: files.firstOrNull() ?: return@forEach
            map[name] = "https:$base/$file"
        }
    }

    private fun bttv(map: MutableMap<String, String>, emotes: JsonElement?) {
        emotes.arr?.forEach { e ->
            val code = e.obj?.get("code").str ?: return@forEach
            val id = e.obj?.get("id").str ?: return@forEach
            map[code] = "https://cdn.betterttv.net/emote/$id/2x"
        }
    }

    private fun ffz(map: MutableMap<String, String>, sets: JsonElement?) {
        sets.obj?.values?.forEach { set ->
            set.obj?.get("emoticons").arr?.forEach { e ->
                val name = e.obj?.get("name").str ?: return@forEach
                val urls = e.obj?.get("urls").obj ?: return@forEach
                val url = urls["2"].str ?: urls["1"].str ?: return@forEach
                map[name] = if (url.startsWith("//")) "https:$url" else url
            }
        }
    }

    private fun global(): Map<String, String> = cache.getOrPut("global") {
        val map = HashMap<String, String>()
        ffz(map, get("https://api.frankerfacez.com/v1/set/global").obj?.get("sets"))
        bttv(map, get("https://api.betterttv.net/3/cached/emotes/global"))
        seventv(map, get("https://7tv.io/v3/emote-sets/global").obj?.get("emotes"))
        map
    }

    fun load(roomId: String): Map<String, String> {
        val room = cache.getOrPut(roomId) {
            val map = HashMap<String, String>()
            ffz(map, get("https://api.frankerfacez.com/v1/room/id/$roomId").obj?.get("sets"))
            val b = get("https://api.betterttv.net/3/cached/users/twitch/$roomId").obj
            bttv(map, JsonArray(b?.get("channelEmotes").arr.orEmpty() + b?.get("sharedEmotes").arr.orEmpty()))
            seventv(map, get("https://7tv.io/v3/users/twitch/$roomId").obj?.get("emote_set").obj?.get("emotes"))
            map
        }
        return global() + room
    }
}
