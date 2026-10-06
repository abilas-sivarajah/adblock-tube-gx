package de.abilas.gxtube.ui.watch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import de.abilas.gxtube.player.NowPlaying
import de.abilas.gxtube.player.PlayerController
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.util.Fmt
import kotlinx.coroutines.delay

/** Bedienelemente über dem Video – Aufbau wie in der YouTube-App. */
@Composable
fun PlayerControls(
    now: NowPlaying,
    fullscreen: Boolean,
    onMinimize: () -> Unit,
    onFullscreen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ps = rememberPlayerState()
    var visible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var seekFeedback by remember { mutableStateOf<Pair<Int, Int>?>(null) } // Richtung, Zähler
    var showSettings by remember { mutableStateOf(false) }
    val ended = ps.state == Player.STATE_ENDED

    LaunchedEffect(visible, ps.isPlaying, interaction, scrub) {
        if (visible && ps.isPlaying && scrub == null) {
            delay(3000)
            visible = false
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(700)
            seekFeedback = null
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(now.video.id) {
                detectTapGestures(
                    onTap = {
                        visible = !visible
                        interaction++
                    },
                    onDoubleTap = { off ->
                        if (now.isLive) return@detectTapGestures
                        val forward = off.x > size.width / 2
                        PlayerController.seekBy(if (forward) 10_000 else -10_000)
                        val prev = seekFeedback
                        val dir = if (forward) 1 else -1
                        seekFeedback = dir to (if (prev?.first == dir) prev.second + 1 else 1)
                    },
                )
            },
    ) {
        // Doppeltipp-Rückmeldung
        seekFeedback?.let { (dir, count) ->
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.4f)
                    .align(if (dir > 0) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${if (dir > 0) "+" else "−"}${count * 10} Sekunden",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        AnimatedVisibility(visible = visible || ended || now.error != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                // Oben
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (fullscreen) onFullscreen(false) else onMinimize()
                    }) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Minimieren", tint = Color.White)
                    }
                    if (fullscreen) {
                        Text(
                            now.video.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    IconButton(onClick = {
                        showSettings = true
                        interaction++
                    }) {
                        Icon(Icons.Outlined.Settings, "Einstellungen", tint = Color.White)
                    }
                }

                // Mitte
                if (!now.loading && ps.state != Player.STATE_BUFFERING && now.error == null) {
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(36.dp),
                    ) {
                        CircleButton(Icons.Filled.SkipPrevious, "Zurück", 44.dp) {
                            PlayerController.previous()
                            interaction++
                        }
                        CircleButton(
                            when {
                                ended -> Icons.Filled.Replay
                                ps.playWhenReady -> Icons.Filled.Pause
                                else -> Icons.Filled.PlayArrow
                            },
                            "Abspielen/Pause",
                            60.dp,
                        ) {
                            PlayerController.togglePlay()
                            interaction++
                        }
                        CircleButton(Icons.Filled.SkipNext, "Weiter", 44.dp) {
                            PlayerController.next()
                            interaction++
                        }
                    }
                }

                // Unten: Zeit + Vollbild
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 4.dp, bottom = if (fullscreen) 28.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (now.isLive) {
                        Text(
                            "● LIVE",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    } else if (ps.duration > 0) {
                        Text(
                            Fmt.durationMs(scrub ?: ps.position),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            " / " + Fmt.durationMs(ps.duration),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onFullscreen(!fullscreen) }) {
                        Icon(
                            if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            "Vollbild",
                            tint = Color.White,
                        )
                    }
                }
            }
        }

        if (now.loading || (ps.state == Player.STATE_BUFFERING && ps.playWhenReady)) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        }

        now.error?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Erneut versuchen",
                    color = Color.Black,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .clickable { PlayerController.retry() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // Fortschrittsbalken
        if (!now.isLive && ps.duration > 0) {
            SeekBar(
                position = ps.position,
                duration = ps.duration,
                buffered = ps.buffered,
                segments = now.segments,
                showThumb = visible,
                onScrub = {
                    scrub = it
                    interaction++
                },
                onSeek = {
                    PlayerController.player.seekTo(it)
                    interaction++
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .then(
                        if (fullscreen) Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                        else Modifier.offset(y = 12.dp),
                    ),
            )
        }
    }

    if (showSettings) {
        PlayerSettingsSheet(now, onDismiss = { showSettings = false })
    }
}

@Composable
private fun CircleButton(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/** Zahnrad-Menü: Qualität, Geschwindigkeit, Wiederholen. */
@Composable
fun PlayerSettingsSheet(now: NowPlaying, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember { mutableStateOf("main") }
    val speed by PlayerController.speed.collectAsStateWithLifecycle()
    var loop by remember { mutableStateOf(PlayerController.player.repeatMode == Player.REPEAT_MODE_ONE) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Yt.colors.card) {
        when (page) {
            "quality" -> LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(now.options) { opt ->
                    SheetRow(
                        icon = if (opt.height == now.height) Icons.Filled.Check else null,
                        text = opt.label,
                    ) {
                        PlayerController.setQuality(opt.height)
                        onDismiss()
                    }
                }
                if (now.options.isEmpty()) item { SheetRow(null, "Automatisch (Live)") { onDismiss() } }
            }
            "speed" -> LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)) { s ->
                    SheetRow(
                        icon = if (s == speed) Icons.Filled.Check else null,
                        text = if (s == 1f) "Normal" else "${s}×".replace('.', ','),
                    ) {
                        PlayerController.setSpeed(s)
                        onDismiss()
                    }
                }
            }
            else -> Column(Modifier.padding(bottom = 24.dp)) {
                SheetRow(
                    Icons.Outlined.HighQuality,
                    "Qualität",
                    detail = if (now.isLive) "Automatisch" else now.options.firstOrNull { it.height == now.height }?.label ?: "${now.height}p",
                ) { if (!now.isLive) page = "quality" }
                SheetRow(Icons.Outlined.Speed, "Wiedergabegeschwindigkeit", detail = if (speed == 1f) "Normal" else "${speed}×".replace('.', ',')) {
                    page = "speed"
                }
                SheetRow(Icons.Outlined.Loop, "Video wiederholen", detail = if (loop) "An" else "Aus") {
                    loop = !loop
                    PlayerController.player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                }
                if (now.segments.isNotEmpty()) {
                    SheetRow(Icons.Outlined.Slideshow, "SponsorBlock", detail = "${now.segments.size} Abschnitte werden übersprungen") {}
                }
            }
        }
    }
}

@Composable
fun SheetRow(icon: ImageVector?, text: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp)) {
            if (icon != null) Icon(icon, null, tint = Yt.colors.text)
        }
        Text(text, color = Yt.colors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (detail != null) Text(detail, color = Yt.colors.textSecondary, fontSize = 14.sp)
    }
}
