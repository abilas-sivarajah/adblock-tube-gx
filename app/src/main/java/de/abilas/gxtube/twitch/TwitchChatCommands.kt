package de.abilas.gxtube.twitch

import android.util.Log
import de.abilas.gxtube.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/** Ein /-Befehl im Chat, wie auf twitch.tv. */
data class ChatCommand(
    val name: String,
    val args: String,
    val description: String,
    val role: Role = Role.ALL,
    /** Twitch-Berechtigung, die das Konto dafür braucht. */
    val scope: String? = null,
    /** Erstes Argument ist ein Nutzer (für die @-Ergänzung). */
    val user: Boolean = false,
) {
    enum class Role { ALL, MOD, BROADCASTER }

    fun allowed(me: ChatMe?): Boolean = when (role) {
        Role.ALL -> true
        Role.MOD -> me?.isMod == true
        Role.BROADCASTER -> me?.broadcaster == true
    }
}

/** Die /-Befehle über die offizielle Twitch-API (Twitch hat sie aus dem IRC-Chat entfernt). */
object ChatCommands {
    private const val TAG = "ChatCommands"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val all: List<ChatCommand> = listOf(
        ChatCommand("me", "<Nachricht>", "Nachricht als Aktion (farbig, kursiv)"),
        ChatCommand("color", "<Farbe>", "Namensfarbe ändern (z. B. blue, hotpink oder #9146FF)", scope = "user:manage:chat_color"),
        ChatCommand("w", "<Nutzer> <Nachricht>", "Flüstern (private Nachricht)", scope = "user:manage:whispers", user = true),
        ChatCommand("user", "<Nutzer>", "Profilkarte öffnen", user = true),
        ChatCommand("block", "<Nutzer>", "Nutzer blockieren", scope = "user:manage:blocked_users", user = true),
        ChatCommand("unblock", "<Nutzer>", "Blockierung aufheben", scope = "user:manage:blocked_users", user = true),
        ChatCommand("help", "", "Alle Befehle anzeigen"),
        // Moderatoren
        ChatCommand("timeout", "<Nutzer> [Dauer] [Grund]", "Nutzer vorübergehend sperren (Dauer z. B. 10m, 1h, 1d – Standard 10 Min.)", ChatCommand.Role.MOD, "moderator:manage:banned_users", true),
        ChatCommand("untimeout", "<Nutzer>", "Timeout aufheben", ChatCommand.Role.MOD, "moderator:manage:banned_users", true),
        ChatCommand("ban", "<Nutzer> [Grund]", "Nutzer dauerhaft bannen", ChatCommand.Role.MOD, "moderator:manage:banned_users", true),
        ChatCommand("unban", "<Nutzer>", "Bann aufheben", ChatCommand.Role.MOD, "moderator:manage:banned_users", true),
        ChatCommand("warn", "<Nutzer> <Grund>", "Nutzer verwarnen", ChatCommand.Role.MOD, "moderator:manage:warnings", true),
        ChatCommand("clear", "", "Chat für alle leeren", ChatCommand.Role.MOD, "moderator:manage:chat_messages"),
        ChatCommand("slow", "[Sekunden]", "Langsamer Modus (Standard 30 s)", ChatCommand.Role.MOD, "moderator:manage:chat_settings"),
        ChatCommand("slowoff", "", "Langsamen Modus aus", ChatCommand.Role.MOD, "moderator:manage:chat_settings"),
        ChatCommand("followers", "[Dauer]", "Nur Follower (z. B. 10m, 1d – Standard: alle Follower)", ChatCommand.Role.MOD, "moderator:manage:chat_settings"),
        ChatCommand("followersoff", "", "Nur-Follower-Modus aus", ChatCommand.Role.MOD, "moderator:manage:chat_settings"),
        ChatCommand("subscribers", "", "Nur Abonnenten", ChatCommand.Role.MOD, "moderator:manage:chat_settings"),
        ChatCommand("subscribersoff", "", "Nur-Abonnenten-Modus aus", ChatCommand.Role.MOD, "moderator:manage:chat_settings"),
        ChatCommand("emoteonly", "", "Nur Emotes", ChatCommand.Role.MOD, "moderator:manage:chat_settings"),
        ChatCommand("emoteonlyoff", "", "Nur-Emotes-Modus aus", ChatCommand.Role.MOD, "moderator:manage:chat_settings"),
        ChatCommand("uniquechat", "", "Einzigartiger Chat (keine doppelten Nachrichten)", ChatCommand.Role.MOD, "moderator:manage:chat_settings"),
        ChatCommand("uniquechatoff", "", "Einzigartigen Chat aus", ChatCommand.Role.MOD, "moderator:manage:chat_settings"),
        ChatCommand("announce", "<Nachricht>", "Ankündigung (hervorgehoben)", ChatCommand.Role.MOD, "moderator:manage:announcements"),
        ChatCommand("announceblue", "<Nachricht>", "Ankündigung in Blau", ChatCommand.Role.MOD, "moderator:manage:announcements"),
        ChatCommand("announcegreen", "<Nachricht>", "Ankündigung in Grün", ChatCommand.Role.MOD, "moderator:manage:announcements"),
        ChatCommand("announceorange", "<Nachricht>", "Ankündigung in Orange", ChatCommand.Role.MOD, "moderator:manage:announcements"),
        ChatCommand("announcepurple", "<Nachricht>", "Ankündigung in Lila", ChatCommand.Role.MOD, "moderator:manage:announcements"),
        ChatCommand("shoutout", "<Kanal>", "Shoutout für einen Kanal", ChatCommand.Role.MOD, "moderator:manage:shoutouts", true),
        // Streamer
        ChatCommand("mod", "<Nutzer>", "Zum Moderator machen", ChatCommand.Role.BROADCASTER, "channel:manage:moderators", true),
        ChatCommand("unmod", "<Nutzer>", "Moderator entfernen", ChatCommand.Role.BROADCASTER, "channel:manage:moderators", true),
        ChatCommand("mods", "", "Moderatoren anzeigen", ChatCommand.Role.BROADCASTER, "moderation:read"),
        ChatCommand("vip", "<Nutzer>", "Zum VIP machen", ChatCommand.Role.BROADCASTER, "channel:manage:vips", true),
        ChatCommand("unvip", "<Nutzer>", "VIP entfernen", ChatCommand.Role.BROADCASTER, "channel:manage:vips", true),
        ChatCommand("vips", "", "VIPs anzeigen", ChatCommand.Role.BROADCASTER, "channel:read:vips"),
        ChatCommand("raid", "<Kanal>", "Anderen Kanal raiden", ChatCommand.Role.BROADCASTER, "channel:manage:raids", true),
        ChatCommand("unraid", "", "Raid abbrechen", ChatCommand.Role.BROADCASTER, "channel:manage:raids"),
        ChatCommand("commercial", "[Sekunden]", "Werbung starten (30–180 s)", ChatCommand.Role.BROADCASTER, "channel:edit:commercial"),
        ChatCommand("marker", "[Beschreibung]", "Stream-Markierung setzen", ChatCommand.Role.BROADCASTER, "channel:manage:broadcast"),
    )

