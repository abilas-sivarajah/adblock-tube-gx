package de.abilas.gxtube.twitch

import android.util.Log
import de.abilas.gxtube.data.Http
import de.abilas.gxtube.data.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/** Ein Stück einer Chat-Nachricht: Text, Emote (Bild), Erwähnung (@name) oder Link. */
sealed interface ChatPart {
    data class Text(val text: String) : ChatPart
    data class Emote(val name: String, val url: String) : ChatPart
    data class Mention(val name: String) : ChatPart
    data class Link(val url: String) : ChatPart
}

/** Abzeichen vor dem Namen, z. B. subscriber/12. */
data class ChatBadge(val set: String, val version: String = "1")

/** Worauf eine Nachricht antwortet. */
data class ChatReply(val id: String, val login: String, val name: String, val text: String)

data class ChatMessage(
    val id: String,
    val login: String = "",
    val name: String = "",
    val color: String? = null,
    val badges: List<ChatBadge> = emptyList(),
    val parts: List<ChatPart>,
    /** Reiner Text (zum Kopieren und Antworten). */
    val text: String = "",
    val action: Boolean = false,
    val system: Boolean = false,
    val highlight: Boolean = false,
    val mention: Boolean = false,
    val self: Boolean = false,
    val deleted: Boolean = false,
    val reply: ChatReply? = null,
    /** Kleine Überschrift über der Nachricht ("Erste Nachricht", "Ankündigung", "Abo" …). */
    val label: String? = null,
    /** Farbe des Rands bei Ankündigungen/Abos usw. (ARGB). */
    val accent: Long? = null,
    val time: Long = System.currentTimeMillis(),
    /** Aus dem Verlauf (vor dem Betreten geschrieben). */
    val history: Boolean = false,
) {
    val canReply: Boolean get() = !system && login.isNotEmpty() && !id.startsWith("self-") && !id.startsWith("m-")
}

/** Einstellungen des Chats (ROOMSTATE). */
data class ChatRoom(
    /** Langsamer Modus: Sekunden zwischen zwei Nachrichten (0 = aus). */
    val slow: Int = 0,
    /** Nur Follower: -1 = aus, sonst Minuten, die man schon folgen muss. */
    val followersOnly: Int = -1,
    val subsOnly: Boolean = false,
    val emoteOnly: Boolean = false,
    val uniqueChat: Boolean = false,
)

/** Man selbst im Chat (aus USERSTATE). */
data class ChatMe(val login: String, val userId: String?, val name: String, val color: String?, val badges: List<ChatBadge>, val broadcaster: Boolean) {
    val isMod: Boolean get() = broadcaster || badges.any { it.set == "moderator" }
    val isVip: Boolean get() = badges.any { it.set == "vip" }
}

/** Emotes zum Auswählen und Ergänzen: Name → Bild. */
data class ChatEmotes(
    val twitch: Map<String, String> = emptyMap(),
    val channel: Map<String, String> = emptyMap(),
    val global: Map<String, String> = emptyMap(),
) {
    /** Alle, die im Text als Emote erkannt werden (ohne Twitch – die markiert Twitch selbst). */
    val thirdParty: Map<String, String> by lazy { global + channel }
    val all: Map<String, String> by lazy { twitch + global + channel }
}

/**
 * Twitch-Chat über IRC (WebSocket), wie twitch.tv. Ohne Anmeldung nur lesen, mit Twitch-Konto
 * auch schreiben, antworten und /-Befehle. Emotes von Twitch plus 7TV, BTTV und FFZ, Abzeichen
 * als Bilder, beim Betreten die letzten Nachrichten.
 *
 * Die Verbindung bleibt kurz bestehen, wenn die Chat-Ansicht verschwindet (z. B. beim Drehen ins
 * Querformat) – so ist der Chat beim Zurückdrehen sofort wieder da.
 */
object TwitchChat {
    private const val TAG = "TwitchChat"
    private const val URL = "wss://irc-ws.chat.twitch.tv:443"
    private const val MAX = 300
    private const val LINGER_MS = 30_000L

