package de.abilas.gxtube.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.ShortsFeed
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.player.PlayerController
import de.abilas.gxtube.player.PlayerMode
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.ShortsLaunch
import de.abilas.gxtube.ui.shareText
import de.abilas.gxtube.ui.watch.CommentsSheet
import de.abilas.gxtube.ui.watch.PlayerSurface
import de.abilas.gxtube.ui.watch.rememberPlayerState
import de.abilas.gxtube.ui.components.Avatar
import de.abilas.gxtube.ui.theme.YtColors
import de.abilas.gxtube.util.Fmt
import kotlinx.coroutines.launch

@Composable
fun ShortsScreen() {
    val feed by ShortsFeed.items.collectAsStateWithLifecycle()
    val start by ShortsLaunch.start.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var loadError by remember { mutableStateOf(false) }

    val list = remember(feed, start) {
        val s = start
        if (s == null) feed else listOf(s) + feed.filterNot { it.id == s.id }
    }
    val pager = rememberPagerState { list.size }

    LaunchedEffect(Unit) {
        runCatching { ShortsFeed.ensureLoaded() }.onFailure { loadError = true }
        if (ShortsFeed.items.value.isEmpty()) loadError = true
    }
    LaunchedEffect(start) {
        if (start != null && list.isNotEmpty()) pager.scrollToPage(0)
    }
    LaunchedEffect(pager.settledPage, list) {
        val video = list.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        PlayerController.playShort(video)
        if (pager.settledPage >= list.size - 4) runCatching { ShortsFeed.loadMore() }
    }
    DisposableEffect(Unit) {
        onDispose {
            PlayerController.stopShorts()
            ShortsLaunch.start.value = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (list.isEmpty()) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (loadError) {
                    Text("Shorts konnten nicht geladen werden.", color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    IconButton(onClick = {
                        loadError = false
                        scope.launch { runCatching { ShortsFeed.refresh() }.onFailure { loadError = true } }
                    }) { Icon(Icons.Outlined.Refresh, "Erneut versuchen", tint = Color.White) }
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        } else {
            VerticalPager(
                state = pager,
                key = { list.getOrNull(it)?.id ?: it },
                beyondViewportPageCount = 1,
            ) { page ->
                val video = list.getOrNull(page)
                if (video != null) ShortPage(video, current = page == pager.settledPage)
            }
        }
        Text(
            "Shorts",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .statusBarsPadding()
                .padding(16.dp),
        )
    }
}

@Composable
private fun ShortPage(video: VideoItem, current: Boolean) {
    val now by PlayerController.now.collectAsStateWithLifecycle()
    val mode by PlayerController.mode.collectAsStateWithLifecycle()
    val lib by Library.data.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val context = LocalContext.current
    val n = now?.takeIf { it.video.id == video.id && mode == PlayerMode.SHORTS }
    val shown = n?.video ?: video
    val ps = rememberPlayerState()
    var showComments by remember { mutableStateOf(false) }
    val liked = Library.isLiked(lib, video.id)
    val disliked = video.id in lib.disliked

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AsyncImage(
            model = video.thumbnailOrDefault,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (current && n != null && !n.loading && n.error == null) {
            PlayerSurface(Modifier.fillMaxSize())
        }
        // Antippen = Pause/Weiter
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { PlayerController.togglePlay() },
        )
        if (current && n != null && (n.loading || ps.state == Player.STATE_BUFFERING)) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        if (current && n != null && !n.loading && !ps.isPlaying && ps.state == Player.STATE_READY) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(8.dp),
            )
        }
        if (current && n?.error != null) {
            Text(
                n.error,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(16.dp),
            )
        }

        // Verlauf unten für lesbaren Text
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(220.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        )

        // Rechte Spalte: Aktionen
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val likes = n?.info?.likeCount ?: -1L
            ShortAction(if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                if (likes >= 0) Fmt.count(likes + if (liked) 1 else 0) else "Mag ich") { Library.toggleLike(shown) }
            ShortAction(if (disliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "Mag ich nicht") {
                Library.toggleDislike(shown)
            }
            ShortAction(Icons.AutoMirrored.Outlined.Comment, "Kommentare") { showComments = true }
            ShortAction(Icons.Outlined.Share, "Teilen") { shareText(context, shown.shareUrl) }
            ShortAction(
                if (Library.isInWatchLater(lib, video.id)) Icons.Outlined.WatchLater else Icons.Outlined.Schedule,
                "Speichern",
            ) { Library.toggleWatchLater(shown) }
        }

        // Unten links: Kanal + Titel
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, end = 80.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    shown.channelAvatar,
                    shown.channelName,
                    32.dp,
                    Modifier.clickable { nav.openChannel(shown.channelUrl) },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    shown.channelName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable { nav.openChannel(shown.channelUrl) },
                )
                Spacer(Modifier.width(8.dp))
                val subscribed = Library.isSubscribed(lib, shown.channelUrl)
                if (!shown.channelUrl.isNullOrBlank()) {
                    Text(
                        if (subscribed) "Abonniert" else "Abonnieren",
                        color = if (subscribed) Color.White else Color.Black,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (subscribed) Color.White.copy(alpha = 0.25f) else Color.White)
                            .clickable {
                                Library.toggleSubscription(shown.channelUrl!!, shown.channelName, shown.channelAvatar)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                shown.title,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Fortschritt
        if (current && n != null && ps.duration > 0) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.3f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((ps.position.toFloat() / ps.duration).coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(YtColors.Red),
                )
            }
        }
    }

    if (showComments) {
        CommentsSheet(videoUrl = video.url, onDismiss = { showComments = false })
    }
}

@Composable
private fun ShortAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(label, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}