    private val aliases = mapOf("unbanuser" to "unban", "untimeoutuser" to "untimeout", "whisper" to "w", "followersonly" to "followers", "followersonlyoff" to "followersoff", "subs" to "subscribers", "subsoff" to "subscribersoff", "r9kbeta" to "uniquechat", "r9kbetaoff" to "uniquechatoff", "so" to "shoutout")

    fun find(name: String): ChatCommand? {
        val n = name.lowercase()
        return all.firstOrNull { it.name == (aliases[n] ?: n) }
    }

    /** Befehle für die Vorschläge beim Tippen. */
    fun suggestions(prefix: String, me: ChatMe?): List<ChatCommand> {
        val p = prefix.lowercase()
        return all.filter { it.allowed(me) && it.name.startsWith(p) }
    }

    /** Befehl ausführen. false: unbekannt oder unvollständig (Eingabe bleibt stehen). */
    fun run(rawName: String, args: String): Boolean {
        val cmd = find(rawName)
        if (cmd == null) {
            TwitchChat.notice("Unbekannter Befehl: /$rawName – /help zeigt alle Befehle.")
            return false
        }
        val me = TwitchChat.me.value
        if (cmd.name == "help") {
            help(me)
            return true
        }
        if (cmd.name == "user") {
            val who = args.substringBefore(' ').removePrefix("@")
            if (who.isEmpty()) return usage(cmd)
            TwitchChat.requestUserCard(who)
            return true
        }
        if (!cmd.allowed(me)) {
            TwitchChat.notice(if (cmd.role == ChatCommand.Role.BROADCASTER) "/${cmd.name} kann nur der Streamer benutzen." else "/${cmd.name} können nur Moderatoren benutzen.")
            return false
        }
        val account = TwitchAccount.state.value
        if (account == null || account.expired) {
            TwitchChat.notice("Für /${cmd.name} musst du bei Twitch angemeldet sein.")
            return false
        }
        val scopes = account.tokens.scopes
        if (cmd.scope != null && scopes.isNotEmpty() && cmd.scope !in scopes) {
            TwitchChat.notice("Für /${cmd.name} braucht GX Tube mehr Rechte bei Twitch: Einstellungen → Twitch → abmelden und neu anmelden.")
            return false
        }
        val words = args.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (cmd.args.startsWith("<") && words.isEmpty()) return usage(cmd)
        if (cmd.name in setOf("w", "warn") && words.size < 2) return usage(cmd)
        scope.launch {
            runCatching { execute(cmd, words, args) }
                .onSuccess { it?.let(TwitchChat::notice) }
                .onFailure {
                    Log.w(TAG, "/${cmd.name}", it)
                    TwitchChat.notice("/${cmd.name}: ${it.message ?: it.toString()}")
                }
        }
        return true
    }

