package de.abilas.gxtube.player

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import de.abilas.gxtube.data.Http
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Macht aus den Streams von NewPipe Extractor eine abspielbare Quelle für ExoPlayer.
 * Höhere Qualitäten gibt es bei YouTube nur als getrennte Video- und Audiospur –
 * beide werden als DASH-Quelle erzeugt und zusammengeführt (wie in NewPipe).
 */
object StreamResolver {
    private const val TAG = "StreamResolver"

    data class VideoOption(val height: Int, val label: String)

    data class Resolved(
        val source: MediaSource,
        val height: Int,
        val width: Int,
        val options: List<VideoOption>,
        val isLive: Boolean,
    )

    @Volatile private var cache: SimpleCache? = null

    private fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "exoplayer"),
            LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        ).also { cache = it }
    }

    private fun cached(context: Context, upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun ytDash(context: Context) = cached(
        context,
        YoutubeHttpDataSource.Factory().setRangeParameterEnabled(true).setRnParameterEnabled(true),
    )

    private fun ytProgressive(context: Context) = cached(
        context,
        YoutubeHttpDataSource.Factory().setRangeParameterEnabled(false).setRnParameterEnabled(true),
    )

    private val liveDataSource: DataSource.Factory
        get() = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)

    // ------------------------------------------------------------ Codecs

    private fun hasDecoder(mime: String, hardwareOnly: Boolean): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                (!hardwareOnly || (Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated))
        }
    }.getOrDefault(false)

    private val vp9 by lazy { hasDecoder("video/x-vnd.on2.vp9", hardwareOnly = false) }
    private val av1 by lazy { Build.VERSION.SDK_INT >= 29 && hasDecoder("video/av01", hardwareOnly = true) }

    /** 0 = H.264 (läuft überall), 1 = VP9, 2 = AV1 (nur mit Hardware-Decoder), null = nicht abspielbar */
    private fun codecRank(v: VideoStream): Int? {
        val c = v.codec.orEmpty().lowercase()
        return when {
            c.startsWith("avc") -> 0
            c.startsWith("vp9") || c.startsWith("vp09") -> if (vp9) 1 else null
            c.startsWith("av01") -> if (av1) 2 else null
            c.isEmpty() && v.format == MediaFormat.MPEG_4 -> 0
            c.isEmpty() && v.format == MediaFormat.WEBM -> if (vp9) 1 else null
            else -> null
        }
    }

    private fun heightOf(v: VideoStream): Int =
        v.height.takeIf { it > 0 }
            ?: Regex("(\\d+)p").find(v.resolution.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

    private fun fpsOf(v: VideoStream): Int =
        v.fps.takeIf { it > 0 }
            ?: Regex("p(\\d+)").find(v.resolution.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?: 30

    private fun usable(s: Stream) = s.isUrl &&
        (s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP ||
            (s.deliveryMethod == DeliveryMethod.DASH && s.itagItem != null) ||
            s.deliveryMethod == DeliveryMethod.HLS)

    // ------------------------------------------------------------ Auswahl

    fun options(info: StreamInfo): List<VideoOption> {
        val all = info.videoOnlyStreams.filter { usable(it) && codecRank(it) != null } +
            info.videoStreams.filter { usable(it) }
        return all.groupBy { heightOf(it) }
            .filterKeys { it > 0 }
            .map { (h, list) ->
                val hfr = list.any { fpsOf(it) > 30 }
                VideoOption(h, if (hfr) "${h}p60" else "${h}p")
            }
            .sortedByDescending { it.height }
    }

    private fun pickAudio(info: StreamInfo): AudioStream? {
        val usableAudio = info.audioStreams.filter { usable(it) }
        val original = usableAudio.filter {
            it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
        }.ifEmpty { usableAudio }
        return original.sortedWith(
            compareBy<AudioStream> { if (it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.WEBMA) 0 else 1 }
                .thenByDescending { maxOf(it.averageBitrate, it.bitrate) },
        ).firstOrNull()
    }

    private fun pickVideoOnly(info: StreamInfo, maxHeight: Int): VideoStream? {
        val candidates = info.videoOnlyStreams.filter { usable(it) && codecRank(it) != null }
        if (candidates.isEmpty()) return null
        val fitting = candidates.filter { heightOf(it) <= maxHeight }.ifEmpty {
            val lowest = candidates.minOf { heightOf(it) }
            candidates.filter { heightOf(it) == lowest }
        }
        return fitting.sortedWith(
            compareByDescending<VideoStream> { heightOf(it) }
                .thenByDescending { fpsOf(it).coerceAtMost(60) }
                .thenBy { codecRank(it) ?: 9 },
        ).firstOrNull()
    }

    private fun pickMuxed(info: StreamInfo, maxHeight: Int): VideoStream? {
        val muxed = info.videoStreams.filter { usable(it) }
        if (muxed.isEmpty()) return null
        return muxed.filter { heightOf(it) <= maxHeight }.maxByOrNull { heightOf(it) }
            ?: muxed.minByOrNull { heightOf(it) }
    }

    // ------------------------------------------------------------ Quellen bauen

    fun resolve(context: Context, info: StreamInfo, maxHeight: Int, item: MediaItem): Resolved {
        val type = info.streamType
        if (type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM) {
            val hls = info.hlsUrl
            val dash = info.dashMpdUrl
            val source = when {
                !hls.isNullOrEmpty() -> HlsMediaSource.Factory(liveDataSource)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(liveItem(item, hls))
                !dash.isNullOrEmpty() -> DashMediaSource.Factory(liveDataSource)
                    .createMediaSource(liveItem(item, dash))
                else -> throw IllegalStateException("Kein Live-Stream gefunden")
            }
            return Resolved(source, 0, 0, emptyList(), isLive = true)
        }

        val options = options(info)
        val audio = pickAudio(info)
        val videoOnly = pickVideoOnly(info, maxHeight)
        val muxed = pickMuxed(info, maxHeight)

        // Getrennte Spuren nur nehmen, wenn sie besser sind als der kombinierte Stream (meist 360p)
        val useSplit = videoOnly != null && audio != null &&
            (muxed == null || heightOf(videoOnly) > heightOf(muxed))

        return when {
            useSplit -> {
                val v = videoOnly!!
                val a = audio!!
                Resolved(
                    MergingMediaSource(true, sourceFor(context, v, info, item), sourceFor(context, a, info, item)),
                    heightOf(v), v.width, options, isLive = false,
                )
            }
            muxed != null -> Resolved(
                sourceFor(context, muxed, info, item), heightOf(muxed), muxed.width, options, isLive = false,
            )
            audio != null -> Resolved(sourceFor(context, audio, info, item), 0, 0, options, isLive = false)
            else -> throw IllegalStateException("Keine abspielbaren Streams gefunden")
        }
    }

    private fun liveItem(item: MediaItem, url: String): MediaItem = item.buildUpon()
        .setUri(url)
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(10_000).build())
        .build()

    private fun sourceFor(context: Context, stream: Stream, info: StreamInfo, item: MediaItem): MediaSource {
        val itag = stream.itagItem
        val streamItem = item.buildUpon()
            .setUri(stream.content)
            .setCustomCacheKey("${info.id}-${stream.formatId}-${(stream as? VideoStream)?.itag ?: (stream as? AudioStream)?.itag}")
            .build()
        return when (stream.deliveryMethod) {
            DeliveryMethod.PROGRESSIVE_HTTP -> {
                val split = (stream is VideoStream && stream.isVideoOnly) || stream is AudioStream
                if (split && itag != null) {
                    try {
                        val mpd = if (info.streamType == StreamType.POST_LIVE_STREAM) {
                            YoutubePostLiveStreamDvrDashManifestCreator.fromPostLiveStreamDvrStreamingUrl(
                                stream.content, itag, itag.targetDurationSec, info.duration,
                            )
                        } else {
                            YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                                stream.content, itag, info.duration,
                            )
                        }
                        dashSource(context, mpd, stream, streamItem)
                    } catch (e: Exception) {
                        Log.w(TAG, "DASH-Manifest fehlgeschlagen, nutze progressiven Stream", e)
                        progressive(context, streamItem)
                    }
                } else {
                    progressive(context, streamItem)
                }
            }
            DeliveryMethod.DASH -> {
                val mpd = YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(
                    stream.content, requireNotNull(itag), info.duration,
                )
                dashSource(context, mpd, stream, streamItem)
            }
            DeliveryMethod.HLS -> HlsMediaSource.Factory(
                YoutubeHttpDataSource.Factory().setRangeParameterEnabled(false).setRnParameterEnabled(false),
            ).createMediaSource(streamItem)
            else -> throw IllegalStateException("Nicht unterstütztes Stream-Format: ${stream.deliveryMethod}")
        }
    }

    private fun progressive(context: Context, item: MediaItem): MediaSource =
        ProgressiveMediaSource.Factory(ytProgressive(context)).createMediaSource(item)

    private fun dashSource(context: Context, mpd: String, stream: Stream, item: MediaItem): MediaSource {
        val manifest = DashManifestParser().parse(
            Uri.parse(stream.content),
            ByteArrayInputStream(mpd.toByteArray(Charsets.UTF_8)),
        )
        val ds = ytDash(context)
        return DashMediaSource.Factory(DefaultDashChunkSource.Factory(ds), ds)
            .createMediaSource(manifest, item)
    }
}
