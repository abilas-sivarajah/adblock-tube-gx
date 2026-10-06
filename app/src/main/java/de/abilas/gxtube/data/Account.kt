package de.abilas.gxtube.data

import android.content.Context
import android.util.Log
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.TimeAgoPatternsManager
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeChannelInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeReelInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemLockupExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItemExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@Serializable
data class AccountData(
    /** Cookies von youtube.com nach der Anmeldung (bleiben nur auf dem Handy) */
    val cookies: String,
    val name: String? = null,
    val handle: String? = null,
    val avatar: String? = null,
)

class AccountException(message: String) : IOException(message)

/**
 * Freiwillige Anmeldung mit dem Google-Konto. Damit kommen Startseite, Abos, Verlauf,
 * "Später ansehen" und "Mag ich" direkt von YouTube, und Abonnieren/Liken landet im Konto.
 * Die Videos selbst werden weiter ohne Konto und ohne Werbung geladen.
 */
object Account {
    private const val TAG = "Account"
    private const val ORIGIN = "https://www.youtube.com"
    private const val API = "https://www.youtube.com/youtubei/v1/"

    private var file: File? = null
    private val _state = MutableStateFlow<AccountData?>(null)
    val state: StateFlow<AccountData?> = _state.asStateFlow()
    val loggedIn: Boolean get() = _state.value != null

    fun init(context: Context) {
        val f = File(context.filesDir, "account.json")
        file = f
        if (f.exists()) {
            runCatching { Library.json.decodeFromString<AccountData>(f.readText()) }
                .onSuccess { _state.value = it }
        }
    }

    private fun save(data: AccountData?) {
        _state.value = data
        val f = file ?: return
        runCatching {
            if (data == null) f.delete()
            else f.writeText(Library.json.encodeToString(AccountData.serializer(), data))
        }
    }

    /** Nach erfolgreicher Anmeldung in der WebView. */
    suspend fun login(cookies: String) {
        require(sapisid(cookies) != null) { "Anmeldung unvollständig (SAPISID fehlt)" }
        save(AccountData(cookies))
        runCatching { refreshProfile() }.onFailure { Log.w(TAG, "Profil nicht geladen", it) }
    }

    fun logout() = save(null)

    // ------------------------------------------------------------ Anfragen

    private fun sapisid(cookies: String): String? =
        Regex("""(?:^|;\s*)(?:SAPISID|__Secure-3PAPISID)=([^;]+)""").find(cookies)?.groupValues?.get(1)

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun authorization(cookies: String): String {
        val sid = sapisid(cookies) ?: throw AccountException("Nicht angemeldet")
        val ts = System.currentTimeMillis() / 1000
        val hash = "${ts}_${sha1("$ts $sid $ORIGIN")}"
        return "SAPISIDHASH $hash SAPISID1PHASH $hash SAPISID3PHASH $hash"
    }

    private fun context(): String {
        val s = Library.settings
        val version = runCatching { YoutubeParsingHelper.getClientVersion() }.getOrDefault("2.20260805.01.00")
        return """{"client":{"clientName":"WEB","clientVersion":"$version","hl":"${s.language}","gl":"${s.country}"},""" +
            """"user":{"lockedSafetyMode":false}}"""
    }