    private fun usage(cmd: ChatCommand): Boolean {
        TwitchChat.notice("So geht's: /${cmd.name} ${cmd.args} – ${cmd.description}")
        return false
    }

    private fun help(me: ChatMe?) {
        val list = all.filter { it.allowed(me) }
        TwitchChat.notice("Befehle: " + list.joinToString("  ") { "/${it.name}" })
        TwitchChat.notice("Tippe / für Vorschläge mit Erklärung. @ ergänzt Namen, : ergänzt Emotes.")
    }

    /** Sekunden aus "10m", "1h", "2d", "1w", "30s" oder "600". */
    fun parseDuration(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val m = Regex("^(\\d+)([smhdw]?)$", RegexOption.IGNORE_CASE).find(s.trim()) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val secs = when (m.groupValues[2].lowercase()) {
            "", "s" -> n
            "m" -> n * 60
            "h" -> n * 3600
            "d" -> n * 86_400
            "w" -> n * 604_800
            else -> return null
        }
        return secs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private val NAMED_COLORS = mapOf(
        "blue" to "blue", "blueviolet" to "blue_violet", "cadetblue" to "cadet_blue", "chocolate" to "chocolate", "coral" to "coral",
        "dodgerblue" to "dodger_blue", "firebrick" to "firebrick", "goldenrod" to "golden_rod", "green" to "green", "hotpink" to "hot_pink",
        "orangered" to "orange_red", "red" to "red", "seagreen" to "sea_green", "springgreen" to "spring_green", "yellowgreen" to "yellow_green",
    )

    private suspend fun execute(cmd: ChatCommand, words: List<String>, args: String): String? {
        TwitchAccount.ensureFresh()
        val me = Helix.myId()
        val room = TwitchChat.currentRoomId ?: TwitchChat.currentChannel()?.let { Helix.userId(it) } ?: throw TwitchException("Chat noch nicht verbunden")
        val target = words.firstOrNull()?.removePrefix("@")
        fun rest(from: Int) = words.drop(from).joinToString(" ")
        val mod = listOf("broadcaster_id" to room, "moderator_id" to me)
        return when (cmd.name) {
            "color" -> {
                val c = words.first().lowercase().replace("_", "")
                val value = NAMED_COLORS[c] ?: if (Regex("^#?[0-9a-f]{6}$").matches(c)) "#" + c.removePrefix("#").uppercase() else throw TwitchException("Unbekannte Farbe – z. B. blue, red, hotpink, seagreen oder #9146FF (Hex nur mit Turbo/Prime)")
                Helix.call("PUT", "chat/color", listOf("user_id" to me, "color" to value))
                "Deine Namensfarbe ist jetzt ${words.first()}."
            }
            "w" -> {
                Helix.call("POST", "whispers", listOf("from_user_id" to me, "to_user_id" to Helix.userId(target!!)), buildJsonObject { put("message", rest(1)) })
                "Geflüstert an $target: ${rest(1)}"
            }
            "block" -> {
                Helix.call("PUT", "users/blocks", listOf("target_user_id" to Helix.userId(target!!)))
                "$target ist blockiert."
            }
            "unblock" -> {
                Helix.call("DELETE", "users/blocks", listOf("target_user_id" to Helix.userId(target!!)))
                "$target ist nicht mehr blockiert."
            }
            "timeout", "ban" -> {
                val secs = if (cmd.name == "timeout") parseDuration(words.getOrNull(1)) else null
                val reason = if (cmd.name == "timeout") rest(if (secs != null) 2 else 1) else rest(1)
                val duration = if (cmd.name == "timeout") (secs ?: 600).coerceIn(1, 1_209_600) else null
                Helix.call("POST", "moderation/bans", mod, buildJsonObject {
                    put("data", buildJsonObject {
                        put("user_id", Helix.userId(target!!))
                        if (duration != null) put("duration", duration)
                        if (reason.isNotEmpty()) put("reason", reason)
                    })
                })
                if (duration != null) "$target ist für ${human(duration)} gesperrt." else "$target ist gebannt."
            }
            "untimeout", "unban" -> {
                Helix.call("DELETE", "moderation/bans", mod + ("user_id" to Helix.userId(target!!)))
                if (cmd.name == "unban") "$target ist entbannt." else "Timeout von $target aufgehoben."
            }
            "warn" -> {
                Helix.call("POST", "moderation/warnings", mod, buildJsonObject {
                    put("data", buildJsonObject {
                        put("user_id", Helix.userId(target!!))
                        put("reason", rest(1))
                    })
                })
                "$target wurde verwarnt."
            }
            "clear" -> {
                Helix.call("DELETE", "moderation/chat", mod)
                null
            }
            "slow" -> settings(mod, buildJsonObject { put("slow_mode", true); put("slow_mode_wait_time", (parseDuration(words.firstOrNull()) ?: 30).coerceIn(3, 120)) })
            "slowoff" -> settings(mod, buildJsonObject { put("slow_mode", false) })
            "followers" -> settings(mod, buildJsonObject { put("follower_mode", true); put("follower_mode_duration", ((parseDuration(words.firstOrNull()) ?: 0) / 60).coerceIn(0, 129_600)) })
            "followersoff" -> settings(mod, buildJsonObject { put("follower_mode", false) })
            "subscribers" -> settings(mod, buildJsonObject { put("subscriber_mode", true) })
            "subscribersoff" -> settings(mod, buildJsonObject { put("subscriber_mode", false) })
            "emoteonly" -> settings(mod, buildJsonObject { put("emote_mode", true) })
            "emoteonlyoff" -> settings(mod, buildJsonObject { put("emote_mode", false) })
            "uniquechat" -> settings(mod, buildJsonObject { put("unique_chat_mode", true) })
            "uniquechatoff" -> settings(mod, buildJsonObject { put("unique_chat_mode", false) })
            "announce", "announceblue", "announcegreen", "announceorange", "announcepurple" -> {
                val color = cmd.name.removePrefix("announce").ifEmpty { "primary" }
                Helix.call("POST", "chat/announcements", mod, buildJsonObject { put("message", args); put("color", color) })
                null
            }
            "shoutout" -> {
                Helix.call("POST", "chat/shoutouts", listOf("from_broadcaster_id" to room, "to_broadcaster_id" to Helix.userId(target!!), "moderator_id" to me))
                "Shoutout für $target gesendet."
            }
            "mod", "unmod" -> {
                Helix.call(if (cmd.name == "mod") "POST" else "DELETE", "moderation/moderators", listOf("broadcaster_id" to room, "user_id" to Helix.userId(target!!)))
                if (cmd.name == "mod") "$target ist jetzt Moderator." else "$target ist kein Moderator mehr."
            }
            "vip", "unvip" -> {
                Helix.call(if (cmd.name == "vip") "POST" else "DELETE", "channels/vips", listOf("broadcaster_id" to room, "user_id" to Helix.userId(target!!)))
                if (cmd.name == "vip") "$target ist jetzt VIP." else "$target ist kein VIP mehr."
            }
            "mods" -> "Moderatoren: " + Helix.names("moderation/moderators", room).ifEmpty { listOf("keine") }.joinToString(", ")
            "vips" -> "VIPs: " + Helix.names("channels/vips", room).ifEmpty { listOf("keine") }.joinToString(", ")
            "raid" -> {
                Helix.call("POST", "raids", listOf("from_broadcaster_id" to room, "to_broadcaster_id" to Helix.userId(target!!)))
                "Raid zu $target startet …"
            }
            "unraid" -> {
                Helix.call("DELETE", "raids", listOf("broadcaster_id" to room))
                "Raid abgebrochen."
            }
            "commercial" -> {
                val len = (parseDuration(words.firstOrNull()) ?: 30).coerceIn(30, 180)
                Helix.call("POST", "channels/commercial", emptyList(), buildJsonObject { put("broadcaster_id", room); put("length", len) })
                "Werbung läuft für $len Sekunden."
            }
            "marker" -> {
                Helix.call("POST", "streams/markers", emptyList(), buildJsonObject { put("user_id", room); if (args.isNotBlank()) put("description", args.take(140)) })
                "Markierung gesetzt."
            }
            else -> throw TwitchException("Befehl nicht unterstützt")
        }
    }

    private suspend fun settings(mod: List<Pair<String, String>>, body: JsonObject): String? {
        Helix.call("PATCH", "chat/settings", mod, body)
        return null // ROOMSTATE kommt von Twitch und Twitch meldet die Änderung selbst
    }

    // ------------------------------------------------------------ Aktionen aus dem Menü einer Nachricht

    private fun launch(what: String, block: suspend () -> String?) {
        scope.launch {
            runCatching {
                TwitchAccount.ensureFresh()
                block()
            }.onSuccess { it?.let(TwitchChat::notice) }
                .onFailure { TwitchChat.notice("$what: ${it.message ?: it}") }
        }
    }

    /** Einzelne Nachricht löschen (Moderatoren). */
    fun deleteMessage(id: String) = launch("Löschen") {
        val room = TwitchChat.currentRoomId ?: throw TwitchException("Chat noch nicht verbunden")
        Helix.call("DELETE", "moderation/chat", listOf("broadcaster_id" to room, "moderator_id" to Helix.myId(), "message_id" to id))
        TwitchChat.markDeleted(id)
        null
    }

    fun timeout(login: String, seconds: Int?) {
        run(if (seconds == null) "ban" else "timeout", if (seconds == null) login else "$login $seconds")
    }

    fun unban(login: String) {
        run("unban", login)
    }

    private fun human(secs: Int): String = when {
        secs < 60 -> "$secs Sekunden"
        secs < 3600 -> "${secs / 60} Minuten"
        secs < 86_400 -> "${secs / 3600} Stunden"
        else -> "${secs / 86_400} Tage"
    }
}

/** Offizielle Twitch-API (api.twitch.tv/helix) mit dem angemeldeten Konto. */
object Helix {
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val JsonElement?.obj get() = this as? JsonObject
    private val JsonElement?.str get() = (this as? JsonPrimitive)?.contentOrNull

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun call(method: String, path: String, query: List<Pair<String, String>> = emptyList(), body: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val account = TwitchApi.auth?.invoke() ?: throw TwitchException("Nicht bei Twitch angemeldet", signIn = true)
        val url = "https://api.twitch.tv/helix/$path" + if (query.isEmpty()) "" else "?" + query.joinToString("&") { "${it.first}=${enc(it.second)}" }
        val reqBody = when {
            body != null -> body.toString().toRequestBody("application/json".toMediaType())
            method == "GET" || method == "DELETE" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val req = Request.Builder().url(url)
            .header("Client-Id", account.second)
            .header("Authorization", "Bearer ${account.first}")
            .method(method, reqBody)
            .build()
        Http.client.newCall(req).execute().use { r ->
            val text = r.body.string()
            val j = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            val message = j?.get("message").str.orEmpty()
            when {
                r.code in 200..299 -> j ?: JsonObject(emptyMap())
                r.code == 401 && message.contains("scope", true) ->
                    throw TwitchException("GX Tube fehlen dafür Rechte bei Twitch – Einstellungen → Twitch → abmelden und neu anmelden.", "scope", signIn = true)
                r.code == 401 -> {
                    TwitchApi.onUnauthorized()
                    throw TwitchException("Twitch-Anmeldung abgelaufen", "401", signIn = true)
                }
                r.code == 403 -> throw TwitchException("Dafür fehlen dir in diesem Kanal die Rechte" + (message.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
                r.code == 429 -> throw TwitchException("Zu viele Anfragen – bitte kurz warten")
                else -> throw TwitchException(message.ifBlank { "Twitch-API: HTTP ${r.code}" })
            }
        }
    }

    /** Eigene Konto-Nummer. */
    suspend fun myId(): String {
        TwitchAccount.state.value?.user?.id?.let { return it }
        val token = TwitchApi.auth?.invoke()?.first ?: throw TwitchException("Nicht bei Twitch angemeldet", signIn = true)
        return TwitchApi.validate(token)?.userId ?: throw TwitchException("Konto nicht gefunden")
    }

    /** Konto-Nummer zu einem Login. */
    suspend fun userId(login: String): String {
        val l = login.removePrefix("@").lowercase()
        ids[l]?.let { return it }
        val j = call("GET", "users", listOf("login" to l))
        val id = (j["data"] as? JsonArray)?.firstOrNull().obj?.get("id").str ?: throw TwitchException("Nutzer „$login“ gibt es nicht")
        ids[l] = id
        return id
    }

    /** Namen aus einer Liste (Moderatoren, VIPs). */
    suspend fun names(path: String, room: String): List<String> {
        val out = ArrayList<String>()
        var after: String? = null
        do {
            val j = call("GET", path, listOfNotNull("broadcaster_id" to room, "first" to "100", after?.let { "after" to it }))
            (j["data"] as? JsonArray)?.forEach { e -> e.obj?.get("user_name").str?.let { out += it } }
            after = j["pagination"].obj?.get("cursor").str
        } while (after != null && out.size < 1000)
        return out.sorted()
    }

    /** Twitch-Emotes, die man benutzen darf (eigene Abos, global …), sonst nur die globalen. */
    suspend fun usableEmotes(room: String): Map<String, String> {
        fun url(id: String) = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0"
        fun parse(j: JsonObject, into: MutableMap<String, String>) {
            (j["data"] as? JsonArray)?.forEach { e ->
                val o = e.obj ?: return@forEach
                val name = o["name"].str ?: return@forEach
                val id = o["id"].str ?: return@forEach
                into.putIfAbsent(name, url(id))
            }
        }
        val out = LinkedHashMap<String, String>()
        val scopes = TwitchAccount.state.value?.tokens?.scopes.orEmpty()
        if ("user:read:emotes" in scopes) {
            runCatching {
                val me = myId()
                var after: String? = null
                var pages = 0
                do {
                    val j = call("GET", "chat/emotes/user", listOfNotNull("user_id" to me, "broadcaster_id" to room, after?.let { "after" to it }))
                    parse(j, out)
                    after = j["pagination"].obj?.get("cursor").str
                } while (after != null && ++pages < 20)
            }
        }
        if (out.isEmpty()) parse(call("GET", "chat/emotes/global"), out)
        return out
    }
}

/** Für die Profilkarte im Chat. */
data class ChatUserInfo(
    val id: String?,
    val login: String,
    val name: String,
    val avatar: String?,
    val createdAt: Long?,
    val followers: Long?,
    val description: String?,
    /** Folgt dem Kanal seit (null: folgt nicht oder unbekannt). */
    val followedAt: Long? = null,
)

object ChatUsers {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ChatUserInfo>()
    private val JsonElement?.obj get() = this as? JsonObject
    private val JsonElement?.str get() = (this as? JsonPrimitive)?.contentOrNull
    private fun time(s: String?) = s?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    suspend fun load(login: String, channel: String?): ChatUserInfo? {
        val l = login.lowercase()
        val key = "$l@$channel"
        cache[key]?.let { return it }
        val vars = buildJsonObject { put("login", l) }
        val u = TwitchApi.gql(
            "query(\$login: String!) { user(login: \$login) { id login displayName profileImageURL(width: 150) createdAt description followers { totalCount } } }",
            vars,
        )["user"].obj ?: return null
        var info = ChatUserInfo(
            id = u["id"].str,
            login = u["login"].str ?: l,
            name = u["displayName"].str ?: l,
            avatar = u["profileImageURL"].str,
            createdAt = time(u["createdAt"].str),
            followers = (u["followers"].obj?.get("totalCount") as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
            description = u["description"].str?.takeIf { it.isNotBlank() },
        )
        // Seit wann folgt der Nutzer dem Kanal? (nicht überall verfügbar – dann eben ohne)
        if (channel != null && channel != l) {
            runCatching {
                TwitchApi.gql(
                    "query(\$login: String!, \$channel: String!) { user(login: \$login) { follow(targetLogin: \$channel) { followedAt } } }",
                    buildJsonObject { put("login", l); put("channel", channel) },
                )["user"].obj?.get("follow").obj?.get("followedAt").str
            }.getOrNull()?.let { info = info.copy(followedAt = time(it)) }
        }
        cache[key] = info
        return info
    }
}
