package de.abilas.gxtube.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.abilas.gxtube.R
import de.abilas.gxtube.data.HomeFeed
import de.abilas.gxtube.data.Kiosk
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.ShortsFeed
import de.abilas.gxtube.data.SubscriptionFeed
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.data.YouTubeRepo
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.ShortsLaunch
import de.abilas.gxtube.ui.components.AppTopBar
import de.abilas.gxtube.ui.components.Chip
import de.abilas.gxtube.ui.components.CollapsingHeaderLayout
import de.abilas.gxtube.ui.components.ErrorBox
import de.abilas.gxtube.ui.components.LoadMoreEffect
import de.abilas.gxtube.ui.components.LoadingBox
import de.abilas.gxtube.ui.components.PagedList
import de.abilas.gxtube.ui.components.ShortCard
import de.abilas.gxtube.ui.components.VideoCard
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.theme.YtColors
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    /** null = "Alle" */
    var category by mutableStateOf<Kiosk?>(null)
        private set
    val list = PagedList<VideoItem>(viewModelScope) { it.id }

    init {
        viewModelScope.launch { runCatching { SubscriptionFeed.refresh() } }
        select(null)
        viewModelScope.launch { runCatching { ShortsFeed.ensureLoaded() } }
    }

    fun select(kiosk: Kiosk?) {
        category = kiosk
        list.start { if (kiosk == null) HomeFeed.mixed() else YouTubeRepo.kiosk(kiosk) }
    }

    fun refresh() {
        if (category == null) viewModelScope.launch { runCatching { SubscriptionFeed.refresh() } }
        list.reload(pull = true)
    }
}

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val nav = LocalNav.current
    val listState = rememberLazyListState()
    val shorts by ShortsFeed.items.collectAsStateWithLifecycle()
    val lib by Library.data.collectAsStateWithLifecycle()
    val videos = vm.list.items.filter { it.id !in lib.hidden }

    LaunchedEffect(vm.category) { listState.scrollToItem(0) }
    LoadMoreEffect(listState, enabled = vm.list.hasMore) { vm.list.loadMore() }

    CollapsingHeaderLayout(
        headerHeight = 100.dp,
        header = {
            AppTopBar()
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item { Chip("Alle", vm.category == null, { vm.select(null) }) }
                items(Kiosk.entries.toList()) { k -> Chip(k.label, vm.category == k, { vm.select(k) }) }
            }
        },
    ) { padding ->
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = vm.list.refreshing,
            onRefresh = { vm.refresh() },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = vm.list.refreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = padding.calculateTopPadding()),
                )
            },
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 80.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    videos.isEmpty() && vm.list.loading -> item { LoadingBox(Modifier.padding(top = 80.dp)) }
                    videos.isEmpty() && vm.list.error != null ->
                        item { ErrorBox(vm.list.error.orEmpty(), onRetry = { vm.list.reload(false) }) }
                    videos.isEmpty() -> item {
                        ErrorBox("Hier ist gerade nichts. Zieh nach unten zum Aktualisieren.", onRetry = { vm.list.reload(false) })
                    }
                }
                itemsIndexed(videos, key = { _, v -> v.id }) { index, video ->
                    VideoCard(video, onClick = { nav.openVideo(video) })
                    if (index == 1 && vm.category == null && lib.settings.shortsOnHome && shorts.isNotEmpty()) {
                        ShortsShelf(shorts.take(12)) { short ->
                            ShortsLaunch.start.value = short
                            nav.openVideo(short)
                        }
                    }
                }
                if (videos.isNotEmpty() && vm.list.loading && !vm.list.refreshing) {
                    item { LoadingBox() }
                }
            }
        }
    }
}

@Composable
fun ShortsShelf(shorts: List<VideoItem>, onClick: (VideoItem) -> Unit) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Row(
            Modifier.padding(start = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painterResource(R.drawable.ic_shorts_filled),
                contentDescription = null,
                colorFilter = ColorFilter.tint(YtColors.Red),
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Shorts", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Yt.colors.text)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(shorts, key = { it.id }) { s ->
                ShortCard(s, onClick = { onClick(s) }, modifier = Modifier.width(170.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Yt.colors.divider, thickness = 4.dp)
    }
}