    enum class State { CLOSED, CONNECTING, OPEN }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _state = MutableStateFlow(State.CLOSED)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _canSend = MutableStateFlow(false)
    val canSend: StateFlow<Boolean> = _canSend.asStateFlow()
    private val _room = MutableStateFlow(ChatRoom())
    val room: StateFlow<ChatRoom> = _room.asStateFlow()
    private val _me = MutableStateFlow<ChatMe?>(null)
    val me: StateFlow<ChatMe?> = _me.asStateFlow()
    private val _emotes = MutableStateFlow(ChatEmotes())
    val emotes: StateFlow<ChatEmotes> = _emotes.asStateFlow()
    private val _badges = MutableStateFlow<Map<String, String>>(emptyMap())
    /** "set/version" → Bild. */
    val badges: StateFlow<Map<String, String>> = _badges.asStateFlow()
    private val _lastSent = MutableStateFlow(0L)
    /** Wann man zuletzt geschrieben hat (für den langsamen Modus). */
    val lastSent: StateFlow<Long> = _lastSent.asStateFlow()
    private val _openUser = MutableSharedFlow<String>(extraBufferCapacity = 2)
    /** /user name → Profilkarte öffnen. */
    val openUser: SharedFlow<String> = _openUser.asSharedFlow()
    private val _channel = MutableStateFlow<String?>(null)
    val channelLogin: StateFlow<String?> = _channel.asStateFlow()

    private var ws: WebSocket? = null
    private var channel: String? = null
    private var auth: Pair<String, String>? = null
    private var backoff = 1000L
    private var reconnectJob: Job? = null
    private var lingerJob: Job? = null
    private var generation = 0
    @Volatile private var roomId: String? = null
    val currentRoomId: String? get() = roomId

