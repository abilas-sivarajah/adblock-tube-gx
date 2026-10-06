package de.abilas.gxtube.ui.watch

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import de.abilas.gxtube.data.SponsorSegment
import de.abilas.gxtube.player.PlayerController
import de.abilas.gxtube.ui.theme.YtColors
import kotlinx.coroutines.delay

/** Videobild des gemeinsamen Players. */
@Composable
fun PlayerSurface(modifier: Modifier = Modifier, zoom: Boolean = false) {
    val mode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                setKeepContentOnPlayerReset(false)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                resizeMode = mode
                player = PlayerController.player
            }
        },
        update = { view ->
            view.resizeMode = mode
            if (view.player !== PlayerController.player) view.player = PlayerController.player
        },
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
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

/** Wiedergabezustand für die Oberfläche (Position wird 5× pro Sekunde aktualisiert). */
@Composable
fun rememberPlayerState(): PlayerUiState {
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
 * Fortschrittsbalken wie bei YouTube: rot = gesehen, grau = geladen,
 * grün = von SponsorBlock markierte Werbung.
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
                    drawRect(YtColors.Sponsor, Offset(x0, y), Size((x1 - x0).coerceAtLeast(2f), track))
                }
            }
            drawRoundRect(YtColors.Red, Offset(0f, y), Size(w * fraction, track), r)
            if (active) {
                drawCircle(YtColors.Red, radius = if (drag != null) 8.dp.toPx() else 6.dp.toPx(), center = Offset(w * fraction, size.height / 2))
            }
        }
    }
}
