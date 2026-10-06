package de.abilas.gxtube.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Eine Liste, die beim Scrollen weitere Seiten nachlädt. */
class Paged<T>(val items: List<T>, val loadMore: (suspend () -> Paged<T>)?)

data class ChannelTab(val key: String, val label: String, val handler: ListLinkHandler)

data class ChannelPage(
    val ref: ChannelRef,
    val banner: String?,
    val tabs: List<ChannelTab>,
)

data class Comment(
    val id: String,
    val author: String,
    val authorUrl: String?,
    val avatar: String?,
    val text: Description,
    val likes: Int,
    val date: String,
    val replyCount: Int,
    val replies: Page?,
    val pinned: Boolean,
    val hearted: Boolean,
    val byChannelOwner: Boolean,
)

data class CommentsResult(val count: Int, val disabled: Boolean, val page: Paged<Comment>)

data class PlaylistPage(val ref: PlaylistRef, val uploaderAvatar: String?, val videos: Paged<VideoItem>)

enum class SearchFilter(val label: String, val filter: String) {
    ALL("Alle", "all"),
    VIDEOS("Videos", "videos"),
    CHANNELS("Kanäle", "channels"),
    PLAYLISTS("Playlists", "playlists"),
}

/** Startseiten-Kategorien (YouTube-"Trends" pro Bereich). */
enum class Kiosk(val id: String, val label: String) {
    MUSIC("trending_music", "Musik"),
    GAMING("trending_gaming", "Gaming"),
    LIVE("live", "Live"),
    MOVIES("trending_movies_and_shows", "Filme & Trailer"),
    PODCASTS("trending_podcasts_episodes", "Podcasts"),
}

/** Alle Abfragen an YouTube – direkt über NewPipe Extractor, ohne Werbedaten. */
object YouTubeRepo {
    val service: StreamingService get() = ServiceList.YouTube

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun <I : InfoItem, T : Any> paged(
        items: List<I>,
        next: Page?,
        map: (I) -> T?,
        fetch: (Page) -> ListExtractor.InfoItemsPage<out I>,
    ): Paged<T> {
        val loader: (suspend () -> Paged<T>)? =
            if (next != null && Page.isValid(next)) {
                suspend {
                    io {
                        val p = fetch(next)
                        paged(p.items, p.nextPage, map, fetch)
                    }
                }
            } else null
        return Paged(items.mapNotNull(map), loader)
    }

    // ---------------------------------------------------------------- Video

    suspend fun stream(url: String): StreamInfo = io { StreamInfo.getInfo(service, url) }

    fun relatedOf(info: StreamInfo): List<VideoItem> =
        info.relatedItems.mapNotNull { (it as? StreamInfoItem)?.toVideoItem() }

    // ---------------------------------------------------------------- Startseite

    suspend fun kiosk(kiosk: Kiosk): Paged<VideoItem> = io {
        val extractor = service.kioskList.getExtractorById(kiosk.id, null)
        extractor.fetchPage()
        val info = KioskInfo.getInfo(extractor)
        val url = info.url
        paged(info.relatedItems, info.nextPage, { it.toVideoItem() }) { page ->
            KioskInfo.getMoreItems(service, url, page)
        }
    }

    // ---------------------------------------------------------------- Suche

    suspend fun search(query: String, filter: SearchFilter): Pair<String?, Paged<ListEntry>> = io {
        val handler = service.searchQHFactory.fromQuery(query, listOf(filter.filter), "")
        val info = org.schabi.newpipe.extractor.search.SearchInfo.getInfo(service, handler)
        val suggestion = info.searchSuggestion?.takeIf { it.isNotBlank() && it != query }
        suggestion to paged(info.relatedItems, info.nextPage, { it.toEntry() }) { page ->
            org.schabi.newpipe.extractor.search.SearchInfo.getMoreItems(service, handler, page)
        }
    }

