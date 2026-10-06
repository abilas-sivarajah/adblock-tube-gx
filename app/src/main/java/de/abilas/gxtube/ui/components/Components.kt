package de.abilas.gxtube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import de.abilas.gxtube.data.ChannelRef
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.PlaylistRef
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.shareText
import de.abilas.gxtube.ui.theme.MetaStyle
import de.abilas.gxtube.ui.theme.VideoTitleStyle
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.theme.YtColors
import de.abilas.gxtube.util.Fmt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Wie weit ein Video schon geschaut wurde (0..1) – für den roten Balken unter dem Vorschaubild. */
val LocalWatchProgress = staticCompositionLocalOf<Map<String, Float>> { emptyMap() }

// ---------------------------------------------------------------- Bilder

@Composable
fun Avatar(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val colors = Yt.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.chip),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            color = colors.textSecondary,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Medium,
        )
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun DurationBadge(video: VideoItem, modifier: Modifier = Modifier) {
    when {
        video.isLive -> Text(
            text = "LIVE",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .background(YtColors.LiveRed)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        video.durationSec > 0 -> Text(
            text = Fmt.duration(video.durationSec),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

@Composable
fun Thumbnail(video: VideoItem, modifier: Modifier = Modifier, corner: Dp = 0.dp) {
    val progress = LocalWatchProgress.current[video.id]
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(corner))
            .background(Yt.colors.chip),
    ) {
        AsyncImage(
            model = video.thumbnailOrDefault,
            contentDescription = video.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        DurationBadge(
            video,
            Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
        )
        if (progress != null && progress > 0.02f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.4f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(YtColors.Red),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Video-Karten

fun videoMeta(video: VideoItem, withChannel: Boolean = true): String = Fmt.joinDot(
    if (withChannel) video.channelName else null,
    if (video.isLive) Fmt.watching(video.viewCount) else Fmt.views(video.viewCount),
    if (video.isLive) null else Fmt.ago(video.uploadedAt, video.uploadedText),
)

/** Große Karte wie auf der YouTube-Startseite. */
@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    extraMenu: List<MenuAction> = emptyList(),
) {
    val nav = LocalNav.current
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Thumbnail(video, Modifier.fillMaxWidth())
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 20.dp)) {
            Avatar(
                url = video.channelAvatar,
                name = video.channelName,
                size = 36.dp,
                modifier = Modifier.clickable { nav.openChannel(video.channelUrl) },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    style = VideoTitleStyle,
                    color = Yt.colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                MetaLine(video)
            }
            VideoMenuButton(video, extraMenu)
        }
    }
}

@Composable
private fun MetaLine(video: VideoItem, withChannel: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            videoMeta(video, withChannel),
            style = MetaStyle,
            color = Yt.colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Kompakte Zeile (Vorschaubild links) – für Verlauf, Kanal-Videos, Playlists. */
@Composable
fun VideoRow(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    extraMenu: List<MenuAction> = emptyList(),
    index: Int? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (index != null) {
            Text(
                "$index",
                style = MetaStyle,
                color = Yt.colors.textSecondary,
                modifier = Modifier
                    .width(24.dp)
                    .padding(top = 30.dp),
                textAlign = TextAlign.Center,
            )
        }
        Thumbnail(video, Modifier.width(160.dp), corner = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                color = Yt.colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(video.channelName, style = MetaStyle, color = Yt.colors.textSecondary, maxLines = 1)
            Text(
                Fmt.joinDot(Fmt.views(video.viewCount), Fmt.ago(video.uploadedAt, video.uploadedText)),
                style = MetaStyle,
                color = Yt.colors.textSecondary,
                maxLines = 1,
            )
        }
        VideoMenuButton(video, extraMenu, Modifier.padding(top = 0.dp))
    }
}

/** Hochkant-Karte für Shorts (Startseite, Kanal). */
@Composable
fun ShortCard(video: VideoItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(10.dp))
            .background(Yt.colors.chip)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = video.thumbnailOrDefault,
            contentDescription = video.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(90.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        ) {
            Text(
                video.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (video.viewCount >= 0) {
                Text(Fmt.views(video.viewCount), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ChannelRow(channel: ChannelRef, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val lib by Library.data.collectAsStateWithLifecycle()
    val subscribed = Library.isSubscribed(lib, channel.url)
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(120.dp), contentAlignment = Alignment.Center) {
            Avatar(channel.avatar, channel.name, 88.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.name,
                    style = VideoTitleStyle,
                    color = Yt.colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (channel.verified) VerifiedIcon()
            }
            Text(
                Fmt.joinDot(Fmt.subscribers(channel.subscriberCount), Fmt.videos(channel.videoCount)),
                style = MetaStyle,
                color = Yt.colors.textSecondary,
            )
            Spacer(Modifier.height(8.dp))
            SubscribeButton(subscribed, small = true) {
                Library.toggleSubscription(channel.url, channel.name, channel.avatar)
            }
        }
    }
}

@Composable
fun PlaylistRow(playlist: PlaylistRef, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .width(160.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Yt.colors.chip),
        ) {
            AsyncImage(
                model = playlist.thumbnail,
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = Color.White, modifier = Modifier.size(14.dp))
                if (playlist.count >= 0) {
                    Text(" ${playlist.count}", color = Color.White, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.name, fontSize = 14.sp, color = Yt.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                Fmt.joinDot(playlist.uploader, "Playlist"),
                style = MetaStyle,
                color = Yt.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun VerifiedIcon() {
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = "Bestätigt",
        tint = Yt.colors.textSecondary,
        modifier = Modifier
            .padding(start = 4.dp)
            .size(14.dp),
    )
}

// ---------------------------------------------------------------- Menü

data class MenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun VideoMenuButton(video: VideoItem, extra: List<MenuAction> = emptyList(), modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val nav = LocalNav.current
    val lib by Library.data.collectAsStateWithLifecycle()
    val inLater = Library.isInWatchLater(lib, video.id)
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Optionen", tint = Yt.colors.text, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val actions = buildList {
                add(
                    MenuAction(
                        if (inLater) "Aus \"Später ansehen\" entfernen" else "Zu \"Später ansehen\" hinzufügen",
                        Icons.Outlined.Schedule,
                    ) { Library.toggleWatchLater(video) },
                )
                add(MenuAction("Teilen", Icons.Outlined.Share) { shareText(context, video.shareUrl) })
                if (!video.channelUrl.isNullOrBlank()) {
                    add(MenuAction("Zum Kanal", Icons.Outlined.Person) { nav.openChannel(video.channelUrl) })
                }
                addAll(extra)
                if (extra.none { it.icon == Icons.Outlined.Delete }) {
                    add(MenuAction("Kein Interesse", Icons.Outlined.Block) { Library.hide(video) })
                }
            }
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label) },
                    leadingIcon = { Icon(a.icon, null) },
                    onClick = {
                        open = false
                        a.onClick()
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Knöpfe & Chips

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Yt.colors
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) c.chipSelected else c.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) c.chipSelectedText else c.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
fun PillButton(
    text: String?,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Yt.colors.text,
) {
    Row(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Yt.colors.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        if (icon != null && text != null) Spacer(Modifier.width(6.dp))
        if (text != null) Text(text, color = Yt.colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SubscribeButton(subscribed: Boolean, small: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Yt.colors
    val height = if (small) 32.dp else 36.dp
    if (subscribed) {
        Row(
            modifier
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(c.chip)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.NotificationsNone, null, tint = c.text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Abonniert", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    } else {
        Box(
            modifier
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(c.chipSelected)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Abonnieren", color = c.chipSelectedText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ---------------------------------------------------------------- Zustände

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = YtColors.Red, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = Yt.colors.text, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRetry) { Text("Erneut versuchen") }
        }
    }
}

@Composable
fun EmptyBox(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Yt.colors.textSecondary, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Yt.colors.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, color = Yt.colors.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        actions()
    }
}

@Composable
fun PrimaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = Yt.colors.chipSelected,
            contentColor = Yt.colors.chipSelectedText,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) { Text(text, fontWeight = FontWeight.Medium) }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Yt.colors.text, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Transparent,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onAction),
            ) {
                Text(
                    action,
                    color = Yt.colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Nachladen beim Scrollen

@Composable
fun LoadMoreEffect(state: LazyListState, enabled: Boolean, threshold: Int = 6, onLoadMore: () -> Unit) {
    val current by rememberUpdatedState(onLoadMore)
    val shouldLoad by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - threshold
        }
    }
    LaunchedEffect(state, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { shouldLoad }
            .distinctUntilChanged()
            .filter { it }
            .collect { current() }
    }
}

@Composable
fun LoadMoreEffect(state: LazyGridState, enabled: Boolean, threshold: Int = 6, onLoadMore: () -> Unit) {
    val current by rememberUpdatedState(onLoadMore)
    val shouldLoad by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - threshold
        }
    }
    LaunchedEffect(state, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { shouldLoad }
            .distinctUntilChanged()
            .filter { it }
            .collect { current() }
    }
}
