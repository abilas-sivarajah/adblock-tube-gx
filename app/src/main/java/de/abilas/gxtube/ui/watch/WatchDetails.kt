package de.abilas.gxtube.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.abilas.gxtube.data.Comment
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.Paged
import de.abilas.gxtube.data.YouTubeRepo
import de.abilas.gxtube.player.NowPlaying
import de.abilas.gxtube.player.PlayerController
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.components.Avatar
import de.abilas.gxtube.ui.components.ErrorBox
import de.abilas.gxtube.ui.components.LoadMoreEffect
import de.abilas.gxtube.ui.components.LoadingBox
import de.abilas.gxtube.ui.components.PagedList
import de.abilas.gxtube.ui.components.PillButton
import de.abilas.gxtube.ui.components.SubscribeButton
import de.abilas.gxtube.ui.components.VerifiedIcon
import de.abilas.gxtube.ui.components.VideoCard
import de.abilas.gxtube.ui.rememberLinkListener
import de.abilas.gxtube.ui.shareText
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.toAnnotated
import de.abilas.gxtube.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Alles unter dem Video: Titel, Kanal, Aktionen, Kommentare, ähnliche Videos. */
@Composable
fun WatchDetails(n: NowPlaying) {
    val nav = LocalNav.current
    val lib by Library.data.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showDescription by remember(n.video.id) { mutableStateOf(false) }
    var showComments by remember(n.video.id) { mutableStateOf(false) }
    val comments = rememberCommentsState(n.video.url)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(n.video.id) { listState.scrollToItem(0) }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp + navBottom),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "title") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { showDescription = true }
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            ) {
                Text(
                    n.video.title,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = Yt.colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    listOf(
                        if (n.isLive) Fmt.watching(n.video.viewCount) else Fmt.views(n.video.viewCount),
                        if (n.isLive) "Live" else Fmt.ago(n.video.uploadedAt, n.video.uploadedText),
                        "...mehr",
                    ).filter { it.isNotBlank() }.joinToString("  "),
                    fontSize = 12.sp,
                    color = Yt.colors.textSecondary,
                )
            }
        }
        item(key = "channel") {
            val subs = n.info?.uploaderSubscriberCount ?: -1L
            val subscribed = Library.isSubscribed(lib, n.video.channelUrl)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { nav.openChannel(n.video.channelUrl) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(n.video.channelAvatar, n.video.channelName, 36.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    n.video.channelName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Yt.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (n.video.verified) VerifiedIcon()
                Spacer(Modifier.width(8.dp))
                Text(
                    if (subs >= 0) Fmt.count(subs) else "",
                    fontSize = 12.sp,
                    color = Yt.colors.textSecondary,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                if (!n.video.channelUrl.isNullOrBlank()) {
                    SubscribeButton(subscribed) {
                        Library.toggleSubscription(n.video.channelUrl, n.video.channelName, n.video.channelAvatar)
                    }
                }
            }
        }
        item(key = "actions") {
            val liked = Library.isLiked(lib, n.video.id)
            val disliked = n.video.id in lib.disliked
            val later = Library.isInWatchLater(lib, n.video.id)
            val likes = n.info?.likeCount ?: -1L
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(
                        Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Yt.colors.chip),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier
                                .clickable { Library.toggleLike(n.video) }
                                .padding(start = 12.dp, end = 10.dp)
                                .height(36.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                                "Mag ich",
                                tint = Yt.colors.text,
                                modifier = Modifier.size(20.dp),
                            )
                            if (likes >= 0) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    Fmt.count(likes + if (liked) 1 else 0),
                                    color = Yt.colors.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(22.dp)
                                .background(Yt.colors.divider),
                        )
                        Box(
                            Modifier
                                .clickable { Library.toggleDislike(n.video) }
                                .padding(horizontal = 12.dp)
                                .height(36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (disliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                                "Mag ich nicht",
                                tint = Yt.colors.text,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                item { PillButton("Teilen", Icons.Outlined.Share, { shareText(context, n.video.shareUrl) }) }
                item {
                    PillButton(
                        if (later) "Gespeichert" else "Speichern",
                        if (later) Icons.Outlined.Check else Icons.Outlined.PlaylistAdd,
                        { Library.toggleWatchLater(n.video) },
                    )
                }
            }
        }
        if (n.queue.isNotEmpty() && n.queueIndex >= 0) {
            item(key = "queue") {
                val next = n.queue.getOrNull(n.queueIndex + 1)
                Row(
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Yt.colors.card)
                        .clickable(enabled = next != null) { PlayerController.next() }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = Yt.colors.text)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (next != null) "Nächstes: ${next.title}" else "Ende der Playlist",
                            color = Yt.colors.text,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Playlist · ${n.queueIndex + 1}/${n.queue.size}",
                            color = Yt.colors.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        item(key = "comments") {
            CommentsTeaser(comments) { showComments = true }
        }
        if (n.loading) item(key = "loading") { LoadingBox() }
        if (n.error != null && !n.loading) {
            item(key = "error") { ErrorBox(n.error, onRetry = { PlayerController.retry() }) }
        }
        items(n.related.filter { it.id !in lib.hidden }, key = { "rel-" + it.id }) { v ->
            VideoCard(v, onClick = { nav.openVideo(v) })
        }
    }

    if (showDescription) DescriptionSheet(n) { showDescription = false }
    if (showComments) CommentsSheet(n.video.url, comments) { showComments = false }
}

// ---------------------------------------------------------------- Beschreibung

@Composable
fun DescriptionSheet(n: NowPlaying, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listener = rememberLinkListener()
    val link = Yt.colors.link
    val text = remember(n.info, link) { n.info?.description?.toAnnotated(link, listener) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Yt.colors.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Beschreibung", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Yt.colors.text)
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Yt.colors.divider)
            Text(n.video.title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Yt.colors.text)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(Fmt.count(n.info?.likeCount ?: -1L).ifBlank { "–" }, "„Mag ich“")
                Stat(Fmt.count(n.video.viewCount).ifBlank { "–" }, if (n.isLive) "Zuschauer" else "Aufrufe")
                Stat(Fmt.ago(n.video.uploadedAt, n.video.uploadedText).ifBlank { "–" }, "Veröffentlicht")
            }
            Spacer(Modifier.height(16.dp))
            if (text != null) {
                SelectionContainer {
                    Text(text, color = Yt.colors.text, fontSize = 14.sp, lineHeight = 20.sp)
                }
            } else {
                LoadingBox()
            }
            val category = n.info?.category
            if (!category.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text("Kategorie: $category", color = Yt.colors.textSecondary, fontSize = 13.sp)
            }
            val tags = n.info?.tags.orEmpty()
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(tags.take(15).joinToString("  ") { "#$it" }, color = Yt.colors.link, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Yt.colors.text)
        Text(label, fontSize = 12.sp, color = Yt.colors.textSecondary)
    }
}

