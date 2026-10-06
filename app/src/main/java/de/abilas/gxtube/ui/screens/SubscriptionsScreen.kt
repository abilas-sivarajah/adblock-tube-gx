package de.abilas.gxtube.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.SubscriptionFeed
import de.abilas.gxtube.data.channelKey
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.components.AppTopBar
import de.abilas.gxtube.ui.components.Avatar
import de.abilas.gxtube.ui.components.Chip
import de.abilas.gxtube.ui.components.CollapsingHeaderLayout
import de.abilas.gxtube.ui.components.EmptyBox
import de.abilas.gxtube.ui.components.LoadingBox
import de.abilas.gxtube.ui.components.PrimaryPill
import de.abilas.gxtube.ui.components.VideoCard
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.theme.YtColors
import kotlinx.coroutines.launch

private enum class SubFilter(val label: String) { ALL("Alle"), TODAY("Heute"), WEEK("Diese Woche"), UNWATCHED("Nicht angesehen") }

@Composable
fun SubscriptionsScreen() {
    val nav = LocalNav.current
    val lib by Library.data.collectAsStateWithLifecycle()
    val feed by SubscriptionFeed.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(SubFilter.ALL) }
    var pulled by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(lib.subscriptions.size) {
        SubscriptionFeed.refresh(force = false)
        SubscriptionFeed.fillMissingAvatars()
    }
    LaunchedEffect(feed.loading) { if (!feed.loading) pulled = false }

    val subKeys = remember(lib.subscriptions) { lib.subscriptions.map { channelKey(it.url) }.toSet() }
    val avatars = remember(lib.subscriptions) { lib.subscriptions.associate { channelKey(it.url) to it.avatar } }
    val watched = remember(lib.history) { lib.history.map { it.video.id }.toSet() }
    val now = System.currentTimeMillis()
    val videos = feed.items
        .filter { channelKey(it.channelUrl) in subKeys && it.id !in lib.hidden }
        .filter {
            when (filter) {
                SubFilter.ALL -> true
                SubFilter.TODAY -> (it.uploadedAt ?: 0L) > now - 86_400_000L
                SubFilter.WEEK -> (it.uploadedAt ?: 0L) > now - 7 * 86_400_000L
                SubFilter.UNWATCHED -> it.id !in watched
            }
        }
        .map { v -> if (v.channelAvatar == null) v.copy(channelAvatar = avatars[channelKey(v.channelUrl)]) else v }

    CollapsingHeaderLayout(headerHeight = 52.dp, header = { AppTopBar() }) { padding ->
        if (lib.subscriptions.isEmpty()) {
            LazyColumn(contentPadding = padding) {
                item {
                    EmptyBox(
                        Icons.Outlined.Subscriptions,
                        "Noch keine Abos",
                        "Abonniere Kanäle, um hier ihre neuesten Videos zu sehen – " +
                            "oder übernimm deine YouTube-Abos aus Google Takeout (unter \"Du\").",
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PrimaryPill("Kanäle suchen", onClick = { nav.openSearch() })
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Abos importieren",
                                color = Yt.colors.link,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable { nav.openTab(de.abilas.gxtube.ui.Routes.YOU) }
                                    .padding(8.dp),
                            )
                        }
                    }
                }
            }
            return@CollapsingHeaderLayout
        }

        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = pulled && feed.loading,
            onRefresh = {
                pulled = true
                scope.launch { SubscriptionFeed.refresh(force = true) }
            },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = pulled && feed.loading,
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
                item(key = "channels") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(lib.subscriptions, key = { it.url }) { s ->
                            Column(
                                Modifier
                                    .width(72.dp)
                                    .clickable { nav.openChannel(s.url) }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Avatar(s.avatar, s.name, 56.dp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    s.name,
                                    fontSize = 11.sp,
                                    color = Yt.colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        item {
                            Text(
                                "Alle",
                                color = Yt.colors.link,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable { nav.openManageSubscriptions() }
                                    .padding(16.dp),
                            )
                        }
                    }
                }
                item(key = "filters") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(SubFilter.entries.toList()) { f -> Chip(f.label, filter == f, { filter = f }) }
                    }
                }
                if (feed.loading && feed.total > 0) {
                    item(key = "progress") {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                "Neue Videos werden geladen … ${feed.done}/${feed.total}",
                                fontSize = 12.sp,
                                color = Yt.colors.textSecondary,
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { feed.done.toFloat() / feed.total },
                                color = YtColors.Red,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (videos.isEmpty() && feed.loading) item { LoadingBox() }
                if (videos.isEmpty() && !feed.loading) {
                    item {
                        Text(
                            "Keine Videos für diesen Filter.",
                            color = Yt.colors.textSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                items(videos, key = { it.id }) { v ->
                    VideoCard(v, onClick = { nav.openVideo(v) })
                }
                if (feed.failed > 0 && !feed.loading) {
                    item {
                        Row(Modifier.padding(16.dp)) {
                            Text(
                                "${feed.failed} Kanäle konnten nicht geladen werden.",
                                fontSize = 12.sp,
                                color = Yt.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
