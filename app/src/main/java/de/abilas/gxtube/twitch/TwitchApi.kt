package de.abilas.gxtube.twitch

import de.abilas.gxtube.data.Http
import de.abilas.gxtube.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import kotlin.random.Random

/** Ein Spiel bzw. eine Kategorie auf Twitch. */
@Serializable
data class TwitchGame(
    val id: String? = null,
    val name: String,
    val displayName: String,
    val boxArt: String? = null,
    val viewers: Long = -1,
)

/** Ein Twitch-Kanal (Suche, Folgen, Kanalseite). */
data class TwitchChannel(
    val login: String,
    val userId: String?,
    val name: String,
    val avatar: String?,
    val followers: Long = -1,
    val live: VideoItem? = null,
    val description: String? = null,
    val banner: String? = null,
)

data class TwitchHome(val streams: List<VideoItem>, val next: String?, val top: List<VideoItem>, val games: List<TwitchGame>)
data class TwitchPage<T>(val items: List<T>, val next: String?)
data class TwitchCategory(val game: TwitchGame, val streams: TwitchPage<VideoItem>)
data class TwitchChannelPage(val channel: TwitchChannel, val videos: TwitchPage<VideoItem>)
data class TwitchSearch(val channels: List<TwitchChannel>, val games: List<TwitchGame>)
data class TwitchPlayback(val url: String, val isLive: Boolean, val login: String?, val vod: String?, val account: Boolean)

/** Was gerade läuft: der Stream bzw. das VOD und der Kanal dazu. */
data class TwitchWatchInfo(
    val video: VideoItem,
    val login: String?,
    val userId: String?,
    val followers: Long,
    val startedAt: Long?,
    val game: TwitchGame?,
)

class TwitchException(message: String, val code: String? = null, val signIn: Boolean = false) : IOException(message)

/**
 * Twitch über die GraphQL-Schnittstelle von twitch.tv – Live-Streams, VODs, Kategorien, Kanäle,
 * Wiedergabe-Adressen und die Anmeldung per Code (twitch.tv/activate, wie die Fernseher-App).
 * Ohne Konto mit der Kennung der Fernseher-App: dort verlangt Twitch beim Weiterblättern keine
 * Integritätsprüfung.
 */
object TwitchApi {
    const val WEB_CLIENT = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    const val TV_CLIENT = "ue6666qo983tsx6so1t0vnawi233wa"
    const val SCOPES = "chat:read chat:edit user:read:follows user:edit:follows"
    private const val GQL = "https://gql.twitch.tv/gql"

    private val json = Json { ignoreUnknownKeys = true }

    /** Konto für Abfragen (gesetzt von [TwitchAccount]). */
    @Volatile var auth: (() -> Pair<String, String>?)? = null // (Token, Client-ID)
    @Volatile var onUnauthorized: () -> Unit = {}

    // ------------------------------------------------------------ IDs

    private val LOGIN = Regex("^[a-z0-9_]{1,25}$")
    private val ID = Regex("^twitch:[a-z0-9_]{1,25}$|^twitch-vod:\\d{1,15}$")

    fun isTwitch(id: String?) = id != null && ID.matches(id)
    fun liveId(login: String) = "twitch:" + login.lowercase()
    fun vodId(id: String) = "twitch-vod:$id"
    fun channelUrl(login: String) = "twitch:" + login.lowercase()
    fun loginOfChannelUrl(url: String?): String? = url?.takeIf { it.startsWith("twitch:") }?.removePrefix("twitch:")?.takeIf { LOGIN.matches(it) }
    fun validLogin(login: String?) = login != null && LOGIN.matches(login.lowercase())

    /** Login (live) bzw. VOD-Nummer aus der ID. */
    fun target(id: String): Pair<String?, String?>? = when {
        !isTwitch(id) -> null
        id.startsWith("twitch-vod:") -> null to id.removePrefix("twitch-vod:")
        else -> id.removePrefix("twitch:") to null
    }