// ---------------------------------------------------------------- Kommentare

@Stable
class CommentsState(private val url: String, private val scope: CoroutineScope) {
    var count by mutableIntStateOf(-1)
        private set
    var disabled by mutableStateOf(false)
        private set
    val list = PagedList<Comment>(scope) { it.id }
    val replies = mutableStateMapOf<String, List<Comment>>()
    val loadingReplies = mutableStateMapOf<String, Boolean>()

    fun start() {
        list.start {
            val r = YouTubeRepo.comments(url)
            count = r.count
            disabled = r.disabled
            r.page
        }
    }

    fun toggleReplies(c: Comment) {
        if (replies.containsKey(c.id)) {
            replies.remove(c.id)
            return
        }
        val page = c.replies ?: return
        loadingReplies[c.id] = true
        scope.launch {
            val result: Paged<Comment>? = runCatching { YouTubeRepo.replies(url, page) }.getOrNull()
            replies[c.id] = result?.items.orEmpty()
            loadingReplies.remove(c.id)
        }
    }
}

@Composable
fun rememberCommentsState(url: String): CommentsState {
    val scope = rememberCoroutineScope()
    return remember(url) { CommentsState(url, scope).also { it.start() } }
}

@Composable
private fun CommentsTeaser(state: CommentsState, onOpen: () -> Unit) {
    val first = state.list.items.firstOrNull()
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Yt.colors.card)
            .clickable(enabled = !state.disabled, onClick = onOpen)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Kommentare", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Yt.colors.text)
            if (state.count > 0) {
                Text("  " + Fmt.count(state.count.toLong()), fontSize = 13.sp, color = Yt.colors.textSecondary)
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            state.disabled -> Text("Kommentare sind deaktiviert.", fontSize = 13.sp, color = Yt.colors.textSecondary)
            first != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(first.avatar, first.author, 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    first.text.content().replace(Regex("<[^>]*>"), " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'"),
                    fontSize = 13.sp,
                    color = Yt.colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.list.loading -> Text("Kommentare werden geladen …", fontSize = 13.sp, color = Yt.colors.textSecondary)
            state.list.error != null -> Text("Kommentare konnten nicht geladen werden.", fontSize = 13.sp, color = Yt.colors.textSecondary)
            else -> Text("Noch keine Kommentare.", fontSize = 13.sp, color = Yt.colors.textSecondary)
        }
    }
}

