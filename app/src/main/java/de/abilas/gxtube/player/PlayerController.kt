package de.abilas.gxtube.player

import android.app.Application
import android.content.ComponentName
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.core.content.getSystemService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import de.abilas.gxtube.cast.Cast
import de.abilas.gxtube.cast.CastConnection
import de.abilas.gxtube.cast.RemoteQueue
import de.abilas.gxtube.cast.RemoteState
import de.abilas.gxtube.data.Chapter
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.SponsorBlock
import de.abilas.gxtube.data.SponsorInfo
import de.abilas.gxtube.data.SponsorSegment
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.data.YouTubeRepo
import de.abilas.gxtube.data.bestUrl
import de.abilas.gxtube.twitch.TwitchAccount
import de.abilas.gxtube.twitch.TwitchApi
import de.abilas.gxtube.twitch.TwitchException
import de.abilas.gxtube.twitch.TwitchPlayer
import de.abilas.gxtube.twitch.TwitchWatchInfo
import kotlinx.coroutines.async
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
    /** Twitch: Stream/VOD und Kanal (Zuschauer, Follower, Kategorie) */
    val twitch: TwitchWatchInfo? = null,
    /** SponsorBlock: Highlight, "ganzes Video ist …", Kapitel aus der Community */
    val sponsor: SponsorInfo? = null,
    /** Untertitel des Videos und die gerade gezeigten (null = aus) */
    val captions: List<StreamResolver.CaptionTrack> = emptyList(),
    val caption: StreamResolver.CaptionTrack? = null,
    /** Qualität automatisch (ExoPlayer schaltet nach Leitung) statt fest gewählt */
    val autoQuality: Boolean = true,
) {
    /** Kapitel: von YouTube (Kanal oder automatisch erzeugt), sonst aus SponsorBlock. */
    val chapters: List<Chapter>
        get() {
            val yt = info?.streamSegments.orEmpty()
                .map { Chapter(it.title.orEmpty().trim(), it.startTimeSeconds * 1000L, it.previewUrl) }
                .filter { it.title.isNotEmpty() }
                .sortedBy { it.startMs }
            return if (yt.size >= 2) yt else sponsor?.chapters.orEmpty()
        }
}

