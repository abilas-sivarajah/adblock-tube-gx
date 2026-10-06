package de.abilas.gxtube.ui.watch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.abilas.gxtube.player.PlayerController
import de.abilas.gxtube.player.PlayerMode
import de.abilas.gxtube.ui.BottomBarHeight
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.theme.YtColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val MiniHeight = 60.dp

/**
 * Video-Seite, die sich zum Mini-Player über der unteren Leiste verkleinern lässt
 * (nach unten wischen oder Pfeil), im Querformat Vollbild.
 */
@Composable
fun WatchOverlay(inPip: Boolean, fullscreen: Boolean) {
    val now by PlayerController.now.collectAsStateWithLifecycle()
    val mode by PlayerController.mode.collectAsStateWithLifecycle()
    val expanded by PlayerController.expanded.collectAsStateWithLifecycle()
    val n = now
    if (mode != PlayerMode.WATCH || n == null) return

    if (inPip) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) { PlayerSurface(Modifier.fillMaxSize()) }
        return
    }

    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(expanded) { progress.animateTo(if (expanded) 1f else 0f, tween(250)) }

    BackHandler(enabled = fullscreen) { PlayerController.requestFullscreen(false) }
    BackHandler(enabled = expanded && !fullscreen) { PlayerController.expanded.value = false }

    if (fullscreen) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            PlayerSurface(Modifier.fillMaxSize())
            PlayerControls(
                now = n,
                fullscreen = true,
                onMinimize = { PlayerController.requestFullscreen(false) },
                onFullscreen = { PlayerController.requestFullscreen(it) },
            )
        }
        return
    }

    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fullH = maxHeight
        val fullW = maxWidth
        val aspect = n.aspect.coerceIn(1f, 2.4f)
        val expandedPlayerH = fullW / aspect
        val miniTop = fullH - navBottom - BottomBarHeight - MiniHeight
        val miniPlayerW = MiniHeight * 16f / 9f
        val p = progress.value
        val rangePx = with(density) { miniTop.toPx() }.coerceAtLeast(1f)

        val sheetTop = lerp(miniTop, 0.dp, p)
        val sheetHeight = lerp(MiniHeight, fullH, p)
        val playerTop = lerp(0.dp, statusTop, p)
        val playerW = lerp(miniPlayerW, fullW, p)
        val playerH = lerp(MiniHeight, expandedPlayerH, p)

        val dragState = rememberDraggableState { delta ->
            scope.launch { progress.snapTo((progress.value - delta / rangePx).coerceIn(0f, 1f)) }
        }
        val dragModifier = Modifier.draggable(
            state = dragState,
            orientation = Orientation.Vertical,
            onDragStopped = { velocity ->
                val target = when {
                    velocity > 1200f -> 0f
                    velocity < -1200f -> 1f
                    progress.value > 0.5f -> 1f
                    else -> 0f
                }
                PlayerController.expanded.value = target == 1f
                progress.animateTo(target, tween(200))
            },
        )

        Box(
            Modifier
                .offset { IntOffset(0, sheetTop.roundToPx()) }
                .fillMaxWidth()
                .height(sheetHeight)
                .background(if (p > 0.05f) Yt.colors.background else Yt.colors.card),
        ) {
            // Statusleiste über dem Video schwarz
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(playerTop)
                    .background(Color.Black),
            )

            // Details unter dem Video
            if (p > 0.01f) {
                Box(
                    Modifier
                        .padding(top = playerTop + playerH)
                        .fillMaxSize()
                        .alpha(p),
                ) {
                    WatchDetails(n)
                }
            }

            // Mini-Player: Titel + Knöpfe
            if (p < 0.3f) {
                MiniBar(
                    title = n.video.title,
                    channel = n.video.channelName,
                    modifier = Modifier
                        .padding(start = playerW)
                        .height(MiniHeight)
                        .fillMaxWidth()
                        .alpha((1f - p / 0.3f).coerceIn(0f, 1f))
                        .then(dragModifier)
                        .clickable { PlayerController.expanded.value = true },
                )
            }

            // Video
            Box(
                Modifier
                    .padding(top = playerTop)
                    .width(playerW)
                    .height(playerH)
                    .background(Color.Black)
                    .then(dragModifier),
            ) {
                PlayerSurface(Modifier.fillMaxSize())
                if (p > 0.9f) {
                    PlayerControls(
                        now = n,
                        fullscreen = false,
                        onMinimize = { PlayerController.expanded.value = false },
                        onFullscreen = { PlayerController.requestFullscreen(it) },
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable { PlayerController.expanded.value = true },
                    )
                }
            }

            if (p < 0.3f) {
                MiniProgress(Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

@Composable
private fun MiniBar(title: String, channel: String, modifier: Modifier) {
    val ps = rememberPlayerState()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Yt.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(channel, fontSize = 12.sp, color = Yt.colors.textSecondary, maxLines = 1)
        }
        IconButton(onClick = { PlayerController.togglePlay() }) {
            Icon(
                if (ps.playWhenReady && ps.state != androidx.media3.common.Player.STATE_ENDED) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = "Abspielen/Pause",
                tint = Yt.colors.text,
            )
        }
        IconButton(onClick = { PlayerController.close() }) {
            Icon(Icons.Filled.Close, contentDescription = "Schließen", tint = Yt.colors.text)
        }
    }
}

@Composable
private fun MiniProgress(modifier: Modifier) {
    val ps = rememberPlayerState()
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(Yt.colors.divider),
    ) {
        if (ps.duration > 0) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((ps.position.toFloat() / ps.duration).coerceIn(0f, 1f))
                    .background(YtColors.Red),
            )
        }
    }
}