@Composable
fun CommentsSheet(videoUrl: String, state: CommentsState? = null, onDismiss: () -> Unit) {
    val own = if (state == null) rememberCommentsState(videoUrl) else null
    val s = state ?: own!!
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, enabled = s.list.hasMore) { s.list.loadMore() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Yt.colors.background) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Kommentare", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Yt.colors.text)
            if (s.count > 0) Text("  " + Fmt.count(s.count.toLong()), fontSize = 15.sp, color = Yt.colors.textSecondary)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = Yt.colors.divider)
        LazyColumn(state = listState, modifier = Modifier.heightIn(min = 300.dp)) {
            if (s.disabled) item { ErrorBox("Kommentare sind deaktiviert.", null) }
            items(s.list.items, key = { it.id }) { c ->
                CommentItem(c, s)
            }
            if (s.list.loading) item { LoadingBox() }
            if (s.list.error != null && s.list.items.isEmpty()) {
                item { ErrorBox(s.list.error.orEmpty(), onRetry = { s.start() }) }
            }
        }
    }
}

@Composable
private fun CommentItem(c: Comment, state: CommentsState, isReply: Boolean = false) {
    val listener = rememberLinkListener()
    val link = Yt.colors.link
    val text = remember(c.id, link) { c.text.toAnnotated(link, listener) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (isReply) 52.dp else 12.dp, end = 12.dp, top = 12.dp),
    ) {
        Avatar(c.avatar, c.author, if (isReply) 24.dp else 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (c.pinned) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PushPin, null, tint = Yt.colors.textSecondary, modifier = Modifier.size(12.dp))
                    Text(" Angepinnt", fontSize = 11.sp, color = Yt.colors.textSecondary)
                }
            }
            Text(
                Fmt.joinDot(c.author, c.date),
                fontSize = 12.sp,
                color = Yt.colors.textSecondary,
                fontWeight = if (c.byChannelOwner) FontWeight.Bold else FontWeight.Normal,
            )
            Spacer(Modifier.height(2.dp))
            Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = Yt.colors.text)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ThumbUp, null, tint = Yt.colors.textSecondary, modifier = Modifier.size(14.dp))
                if (c.likes > 0) Text(" " + Fmt.count(c.likes.toLong()), fontSize = 12.sp, color = Yt.colors.textSecondary)
                if (c.hearted) {
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Filled.Favorite, "Vom Kanal mit Herz markiert", tint = de.abilas.gxtube.ui.theme.YtColors.Red, modifier = Modifier.size(14.dp))
                }
            }
            if (!isReply && c.replyCount > 0 && c.replies != null) {
                val open = state.replies.containsKey(c.id)
                Text(
                    if (open) "Antworten ausblenden" else if (c.replyCount == 1) "1 Antwort" else "${c.replyCount} Antworten",
                    color = Yt.colors.link,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { state.toggleReplies(c) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                )
            }
        }
    }
    if (!isReply) {
        if (state.loadingReplies[c.id] == true) LoadingBox()
        state.replies[c.id]?.forEach { r -> CommentItem(r, state, isReply = true) }
    }
}