/** Hinweis mit Knopf (z. B. "Sponsor übersprungen – Rückgängig"). */
data class ActionMessage(val text: String, val action: String, val run: () -> Unit)

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
    /** Bedienleiste über dem Video gerade zu sehen – Untertitel weichen dann nach oben aus. */
    val controlsShown = MutableStateFlow(false)

    private val _fullscreenRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    /** Vollbild-Knopf gedrückt (true) oder Vollbild verlassen (false) – MainActivity dreht den Bildschirm. */
    val fullscreenRequests: SharedFlow<Boolean> = _fullscreenRequests.asSharedFlow()

    fun requestFullscreen(on: Boolean) {
        fullscreen.value = on
        _fullscreenRequests.tryEmit(on)
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _actions = MutableSharedFlow<ActionMessage>(extraBufferCapacity = 4)
    /** Hinweise mit Knopf ("Rückgängig") */
    val actions: SharedFlow<ActionMessage> = _actions.asSharedFlow()

    /** Punktgenaues Überspringen: der Player meldet sich genau am Anfang (und Ende) jedes Abschnitts. */
    private val sponsorMessages = mutableListOf<PlayerMessage>()
    /** SponsorBlock hat gerade stumm geschaltet (Abschnitt "stumm"). */
    private var mutedBySponsor = false
    /** Zuletzt übersprungener Abschnitt (für "Rückgängig"). */
    private var lastSkip: SponsorSegment? = null
    /** Gewählte Untertitel (bleiben beim Qualitätswechsel). */
    private var captionChoice: StreamResolver.CaptionTrack? = null

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val skippedSegments = mutableSetOf<String>()
    /** Letzter automatischer Neuversuch (Video-ID und Zeitpunkt) – höchstens einer pro Minute und Video. */
    private var retriedVideo: String? = null
    private var retriedAt = 0L
    private var qualityOverride: Int? = null
    /** Laufender Qualitätswechsel – wird beim Videowechsel abgebrochen. */
    private var qualityJob: Job? = null
    /** App im Hintergrund (ohne Bild-im-Bild)? */
    private var appInBackground = false

    val player: ExoPlayer by lazy { createPlayer() }

    private val _remote = MutableStateFlow(false)
    /** Das Video läuft gerade auf dem PC (GX Tube Desktop), nicht auf dem Handy. */
    val remote: StateFlow<Boolean> = _remote.asStateFlow()
    private var castDetailsJob: Job? = null
    private var lastRemote: RemoteState? = null
    private var lastCastProgressSave = 0L
    private var pendingDisconnect: DisconnectAction? = null

    private enum class DisconnectAction { LEAVE, CONTINUE_HERE }

    fun init(application: Application) {
        app = application
        observeCast()
        // Twitch-Werbung, die nicht durch Schwarzbild ersetzt werden kann, läuft stumm darunter
        scope.launch {
            TwitchPlayer.ads.collect { a ->
                if (_now.value?.video?.isTwitch == true && !_remote.value) player.volume = if (a.masked && !a.hold) 0f else 1f
            }
        }
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

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // Automatische Qualität: anzeigen, was gerade wirklich läuft
                val n = _now.value ?: return
                if (!n.autoQuality || n.video.isTwitch || videoSize.height <= 0) return
                val f = player.videoFormat ?: return
                val h = listOf(f.height, f.width).firstOrNull { c -> n.options.any { it.height == c } } ?: f.height
                if (h > 0 && h != n.height) _now.update { it?.copy(height = h) }
            }
        })
        return p
    }

    // ------------------------------------------------------------ Starten / Beenden

    fun play(video: VideoItem, queue: List<VideoItem> = emptyList(), startMs: Long? = null) {
        if (Cast.connected) {
            castPlay(video, queue, startMs)
            return
        }
        if (_remote.value) {
            // Verbindung zum PC gerade weg (wird neu aufgebaut): dann eben auf dem Handy
            castDetailsJob?.cancel()
            _remote.value = false
        }
        _mode.value = PlayerMode.WATCH
        expanded.value = true
        qualityOverride = null
        load(video, startMs, shorts = false, queue = queue)
    }

    fun playShort(video: VideoItem) {
        // Shorts laufen immer auf dem Handy; ein Video am PC läuft dort weiter
        if (_remote.value) {
            castDetailsJob?.cancel()
            _remote.value = false
        }
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
        if (_remote.value) {
            Cast.stop()
            leaveRemote()
            return
        }
        saveProgress()
        loadJob?.cancel()
        qualityJob?.cancel()
        tickerJob?.cancel()
        resetSponsorState()
        player.stop()
        player.clearMediaItems()
        _now.value = null
        _mode.value = PlayerMode.NONE
        if (fullscreen.value) requestFullscreen(false)
        expanded.value = true
    }

    fun retry() {
        val n = _now.value ?: return
        if (_remote.value) {
            Cast.play(n.video, n.queue, Cast.positionNow().takeIf { it > 0 })
            return
        }
        load(n.video, player.currentPosition.takeIf { it > 0 }, _mode.value == PlayerMode.SHORTS, n.queue)
    }

    private fun load(video: VideoItem, startMs: Long?, shorts: Boolean, queue: List<VideoItem>, autoPlay: Boolean = true) {
        saveProgress()
        loadJob?.cancel()
        // Ein noch laufender Qualitätswechsel würde sonst den Stream des alten Videos einsetzen
        qualityJob?.cancel()
        skippedSegments.clear()
        resetSponsorState()
        if (_now.value?.video?.id != video.id) captionChoice = null
        player.stop()
        player.clearMediaItems()
        player.repeatMode = if (shorts) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        val queueIndex = queue.indexOfFirst { it.id == video.id }
        _now.value = NowPlaying(video = video, queue = queue, queueIndex = queueIndex)
        // Qualitätswahl von Twitch gilt nicht für das nächste Video
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearVideoSizeConstraints().build()

        if (video.isTwitch) {
            loadJob = scope.launch { loadTwitch(video, startMs, queue, queueIndex, autoPlay) }
            return
        }

        loadJob = scope.launch {
            // SponsorBlock gleich mit dem Stream anfragen (nicht erst danach) – sonst läuft ein Sponsor
            // ganz am Anfang des Videos schon, bevor die Abschnitte da sind
            val settings0 = Library.settings
            val sponsorJob = if (!shorts && settings0.sponsorBlock) async { SponsorBlock.info(video.id, settings0.sponsorCategories) } else null
            try {
                val info = YouTubeRepo.stream(video.url)
                val enriched = enrich(video, info)
                val related = YouTubeRepo.relatedOf(info)
                val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
                val item = mediaItem(enriched)
                val maxHeight = qualityOverride ?: preferredHeight(shorts)
                val captions = if (live) emptyList() else StreamResolver.captionTracks(info)
                // Untertitel: gewählte behalten, sonst automatisch in der eingestellten Sprache
                val caption = captionChoice?.let { c -> captions.firstOrNull { it.key == c.key } }
                    ?: if (!shorts && settings0.captions) preferredCaption(captions, settings0.captionLang) else null
                captionChoice = caption
                val resolved = withContext(Dispatchers.Default) {
                    StreamResolver.resolve(app, info, maxHeight, item, adaptive = qualityOverride == null, caption = caption)
                }
                val resume = if (live) 0L else startMs ?: resumePosition(enriched.id, info.duration * 1000)

                if (live) player.setMediaSource(resolved.source, true)
                else player.setMediaSource(resolved.source, resume)
                applyCaptionSelection(caption)
                player.setPlaybackSpeed(if (shorts) 1f else _speed.value)
                player.prepare()
                // App inzwischen im Hintergrund und Hintergrundwiedergabe aus: nicht von selbst losspielen
                player.playWhenReady = autoPlay && !(appInBackground && !Library.settings.backgroundPlay)

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
                    captions = captions,
                    caption = caption,
                    autoQuality = resolved.adaptive || qualityOverride == null && resolved.options.size <= 1,
                )
                ensureService()
                startTicker()

                Library.recordWatch(enriched, resume, info.duration * 1000)
                Library.addRecommendations(related.take(if (shorts) 4 else 12))

                if (sponsorJob != null && !live) {
                    val sb = sponsorJob.await()
                    if (_now.value?.video?.id == enriched.id) {
                        _now.update { n -> n?.copy(segments = sb.segments, sponsor = sb) }
                        scheduleSponsorMessages(sb.segments)
                        if (player.playWhenReady) applySponsor(sb.segments)
                    }
                } else sponsorJob?.cancel()
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
        isLive = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM,
        isShort = video.isShort || info.isShortFormContent,
        verified = info.isUploaderVerified,
    )

    // ------------------------------------------------------------ Twitch

    /** Twitch-Stream oder -VOD: Infos und Wiedergabe-Adresse gleichzeitig, die Werbung filtert [TwitchPlayer]. */
    private suspend fun loadTwitch(video: VideoItem, startMs: Long?, queue: List<VideoItem>, queueIndex: Int, autoPlay: Boolean) {
        try {
            TwitchPlayer.reset()
            TwitchAccount.ensureFresh()
            val infoJob = scope.async { runCatching { TwitchApi.watchInfo(video.id) } }
            val play = runCatching { TwitchApi.playback(video.id) }
            val info = infoJob.await()
            // Offline: lieber das sagen als einen HTTP-Fehler
            (info.exceptionOrNull() as? TwitchException)?.takeIf { it.code == "offline" }?.let { throw it }
            val p = play.getOrThrow()
            val w = info.getOrNull()
            val enriched = w?.video?.let { v ->
                video.copy(
                    title = v.title.ifBlank { video.title },
                    channelName = v.channelName.ifBlank { video.channelName },
                    channelUrl = v.channelUrl ?: video.channelUrl,
                    channelAvatar = v.channelAvatar ?: video.channelAvatar,
                    thumbnail = video.thumbnail ?: v.thumbnail,
                    durationSec = if (p.isLive) -1 else v.durationSec.takeIf { it > 0 } ?: video.durationSec,
                    viewCount = v.viewCount,
                    uploadedAt = v.uploadedAt ?: video.uploadedAt,
                    isLive = p.isLive,
                    game = v.game ?: video.game,
                    gameName = v.gameName ?: video.gameName,
                )
            } ?: video.copy(isLive = p.isLive)
            val source = TwitchPlayer.source(p.url, mediaItem(enriched))
            val resume = if (p.isLive) 0L else startMs ?: resumePosition(enriched.id, enriched.durationSec * 1000)
            if (p.isLive) player.setMediaSource(source, true) else player.setMediaSource(source, resume)
            player.setPlaybackSpeed(if (p.isLive) 1f else _speed.value)
            player.prepare()
            player.playWhenReady = autoPlay
            _now.value = NowPlaying(
                video = enriched,
                loading = false,
                options = listOf(StreamResolver.VideoOption(0, "Automatisch")),
                height = 0,
                isLive = p.isLive,
                queue = queue,
                queueIndex = queueIndex,
                twitch = w,
            )
            ensureService()
            startTicker()
            Library.recordWatch(enriched, resume, enriched.durationSec * 1000)
            // Qualitätsstufen stehen fest, sobald ExoPlayer die Master-Playlist geladen hat
            repeat(40) {
                delay(500)
                val list = TwitchPlayer.variants(p.login, p.vod)
                if (list.isNotEmpty()) {
                    val opts = listOf(StreamResolver.VideoOption(0, "Automatisch")) +
                        list.map { (h, fps) -> StreamResolver.VideoOption(h, "${h}p" + if (fps >= 50) fps.toString() else "") }
                    _now.update { n -> if (n?.video?.id == enriched.id) n.copy(options = opts) else n }
                    return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Twitch-Stream konnte nicht geladen werden", t)
            _now.update { n -> n?.copy(loading = false, error = t.message ?: "Stream konnte nicht geladen werden") }
        }
    }

    /** Twitch: Höhe festlegen (0 = automatisch) – ExoPlayer wählt aus den HLS-Varianten. */
    private fun setTwitchQuality(height: Int) {
        val b = player.trackSelectionParameters.buildUpon()
        if (height <= 0) b.clearVideoSizeConstraints() else b.setMaxVideoSize(Int.MAX_VALUE, height).setMinVideoSize(0, height)
        player.trackSelectionParameters = b.build()
        _now.update { it?.copy(height = height) }
    }

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

    fun ensureService() {
        if (controllerFuture != null) return
        runCatching {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            controllerFuture = MediaController.Builder(app, token).buildAsync()
        }.onFailure { Log.w(TAG, "Wiedergabe-Dienst konnte nicht gestartet werden", it) }
    }

    // ------------------------------------------------------------ Bedienung

    fun togglePlay() {
        if (_remote.value) {
            Cast.togglePlay()
            return
        }
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
        } else if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) {
        if (_remote.value) {
            Cast.seekBy(deltaMs)
            return
        }
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, dur))
    }

    fun seekTo(positionMs: Long) {
        if (_remote.value) Cast.seek(positionMs) else player.seekTo(positionMs)
    }

    fun setSpeed(value: Float) {
        if (_remote.value) {
            Cast.setSpeed(value)
            return
        }
        _speed.value = value
        player.setPlaybackSpeed(value)
    }

    /** Qualität fest wählen ([height] > 0) oder automatisch (0: nach Leitung, höchstens wie eingestellt). */
    fun setQuality(height: Int) {
        val n = _now.value ?: return
        if (n.video.isTwitch) return setTwitchQuality(height)
        // Beim Streamen wählt der PC die Qualität selbst
        if (_remote.value) return
        val info = n.info ?: return
        qualityOverride = height.takeIf { it > 0 }
        val auto = qualityOverride == null
        val maxHeight = qualityOverride ?: preferredHeight(_mode.value == PlayerMode.SHORTS)
        qualityJob?.cancel()
        qualityJob = scope.launch {
            try {
                val resolved = withContext(Dispatchers.Default) {
                    StreamResolver.resolve(app, info, maxHeight, mediaItem(n.video), adaptive = auto, caption = captionChoice)
                }
                // Inzwischen ein anderes Video oder auf den PC gewechselt: nichts mehr ersetzen
                if (_now.value?.video?.id != n.video.id || _remote.value) return@launch
                // Position erst jetzt lesen – das Video lief während des Ladens weiter
                val pos = player.currentPosition
                val wasPlaying = player.playWhenReady
                player.setMediaSource(resolved.source, pos)
                applyCaptionSelection(captionChoice)
                player.prepare()
                player.playWhenReady = wasPlaying
                scheduleSponsorMessages(_now.value?.segments.orEmpty())
                _now.update { it?.copy(height = resolved.height, autoQuality = auto && resolved.adaptive) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _messages.tryEmit("Qualität konnte nicht gewechselt werden")
            }
        }
    }

    // ------------------------------------------------------------ Untertitel

    private fun preferredCaption(tracks: List<StreamResolver.CaptionTrack>, lang: String): StreamResolver.CaptionTrack? {
        if (tracks.isEmpty()) return null
        if (lang.isBlank()) return tracks.firstOrNull { !it.auto } ?: tracks.first()
        fun match(t: StreamResolver.CaptionTrack) = t.lang.equals(lang, true) || t.lang.startsWith("$lang-", true)
        return tracks.firstOrNull { match(it) && !it.auto } ?: tracks.firstOrNull { match(it) }
    }

    /** Textspur ein- oder ausschalten (die Untertitel stecken als eigene Spur in der Quelle). */
    private fun applyCaptionSelection(track: StreamResolver.CaptionTrack?) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)
            .apply { if (track != null) setPreferredTextLanguage(track.lang) }
            .build()
    }

    /** Untertitel wählen (null = aus). Andere Sprache: Quelle mit dieser Spur neu aufbauen (kurzes Nachladen). */
    fun setCaption(track: StreamResolver.CaptionTrack?) {
        val n = _now.value ?: return
        if (_remote.value || n.video.isTwitch) return
        if (track == null || track.key == n.caption?.key) {
            captionChoice = track
            applyCaptionSelection(track)
            _now.update { it?.copy(caption = track) }
            return
        }
        val info = n.info ?: return
        captionChoice = track
        val maxHeight = qualityOverride ?: preferredHeight(_mode.value == PlayerMode.SHORTS)
        qualityJob?.cancel()
        qualityJob = scope.launch {
            try {
                val resolved = withContext(Dispatchers.Default) {
                    StreamResolver.resolve(app, info, maxHeight, mediaItem(n.video), adaptive = qualityOverride == null, caption = track)
                }
                if (_now.value?.video?.id != n.video.id || _remote.value) return@launch
                val pos = player.currentPosition
                val wasPlaying = player.playWhenReady
                player.setMediaSource(resolved.source, pos)
                applyCaptionSelection(track)
                player.prepare()
                player.playWhenReady = wasPlaying
                scheduleSponsorMessages(_now.value?.segments.orEmpty())
                _now.update { it?.copy(caption = track) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _messages.tryEmit("Untertitel konnten nicht geladen werden")
            }
        }
    }

    val hasNext: Boolean
        get() = if (_remote.value) Cast.state.value?.hasNext == true
        else _now.value?.let { n -> n.queueIndex >= 0 && n.queueIndex < n.queue.lastIndex } ?: false

    val hasPrevious: Boolean
        get() = if (_remote.value) Cast.state.value?.hasPrevious == true
        else _now.value?.let { n -> n.queueIndex > 0 } ?: false

    fun next() {
        if (_remote.value) {
            Cast.next()
            return
        }
        val n = _now.value ?: return
        if (hasNext) {
            load(n.queue[n.queueIndex + 1], null, false, n.queue)
        } else {
            nextRelated()?.let { load(it, null, false, emptyList()) }
        }
    }

    fun previous() {
        if (_remote.value) {
            Cast.previous()
            return
        }
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
        return n.related.firstOrNull { it.id !in watched && !it.isLive && !it.looksLikeShort }
            ?: n.related.firstOrNull { !it.isLive && (Library.settings.shortsEnabled || !it.looksLikeShort) }
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
        if ((n.video.isTwitch || n.isLive) && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            // Zu weit hinter dem Live-Rand (z. B. nach langer Pause oder langem Puffern): wieder live einsteigen.
            // Gilt auch für YouTube-Livestreams, nicht nur Twitch
            if (n.video.isTwitch) TwitchPlayer.onStalled()
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        val http = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        // Abgelaufene oder gesperrte Stream-URL: frisch laden. Früher nur einmal pro Video überhaupt –
        // nach langer Pause (Adressen laufen nach Stunden ab) kam dann beim zweiten Mal nur noch der Fehler.
        // Jetzt einmal pro Minute, damit es keine Endlosschleife gibt
        val now = System.currentTimeMillis()
        if ((retriedVideo != n.video.id || now - retriedAt > 60_000) &&
            (http != null || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        ) {
            retriedVideo = n.video.id
            retriedAt = now
            load(n.video, player.currentPosition.takeIf { it > 0 }, _mode.value == PlayerMode.SHORTS, n.queue)
            return
        }
        val text = when {
            n.video.isTwitch && http?.responseCode == 404 && n.isLive -> "${n.video.channelName.ifBlank { "Der Kanal" }} ist gerade nicht live."
            http != null -> "${if (n.video.isTwitch) "Twitch" else "YouTube"} hat den Stream abgelehnt (HTTP ${http.responseCode})."
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Keine Internetverbindung."
            else -> "Wiedergabefehler: ${error.errorCodeName}"
        }
        _now.update { it?.copy(error = text) }
    }

    // ------------------------------------------------------------ Hintergrund / Bild-im-Bild

    fun onAppBackground(inPip: Boolean) {
        appInBackground = !inPip
        if (inPip || _now.value == null || _remote.value) return
        when (_mode.value) {
            PlayerMode.WATCH -> if (Library.settings.backgroundPlay) setVideoTrackDisabled(true) else player.pause()
            PlayerMode.SHORTS -> player.pause()
            PlayerMode.NONE -> Unit
        }
    }

    fun onAppForeground() {
        appInBackground = false
        setVideoTrackDisabled(false)
    }

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
                if (n.segments.isNotEmpty() && player.isPlaying) applySponsor(n.segments)
                if (++ticks % 10 == 0 && player.isPlaying) saveProgress()
            }
        }
    }

    private fun resetSponsorState() {
        sponsorMessages.forEach { it.cancel() }
        sponsorMessages.clear()
        if (mutedBySponsor && _now.value?.video?.isTwitch != true) player.volume = 1f
        mutedBySponsor = false
        lastSkip = null
    }

    /**
     * Der Player meldet sich genau an jedem Abschnittsanfang (und am Ende stummer Abschnitte) – so beginnt
     * kein Sponsor-Abschnitt mehr für bis zu eine halbe Sekunde, wie mit der Abfrage alle 500 ms allein.
     * Muss nach jedem Austausch der Quelle neu gesetzt werden.
     */
    private fun scheduleSponsorMessages(segments: List<SponsorSegment>) {
        sponsorMessages.forEach { it.cancel() }
        sponsorMessages.clear()
        if (_remote.value) return
        val times = segments.flatMap { if (it.isMute) listOf(it.startMs, it.endMs) else listOf(it.startMs) }.distinct()
        for (t in times) {
            sponsorMessages += player.createMessage { _, _ -> _now.value?.let { applySponsor(it.segments) } }
                .setLooper(Looper.getMainLooper())
                .setPosition(t)
                .setDeleteAfterDelivery(false)
                .send()
        }
    }

    /** Stummschalten bzw. Überspringen an der aktuellen Stelle. */
    private fun applySponsor(segments: List<SponsorSegment>) {
        if (_remote.value || _now.value?.video?.isTwitch == true) return
        val pos = player.currentPosition
        val mute = segments.firstOrNull { it.isMute && pos >= it.startMs && pos < it.endMs }
        if (mute != null && !mutedBySponsor) {
            mutedBySponsor = true
            player.volume = 0f
            _messages.tryEmit("${SponsorBlock.label(mute.category)}: stumm geschaltet")
        } else if (mute == null && mutedBySponsor) {
            mutedBySponsor = false
            player.volume = 1f
        }
        skipSegments(segments.filterNot { it.isMute })
    }

    /** Den zuletzt übersprungenen Abschnitt doch ansehen (bleibt als übersprungen markiert). */
    fun undoSkip() {
        val seg = lastSkip ?: return
        lastSkip = null
        seekTo(seg.startMs)
    }

    /** Zum Höhepunkt des Videos (SponsorBlock-Highlight) springen. */
    fun seekToHighlight() {
        _now.value?.sponsor?.highlightMs?.let { seekTo(it) }
    }

    private fun skipSegments(segments: List<SponsorSegment>) {
        val pos = player.currentPosition
        val seg = segments.firstOrNull {
            pos >= it.startMs && pos < it.endMs - 500 && it.uuid !in skippedSegments
        } ?: return
        skippedSegments += seg.uuid
        var endMs = seg.endMs
        // Direkt aneinandergrenzende oder überlappende Segmente in einem Sprung zusammenfassen
        while (true) {
            val next = segments.firstOrNull {
                it.uuid !in skippedSegments && it.startMs <= endMs + 500 && it.endMs > endMs
            } ?: break
            skippedSegments += next.uuid
            endMs = next.endMs
        }
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        player.seekTo(if (dur != null) minOf(endMs, dur) else endMs)
        lastSkip = seg
        _actions.tryEmit(ActionMessage("${SponsorBlock.label(seg.category)} übersprungen", "Rückgängig") { undoSkip() })
    }

    fun saveProgress() {
        if (_remote.value) return
        val n = _now.value ?: return
        if (n.loading || n.error != null || n.isLive) return
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        Library.updateProgress(n.video.id, player.currentPosition, dur)
    }

    // ------------------------------------------------------------ Streamen auf den PC

    private fun observeCast() {
        scope.launch {
            Cast.connection.collect { c ->
                when (c) {
                    is CastConnection.Connected -> onCastConnected()
                    CastConnection.Disconnected -> onCastDisconnected()
                    is CastConnection.Connecting -> Unit
                }
            }
        }
        scope.launch { Cast.state.collect { s -> if (Cast.connected && s != null) syncFromRemote(s) } }
        scope.launch { Cast.queue.collect { q -> if (_remote.value) syncQueue(q) } }
    }

    /** Video auf dem PC abspielen; das Handy zeigt Details und die Fernbedienung. */
    private fun castPlay(video: VideoItem, queue: List<VideoItem>, startMs: Long?) {
        stopLocal()
        _mode.value = PlayerMode.WATCH
        expanded.value = true
        _remote.value = true
        val resume = startMs ?: resumePosition(video.id, video.durationSec * 1000).takeIf { it > 0 }
        _now.value = NowPlaying(video = video, loading = true, queue = queue, queueIndex = queue.indexOfFirst { it.id == video.id })
        Cast.play(video, queue, resume)
        loadRemoteDetails(video, resume ?: 0)
        ensureService()
    }

    private fun stopLocal() {
        saveProgress()
        loadJob?.cancel()
        qualityJob?.cancel()
        tickerJob?.cancel()
        player.stop()
        player.clearMediaItems()
        if (fullscreen.value) requestFullscreen(false)
    }

    private fun onCastConnected() {
        ensureService()
        // Wie bei YouTube: läuft gerade ein Video auf dem Handy, geht es auf dem PC weiter
        val n = _now.value
        if (!_remote.value && _mode.value == PlayerMode.WATCH && n != null && n.error == null && !n.loading) {
            val pos = player.currentPosition.takeIf { !n.isLive && it > 0 }
            castPlay(n.video, n.queue, pos ?: 0)
        }
    }

    /** Was am PC läuft, auf dem Handy anzeigen (auch wenn es ein anderes Handy oder Autoplay gestartet hat). */
    private fun syncFromRemote(s: RemoteState) {
        val rv = s.video
        if (rv == null) {
            lastRemote = null
            if (_remote.value) leaveRemote()
            return
        }
        lastRemote = s
        val segments = s.segments.map { SponsorSegment(listOf(it.startMs / 1000.0, it.endMs / 1000.0), it.category, actionType = it.action, uuid = it.uuid) }
        val n = _now.value
        if (!_remote.value || n == null || n.video.id != rv.id || _mode.value != PlayerMode.WATCH) {
            if (_mode.value == PlayerMode.SHORTS) return // Shorts laufen auf dem Handy weiter
            val wasOpen = _mode.value == PlayerMode.WATCH
            stopLocal()
            _remote.value = true
            _mode.value = PlayerMode.WATCH
            if (!wasOpen) expanded.value = false // wie YouTube: als Mini-Player "Läuft auf …"
            val q = Cast.queue.value
            _now.value = NowPlaying(
                video = rv.toVideoItem(),
                loading = true,
                isLive = rv.isLive,
                segments = segments,
                queue = q.items.map { it.toVideoItem() },
                queueIndex = q.index,
            )
            loadRemoteDetails(rv.toVideoItem(), s.positionMs)
            ensureService()
        } else {
            _now.update { cur ->
                if (cur == null) cur
                else cur.copy(
                    segments = if (segments != cur.segments) segments else cur.segments,
                    isLive = s.isLive || cur.isLive,
                    video = if (cur.video.title.isBlank() && rv.title.isNotBlank()) cur.video.copy(title = rv.title, channelName = rv.channelName) else cur.video,
                )
            }
        }
        // Fortschritt merken ("Weiter ansehen" auf dem Handy)
        val now = System.currentTimeMillis()
        if (!s.isLive && s.durationMs > 0 && s.isPlaying && now - lastCastProgressSave > 10_000) {
            lastCastProgressSave = now
            Library.updateProgress(rv.id, s.positionMs, s.durationMs)
        }
    }

    private fun syncQueue(q: RemoteQueue) {
        _now.update { n -> n?.copy(queue = q.items.map { it.toVideoItem() }, queueIndex = q.index) }
    }

    /** Titel, Beschreibung, Kommentare, ähnliche Videos – abgespielt wird am PC. */
    private fun loadRemoteDetails(video: VideoItem, positionMs: Long) {
        castDetailsJob?.cancel()
        if (video.isTwitch) {
            castDetailsJob = scope.launch {
                runCatching { TwitchApi.watchInfo(video.id) }
                    .onSuccess { w ->
                        val v = w.video.copy(thumbnail = video.thumbnail ?: w.video.thumbnail)
                        _now.update { n -> if (n?.video?.id != video.id) n else n.copy(video = v, loading = false, isLive = v.isLive, twitch = w) }
                        Library.recordWatch(v, positionMs, v.durationSec * 1000)
                    }
                    .onFailure { _now.update { n -> if (n?.video?.id == video.id) n.copy(loading = false) else n } }
            }
            return
        }
        castDetailsJob = scope.launch {
            try {
                val info = YouTubeRepo.stream(video.url)
                val enriched = enrich(video, info)
                val related = YouTubeRepo.relatedOf(info)
                val live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
                _now.update { n ->
                    if (n?.video?.id != video.id) n
                    else n.copy(video = enriched, info = info, loading = false, related = related, isLive = live)
                }
                Library.recordWatch(enriched, positionMs, info.duration * 1000)
                Library.addRecommendations(related.take(12))
                // Highlight, "ganzes Video" und Kapitel für die Anzeige (übersprungen wird am PC)
                val settings = Library.settings
                if (!live && settings.sponsorBlock) {
                    val sb = SponsorBlock.info(video.id, settings.sponsorCategories)
                    _now.update { n -> if (n?.video?.id == video.id) n.copy(sponsor = sb) else n }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Details sind nur Beiwerk – das Video läuft trotzdem am PC
                Log.w(TAG, "Details für das gestreamte Video fehlen", t)
                _now.update { n -> if (n?.video?.id == video.id) n.copy(loading = false) else n }
            }
        }
    }

    /** Nichts mehr vom PC anzeigen (PC spielt nichts mehr oder Verbindung getrennt). */
    private fun leaveRemote() {
        castDetailsJob?.cancel()
        _remote.value = false
        _now.value = null
        _mode.value = PlayerMode.NONE
        expanded.value = true
    }

    /**
     * Verbindung zum PC beenden. [continueHere]: am PC anhalten und auf dem Handy an derselben
     * Stelle weiterschauen; sonst läuft das Video am PC einfach weiter.
     */
    fun disconnectCast(continueHere: Boolean) {
        pendingDisconnect = if (continueHere) DisconnectAction.CONTINUE_HERE else DisconnectAction.LEAVE
        if (continueHere) Cast.pause()
        Cast.disconnect(forget = true)
    }

    private fun onCastDisconnected() {
        val action = pendingDisconnect
        pendingDisconnect = null
        val last = lastRemote
        lastRemote = null
        if (!_remote.value) return
        val n = _now.value
        castDetailsJob?.cancel()
        _remote.value = false
        if (n == null || last?.video == null || action == DisconnectAction.LEAVE) {
            leaveRemote()
            return
        }
        // Verbindung verloren → angehalten auf dem Handy; "Hier weiterschauen" → gleich weiter
        val pos = if (last.isLive) null else last.positionMs
        load(n.video, pos, shorts = false, queue = n.queue, autoPlay = action == DisconnectAction.CONTINUE_HERE)
    }
}