    fun livePreview(login: String, w: Int = 440, h: Int = 248) =
        "https://static-cdn.jtvnw.net/previews-ttv/live_user_$login-${w}x$h.jpg"

    // ------------------------------------------------------------ GraphQL

    private const val STREAM = "id title viewersCount createdAt type previewImageURL(width: 640, height: 360) game { id name displayName boxArtURL(width: 72, height: 96) }"
    private const val USER = "id login displayName profileImageURL(width: 70)"
    private const val VIDEO = "id title lengthSeconds viewCount publishedAt broadcastType previewThumbnailURL(width: 640, height: 360) game { name displayName }"
    private const val GAME = "id name displayName viewersCount boxArtURL(width: 188, height: 250)"

    const val TOKEN_QUERY =
        "query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, \$isVod: Boolean!, \$playerType: String!, \$platform: String!) {" +
            "  streamPlaybackAccessToken(channelName: \$login, params: {platform: \$platform, playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isLive) { value signature authorization { isForbidden forbiddenReasonCode } __typename }" +
            "  videoPlaybackAccessToken(id: \$vodID, params: {platform: \$platform, playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: \$isVod) { value signature __typename } }"

    suspend fun gql(query: String, variables: JsonObject = JsonObject(emptyMap()), withAuth: Boolean = false): JsonObject = withContext(Dispatchers.IO) {
        val account = if (withAuth) auth?.invoke() ?: throw TwitchException("Nicht bei Twitch angemeldet", signIn = true) else null
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()
        val req = Request.Builder()
            .url(GQL)
            .header("Client-ID", account?.second ?: TV_CLIENT)
            .apply { if (account != null) header("Authorization", "OAuth ${account.first}") }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(req).execute().use { r ->
            val text = r.body.string()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (r.code == 401 || obj?.get("status")?.str == "401") {
                if (account != null) onUnauthorized()
                throw TwitchException("Twitch-Anmeldung abgelaufen", "401", signIn = true)
            }
            if (!r.isSuccessful || obj == null) throw TwitchException("Twitch antwortet nicht (HTTP ${r.code})")
            val data = obj["data"] as? JsonObject
            val error = (obj["errors"] as? JsonArray)?.firstOrNull()?.obj?.get("message")?.str
            if (data == null) {
                if (error != null && Regex("unauthenticated|unauthorized", RegexOption.IGNORE_CASE).containsMatchIn(error)) {
                    throw TwitchException("Dafür musst du bei Twitch angemeldet sein", "auth", signIn = true)
                }
                throw TwitchException("Twitch: ${error ?: "unbekannter Fehler"}")
            }
            if (account != null && error != null && error.contains("unauthenticated", true)) {
                onUnauthorized()
                throw TwitchException("Twitch-Anmeldung abgelaufen", "401", signIn = true)
            }
            data
        }
    }

    // ------------------------------------------------------------ Umwandeln

    private val JsonElement?.obj get() = this as? JsonObject
    private val JsonElement?.str get() = (this as? JsonPrimitive)?.contentOrNull
    private val JsonElement?.long get() = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
    private fun JsonObject?.edges(): List<JsonObject> = (this?.get("edges") as? JsonArray)?.mapNotNull { it.obj }.orEmpty()
    private fun JsonObject?.next(): String? {
        val e = edges()
        val more = (this?.get("pageInfo").obj?.get("hasNextPage") as? JsonPrimitive)?.contentOrNull == "true"
        return if (more && e.isNotEmpty()) e.last()["cursor"].str else null
    }