    /** Wer zuletzt geschrieben hat (für @-Ergänzung): login → Anzeigename, neueste zuletzt. */
    private val chatters = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 800
    }

    /** Eigene, gerade gesendete Nachrichten, bis Twitch ihre richtige ID schickt (USERSTATE). */
    private val pendingSelf = ArrayDeque<String>()

    /** Kanal betreten (bei gleichem Kanal und Zugang bleibt alles, wie es ist). */
    fun join(login: String) {
        val l = login.lowercase()
        val a = TwitchAccount.chatAuth()
        synchronized(lock) {
            lingerJob?.cancel()
            lingerJob = null
            if (l == channel && a == auth && ws != null) return
            val sameChannel = l == channel || l == _channel.value
            disconnect()
            channel = l
            _channel.value = l
            auth = a
            backoff = 1000
            generation++
            if (!sameChannel) {
                _messages.value = listOf(system("Willkommen im Chat von $l!"))
                _room.value = ChatRoom()
                _emotes.value = ChatEmotes()
                _badges.value = emptyMap()
                _me.value = null
                roomId = null
                synchronized(chatters) { chatters.clear() }
            }
            connect()
            val gen = generation
            // Verlauf auch beim Wiederkommen laden: füllt die Lücke, solange man weg war
            scope.launch { loadExtras(l, gen, history = true) }
        }
    }

    /** Chat-Ansicht ist weg – Verbindung noch kurz halten (Drehen, Mini-Player), dann schließen. */
    fun release() {
        synchronized(lock) {
            lingerJob?.cancel()
            lingerJob = scope.launch {
                delay(LINGER_MS)
                close()
            }
        }
    }

    fun close() {
        synchronized(lock) {
            lingerJob?.cancel()
            lingerJob = null
            disconnect()
            channel = null
        }
    }

    private fun disconnect() {
        reconnectJob?.cancel()
        val w = ws
        ws = null
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
                text.split("\r\n").filter { it.isNotEmpty() }.forEach { runCatching { line(webSocket, it) }.onFailure { e -> Log.w(TAG, "Zeile: $it", e) } }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        }
        ws = Http.client.newWebSocket(Request.Builder().url(URL).build(), listener)
    }

    private fun lost(webSocket: WebSocket) {
        synchronized(lock) {
            if (webSocket !== ws) return
            ws = null
            _state.value = State.CLOSED
            _canSend.value = false
            // Neu verbinden, mit wachsender Pause
            reconnectJob = scope.launch {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
                synchronized(lock) { if (channel != null && ws == null) connect() }
            }
        }
    }

    /** Emotes, Abzeichen und (beim ersten Betreten) die letzten Nachrichten laden. */
    private suspend fun loadExtras(login: String, gen: Int, history: Boolean) {
        scope.launch {
            runCatching { TwitchBadges.load(login) }.onSuccess { if (gen == generation) _badges.value = it }
                .onFailure { Log.w(TAG, "Abzeichen nicht geladen", it) }
        }
        if (history && Library.settings.twitchChatHistory) {
            runCatching { RecentMessages.load(login) }.onSuccess { lines ->
                if (gen != generation || lines.isEmpty()) return@onSuccess
                // Für die Emotes im Verlauf: Raum-ID steht in jeder Zeile
                val room = lines.firstNotNullOfOrNull { Irc.parse(it).tags["room-id"] }
                if (room != null) ensureEmotes(room, gen)
                val old = lines.mapNotNull { raw -> historyMessage(Irc.parse(raw)) }
                if (gen != generation) return@onSuccess
                _messages.update { list ->
                    val known = list.mapTo(HashSet()) { it.id }
                    val add = old.filter { it.id !in known }
                    if (add.isEmpty()) list
                    else {
                        // "Willkommen …" bleibt oben, alles andere nach Zeit
                        val welcome = list.take(1).filter { it.system }
                        (welcome + (list.drop(welcome.size) + add).sortedBy { it.time }).takeLast(MAX)
                    }
                }
            }.onFailure { Log.w(TAG, "Verlauf nicht geladen", it) }
        }
    }

    private suspend fun ensureEmotes(room: String, gen: Int) {
        val needThirdParty = roomId != room || _emotes.value.thirdParty.isEmpty()
        roomId = room
        if (needThirdParty) {
            runCatching { ThirdPartyEmotes.load(room) }.onSuccess { (channelSet, globalSet) ->
                if (gen == generation) _emotes.update { it.copy(channel = channelSet, global = globalSet) }
            }
        }
        if (needThirdParty || _emotes.value.twitch.isEmpty()) loadTwitchEmotes(room, gen)
    }

    private fun loadTwitchEmotes(room: String, gen: Int) {
        if (auth == null) return
        scope.launch {
            runCatching { Helix.usableEmotes(room) }.onSuccess { if (gen == generation) _emotes.update { e -> e.copy(twitch = it) } }
                .onFailure { Log.w(TAG, "Twitch-Emotes nicht geladen", it) }
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
            "ROOMSTATE" -> {
                _room.update { r ->
                    r.copy(
                        slow = m.tags["slow"]?.toIntOrNull() ?: r.slow,
                        followersOnly = m.tags["followers-only"]?.toIntOrNull() ?: r.followersOnly,
                        subsOnly = m.tags["subs-only"]?.let { it == "1" } ?: r.subsOnly,
                        emoteOnly = m.tags["emote-only"]?.let { it == "1" } ?: r.emoteOnly,
                        uniqueChat = m.tags["r9k"]?.let { it == "1" } ?: r.uniqueChat,
                    )
                }
                m.tags["room-id"]?.let { room ->
                    val gen = generation
                    if (room != roomId || _emotes.value.thirdParty.isEmpty() || (auth != null && _emotes.value.twitch.isEmpty())) scope.launch { ensureEmotes(room, gen) }
                }
            }
            "USERSTATE", "GLOBALUSERSTATE" -> {
                val a = auth
                if (m.command == "USERSTATE") {
                    // Nach dem Senden schickt Twitch die richtige ID der eigenen Nachricht
                    m.tags["id"]?.takeIf { it.isNotEmpty() }?.let { real ->
                        val tmp = synchronized(pendingSelf) { pendingSelf.removeFirstOrNull() }
                        if (tmp != null) _messages.update { list -> list.map { if (it.id == tmp) it.copy(id = real) else it } }
                    }
                }
                val login = a?.first.orEmpty()
                val badges = Irc.badgeList(m.tags["badges"])
                _me.value = ChatMe(
                    login = login,
                    userId = m.tags["user-id"] ?: TwitchAccount.state.value?.user?.id,
                    name = m.tags["display-name"]?.takeIf { it.isNotEmpty() } ?: login,
                    color = m.tags["color"]?.takeIf { it.isNotEmpty() },
                    badges = if (m.command == "USERSTATE") badges else (_me.value?.badges ?: badges),
                    broadcaster = login.isNotEmpty() && login == channel,
                )
            }
            "PRIVMSG" -> message(m)?.let { add(it) }
            "USERNOTICE" -> userNotice(m).forEach { add(it) }
            "CLEARCHAT" -> {
                val user = m.text?.lowercase()
                if (user == null) {
                    _messages.value = listOf(system("Der Chat wurde von einem Moderator geleert."))
                } else {
                    _messages.update { list -> list.map { if (it.login == user) it.copy(deleted = true) else it } }
                    val secs = m.tags["ban-duration"]?.toIntOrNull()
                    if (_me.value?.isMod == true || user == auth?.first) {
                        val who = if (user == auth?.first) "Du wurdest" else "$user wurde"
                        add(system(if (secs != null) "$who für ${duration(secs)} gesperrt (Timeout)." else "$who dauerhaft gebannt."))
                    }
                }
            }
            "CLEARMSG" -> m.tags["target-msg-id"]?.let { id -> _messages.update { list -> list.map { if (it.id == id) it.copy(deleted = true) else it } } }
            "NOTICE" -> {
                val text = m.text.orEmpty()
                if (Regex("authentication failed|improperly formatted auth|invalid nick", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    add(system("Twitch hat die Anmeldung für den Chat abgelehnt – du liest weiter mit. Melde dich in den Einstellungen neu bei Twitch an."))
                    TwitchAccount.markExpired()
                    val ch = channel
                    scope.launch { if (ch != null) join(ch) }
                } else {
                    // Eigene Nachricht abgelehnt (langsamer Modus, doppelt, gesperrt …): durchstreichen
                    if (m.tags["msg-id"].orEmpty().startsWith("msg_")) {
                        val tmp = synchronized(pendingSelf) { pendingSelf.removeFirstOrNull() }
                        if (tmp != null) markDeleted(tmp)
                    }
                    if (text.isNotEmpty()) add(system(Notices.german(m.tags["msg-id"], text)))
                }
            }
        }
    }

    /** Abos, Raids, Ankündigungen … */
    private fun userNotice(m: Irc.Line): List<ChatMessage> {
        val kind = m.tags["msg-id"].orEmpty()
        val sys = m.tags["system-msg"]?.takeIf { it.isNotBlank() }
        val time = m.tags["tmi-sent-ts"]?.toLongOrNull() ?: System.currentTimeMillis()
        if (kind == "announcement") {
            val color = when (m.tags["msg-param-color"]?.uppercase()) {
                "BLUE" -> 0xFF00D6D6
                "GREEN" -> 0xFF00DB84
                "ORANGE" -> 0xFFFFB31A
                else -> 0xFF9146FF
            }
            return listOfNotNull(message(m)?.copy(label = "📣 Ankündigung", accent = color, highlight = true))
        }
        val label = when (kind) {
            "sub", "resub" -> "⭐ Abo"
            "subgift", "anonsubgift", "submysterygift", "anonsubmysterygift", "giftpaidupgrade", "anongiftpaidupgrade", "primepaidupgrade" -> "🎁 Geschenk-Abo"
            "raid" -> "🚀 Raid"
            "bitsbadgetier" -> "💎 Bits"
            "viewermilestone" -> "🏆 Meilenstein"
            else -> null
        }
        val accent = if (kind == "raid") 0xFFFF6905 else 0xFF9146FF
        val out = ArrayList<ChatMessage>()
        if (!m.text.isNullOrEmpty()) {
            message(m)?.let { msg ->
                out += msg.copy(label = listOfNotNull(label, sys).joinToString("  "), accent = accent, highlight = true)
            }
        } else if (sys != null) {
            out += ChatMessage(
                id = m.tags["id"] ?: "sys-${System.nanoTime()}",
                parts = listOf(ChatPart.Text(sys)),
                text = sys,
                system = true,
                highlight = true,
                label = label,
                accent = accent,
                time = time,
            )
        }
        return out
    }

    private fun system(text: String) = ChatMessage(id = "sys-${System.nanoTime()}", parts = listOf(ChatPart.Text(text)), text = text, system = true)

    /** Hinweis im Chat (nur für einen selbst sichtbar). */
    fun notice(text: String) = add(system(text))

    private fun historyMessage(m: Irc.Line): ChatMessage? = when (m.command) {
        "PRIVMSG" -> message(m)
        "USERNOTICE" -> userNotice(m).firstOrNull()
        else -> null
    }?.copy(history = true)

    private fun message(m: Irc.Line): ChatMessage? {
        var text = m.text ?: return null
        val action = Regex("^\u0001ACTION (.*)\u0001$").find(text)
        if (action != null) text = action.groupValues[1]
        val login = m.nick ?: m.tags["login"].orEmpty()
        val name = m.tags["display-name"]?.takeIf { it.isNotEmpty() } ?: login
        if (login.isNotEmpty()) synchronized(chatters) { chatters[login] = name }
        val mine = auth?.first
        val replyId = m.tags["reply-parent-msg-id"]?.takeIf { it.isNotEmpty() }
        val reply = replyId?.let {
            ChatReply(
                id = it,
                login = m.tags["reply-parent-user-login"].orEmpty(),
                name = m.tags["reply-parent-display-name"]?.takeIf { n -> n.isNotEmpty() } ?: m.tags["reply-parent-user-login"].orEmpty(),
                text = m.tags["reply-parent-msg-body"].orEmpty(),
            )
        }
        // Bei Antworten steht "@name " vorne – Twitch blendet das aus (Emote-Positionen verschieben sich mit)
        var emoteTag = m.tags["emotes"]
        if (reply != null) {
            val prefix = Regex("^@${Regex.escape(reply.login)}\\s+", RegexOption.IGNORE_CASE).find(text)
                ?: Regex("^@${Regex.escape(reply.name)}\\s+", RegexOption.IGNORE_CASE).find(text)
            if (prefix != null) {
                val shift = prefix.value.codePointCount(0, prefix.value.length)
                text = text.substring(prefix.value.length)
                emoteTag = Irc.shiftEmotes(emoteTag, -shift)
            }
        }
        val bits = m.tags["bits"]?.toIntOrNull()
        val first = m.tags["first-msg"] == "1"
        val highlighted = m.tags["msg-id"] == "highlighted-message"
        val mention = mine != null && login != mine && (
            Regex("(^|\\W)@?${Regex.escape(mine)}(\\W|$)", RegexOption.IGNORE_CASE).containsMatchIn(text) || reply?.login == mine
            )
        return ChatMessage(
            id = m.tags["id"] ?: "m-${System.nanoTime()}",
            login = login,
            name = name,
            color = m.tags["color"]?.takeIf { it.isNotEmpty() },
            badges = Irc.badgeList(m.tags["badges"]),
            parts = Irc.parts(text, emoteTag, _emotes.value.thirdParty),
            text = text,
            action = action != null,
            highlight = highlighted || first || bits != null,
            mention = mention,
            self = login == mine,
            reply = reply,
            label = when {
                first -> "👋 Erste Nachricht im Chat"
                highlighted -> "✨ Hervorgehoben (Kanalpunkte)"
                bits != null -> "💎 $bits Bits"
                else -> null
            },
            accent = when {
                first -> 0xFF00DB84
                highlighted -> 0xFF00D6D6
                bits != null -> 0xFF9146FF
                else -> null
            },
            time = m.tags["tmi-sent-ts"]?.toLongOrNull() ?: System.currentTimeMillis(),
        )
    }

    private fun add(msg: ChatMessage) = _messages.update { list -> if (list.any { it.id == msg.id }) list else (list + msg).takeLast(MAX) }

    /** Wer im Chat geschrieben hat, passend zu [prefix] (für @-Ergänzung), zuletzt aktive zuerst. */
    fun chatters(prefix: String, limit: Int = 8): List<Pair<String, String>> {
        val p = prefix.lowercase()
        val all = synchronized(chatters) { chatters.entries.map { it.key to it.value } }.asReversed()
        val ch = channel
        val withChannel = if (ch != null && all.none { it.first == ch }) all + (ch to ch) else all
        return withChannel.filter { (login, name) -> login.startsWith(p) || name.lowercase().startsWith(p) }
            .filter { it.first != auth?.first }
            .take(limit)
    }

    /** Ist man im langsamen Modus noch gesperrt? Restzeit in ms (0 = darf schreiben). */
    fun cooldownLeft(now: Long = System.currentTimeMillis()): Long {
        val slow = _room.value.slow
        val me = _me.value
        if (slow <= 0 || me?.isMod == true || me?.isVip == true) return 0
        return (_lastSent.value + slow * 1000L - now).coerceAtLeast(0)
    }

    /**
     * Nachricht oder /-Befehl schicken. Gibt false zurück, wenn nichts passiert ist (Eingabe bleibt stehen).
     * Twitch schickt die eigene Nachricht nicht zurück – deshalb gleich anzeigen.
     */
    fun send(text: String, replyTo: ChatMessage? = null): Boolean {
        val msg = text.replace(Regex("[\r\n]+"), " ").trim().take(500)
        if (msg.isEmpty()) return false
        if (msg.startsWith("/") || (msg.startsWith(".") && msg.length > 1 && msg[1].isLetter() && ChatCommands.find(msg.drop(1).substringBefore(' ')) != null)) {
            val name = msg.drop(1).substringBefore(' ').lowercase()
            val args = msg.substringAfter(' ', "").trim()
            if (name == "me") return if (args.isEmpty()) false else say(args, action = true, replyTo = null)
            return ChatCommands.run(name, args)
        }
        return say(msg, action = false, replyTo = replyTo)
    }

    private fun say(msg: String, action: Boolean, replyTo: ChatMessage?): Boolean {
        val w: WebSocket
        val a: Pair<String, String>
        val ch: String
        synchronized(lock) {
            w = ws ?: return false
            a = auth ?: return false
            ch = channel ?: return false
        }
        if (!_canSend.value) return false
        val left = cooldownLeft()
        if (left > 0) {
            notice("Langsamer Modus: Du kannst in ${(left + 999) / 1000} Sekunden wieder schreiben.")
            return false
        }
        val body = if (action) "\u0001ACTION $msg\u0001" else msg
        val tags = replyTo?.takeIf { it.canReply }?.let { "@reply-parent-msg-id=${it.id} " }.orEmpty()
        if (!w.send("${tags}PRIVMSG #$ch :$body")) return false
        _lastSent.value = System.currentTimeMillis()
        val me = _me.value
        val id = "self-${System.nanoTime()}"
        synchronized(pendingSelf) {
            pendingSelf.addLast(id)
            while (pendingSelf.size > 10) pendingSelf.removeFirst()
        }
        add(
            ChatMessage(
                id = id,
                login = a.first,
                name = me?.name ?: a.first,
                color = me?.color,
                badges = me?.badges.orEmpty(),
                parts = Irc.parts(msg, null, _emotes.value.all),
                text = msg,
                action = action,
                self = true,
                reply = replyTo?.takeIf { it.canReply }?.let { ChatReply(it.id, it.login, it.name, it.text) },
            ),
        )
        return true
    }

    /** Einzelne Nachricht als gelöscht markieren (nach /delete). */
    internal fun markDeleted(id: String) = _messages.update { list -> list.map { if (it.id == id) it.copy(deleted = true) else it } }

    internal fun requestUserCard(login: String) {
        _openUser.tryEmit(login.removePrefix("@").lowercase())
    }

    internal fun currentChannel(): String? = channel

    private fun duration(secs: Int): String = when {
        secs < 60 -> "$secs Sekunden"
        secs < 3600 -> "${secs / 60} Minuten"
        secs < 86_400 -> "${secs / 3600} Stunden"
        else -> "${secs / 86_400} Tage"
    }
}