    /** POST an die YouTube-Schnittstelle mit Konto. [fields] ist JSON ohne äußere Klammern. */
    private suspend fun call(endpoint: String, fields: String): JsonObject = withContext(Dispatchers.IO) {
        val account = _state.value ?: throw AccountException("Nicht angemeldet")
        val version = runCatching { YoutubeParsingHelper.getClientVersion() }.getOrDefault("2.20260805.01.00")
        val body = """{"context":${context()}${if (fields.isBlank()) "" else ",$fields"}}"""
        val request = okhttp3.Request.Builder()
            .url("$API$endpoint?prettyPrint=false")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", Http.USER_AGENT)
            .header("Cookie", account.cookies)
            .header("Authorization", authorization(account.cookies))
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-Goog-AuthUser", "0")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", version)
            .build()
        Http.client.newCall(request).execute().use { r ->
            val text = r.body.string()
            if (r.code == 401 || r.code == 403) {
                throw AccountException("YouTube hat die Anmeldung abgelehnt (HTTP ${r.code}). Bitte neu anmelden.")
            }
            if (!r.isSuccessful) throw AccountException("YouTube-Fehler HTTP ${r.code}")
            JsonParser.`object`().from(text)
        }
    }

    private fun quote(s: String) = JsonWriter.string(s)

    // ------------------------------------------------------------ Profil

    suspend fun refreshProfile() {
        val json = call("account/account_menu", "")
        val header = json.getArray("actions").getObject(0)
            .getObject("openPopupAction").getObject("popup")
            .getObject("multiPageMenuRenderer").getObject("header")
            .getObject("activeAccountHeaderRenderer")
        val current = _state.value ?: return
        save(
            current.copy(
                name = YoutubeParsingHelper.getTextFromObject(header.getObject("accountName")) ?: current.name,
                handle = YoutubeParsingHelper.getTextFromObject(header.getObject("channelHandle")) ?: current.handle,
                avatar = YoutubeParsingHelper.getImagesFromThumbnailsArray(
                    header.getObject("accountPhoto").getArray("thumbnails"),
                ).bestUrl(200) ?: current.avatar,
            ),
        )
    }

    // ------------------------------------------------------------ Listen von YouTube

    private class Found(
        val videos: MutableList<StreamInfoItemExtractor> = mutableListOf(),
        val channels: MutableList<JsonObject> = mutableListOf(),
        var continuation: String? = null,
    )

    /** Sucht Videos, Kanäle und das Weiterlade-Token überall in der Antwort. */
    private fun walk(node: Any?, found: Found, parser: org.schabi.newpipe.extractor.localization.TimeAgoParser?) {
        when (node) {
            is JsonObject -> {
                for ((key, value) in node) {
                    val obj = value as? JsonObject
                    when {
                        obj != null && (key == "videoRenderer" || key == "gridVideoRenderer" ||
                            key == "playlistVideoRenderer" || key == "compactVideoRenderer") ->
                            found.videos += YoutubeStreamInfoItemExtractor(obj, parser)
                        obj != null && key == "lockupViewModel" &&
                            obj.getString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" ->
                            found.videos += YoutubeStreamInfoItemLockupExtractor(obj, parser)
                        obj != null && key == "reelItemRenderer" ->
                            found.videos += YoutubeReelInfoItemExtractor(obj)
                        obj != null && key == "channelRenderer" -> found.channels += obj
                        obj != null && key == "continuationCommand" && obj.getString("token") != null ->
                            found.continuation = obj.getString("token")
                        else -> walk(value, found, parser)
                    }
                }
            }
            is JsonArray -> node.forEach { walk(it, found, parser) }
        }
    }

    private fun collect(found: Found): List<VideoItem> {
        val collector = StreamInfoItemsCollector(ServiceList.YouTube.serviceId)
        val items = mutableListOf<StreamInfoItem>()
        found.videos.forEach { ex -> runCatching { collector.extract(ex) }.getOrNull()?.let(items::add) }
        return items.mapNotNull { it.toVideoItem() }.distinctBy { it.id }
    }

    private suspend fun browsePaged(fields: String): Paged<VideoItem> {
        val json = call("browse", fields)
        val parser = runCatching {
            TimeAgoPatternsManager.getTimeAgoParserFor(
                org.schabi.newpipe.extractor.localization.Localization(Library.settings.language, Library.settings.country),
            )
        }.getOrNull()
        val found = Found()
        walk(json, found, parser)
        val token = found.continuation
        return Paged(
            collect(found),
            token?.let { t -> suspend { browsePaged(""""continuation":${quote(t)}""") } },
        )
    }

    /** Deine persönliche YouTube-Startseite. */
    suspend fun home(): Paged<VideoItem> = browsePaged(""""browseId":"FEwhat_to_watch"""")

    /** Neue Videos deiner Abos (von YouTube). */
    suspend fun subscriptionFeed(): Paged<VideoItem> = browsePaged(""""browseId":"FEsubscriptions"""")

    /** Dein YouTube-Verlauf. */
    suspend fun history(): Paged<VideoItem> = browsePaged(""""browseId":"FEhistory"""")

    /** "Später ansehen" (WL) oder "Mag ich" (LL). */
    suspend fun playlist(id: String): Paged<VideoItem> = browsePaged(""""browseId":"VL$id"""")

    /** Alle abonnierten Kanäle. */
    suspend fun subscriptions(): List<Subscription> {
        val result = mutableListOf<Subscription>()
        var fields = """"browseId":"FEchannels""""
        repeat(30) {
            val json = call("browse", fields)
            val found = Found()
            walk(json, found, null)
            found.channels.forEach { obj ->
                runCatching {
                    val ex = YoutubeChannelInfoItemExtractor(obj)
                    Subscription(ex.url, ex.name, ex.thumbnails.bestUrl(240))
                }.getOrNull()?.let(result::add)
            }
            val token = found.continuation ?: return result.distinctBy { channelKey(it.url) }
            fields = """"continuation":${quote(token)}"""
        }
        return result.distinctBy { channelKey(it.url) }
    }

    // ------------------------------------------------------------ Aktionen

    private fun channelId(url: String): String? =
        channelKey(url).takeIf { it.startsWith("UC") && it.length == 24 }

    suspend fun setSubscribed(channelUrl: String, subscribed: Boolean) {
        val id = channelId(channelUrl) ?: return
        call(if (subscribed) "subscription/subscribe" else "subscription/unsubscribe", """"channelIds":[${quote(id)}]""")
    }

    enum class Rating { LIKE, DISLIKE, NONE }

    suspend fun rate(videoId: String, rating: Rating) {
        val endpoint = when (rating) {
            Rating.LIKE -> "like/like"
            Rating.DISLIKE -> "like/dislike"
            Rating.NONE -> "like/removelike"
        }
        call(endpoint, """"target":{"videoId":${quote(videoId)}}""")
    }

    suspend fun setWatchLater(videoId: String, add: Boolean) {
        val action = if (add) {
            """{"action":"ACTION_ADD_VIDEO","addedVideoId":${quote(videoId)}}"""
        } else {
            """{"action":"ACTION_REMOVE_VIDEO_BY_VIDEO_ID","removedVideoId":${quote(videoId)}}"""
        }
        call("browse/edit_playlist", """"playlistId":"WL","actions":[$action]""")
    }
}
