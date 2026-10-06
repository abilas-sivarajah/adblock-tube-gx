package de.abilas.gxtube.player

import android.app.Application
import android.content.ComponentName
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.core.content.getSystemService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.SponsorBlock
import de.abilas.gxtube.data.SponsorSegment
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.data.YouTubeRepo
import de.abilas.gxtube.data.bestUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

enum class PlayerMode { NONE, WATCH, SHORTS }

data class NowPlaying(
    val video: VideoItem,
    val info: StreamInfo? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val options: List<StreamResolver.VideoOption> = emptyList(),
    val height: Int = 0,
    val related: List<VideoItem> = emptyList(),
    val segments: List<SponsorSegment> = emptyList(),
    /** Seitenverhältnis Breite/Höhe des Videos */
    val aspect: Float = 16f / 9f,
    val isLive: Boolean = false,
    val queue: List<VideoItem> = emptyList(),
    val queueIndex: Int = -1,
)

/**
 * Ein einziger Player für die ganze App (Video-Seite, Mini-Player, Shorts, Hintergrund,
 * Bild-im-Bild). Die Benachrichtigung/Sperrbildschirm-Steuerung kommt über [PlaybackService].
 */
object PlayerController {
    private const val TAG = "PlayerController"

    private lateinit var app: Application
    private val scope = MainScope()
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now.asStateFlow()

    private val _mode = MutableStateFlow(PlayerMode.NONE)
    val mode: StateFlow<PlayerMode> = _mode.asStateFlow()

    /** Video-Seite aufgeklappt (true) oder Mini-Player (false) */
    val expanded = MutableStateFlow(true)
    val fullscreen = MutableStateFlow(false)

    private val _fullscreenRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    /** Vollbild-Knopf gedrückt (true) oder Vollbild verlassen (false) – MainActivity dreht den Bildschirm. */
    val fullscreenRequests: SharedFlow<Boolean> = _fullscreenRequests.asSharedFlow()