/** Häufige Twitch-Hinweise (NOTICE) auf Deutsch, sonst Twitchs Text. */
internal object Notices {
    fun german(id: String?, text: String): String = when (id) {
        "msg_slowmode" -> "Langsamer Modus: Bitte kurz warten, bevor du wieder schreibst."
        "msg_followersonly", "msg_followersonly_zero" -> "Dieser Chat ist nur für Follower."
        "msg_followersonly_followed" -> "Dieser Chat ist nur für Follower, die schon länger folgen."
        "msg_subsonly" -> "Dieser Chat ist nur für Abonnenten."
        "msg_emoteonly" -> "In diesem Chat sind gerade nur Emotes erlaubt."
        "msg_r9k" -> "Einzigartiger Chat: Deine Nachricht ist nicht einzigartig genug."
        "msg_duplicate" -> "Du kannst dieselbe Nachricht nicht zweimal hintereinander schicken."
        "msg_ratelimit" -> "Du schreibst zu schnell – bitte kurz warten."
        "msg_banned" -> "Du bist in diesem Kanal gebannt."
        "msg_timedout" -> "Du bist gerade gesperrt (Timeout). $text"
        "msg_verified_email" -> "Zum Schreiben in diesem Chat musst du deine E-Mail-Adresse bei Twitch bestätigen."
        "msg_requires_verified_phone_number" -> "Zum Schreiben in diesem Chat brauchst du eine bestätigte Telefonnummer bei Twitch."
        "msg_channel_suspended" -> "Dieser Kanal ist gesperrt."
        "msg_suspended" -> "Dein Konto ist gesperrt."
        else -> text
    }
}