    private fun time(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** Live-Vorschau wechselt alle paar Minuten – nicht ewig aus dem Zwischenspeicher. */
    private fun fresh(url: String) = url + (if ('?' in url) "&" else "?") + "t=" + System.currentTimeMillis() / 300_000

    fun game(obj: JsonObject?): TwitchGame? {
        val o = obj ?: return null
        val name = o["name"].str ?: return null
        return TwitchGame(o["id"].str, name, o["displayName"].str ?: name, o["boxArtURL"].str, o["viewersCount"].long ?: -1)
    }

    /** Live-Stream als Video ('twitch:<login>'); [user]: Kanal, falls der Stream ihn nicht mitbringt. */
    fun stream(node: JsonObject?, user: JsonObject? = null): VideoItem? {
        node ?: return null
        val b = node["broadcaster"].obj ?: user ?: return null
        val login = b["login"].str?.lowercase() ?: return null
        val g = game(node["game"].obj)
        return VideoItem(
            id = liveId(login),
            title = node["title"].str.orEmpty(),
            channelName = b["displayName"].str ?: login,
            channelUrl = channelUrl(login),
            channelAvatar = b["profileImageURL"].str,
            thumbnail = fresh(node["previewImageURL"].str ?: livePreview(login, 640, 360)),
            durationSec = -1,
            viewCount = node["viewersCount"].long ?: -1,
            uploadedAt = time(node["createdAt"].str),
            isLive = true,
            game = g?.displayName,
            gameName = g?.name,
        )
    }

    private val PROCESSING = Regex("/_404/|404_processing")

    /** VOD ('twitch-vod:<id>'). */
    fun vod(obj: JsonObject?, owner: JsonObject? = null): VideoItem? {
        val node = obj ?: return null
        val id = node["id"].str?.takeIf { it.all(Char::isDigit) } ?: return null
        val o = node["owner"].obj ?: owner
        val login = o?.get("login").str?.lowercase()
        val thumb = node["previewThumbnailURL"].str?.takeIf { !PROCESSING.containsMatchIn(it) }
        val g = game(node["game"].obj)
        return VideoItem(
            id = vodId(id),
            title = node["title"].str.orEmpty(),
            channelName = o?.get("displayName").str ?: login.orEmpty(),
            channelUrl = login?.let { channelUrl(it) },
            channelAvatar = o?.get("profileImageURL").str,
            // Noch laufendes VOD hat kein Vorschaubild ("404_processing") → Live-Vorschau
            thumbnail = thumb ?: login?.let { fresh(livePreview(it, 640, 360)) },
            durationSec = node["lengthSeconds"].long ?: -1,
            viewCount = node["viewCount"].long ?: -1,
            uploadedAt = time(node["publishedAt"].str),
            isLive = false,
            game = g?.displayName,
            gameName = g?.name,
        )
    }

    fun channel(obj: JsonObject?): TwitchChannel? {
        val u = obj ?: return null
        val login = u["login"].str?.lowercase() ?: return null
        return TwitchChannel(
            login = login,
            userId = u["id"].str,
            name = u["displayName"].str ?: login,
            avatar = u["profileImageURL150"].str ?: u["profileImageURL"].str,
            followers = u["followers"].obj?.get("totalCount").long ?: -1,
            live = u["stream"].obj?.let { stream(it, u) },
            description = u["description"].str?.takeIf { it.isNotBlank() },
            banner = u["bannerImageURL"].str,
        )
    }

    // ------------------------------------------------------------ Abfragen

    private fun options(language: String) = when (language) {
        "de" -> "{sort: VIEWER_COUNT, broadcasterLanguages: [DE]}"
        "en" -> "{sort: VIEWER_COUNT, broadcasterLanguages: [EN]}"
        else -> "{sort: VIEWER_COUNT}"
    }

    private fun vars(vararg pairs: Pair<String, Any?>) = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            null -> put(k, JsonNull)
            is String -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            is List<*> -> put(k, JsonArray(v.map { JsonPrimitive(it.toString()) }))
            else -> put(k, v.toString())
        }
    }

    /** Startseite: beliebte Streams (Sprache), weltweit beliebt, größte Kategorien. */
    suspend fun home(language: String): TwitchHome {
        val d = gql(
            "query { lang: streams(first: 24, options: ${options(language)}) { edges { cursor node { $STREAM broadcaster { $USER } } } pageInfo { hasNextPage } }" +
                " top: streams(first: 12, options: {sort: VIEWER_COUNT}) { edges { node { $STREAM broadcaster { $USER } } } }" +
                " games(first: 30, options: {sort: VIEWER_COUNT}) { edges { node { $GAME } } } }",
        )
        return TwitchHome(
            streams = d["lang"].obj.edges().mapNotNull { stream(it["node"].obj) },
            next = d["lang"].obj.next(),
            top = d["top"].obj.edges().mapNotNull { stream(it["node"].obj) },
            games = d["games"].obj.edges().mapNotNull { game(it["node"].obj) }.distinctBy { it.name },
        )
    }

    suspend fun moreStreams(language: String, cursor: String): TwitchPage<VideoItem> {
        val d = gql(
            "query(\$after: Cursor) { streams(first: 24, after: \$after, options: ${options(language)}) { edges { cursor node { $STREAM broadcaster { $USER } } } pageInfo { hasNextPage } } }",
            vars("after" to cursor),
        )
        return TwitchPage(d["streams"].obj.edges().mapNotNull { stream(it["node"].obj) }, d["streams"].obj.next())
    }

    /** Streams einer Kategorie. */
    suspend fun category(name: String, language: String, cursor: String? = null): TwitchCategory {
        val d = gql(
            "query(\$name: String!, \$after: Cursor) { game(name: \$name) { $GAME streams(first: 30, after: \$after, options: ${options(language)}) { edges { cursor node { $STREAM broadcaster { $USER } } } pageInfo { hasNextPage } } } }",
            vars("name" to name.take(200), "after" to cursor),
        )
        val g = d["game"].obj ?: throw TwitchException("Kategorie nicht gefunden")
        val streams = g["streams"].obj
        return TwitchCategory(game(g)!!, TwitchPage(streams.edges().mapNotNull { stream(it["node"].obj) }, streams.next()))
    }

    /** Suche: Kanäle (genau der gesuchte zuerst, dann wer live ist) und Kategorien. */
    suspend fun search(query: String): TwitchSearch {
        val d = gql(
            "query(\$q: String!) { searchFor(userQuery: \$q, platform: \"web\", options: {targets: [{index: CHANNEL}]}) {" +
                " channels { items { $USER followers { totalCount } stream { $STREAM } } } }" +
                " searchCategories(query: \$q, first: 12) { edges { node { $GAME } } } }",
            vars("q" to query.take(200)),
        )
        val channels = (d["searchFor"].obj?.get("channels").obj?.get("items") as? JsonArray)?.mapNotNull { channel(it.obj) }.orEmpty().distinctBy { it.login }
        val wanted = query.trim().lowercase().removePrefix("@")
        val exact = channels.filter { it.login == wanted || it.name.lowercase() == wanted }
        val rest = channels - exact.toSet()
        return TwitchSearch(
            channels = exact + rest.filter { it.live != null }.sortedByDescending { it.live?.viewCount ?: 0 } + rest.filter { it.live == null },
            games = d["searchCategories"].obj.edges().mapNotNull { game(it["node"].obj) }.distinctBy { it.name },
        )
    }

    private const val CHANNEL_QUERY = "query(\$login: String!, \$after: Cursor) { user(login: \$login) {" +
        " $USER profileImageURL150: profileImageURL(width: 150) description bannerImageURL followers { totalCount }" +
        " stream { $STREAM }" +
        " videos(first: 30, after: \$after, sort: TIME) { edges { cursor node { $VIDEO } } pageInfo { hasNextPage } } } }"

    /** Kanalseite: Kopf, laufender Stream und VODs. */
    suspend fun channelPage(login: String, cursor: String? = null): TwitchChannelPage {
        if (!validLogin(login)) throw TwitchException("Ungültiger Kanal")
        val u = gql(CHANNEL_QUERY, vars("login" to login.lowercase(), "after" to cursor))["user"].obj
            ?: throw TwitchException("Kanal nicht gefunden")
        val c = channel(u)!!
        val videos = u["videos"].obj
        return TwitchChannelPage(c, TwitchPage(videos.edges().mapNotNull { vod(it["node"].obj, u) }, videos.next()))
    }

    /** Kanäle mit Live-Status (z. B. die gefolgten). */
    suspend fun users(logins: List<String>): List<TwitchChannel> {
        val list = logins.map { it.lowercase() }.filter { LOGIN.matches(it) }.distinct()
        val out = ArrayList<TwitchChannel>()
        for (chunk in list.chunked(50)) {
            val d = gql("query(\$logins: [String!]) { users(logins: \$logins) { $USER stream { $STREAM } } }", vars("logins" to chunk))
            (d["users"] as? JsonArray)?.forEach { el -> channel(el.obj)?.let { out += it } }
        }
        return out
    }

    /** Was gerade läuft (für den Player). */
    suspend fun watchInfo(id: String): TwitchWatchInfo {
        val (login, vod) = target(id) ?: throw TwitchException("Ungültiger Twitch-Stream")
        if (vod != null) {
            val v = gql("query(\$id: ID!) { video(id: \$id) { $VIDEO owner { $USER followers { totalCount } } } }", vars("id" to vod))["video"].obj
                ?: throw TwitchException("Dieses Video gibt es nicht mehr")
            val owner = v["owner"].obj
            return TwitchWatchInfo(vod(v)!!, owner?.get("login").str?.lowercase(), owner?.get("id").str, owner?.get("followers").obj?.get("totalCount").long ?: -1, null, game(v["game"].obj))
        }
        val u = gql("query(\$login: String!) { user(login: \$login) { $USER followers { totalCount } stream { $STREAM } } }", vars("login" to login!!))["user"].obj
            ?: throw TwitchException("Kanal nicht gefunden")
        val s = u["stream"].obj ?: throw TwitchException("${u["displayName"].str ?: login} ist gerade nicht live", "offline")
        val video = stream(s, u)!!
        return TwitchWatchInfo(video, login, u["id"].str, u["followers"].obj?.get("totalCount").long ?: -1, video.uploadedAt, game(s["game"].obj))
    }

    // ------------------------------------------------------------ Wiedergabe

    private val FORBIDDEN = mapOf(
        "UNAUTHORIZED_ENTITLEMENTS" to "Nur für Abonnenten des Kanals – melde dich mit einem Konto an, das den Kanal abonniert hat.",
        "GEOBLOCK" to "Dieser Stream ist in deinem Land nicht verfügbar.",
    )

    fun usherUrl(login: String?, vod: String?, value: String, signature: String): String {
        val base = if (vod != null) "https://usher.ttvnw.net/vod/$vod.m3u8" else "https://usher.ttvnw.net/api/channel/hls/$login.m3u8"
        val params = listOf(
            "allow_source" to "true",
            "allow_audio_only" to "true",
            "cdm" to "wv",
            "fast_bread" to "true",
            "p" to Random.nextInt(10_000_000).toString(),
            "play_session_id" to TwitchAdBlock.randomHex(32),
            "player_backend" to "mediaplayer",
            "playlist_include_framerate" to "true",
            "reassignments_supported" to "true",
            // Nur H.264: läuft überall, und das schwarze Ersatz-Stück des Werbeblockers ist H.264
            "supported_codecs" to "avc1",
            "sig" to signature,
            "token" to value,
        )
        return base + "?" + params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
    }

    /** Wiedergabe-Adresse. Angemeldet zuerst mit Konto (Abo-Streams, Abonnenten ohne Werbung), sonst anonym. */
    suspend fun playback(id: String): TwitchPlayback {
        val (login, vod) = target(id) ?: throw TwitchException("Ungültiger Twitch-Stream")
        val v = vars("isLive" to (vod == null), "login" to (login ?: ""), "isVod" to (vod != null), "vodID" to (vod ?: ""), "playerType" to "site", "platform" to "web")
        var last: Exception? = null
        for (withAuth in if (auth?.invoke() != null) listOf(true, false) else listOf(false)) {
            try {
                val d = gql(TOKEN_QUERY, v, withAuth)
                val t = (d["streamPlaybackAccessToken"] ?: d["videoPlaybackAccessToken"]).obj
                    ?: throw TwitchException(if (vod != null) "Dieses Video gibt es nicht mehr" else "Kanal nicht gefunden")
                val value = t["value"].str
                val sig = t["signature"].str
                if (value == null || sig == null) throw TwitchException(if (vod != null) "Dieses Video gibt es nicht mehr" else "Kanal nicht gefunden")
                val a = t["authorization"].obj
                if (a != null && a["isForbidden"].str == "true") {
                    val code = a["forbiddenReasonCode"].str
                    throw TwitchException(FORBIDDEN[code] ?: "Twitch erlaubt die Wiedergabe nicht (${code ?: "gesperrt"})", code, signIn = code == "UNAUTHORIZED_ENTITLEMENTS" && auth?.invoke() == null)
                }
                return TwitchPlayback(usherUrl(login, vod, value, sig), vod == null, login, vod, withAuth)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: TwitchException("Stream konnte nicht geladen werden")
    }

    // ------------------------------------------------------------ Mit Konto

    /** Kanäle, denen das Konto folgt. */
    suspend fun followedChannels(limit: Int = 300): List<TwitchChannel> {
        val out = ArrayList<TwitchChannel>()
        var after: String? = null
        while (out.size < limit) {
            val d = gql(
                "query(\$after: Cursor) { currentUser { follows(first: 100, after: \$after) { edges { cursor node { $USER stream { $STREAM } } } pageInfo { hasNextPage } } } }",
                vars("after" to after),
                withAuth = true,
            )
            val conn = d["currentUser"].obj?.get("follows").obj
            conn.edges().forEach { e -> channel(e["node"].obj)?.let { out += it } }
            after = conn.next() ?: break
        }
        return out
    }

    /** Folgen / entfolgen mit dem Konto. */
    suspend fun follow(userId: String, on: Boolean) {
        val q = if (on) "mutation(\$id: ID!) { followUser(input: {targetID: \$id, disableNotifications: false}) { follow { followedAt } error { code } } }"
        else "mutation(\$id: ID!) { unfollowUser(input: {targetID: \$id}) { follow { followedAt } } }"
        val d = gql(q, vars("id" to userId), withAuth = true)
        val code = d["followUser"].obj?.get("error").obj?.get("code").str
        if (code != null) throw TwitchException("Twitch: $code")
        if ((if (on) d["followUser"] else d["unfollowUser"]) !is JsonObject) throw TwitchException("Twitch hat nicht geantwortet")
    }

    // ------------------------------------------------------------ Anmeldung per Code

    @Serializable
    data class Tokens(
        val accessToken: String,
        val refreshToken: String? = null,
        val expiresAt: Long? = null,
        val scopes: List<String> = emptyList(),
        val clientId: String = TV_CLIENT,
    )

    data class DeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresAt: Long, val interval: Int) {
        /** Mit schon eingetragenem Code (fürs Anmelden hier in der App). */
        val activateUrl: String get() = verificationUrl + (if ('?' in verificationUrl) "&" else "?") + "device-code=" + URLEncoder.encode(userCode, "UTF-8")
    }

    class DeviceLoginException(val reason: String, message: String) : IOException(message)

    private suspend fun postForm(url: String, vararg fields: Pair<String, String>): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        Http.client.newCall(Request.Builder().url(url).post(form).build()).execute().use { r ->
            r.code to (runCatching { json.parseToJsonElement(r.body.string()).jsonObject }.getOrNull() ?: JsonObject(emptyMap()))
        }
    }

    suspend fun startDeviceLogin(): DeviceCode {
        val (code, j) = postForm("https://id.twitch.tv/oauth2/device", "client_id" to TV_CLIENT, "scopes" to SCOPES)
        val deviceCode = j["device_code"].str ?: throw TwitchException("Twitch gibt gerade keinen Code heraus (${j["message"].str ?: code})")
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = j["user_code"].str.orEmpty(),
            verificationUrl = j["verification_uri"].str ?: "https://www.twitch.tv/activate",
            expiresAt = System.currentTimeMillis() + (j["expires_in"].long ?: 1800) * 1000,
            interval = (j["interval"].long ?: 5).toInt(),
        )
    }

    /** Wartet, bis der Code bestätigt ist (wirft [DeviceLoginException] bei Ablauf/Ablehnung). */
    suspend fun awaitDeviceLogin(code: DeviceCode): Tokens {
        var delayMs = code.interval.coerceAtLeast(2) * 1000L
        while (true) {
            delay(delayMs)
            if (System.currentTimeMillis() > code.expiresAt) throw DeviceLoginException("expired", "Der Code ist abgelaufen")
            val (status, j) = runCatching {
                postForm(
                    "https://id.twitch.tv/oauth2/token",
                    "client_id" to TV_CLIENT,
                    "device_code" to code.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "scopes" to SCOPES,
                )
            }.getOrNull() ?: continue
            if (status in 200..299 && j["access_token"].str != null) return tokens(j, null)
            val msg = j["message"].str.orEmpty()
            when {
                "authorization_pending" in msg -> Unit
                "slow_down" in msg -> delayMs += 5000
                Regex("expired|invalid device code", RegexOption.IGNORE_CASE).containsMatchIn(msg) -> throw DeviceLoginException("expired", "Der Code ist abgelaufen")
                msg.contains("denied", true) -> throw DeviceLoginException("denied", "Die Anmeldung wurde abgelehnt")
                else -> throw DeviceLoginException("error", msg.ifBlank { "HTTP $status" })
            }
        }
    }

    private fun tokens(j: JsonObject, oldRefresh: String?): Tokens {
        val scope = j["scope"]
        return Tokens(
            accessToken = j["access_token"].str!!,
            refreshToken = j["refresh_token"].str ?: oldRefresh,
            expiresAt = j["expires_in"].long?.let { System.currentTimeMillis() + it * 1000 },
            scopes = (scope as? JsonArray)?.mapNotNull { it.str } ?: scope.str?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
        )
    }

    suspend fun refresh(t: Tokens): Tokens {
        val refresh = t.refreshToken ?: throw TwitchException("Twitch-Anmeldung abgelaufen", "401", signIn = true)
        val (status, j) = postForm("https://id.twitch.tv/oauth2/token", "client_id" to t.clientId, "grant_type" to "refresh_token", "refresh_token" to refresh)
        if (status !in 200..299 || j["access_token"].str == null) throw TwitchException("Twitch-Anmeldung abgelaufen", "401", signIn = true)
        return tokens(j, refresh).copy(clientId = t.clientId)
    }

    data class Validation(val login: String, val userId: String?, val scopes: List<String>)

    /** Token prüfen (null: ungültig). */
    suspend fun validate(token: String): Validation? = withContext(Dispatchers.IO) {
        Http.client.newCall(Request.Builder().url("https://id.twitch.tv/oauth2/validate").header("Authorization", "OAuth $token").build()).execute().use { r ->
            if (r.code == 401) return@withContext null
            val j = runCatching { json.parseToJsonElement(r.body.string()).jsonObject }.getOrNull()
                ?: throw TwitchException("Twitch-Prüfung fehlgeschlagen (HTTP ${r.code})")
            val login = j["login"].str ?: throw TwitchException("Twitch-Prüfung fehlgeschlagen (HTTP ${r.code})")
            Validation(login, j["user_id"].str, (j["scopes"] as? JsonArray)?.mapNotNull { it.str }.orEmpty())
        }
    }

    suspend fun revoke(t: Tokens) {
        runCatching { postForm("https://id.twitch.tv/oauth2/revoke", "client_id" to t.clientId, "token" to t.accessToken) }
    }
}
