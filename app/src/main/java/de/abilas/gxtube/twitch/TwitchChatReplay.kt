package de.abilas.gxtube.twitch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Eine Chat-Nachricht aus einem VOD: [offset] = Sekunde im Video. */
data class ReplayMessage(val offset: Int, val message: ChatMessage)

/** Eine Seite Chat-Wiedergabe; [next]: weiter mit diesem Cursor. */
data class ReplayPage(val messages: List<ReplayMessage>, val next: String?)

/** Chat-Wiedergabe bei vergangenen Streams (wie auf twitch.tv), über Twitchs GraphQL. */
object TwitchChatReplay {
    private const val QUERY = "query(\$id: ID!, \$offset: Int, \$cursor: Cursor) { video(id: \$id) { comments(contentOffsetSeconds: \$offset, after: \$cursor) {" +
        " edges { cursor node { id contentOffsetSeconds commenter { id login displayName }" +
        " message { fragments { text emote { emoteID } } userBadges { setID version } userColor } } } pageInfo { hasNextPage } } } }"

    private val JsonElement?.obj get() = this as? JsonObject
    private val JsonElement?.str get() = (this as? JsonPrimitive)?.contentOrNull

    /** Ab Sekunde [offset] oder mit [cursor] weiter. [extra]: Emotes von 7TV/BTTV/FFZ. */
    suspend fun page(vod: String, offset: Int?, cursor: String?, extra: Map<String, String>): ReplayPage {
        val vars = buildJsonObject {
            put("id", vod)
            if (cursor != null) put("cursor", cursor) else put("offset", (offset ?: 0).coerceAtLeast(0))
        }
        val conn = TwitchApi.gql(QUERY, vars)["video"].obj?.get("comments").obj
            ?: throw TwitchException("Für dieses Video gibt es keinen Chat")
        return parse(conn, extra)
    }

    fun parse(conn: JsonObject, extra: Map<String, String>): ReplayPage {
        val edges = (conn["edges"] as? JsonArray)?.mapNotNull { it.obj }.orEmpty()
        val out = edges.mapNotNull { e -> message(e["node"].obj, extra) }
        val more = conn["pageInfo"].obj?.get("hasNextPage").str == "true"
        return ReplayPage(out, if (more) edges.lastOrNull()?.get("cursor").str else null)
    }

    private fun message(node: JsonObject?, extra: Map<String, String>): ReplayMessage? {
        val n = node ?: return null
        val c = n["commenter"].obj
        val login = c?.get("login").str?.lowercase().orEmpty()
        val m = n["message"].obj ?: return null
        val parts = ArrayList<ChatPart>()
        val text = StringBuilder()
        (m["fragments"] as? JsonArray)?.forEach { f ->
            val fo = f.obj ?: return@forEach
            val t = fo["text"].str.orEmpty()
            text.append(t)
            val emote = fo["emote"].obj?.get("emoteID").str
            if (emote != null) parts += ChatPart.Emote(t, "https://static-cdn.jtvnw.net/emoticons/v2/$emote/default/dark/2.0")
            else parts += Irc.parts(t, null, extra)
        }
        // Benachbarte Textstücke zusammenlegen
        val merged = ArrayList<ChatPart>()
        for (p in parts) {
            val last = merged.lastOrNull()
            if (p is ChatPart.Text && last is ChatPart.Text) merged[merged.lastIndex] = ChatPart.Text(last.text + p.text) else merged += p
        }
        val badges = (m["userBadges"] as? JsonArray)?.mapNotNull { b ->
            val bo = b.obj ?: return@mapNotNull null
            val set = bo["setID"].str?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ChatBadge(set, bo["version"].str ?: "1")
        }.orEmpty()
        val offset = (n["contentOffsetSeconds"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return null
        var body = text.toString()
        val action = body.startsWith("\u0001ACTION ") && body.endsWith("\u0001")
        if (action) body = body.removePrefix("\u0001ACTION ").removeSuffix("\u0001")
        return ReplayMessage(
            offset,
            ChatMessage(
                id = n["id"].str ?: "r-$offset-${login.hashCode()}-${body.hashCode()}",
                login = login,
                name = c?.get("displayName").str ?: login,
                color = m["userColor"].str?.takeIf { it.isNotEmpty() },
                badges = badges,
                parts = merged,
                text = body,
                action = action,
                time = offset * 1000L,
            ),
        )
    }
}