    fun requestFullscreen(on: Boolean) {
        fullscreen.value = on
        _fullscreenRequests.tryEmit(on)
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val skippedSegments = mutableSetOf<String>()
    private var retriedVideo: String? = null
    private var qualityOverride: Int? = null

    val player: ExoPlayer by lazy { createPlayer() }

    fun init(application: Application) {
        app = application
    }

    private fun createPlayer(): ExoPlayer {
        val p = ExoPlayer.Builder(app)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) = handleError(error)
        })
        return p
    }

    // ------------------------------------------------------------ Starten / Beenden

    fun play(video: VideoItem, queue: List<VideoItem> = emptyList(), startMs: Long? = null) {
        _mode.value = PlayerMode.WATCH
        expanded.value = true
        qualityOverride = null
        load(video, startMs, shorts = false, queue = queue)
    }

    fun playShort(video: VideoItem) {
        if (_mode.value == PlayerMode.SHORTS && _now.value?.video?.id == video.id) {
            player.play()
            return
        }
        _mode.value = PlayerMode.SHORTS
        fullscreen.value = false
        qualityOverride = null
        load(video, 0, shorts = true, queue = emptyList())
    }

    fun stopShorts() {
        if (_mode.value == PlayerMode.SHORTS) close()
    }

    fun close() {
        saveProgress()
        loadJob?.cancel()
        tickerJob?.cancel()
        player.stop()
        player.clearMediaItems()
        _now.value = null
        _mode.value = PlayerMode.NONE
        if (fullscreen.value) requestFullscreen(false)
        expanded.value = true
    }

    fun retry() {
        val n = _now.value ?: return
        load(n.video, player.currentPosition.takeIf { it > 0 }, _mode.value == PlayerMode.SHORTS, n.queue)
    }

    private fun load(video: VideoItem, startMs: Long?, shorts: Boolean, queue: List<VideoItem>) {
        saveProgress()
        loadJob?.cancel()
        skippedSegments.clear()
        player.stop()
        player.clearMediaItems()
        player.repeatMode = if (shorts) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        val queueIndex = queue.indexOfFirst { it.id == video.id }
        _now.value = NowPlaying(video = video, queue = queue, queueIndex = queueIndex)

        loadJob = scope.launch {
            try {
                val info = YouTubeRepo.stream(video.url)
                val enriched = enrich(video, info)
                val related = YouTubeRepo.relatedOf(info)
                val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
                val item = mediaItem(enriched)
                val maxHeight = qualityOverride ?: preferredHeight(shorts)
                val resolved = withContext(Dispatchers.Default) {
                    StreamResolver.resolve(app, info, maxHeight, item)
                }
                val resume = if (live) 0L else startMs ?: resumePosition(enriched.id, info.duration * 1000)

                if (live) player.setMediaSource(resolved.source, true)
                else player.setMediaSource(resolved.source, resume)
                player.setPlaybackSpeed(if (shorts) 1f else _speed.value)
                player.prepare()
                player.playWhenReady = true

                val aspect = if (resolved.width > 0 && resolved.height > 0) {
                    resolved.width.toFloat() / resolved.height
                } else 16f / 9f
                _now.value = NowPlaying(
                    video = enriched,
                    info = info,
                    loading = false,
                    options = resolved.options,
                    height = resolved.height,
                    related = related,
                    aspect = aspect,
                    isLive = live,
                    queue = queue,
                    queueIndex = queueIndex,
                )
                ensureService()
                startTicker()

                Library.recordWatch(enriched, resume, info.duration * 1000)
                Library.addRecommendations(related.take(if (shorts) 4 else 12))

                val settings = Library.settings
                if (!live && settings.sponsorBlock) {
                    val segments = SponsorBlock.segments(enriched.id, settings.sponsorCategories)
                    if (segments.isNotEmpty()) {
                        _now.update { n -> if (n?.video?.id == enriched.id) n.copy(segments = segments) else n }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Video konnte nicht geladen werden", t)
                _now.update { n -> n?.copy(loading = false, error = YouTubeRepo.errorText(t)) }
            }
        }
    }

    private fun enrich(video: VideoItem, info: StreamInfo): VideoItem = video.copy(
        title = info.name?.takeIf { it.isNotBlank() } ?: video.title,
        channelName = info.uploaderName?.takeIf { it.isNotBlank() } ?: video.channelName,
        channelUrl = info.uploaderUrl ?: video.channelUrl,
        channelAvatar = info.uploaderAvatars.bestUrl(240) ?: video.channelAvatar,
        thumbnail = video.thumbnail ?: info.thumbnails.bestUrl(),
        durationSec = info.duration.takeIf { it > 0 } ?: video.durationSec,
        viewCount = info.viewCount.takeIf { it >= 0 } ?: video.viewCount,
        uploadedAt = runCatching { info.uploadDate?.instant?.toEpochMilli() }.getOrNull() ?: video.uploadedAt,
        uploadedText = info.textualUploadDate ?: video.uploadedText,
        isLive = info.streamType == StreamType.LIVE_STREAM,
        isShort = video.isShort || info.isShortFormContent,
        verified = info.isUploaderVerified,
    )

    private fun mediaItem(video: VideoItem): MediaItem = MediaItem.Builder()
        .setMediaId(video.id)
        .setUri(video.url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(video.title)
                .setArtist(video.channelName)
                .setArtworkUri(video.thumbnail?.let { Uri.parse(it) })
                .build(),
        )
        .build()

    private fun resumePosition(id: String, durationMs: Long): Long {
        val entry = Library.historyEntry(id) ?: return 0
        val pos = entry.positionMs
        val dur = if (durationMs > 0) durationMs else entry.durationMs
        return if (pos > 5_000 && (dur <= 0 || pos < dur - 15_000)) pos else 0
    }

    private fun preferredHeight(shorts: Boolean): Int {
        val s = Library.settings
        val cm = app.getSystemService<ConnectivityManager>()
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val metered = caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val h = if (metered) s.qualityMobile else s.qualityWifi
        return if (shorts) minOf(h, 1080) else h
    }

    private fun ensureService() {
        if (controllerFuture != null) return
        runCatching {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            controllerFuture = MediaController.Builder(app, token).buildAsync()
        }.onFailure { Log.w(TAG, "Wiedergabe-Dienst konnte nicht gestartet werden", it) }
    }

    // ------------------------------------------------------------ Bedienung

    fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
        } else if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, dur))
    }

    fun setSpeed(value: Float) {
        _speed.value = value
        player.setPlaybackSpeed(value)
    }

    fun setQuality(height: Int) {
        val n = _now.value ?: return
        val info = n.info ?: return
        qualityOverride = height
        val pos = player.currentPosition
        val wasPlaying = player.playWhenReady
        scope.launch {
            runCatching {
                val resolved = withContext(Dispatchers.Default) {
                    StreamResolver.resolve(app, info, height, mediaItem(n.video))
                }
                player.setMediaSource(resolved.source, pos)
                player.prepare()
                player.playWhenReady = wasPlaying
                _now.update { it?.copy(height = resolved.height) }
            }.onFailure { _messages.tryEmit("Qualität konnte nicht gewechselt werden") }
        }
    }

    val hasNext: Boolean
        get() = _now.value?.let { n -> n.queueIndex >= 0 && n.queueIndex < n.queue.lastIndex } ?: false

    val hasPrevious: Boolean
        get() = _now.value?.let { n -> n.queueIndex > 0 } ?: false

    fun next() {
        val n = _now.value ?: return
        if (hasNext) {
            load(n.queue[n.queueIndex + 1], null, false, n.queue)
        } else {
            nextRelated()?.let { load(it, null, false, emptyList()) }
        }
    }

    fun previous() {
        val n = _now.value ?: return
        if (player.currentPosition > 5_000 || !hasPrevious) {
            player.seekTo(0)
        } else {
            load(n.queue[n.queueIndex - 1], null, false, n.queue)
        }
    }

    private fun nextRelated(): VideoItem? {
        val n = _now.value ?: return null
        val watched = Library.data.value.history.take(50).map { it.video.id }.toSet()
        return n.related.firstOrNull { it.id !in watched && !it.isLive && !it.isShort }
            ?: n.related.firstOrNull { !it.isLive }
    }

    private fun onEnded() {
        saveProgress()
        if (_mode.value != PlayerMode.WATCH) return
        val n = _now.value ?: return
        when {
            hasNext -> load(n.queue[n.queueIndex + 1], null, false, n.queue)
            Library.settings.autoplay -> nextRelated()?.let { load(it, null, false, emptyList()) }
        }
    }

    private fun handleError(error: PlaybackException) {
        Log.e(TAG, "Wiedergabefehler", error)
        val n = _now.value ?: return
        val http = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        // Abgelaufene oder gesperrte Stream-URL: einmal frisch laden
        if (retriedVideo != n.video.id && (http != null || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)) {
            retriedVideo = n.video.id
            load(n.video, player.currentPosition.takeIf { it > 0 }, _mode.value == PlayerMode.SHORTS, n.queue)
            return
        }
        val text = when {
            http != null -> "YouTube hat den Stream abgelehnt (HTTP ${http.responseCode})."
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Keine Internetverbindung."
            else -> "Wiedergabefehler: ${error.errorCodeName}"
        }
        _now.update { it?.copy(error = text) }
    }

    // ------------------------------------------------------------ Hintergrund / Bild-im-Bild

    fun onAppBackground(inPip: Boolean) {
        if (inPip || _now.value == null) return
        when (_mode.value) {
            PlayerMode.WATCH -> if (Library.settings.backgroundPlay) setVideoTrackDisabled(true) else player.pause()
            PlayerMode.SHORTS -> player.pause()
            PlayerMode.NONE -> Unit
        }
    }

    fun onAppForeground() = setVideoTrackDisabled(false)

    private fun setVideoTrackDisabled(disabled: Boolean) {
        val params = player.trackSelectionParameters
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == disabled) return
        player.trackSelectionParameters = params.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
            .build()
    }

    // ------------------------------------------------------------ Fortschritt / SponsorBlock

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(500)
                val n = _now.value ?: continue
                if (n.segments.isNotEmpty() && player.isPlaying) skipSegments(n.segments)
                if (++ticks % 10 == 0 && player.isPlaying) saveProgress()
            }
        }
    }

    private fun skipSegments(segments: List<SponsorSegment>) {
        val pos = player.currentPosition
        val seg = segments.firstOrNull {
            pos >= it.startMs && pos < it.endMs - 500 && it.uuid !in skippedSegments
        } ?: return
        skippedSegments += seg.uuid
        player.seekTo(seg.endMs)
        _messages.tryEmit("${SponsorBlock.label(seg.category)} übersprungen")
    }

    fun saveProgress() {
        val n = _now.value ?: return
        if (n.loading || n.error != null || n.isLive) return
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        Library.updateProgress(n.video.id, player.currentPosition, dur)
    }
}
