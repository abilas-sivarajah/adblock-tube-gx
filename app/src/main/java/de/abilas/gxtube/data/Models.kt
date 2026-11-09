package de.abilas.gxtube.data

import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

@Serializable
data class VideoItem(
    val id: String,
    val title: String,
    val channelName: String = "",
    val channelUrl: String? = null,
    val channelAvatar: String? = null,
    val thumbnail: String? = null,
    val durationSec: Long = -1,
    val viewCount: Long = -1,
    val uploadedAt: Long? = null,
    val uploadedText: String? = null,
    val isLive: Boolean = false,
    val isShort: Boolean = false,
    val verified: Boolean = false,
    /** Twitch: Kategorie (Anzeige-Name) und ihr Name für die Kategorie-Seite */
    val game: String? = null,
    val gameName: String? = null,
) {
    /** Twitch-Stream ('twitch:<login>') oder -VOD ('twitch-vod:<id>') statt YouTube-Video */
    val isTwitch: Boolean get() = id.startsWith("twitch:") || id.startsWith("twitch-vod:")
    val twitchLogin: String? get() = if (id.startsWith("twitch:")) id.removePrefix("twitch:") else channelUrl?.takeIf { it.startsWith("twitch:") }?.removePrefix("twitch:")

    val url: String get() = when {
        id.startsWith("twitch-vod:") -> "https://www.twitch.tv/videos/" + id.removePrefix("twitch-vod:")
        id.startsWith("twitch:") -> "https://www.twitch.tv/" + id.removePrefix("twitch:")
        else -> "https://www.youtube.com/watch?v=$id"
    }
    val shareUrl: String get() = if (isTwitch) url else if (isShort) "https://youtube.com/shorts/$id" else "https://youtu.be/$id"
    val thumbnailOrDefault: String get() = thumbnail ?: if (id.startsWith("twitch:")) {
        "https://static-cdn.jtvnw.net/previews-ttv/live_user_${id.removePrefix("twitch:")}-640x360.jpg"
    } else "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    /** Als Short markiert oder offensichtlich einer (#shorts im Titel, höchstens 3 Minuten) */
    val looksLikeShort: Boolean
        get() = isShort || (title.contains("#shorts", ignoreCase = true) && durationSec in 0..180)
}

@Serializable
data class ChannelRef(
    val url: String,
    val name: String,
    val avatar: String? = null,
    val subscriberCount: Long = -1,
    val videoCount: Long = -1,
    val description: String? = null,
    val verified: Boolean = false,
)

@Serializable
data class PlaylistRef(
    val url: String,
    val name: String,
    val thumbnail: String? = null,
    val uploader: String? = null,
    val count: Long = -1,
)

sealed interface ListEntry {
    val key: String

    data class Video(val video: VideoItem) : ListEntry {
        override val key get() = "v:" + video.id
    }

    data class Channel(val channel: ChannelRef) : ListEntry {
        override val key get() = "c:" + channel.url
    }

    data class Playlist(val playlist: PlaylistRef) : ListEntry {
        override val key get() = "p:" + playlist.url
    }
}

private val VIDEO_ID = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/|/live/|/v/)([A-Za-z0-9_-]{11})""")
private val CHANNEL_ID = Regex("""/channel/(UC[A-Za-z0-9_-]{22})""")

private val TIME_PARAM = Regex("""[?&#](?:t|start|time_continue)=(\d+h)?(\d+m)?(\d+)?s?(?=[&#]|$)""")

/**
 * Startzeit aus einem Link in Sekunden: "?t=90", "?t=90s", "&t=1m30s", "#t=1h2m3s" (wie bei YouTube).
 * Früher wurde nur die erste Zahl gelesen – "?t=1m30s" sprang auf 1 Sekunde.
 */
fun startSecondsOf(url: String?): Long? {
    val m = TIME_PARAM.find(url ?: return null) ?: return null
    val h = m.groupValues[1].dropLast(1).toLongOrNull() ?: 0
    val min = m.groupValues[2].dropLast(1).toLongOrNull() ?: 0
    val s = m.groupValues[3].toLongOrNull() ?: 0
    if (m.groupValues.drop(1).all { it.isEmpty() }) return null
    return h * 3600 + min * 60 + s
}

fun videoIdOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    VIDEO_ID.find(url)?.let { return it.groupValues[1] }
    return if (Regex("^[A-Za-z0-9_-]{11}$").matches(url)) url else null
}

/** Kanal-ID (UC…) aus einer Kanal-URL, sonst die URL selbst – zum Vergleichen von Abos. */
fun channelKey(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return CHANNEL_ID.find(url)?.groupValues?.get(1)
        ?: url.substringBefore('?').trimEnd('/').lowercase()
}

fun fixUrl(url: String?): String? = when {
    url.isNullOrBlank() -> null
    url.startsWith("//") -> "https:$url"
    else -> url
}

/** Größtes Bild bis [maxWidth] Pixel Breite. */
fun List<Image>?.bestUrl(maxWidth: Int = Int.MAX_VALUE): String? {
    if (this.isNullOrEmpty()) return null
    val withWidth = filter { it.width > 0 }
    val pick = if (withWidth.isEmpty()) {
        // Reihenfolge des Enums: HIGH, MEDIUM, LOW, UNKNOWN
        minByOrNull { it.estimatedResolutionLevel.ordinal } ?: last()
    } else {
        withWidth.filter { it.width <= maxWidth }.maxByOrNull { it.width } ?: withWidth.minBy { it.width }
    }
    return fixUrl(pick.url)
}

fun StreamInfoItem.toVideoItem(): VideoItem? {
    val id = videoIdOf(url) ?: return null
    val live = streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM
    return VideoItem(
        id = id,
        title = name.orEmpty(),
        channelName = uploaderName.orEmpty(),
        channelUrl = uploaderUrl,
        channelAvatar = uploaderAvatars.bestUrl(240),
        thumbnail = thumbnails.bestUrl() ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        durationSec = duration,
        viewCount = viewCount,
        uploadedAt = runCatching { uploadDate?.instant?.toEpochMilli() }.getOrNull(),
        uploadedText = textualUploadDate,
        isLive = live,
        isShort = isShortFormContent || url.orEmpty().contains("/shorts/"),
        verified = isUploaderVerified,
    )
}

fun ChannelInfoItem.toChannelRef(): ChannelRef = ChannelRef(
    url = url,
    name = name.orEmpty(),
    avatar = thumbnails.bestUrl(400),
    subscriberCount = subscriberCount,
    videoCount = streamCount,
    description = description,
    verified = isVerified,
)

fun PlaylistInfoItem.toPlaylistRef(): PlaylistRef = PlaylistRef(
    url = url,
    name = name.orEmpty(),
    thumbnail = thumbnails.bestUrl(),
    uploader = uploaderName,
    count = streamCount,
)

fun InfoItem.toEntry(): ListEntry? = when (this) {
    is StreamInfoItem -> toVideoItem()?.let { ListEntry.Video(it) }
    is ChannelInfoItem -> ListEntry.Channel(toChannelRef())
    is PlaylistInfoItem -> ListEntry.Playlist(toPlaylistRef())
    else -> null
}
