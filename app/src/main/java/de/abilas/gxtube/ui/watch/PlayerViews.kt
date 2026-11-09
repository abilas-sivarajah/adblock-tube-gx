package de.abilas.gxtube.ui.watch

import android.graphics.Color as AndroidColor
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.abilas.gxtube.cast.Cast
import de.abilas.gxtube.data.Chapter
import de.abilas.gxtube.data.SponsorSegment
import de.abilas.gxtube.player.PlayerController
import de.abilas.gxtube.ui.theme.YtColors
import kotlinx.coroutines.delay

/** Videobild des gemeinsamen Players – mit Untertiteln (selbst gezeichnet, siehe [CaptionOverlay]). */
@Composable
fun PlayerSurface(modifier: Modifier = Modifier, zoom: Boolean = false) {
    val mode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                    setKeepContentOnPlayerReset(false)
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    resizeMode = mode
                    // Untertitel zeichnet CaptionOverlay (YouTubes TTML setzt manche sonst oben ins Bild)
                    subtitleView?.visibility = View.GONE
                    player = PlayerController.player
                }
            },
            update = { view ->
                view.resizeMode = mode
                if (view.player !== PlayerController.player) view.player = PlayerController.player
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        CaptionOverlay(Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Untertitel wie bei YouTube: immer unten mittig, weiße Schrift auf halbdurchsichtigem Schwarz,
 * Schriftgröße passend zur Höhe des Videos (im Mini-Player kleiner, im Vollbild größer).
 */
@Composable
fun CaptionOverlay(modifier: Modifier = Modifier) {
    val player = PlayerController.player
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    DisposableEffect(player) {
        fun read(group: CueGroup) = group.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                lines = read(cueGroup)
            }
        }
        lines = read(player.currentCues)
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val controls by PlayerController.controlsShown.collectAsStateWithLifecycle()
    val lift by androidx.compose.animation.core.animateDpAsState(if (controls) 44.dp else 0.dp, label = "Untertitel")
    if (lines.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        // Im Mini-Player (schmales Bild) nicht: dort wären sie unlesbar klein bzw. verdeckten das ganze Bild
        if (maxWidth < 220.dp) return@BoxWithConstraints
        val size = (maxWidth.value * 0.042f).coerceIn(10f, 26f).sp
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = (maxWidth.value * 0.06f).coerceIn(6f, 40f).dp + lift),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            lines.forEach { line ->
                Text(
                    line,
                    color = Color.White,
                    fontSize = size,
                    lineHeight = size * 1.25f,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Stable
class PlayerUiState {
    var isPlaying by mutableStateOf(false)
    var state by mutableIntStateOf(Player.STATE_IDLE)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var playWhenReady by mutableStateOf(false)
}

/**
 * Wiedergabezustand für die Oberfläche (Position wird 5× pro Sekunde aktualisiert).
 * Beim Streamen kommt er vom PC.
 */
@Composable
fun rememberPlayerState(): PlayerUiState {
    val remote by PlayerController.remote.collectAsStateWithLifecycle()
    return if (remote) rememberRemoteState() else rememberLocalState()
}

@Composable
private fun rememberRemoteState(): PlayerUiState {
    val ui = remember { PlayerUiState() }
    val state by Cast.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        while (true) {
            val s = state
            ui.isPlaying = s?.isPlaying == true
            ui.playWhenReady = s?.playWhenReady == true
            ui.state = when (s?.status) {
                "loading", "buffering" -> Player.STATE_BUFFERING
                "ended" -> Player.STATE_ENDED
                "playing", "paused" -> Player.STATE_READY
                else -> Player.STATE_IDLE
            }
            ui.position = Cast.positionNow()
            ui.duration = s?.durationMs?.takeIf { it > 0 && !s.isLive } ?: 0L
            ui.buffered = s?.bufferedMs ?: 0L
            delay(200)
        }
    }
    return ui
}

@Composable
private fun rememberLocalState(): PlayerUiState {
    val player = PlayerController.player
    val ui = remember { PlayerUiState() }
    DisposableEffect(player) {
        fun sync() {
            ui.isPlaying = player.isPlaying
            ui.state = player.playbackState
            ui.playWhenReady = player.playWhenReady
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()
        }
        sync()
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            ui.position = player.currentPosition
            ui.duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            ui.buffered = player.bufferedPosition
            delay(200)
        }
    }
    return ui
}

/**
 * Fortschrittsbalken wie bei YouTube: rot = gesehen, grau = geladen, grün = von SponsorBlock markierte
 * Werbung (gelb: wird stumm geschaltet), Lücken zwischen den Kapiteln, ◆ = Highlight.
 */
@Composable
fun SeekBar(
    position: Long,
    duration: Long,
    buffered: Long,
    segments: List<SponsorSegment>,
    showThumb: Boolean,
    onScrub: (Long?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<Chapter> = emptyList(),
    highlightMs: Long? = null,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val currentDuration by rememberUpdatedState(duration)
    val fraction = drag ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val d = currentDuration
                    if (d > 0) onSeek((off.x / size.width * d).toLong())
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        drag = (off.x / size.width).coerceIn(0f, 1f)
                        onScrub((drag!! * currentDuration).toLong())
                    },
                    onDragEnd = {
                        drag?.let { onSeek((it * currentDuration).toLong()) }
                        drag = null
                        onScrub(null)
                    },
                    onDragCancel = {
                        drag = null
                        onScrub(null)
                    },
                ) { change, _ ->
                    change.consume()
                    drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    onScrub((drag!! * currentDuration).toLong())
                }
            },
    ) {
        val active = showThumb || drag != null
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val track = if (drag != null) 5.dp.toPx() else 3.dp.toPx()
            val y = size.height / 2 - track / 2
            val w = size.width
            val r = CornerRadius(track / 2, track / 2)
            drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, y), Size(w, track), r)
            drawRoundRect(Color.White.copy(alpha = 0.5f), Offset(0f, y), Size(w * bufferedFraction, track), r)
            if (duration > 0) {
                segments.forEach { s ->
                    val x0 = w * (s.startMs.toFloat() / duration).coerceIn(0f, 1f)
                    val x1 = w * (s.endMs.toFloat() / duration).coerceIn(0f, 1f)
                    drawRect(if (s.isMute) Color(0xFFFFD400) else YtColors.Sponsor, Offset(x0, y), Size((x1 - x0).coerceAtLeast(2f), track))
                }
            }
            drawRoundRect(YtColors.Red, Offset(0f, y), Size(w * fraction, track), r)
            if (duration > 0) {
                // Kapitel: kleine Lücken im Balken
                val gap = 2.dp.toPx()
                chapters.forEach { c ->
                    if (c.startMs <= 0 || c.startMs >= duration) return@forEach
                    val x = w * (c.startMs.toFloat() / duration)
                    drawRect(Color.Black, Offset(x - gap / 2, y - 1f), Size(gap, track + 2f))
                }
                // Highlight: Raute
                if (highlightMs != null && highlightMs in 0..duration) {
                    val cx = w * (highlightMs.toFloat() / duration)
                    val cy = size.height / 2
                    val d = 5.dp.toPx()
                    val path = Path().apply {
                        moveTo(cx, cy - d)
                        lineTo(cx + d, cy)
                        lineTo(cx, cy + d)
                        lineTo(cx - d, cy)
                        close()
                    }
                    drawPath(path, Color(0xFFFF70FF))
                }
            }
            if (active) {
                drawCircle(YtColors.Red, radius = if (drag != null) 8.dp.toPx() else 6.dp.toPx(), center = Offset(w * fraction, size.height / 2))
            }
        }
    }
}