/** Die letzten Nachrichten eines Kanals (recent-messages.robotty.de, wie bei Chatterino). */
object RecentMessages {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(login: String, limit: Int = 100): List<String> {
        val url = "https://recent-messages.robotty.de/api/v2/recent-messages/${login.lowercase()}?limit=$limit"
        return Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            val o = json.parseToJsonElement(r.body.string()) as? JsonObject ?: return emptyList()
            (o["messages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        }
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

    /** Emote-Positionen verschieben (wenn vorne Text wegfällt). */
    fun shiftEmotes(tag: String?, by: Int): String? {
        if (tag.isNullOrEmpty() || by == 0) return tag
        return emoteTag(tag).filter { it.start + by >= 0 }.groupBy { it.id }.entries.joinToString("/") { (id, list) ->
            id + ":" + list.joinToString(",") { "${it.start + by}-${it.end + by}" }
        }.ifEmpty { null }
    }

    fun badges(tag: String?): List<String> = badgeList(tag).map { it.set }

    fun badgeList(tag: String?): List<ChatBadge> = tag.orEmpty().split(',').mapNotNull { b ->
        val set = b.substringBefore('/')
        if (set.isEmpty()) null else ChatBadge(set, b.substringAfter('/', "1").ifEmpty { "1" })
    }

    private val LINK = Regex("^(https?://\\S+|(www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*\\.(tv|com|de|net|org|gg|io|me|be|ly|at|ch|co|app|dev|eu|info|uk)(/\\S*)?)$", RegexOption.IGNORE_CASE)
    private val MENTION = Regex("^@[A-Za-z0-9_]{2,25}[.,!?:;]*$")

    /** Nachricht in Text, Emotes, Erwähnungen und Links zerlegen. [extra]: Name → Bild (7TV/BTTV/FFZ). */
    fun parts(text: String, emoteTag: String?, extra: Map<String, String>): List<ChatPart> {
        val cps = text.codePoints().toArray()
        fun sub(a: Int, b: Int) = String(cps, a, b - a)
        val out = ArrayList<ChatPart>()
        fun appendText(s: String) {
            val last = out.lastOrNull()
            if (last is ChatPart.Text) out[out.lastIndex] = ChatPart.Text(last.text + s) else out += ChatPart.Text(s)
        }
        fun pushText(s: String) {
            if (s.isEmpty()) return
            for (piece in Regex("(?<=\\s)|(?=\\s)").split(s)) {
                if (piece.isEmpty()) continue
                val url = extra[piece]
                when {
                    url != null -> out += ChatPart.Emote(piece, url)
                    MENTION.matches(piece) -> {
                        val name = piece.trimEnd('.', ',', '!', '?', ':', ';')
                        out += ChatPart.Mention(name.removePrefix("@"))
                        if (name.length < piece.length) appendText(piece.substring(name.length))
                    }
                    piece.length > 4 && LINK.matches(piece.trimEnd('.', ',', '!', '?', ':', ';', ')')) -> {
                        val link = piece.trimEnd('.', ',', '!', '?', ':', ';', ')')
                        out += ChatPart.Link(link)
                        if (link.length < piece.length) appendText(piece.substring(link.length))
                    }
                    else -> appendText(piece)
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

/** Abzeichen-Bilder (global und vom Kanal, z. B. eigene Abo-Abzeichen). */
object TwitchBadges {
    private val cache = ConcurrentHashMap<String, Map<String, String>>()
    private const val FIELDS = "setID version imageURL(size: DOUBLE)"

    private fun JsonElement?.list(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private val JsonElement?.str get() = (this as? JsonPrimitive)?.contentOrNull

    private fun toMap(list: List<JsonObject>): Map<String, String> = list.mapNotNull { b ->
        val set = b["setID"].str ?: return@mapNotNull null
        val version = b["version"].str ?: return@mapNotNull null
        val url = b["imageURL"].str ?: return@mapNotNull null
        "$set/$version" to url
    }.toMap()

    suspend fun load(channelLogin: String): Map<String, String> {
        val key = channelLogin.lowercase()
        cache[key]?.let { return it }
        val d = TwitchApi.gql(
            "query(\$login: String!) { badges { $FIELDS } user(login: \$login) { broadcastBadges { $FIELDS } } }",
            kotlinx.serialization.json.buildJsonObject { put("login", JsonPrimitive(key)) },
        )
        val global = toMap(d["badges"].list())
        val channel = toMap((d["user"] as? JsonObject)?.get("broadcastBadges").list())
        val all = global + channel
        if (all.isNotEmpty()) cache[key] = all
        return all
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

    /** (Kanal, global) */
    fun load(roomId: String): Pair<Map<String, String>, Map<String, String>> {
        val room = cache.getOrPut(roomId) {
            val map = HashMap<String, String>()
            ffz(map, get("https://api.frankerfacez.com/v1/room/id/$roomId").obj?.get("sets"))
            val b = get("https://api.betterttv.net/3/cached/users/twitch/$roomId").obj
            bttv(map, JsonArray(b?.get("channelEmotes").arr.orEmpty() + b?.get("sharedEmotes").arr.orEmpty()))
            seventv(map, get("https://7tv.io/v3/users/twitch/$roomId").obj?.get("emote_set").obj?.get("emotes"))
            map
        }
        return room to global()
    }
}
