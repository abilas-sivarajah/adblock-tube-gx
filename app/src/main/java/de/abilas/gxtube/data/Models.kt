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
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
    val shareUrl: String get() = if (isShort) "https://youtube.com/shorts/$id" else "https://youtu.be/$id"
    val thumbnailOrDefault: String get() = thumbnail ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg"
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
