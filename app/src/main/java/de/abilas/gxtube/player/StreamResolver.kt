package de.abilas.gxtube.player

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
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
import org.schabi.newpipe.extractor.stream.SubtitlesStream
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

    /** Untertitel eines Videos ([mimeType]: WebVTT, TTML oder SRT – so wie YouTube sie liefert). */
    data class CaptionTrack(val key: String, val lang: String, val label: String, val auto: Boolean, val url: String, val mimeType: String = MimeTypes.TEXT_VTT)

    /** Unterstützte Formate der Untertitel, das beste zuerst. */
    private val captionFormats = linkedMapOf(
        MediaFormat.VTT to MimeTypes.TEXT_VTT,
        MediaFormat.TTML to MimeTypes.APPLICATION_TTML,
        MediaFormat.SRT to MimeTypes.APPLICATION_SUBRIP,
    )

    data class Resolved(
        val source: MediaSource,
        val height: Int,
        val width: Int,
        val options: List<VideoOption>,
        val isLive: Boolean,
        /** Mehrere Qualitäten in einem Manifest: ExoPlayer schaltet selbst hoch und runter */
        val adaptive: Boolean = false,
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
    /**
     * VP9 in Hardware? Über 1080p schafft ein Software-Decoder VP9 auf vielen Geräten nicht flüssig.
     * Vor Android 10 gibt es isHardwareAccelerated nicht – dann am Namen erkennen
     * (Software-Decoder heißen OMX.google.* bzw. c2.android.*).
     */
    private val vp9Hardware by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder &&
                    info.supportedTypes.any { it.equals("video/x-vnd.on2.vp9", ignoreCase = true) } &&
                    if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                    else !info.name.startsWith("OMX.google.", true) && !info.name.startsWith("c2.android.", true)
            }
        }.getOrDefault(false)
    }
    private val av1 by lazy { Build.VERSION.SDK_INT >= 29 && hasDecoder("video/av01", hardwareOnly = true) }

    /** 0 = H.264 (läuft überall), 1 = VP9, 2 = AV1 (nur mit Hardware-Decoder), null = nicht abspielbar */
    private fun codecRank(v: VideoStream): Int? {
        val c = v.codec.orEmpty().lowercase()
        return when {
            c.startsWith("avc") -> 0
            c.startsWith("vp9") || c.startsWith("vp09") ->
                if (vp9 && (vp9Hardware || heightOf(v) <= 1080)) 1 else null
            c.startsWith("av01") -> if (av1) 2 else null
            c.isEmpty() && v.format == MediaFormat.MPEG_4 -> 0
            c.isEmpty() && v.format == MediaFormat.WEBM ->
                if (vp9 && (vp9Hardware || heightOf(v) <= 1080)) 1 else null
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

    /** Untertitel (je Sprache das beste Format; von Hand erstellte vor automatisch erzeugten). */
    fun captionTracks(info: StreamInfo): List<CaptionTrack> =
        info.subtitles
            .filter { it.isUrl && it.format in captionFormats && !it.content.isNullOrBlank() && !it.languageTag.isNullOrBlank() }
            .sortedBy { captionFormats.keys.indexOf(it.format) }
            .map { s: SubtitlesStream ->
                val name = s.displayLanguageName?.takeIf { it.isNotBlank() } ?: s.languageTag
                CaptionTrack(
                    key = "${s.languageTag}:${if (s.isAutoGenerated) "auto" else "manuell"}",
                    lang = s.languageTag,
                    label = if (s.isAutoGenerated) "$name (automatisch)" else name,
                    auto = s.isAutoGenerated,
                    url = s.content,
                    mimeType = captionFormats[s.format] ?: MimeTypes.TEXT_VTT,
                )
            }
            .distinctBy { it.key }
            .sortedBy { it.auto }

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

    /**
     * Quelle für ExoPlayer. [adaptive]: alle Qualitäten bis [maxHeight] in einem Manifest (ExoPlayer wählt
     * nach Leitung), sonst genau eine. [caption]: diese Untertitel als zusätzliche Spur.
     */
    fun resolve(context: Context, info: StreamInfo, maxHeight: Int, item: MediaItem, adaptive: Boolean = false, caption: CaptionTrack? = null): Resolved {
        val r = resolveMain(context, info, maxHeight, item, adaptive)
        if (caption == null || r.isLive) return r
        return r.copy(source = MergingMediaSource(r.source, subtitleSource(caption)))
    }

    private val subtitleParsers = DefaultSubtitleParserFactory()

    /**
     * Untertitel als eigene Quelle. Die Datei wird schon beim Laden in Untertitel-Stücke zerlegt (wie es
     * Media3 selbst für Untertitel macht) – roh als TTML/WebVTT an den Player gegeben, bricht Media3 seit
     * 1.4 die Wiedergabe ab ("Legacy decoding is disabled").
     */
    private fun subtitleSource(track: CaptionTrack): MediaSource {
        val format = Format.Builder()
            .setSampleMimeType(track.mimeType)
            .setLanguage(track.lang)
            .setLabel(track.label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setId(track.key)
            .build()
        require(subtitleParsers.supportsFormat(format)) { "Untertitel-Format ${track.mimeType} wird nicht unterstützt" }
        val extractors = ExtractorsFactory { arrayOf<Extractor>(SubtitleExtractor(subtitleParsers.create(format), format)) }
        return ProgressiveMediaSource.Factory(liveDataSource, extractors).createMediaSource(MediaItem.fromUri(track.url))
    }

    private fun resolveMain(context: Context, info: StreamInfo, maxHeight: Int, item: MediaItem, adaptive: Boolean): Resolved {
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
                val abr = if (adaptive) runCatching { adaptiveSource(context, info, v, a, maxHeight, item) }
                    .onFailure { Log.w(TAG, "Automatische Qualität nicht möglich, nehme eine feste", it) }
                    .getOrNull() else null
                if (abr != null) {
                    Resolved(abr, heightOf(v), v.width, options, isLive = false, adaptive = true)
                } else {
                    Resolved(
                        MergingMediaSource(true, sourceFor(context, v, info, item), sourceFor(context, a, info, item)),
                        heightOf(v), v.width, options, isLive = false,
                    )
                }
            }
            muxed != null -> Resolved(
                sourceFor(context, muxed, info, item), heightOf(muxed), muxed.width, options, isLive = false,
            )
            audio != null -> Resolved(sourceFor(context, audio, info, item), 0, 0, options, isLive = false)
            else -> throw IllegalStateException("Keine abspielbaren Streams gefunden")
        }
    }

    // ------------------------------------------------------------ Automatische Qualität

    private fun codecFamily(v: VideoStream): String? {
        val c = v.codec.orEmpty().lowercase()
        return when {
            c.startsWith("avc") -> "avc"
            c.startsWith("vp9") || c.startsWith("vp09") -> "vp9"
            c.startsWith("av01") -> "av01"
            c.isEmpty() && v.format == MediaFormat.MPEG_4 -> "avc"
            c.isEmpty() && v.format == MediaFormat.WEBM -> "vp9"
            else -> null
        }
    }

    private fun parsedManifest(stream: Stream, info: StreamInfo): DashManifest {
        val itag = requireNotNull(stream.itagItem)
        val mpd = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(stream.content, itag, info.duration)
        return DashManifestParser().parse(Uri.parse(stream.content), ByteArrayInputStream(mpd.toByteArray(Charsets.UTF_8)))
    }

    /**
     * Ein DASH-Manifest mit allen Videospuren desselben Codecs bis [maxHeight] und der Tonspur – wie im
     * YouTube-Player schaltet ExoPlayer dann nach Leitung hoch und runter, statt bei schlechtem Netz zu stocken.
     * Nur für normale Videos mit getrennten Spuren (keine Live-/DVR-Streams); sonst null.
     */
    private fun adaptiveSource(context: Context, info: StreamInfo, best: VideoStream, audio: AudioStream, maxHeight: Int, item: MediaItem): MediaSource? {
        if (info.streamType != StreamType.VIDEO_STREAM) return null
        if (best.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP || audio.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP || audio.itagItem == null) return null
        val family = codecFamily(best) ?: return null
        val ladder = info.videoOnlyStreams
            .filter { usable(it) && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.itagItem != null }
            .filter { codecRank(it) != null && codecFamily(it) == family && heightOf(it) in 144..maxHeight }
            // Je Auflösung und Bildrate die Spur mit der höchsten Bitrate
            .groupBy { heightOf(it) to fpsOf(it).coerceAtMost(60) }
            .map { (_, list) -> list.maxBy { it.bitrate } }
        if (ladder.size < 2) return null
        val video = ladder.mapNotNull { v ->
            runCatching { parsedManifest(v, info).getPeriod(0).adaptationSets.firstOrNull()?.representations?.firstOrNull() }.getOrNull()
        }
        if (video.size < 2) return null
        val audioManifest = parsedManifest(audio, info)
        val audioSet = audioManifest.getPeriod(0).adaptationSets.firstOrNull() ?: return null
        val period = Period(
            null,
            0,
            listOf(
                AdaptationSet(0, C.TRACK_TYPE_VIDEO, video.sortedByDescending { it.format.height }, emptyList(), emptyList(), emptyList()),
                AdaptationSet(1, C.TRACK_TYPE_AUDIO, audioSet.representations, audioSet.accessibilityDescriptors, audioSet.essentialProperties, audioSet.supplementalProperties),
            ),
        )
        val base = audioManifest
        val manifest = DashManifest(
            base.availabilityStartTimeMs, base.durationMs, base.minBufferTimeMs, false, base.minUpdatePeriodMs,
            base.timeShiftBufferDepthMs, base.suggestedPresentationDelayMs, base.publishTimeMs, base.programInformation,
            base.utcTiming, base.serviceDescription, base.location, listOf(period),
        )
        val ds = ytDash(context)
        return DashMediaSource.Factory(DefaultDashChunkSource.Factory(ds), ds).createMediaSource(manifest, item)
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