    suspend fun suggestions(query: String): List<String> = io {
        runCatching { service.suggestionExtractor.suggestionList(query) }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- Kanal

    suspend fun channel(url: String): ChannelPage = io {
        val info = ChannelInfo.getInfo(service, url)
        val ref = ChannelRef(
            url = info.url,
            name = info.name.orEmpty(),
            avatar = info.avatars.bestUrl(400),
            subscriberCount = info.subscriberCount,
            description = info.description,
            verified = runCatching { info.isVerified }.getOrDefault(false),
        )
        val tabs = info.tabs.mapNotNull { handler ->
            val key = handler.contentFilters.firstOrNull() ?: return@mapNotNull null
            val label = when (key) {
                ChannelTabs.VIDEOS -> "Videos"
                ChannelTabs.SHORTS -> "Shorts"
                ChannelTabs.LIVESTREAMS -> "Live"
                ChannelTabs.PLAYLISTS -> "Playlists"
                ChannelTabs.PODCASTS -> "Podcasts"
                else -> return@mapNotNull null
            }
            ChannelTab(key, label, handler)
        }
        ChannelPage(ref, info.banners.bestUrl(), tabs)
    }

    suspend fun channelTab(tab: ChannelTab): Paged<ListEntry> = io {
        val info = ChannelTabInfo.getInfo(service, tab.handler)
        paged(info.relatedItems, info.nextPage, { it.toEntry() }) { page ->
            ChannelTabInfo.getMoreItems(service, tab.handler, page)
        }
    }

    /** Die Shorts eines Kanals (für den Shorts-Tab). */
    suspend fun channelShorts(channelUrl: String): List<VideoItem> = io {
        val info = ChannelInfo.getInfo(service, channelUrl)
        val handler = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.SHORTS) }
            ?: return@io emptyList()
        ChannelTabInfo.getInfo(service, handler).relatedItems
            .mapNotNull { (it as? StreamInfoItem)?.toVideoItem()?.copy(isShort = true) }
    }

    suspend fun channelAvatar(url: String): String? = io {
        ChannelInfo.getInfo(service, url).avatars.bestUrl(400)
    }

    /** Neueste Videos eines Kanals über den RSS-Feed – schnell, ideal für den Abo-Feed. */
    suspend fun feed(channelUrl: String): List<VideoItem> = io {
        FeedInfo.getInfo(service, channelUrl).relatedItems.mapNotNull { it.toVideoItem() }
    }

    // ---------------------------------------------------------------- Kommentare

    private fun CommentsInfoItem.toComment(): Comment = Comment(
        id = commentId ?: url.orEmpty(),
        author = uploaderName.orEmpty(),
        authorUrl = uploaderUrl,
        avatar = uploaderAvatars.bestUrl(160),
        text = commentText ?: Description.EMPTY_DESCRIPTION,
        likes = likeCount,
        date = textualUploadDate.orEmpty(),
        replyCount = replyCount,
        replies = replies,
        pinned = isPinned,
        hearted = isHeartedByUploader,
        byChannelOwner = isChannelOwner,
    )

    suspend fun comments(videoUrl: String): CommentsResult = io {
        val info = CommentsInfo.getInfo(service, videoUrl)
        val url = info.url
        CommentsResult(
            count = info.commentsCount,
            disabled = info.isCommentsDisabled,
            page = paged(info.relatedItems, info.nextPage, { it.toComment() }) { page ->
                CommentsInfo.getMoreItems(service, url, page)
            },
        )
    }

    suspend fun replies(videoUrl: String, page: Page): Paged<Comment> = io {
        val p = CommentsInfo.getMoreItems(service, videoUrl, page)
        paged(p.items, p.nextPage, { it.toComment() }) { next ->
            CommentsInfo.getMoreItems(service, videoUrl, next)
        }
    }

    // ---------------------------------------------------------------- Playlist

    suspend fun playlist(url: String): PlaylistPage = io {
        val info = PlaylistInfo.getInfo(service, url)
        val ref = PlaylistRef(
            url = info.url,
            name = info.name.orEmpty(),
            thumbnail = info.thumbnails.bestUrl(),
            uploader = info.uploaderName,
            count = info.streamCount,
        )
        PlaylistPage(
            ref = ref,
            uploaderAvatar = info.uploaderAvatars.bestUrl(160),
            videos = paged(info.relatedItems, info.nextPage, { it.toVideoItem() }) { page ->
                PlaylistInfo.getMoreItems(service, url, page)
            },
        )
    }

    // ---------------------------------------------------------------- Abos importieren

    /**
     * Liest Abos aus Google Takeout (ZIP oder subscriptions.csv) oder einem
     * NewPipe-/GX-Tube-Export (JSON).
     */
    suspend fun importSubscriptions(input: InputStream, fileName: String?): List<Subscription> = io {
        val bytes = input.readBytes()
        val head = String(bytes, 0, minOf(bytes.size, 64)).trimStart()
        val name = fileName.orEmpty().lowercase()
        when {
            head.startsWith("{") -> parseNewPipeJson(String(bytes))
            bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
                fromExtractor(bytes, "zip")
            name.endsWith(".json") || head.startsWith("[") -> fromExtractor(bytes, "json")
            else -> fromExtractor(bytes, "csv")
        }
    }

    private fun fromExtractor(bytes: ByteArray, type: String): List<Subscription> =
        service.subscriptionExtractor.fromInputStream(ByteArrayInputStream(bytes), type)
            .map { Subscription(url = it.url(), name = it.name()) }

    private fun parseNewPipeJson(text: String): List<Subscription> {
        val root = Library.json.parseToJsonElement(text).jsonObject
        return root["subscriptions"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!url.contains("youtube.com")) return@mapNotNull null
            Subscription(url = url, name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }

    /** Export im NewPipe-Format – lässt sich auch in NewPipe importieren. */
    fun exportSubscriptionsJson(subs: List<Subscription>): String {
        val items = subs.joinToString(",\n") { s ->
            "    {\"service_id\": 0, \"url\": ${quote(s.url)}, \"name\": ${quote(s.name)}}"
        }
        return "{\n  \"app_version\": \"0.28.0\",\n  \"app_version_int\": 1006,\n  \"subscriptions\": [\n$items\n  ]\n}\n"
    }

    private fun quote(s: String) = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
        append('"')
    }

    // ---------------------------------------------------------------- Fehlertexte

    fun errorText(t: Throwable): String = when (t) {
        is ReCaptchaException -> "YouTube möchte eine Bestätigung (zu viele Anfragen). Bitte später erneut versuchen."
        is SignInConfirmNotBotException -> "YouTube blockiert gerade anonyme Zugriffe von deinem Netz. Später erneut versuchen oder WLAN/Mobilfunk wechseln."
        is AgeRestrictedContentException -> "Altersbeschränktes Video – ohne Anmeldung nicht abspielbar."
        is GeographicRestrictionException -> "Dieses Video ist in deinem Land nicht verfügbar."
        is YoutubeMusicPremiumContentException -> "Nur mit YouTube Music Premium verfügbar."
        is PaidContentException -> "Dieses Video ist kostenpflichtig oder nur für Kanalmitglieder."
        is PrivateContentException -> "Dieses Video ist privat."
        is ContentNotAvailableException -> "Video nicht verfügbar." + (t.message?.let { "\n$it" } ?: "")
        is IOException -> "Keine Verbindung zu YouTube. Bitte Internet prüfen."
        else -> t.cause?.takeIf { it !== t }?.let { errorText(it) }
            ?: ("Fehler: " + (t.message ?: t.javaClass.simpleName))
    }
}
