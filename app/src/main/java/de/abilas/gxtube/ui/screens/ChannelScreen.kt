package de.abilas.gxtube.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.abilas.gxtube.data.ChannelPage
import de.abilas.gxtube.data.ChannelTab
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.ListEntry
import de.abilas.gxtube.data.YouTubeRepo
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.components.Avatar
import de.abilas.gxtube.ui.components.BackTopBar
import de.abilas.gxtube.ui.components.ErrorBox
import de.abilas.gxtube.ui.components.LoadMoreEffect
import de.abilas.gxtube.ui.components.LoadingBox
import de.abilas.gxtube.ui.components.PagedList
import de.abilas.gxtube.ui.components.PlaylistRow
import de.abilas.gxtube.ui.components.ShortCard
import de.abilas.gxtube.ui.components.SubscribeButton
import de.abilas.gxtube.ui.components.VerifiedIcon
import de.abilas.gxtube.ui.components.VideoRow
import de.abilas.gxtube.ui.shareText
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.util.Fmt
import kotlinx.coroutines.launch

class ChannelViewModel(private val url: String) : ViewModel() {
    var page by mutableStateOf<ChannelPage?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var selected by mutableIntStateOf(0)
    private val lists = mutableMapOf<String, PagedList<ListEntry>>()

    init {
        load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            runCatching { YouTubeRepo.channel(url) }
                .onSuccess { p ->
                    page = p
                    // Kanalbild im Abo nachtragen
                    if (p.ref.avatar != null && Library.isSubscribed(Library.data.value, p.ref.url)) {
                        Library.setSubscriptionAvatar(p.ref.url, p.ref.avatar)
                    }
                }
                .onFailure { error = YouTubeRepo.errorText(it) }
        }
    }

    fun listFor(tab: ChannelTab): PagedList<ListEntry> = lists.getOrPut(tab.key) {
        PagedList<ListEntry>(viewModelScope) { it.key }.also { l -> l.start { YouTubeRepo.channelTab(tab) } }
    }
}

@Composable
fun ChannelScreen(url: String) {
    val vm: ChannelViewModel = viewModel(key = "channel-$url") { ChannelViewModel(url) }
    val nav = LocalNav.current
    val context = LocalContext.current
    val lib by Library.data.collectAsStateWithLifecycle()
    val page = vm.page
    val gridState = rememberLazyGridState()
    val tabs = page?.tabs.orEmpty().filter { lib.settings.shortsEnabled || it.key != "shorts" }
    val tab = tabs.getOrNull(vm.selected)
    val list = tab?.let { vm.listFor(it) }
    var descriptionOpen by androidx.compose.runtime.remember { mutableStateOf(false) }

    LoadMoreEffect(gridState, enabled = list?.hasMore == true) { list?.loadMore() }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(page?.ref?.name ?: "") {
            IconButton(onClick = { nav.openSearch(page?.ref?.name.orEmpty()) }) {
                Icon(Icons.Outlined.Search, "Suchen", tint = Yt.colors.text)
            }
            IconButton(onClick = { shareText(context, page?.ref?.url ?: url) }) {
                Icon(Icons.Outlined.Share, "Teilen", tint = Yt.colors.text)
            }
        }
        when {
            page == null && vm.error != null -> {
                ErrorBox(vm.error.orEmpty(), onRetry = { vm.load() })
                return@Column
            }
            page == null -> {
                LoadingBox()
                return@Column
            }
        }
        val p = page!!
        val ref = p.ref
        val subscribed = Library.isSubscribed(lib, ref.url)

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = gridState,
            contentPadding = PaddingValues(bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

            if (p.banner != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "banner") {
                    AsyncImage(
                        model = p.banner,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .aspectRatio(6.2f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Yt.colors.chip),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(ref.avatar, ref.name, 72.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    ref.name,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Yt.colors.text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (ref.verified) VerifiedIcon()
                            }
                            Text(
                                Fmt.subscribers(ref.subscriberCount),
                                fontSize = 13.sp,
                                color = Yt.colors.textSecondary,
                            )
                        }
                    }
                    if (!ref.description.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            ref.description,
                            fontSize = 13.sp,
                            color = Yt.colors.textSecondary,
                            maxLines = if (descriptionOpen) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { descriptionOpen = !descriptionOpen },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SubscribeButton(subscribed, modifier = Modifier.fillMaxWidth()) {
                        Library.toggleSubscription(ref.url, ref.name, ref.avatar)
                    }
                }
            }
            item(span = full, key = "tabs") {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp),
                    ) {
                        tabs.forEachIndexed { i, t ->
                            Column(
                                Modifier
                                    .clickable { vm.selected = i }
                                    .padding(horizontal = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    t.label,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (i == vm.selected) Yt.colors.text else Yt.colors.textSecondary,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                                Box(
                                    Modifier
                                        .height(2.dp)
                                        .width(40.dp)
                                        .background(if (i == vm.selected) Yt.colors.text else androidx.compose.ui.graphics.Color.Transparent),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Yt.colors.divider)
                }
            }

            if (list != null) {
                val entries = list.items.filter {
                    lib.settings.shortsEnabled || !(it is ListEntry.Video && it.video.looksLikeShort)
                }
                val shortsTab = tab?.key == "shorts"
                items(
                    entries,
                    key = { "e-" + it.key },
                    span = { e ->
                        if (shortsTab && e is ListEntry.Video) GridItemSpan(1) else GridItemSpan(maxLineSpan)
                    },
                ) { e ->
                    when (e) {
                        is ListEntry.Video -> if (shortsTab) {
                            ShortCard(e.video.copy(isShort = true), onClick = { nav.openVideo(e.video.copy(isShort = true)) })
                        } else {
                            VideoRow(e.video, onClick = { nav.openVideo(e.video) })
                        }
                        is ListEntry.Playlist -> PlaylistRow(e.playlist, onClick = { nav.openPlaylist(e.playlist.url) })
                        is ListEntry.Channel -> Unit
                    }
                }
                if (list.loading) item(span = full) { LoadingBox() }
                if (!list.loading && entries.isEmpty()) {
                    item(span = full) {
                        ErrorBox(list.error ?: "Hier gibt es nichts.", onRetry = if (list.error != null) ({ list.reload(false) }) else null)
                    }
                }
            }
        }
    }
}
