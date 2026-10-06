package de.abilas.gxtube.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.abilas.gxtube.data.PlaylistRef
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.data.YouTubeRepo
import de.abilas.gxtube.player.PlayerController
import de.abilas.gxtube.ui.components.BackTopBar
import de.abilas.gxtube.ui.components.ErrorBox
import de.abilas.gxtube.ui.components.LoadMoreEffect
import de.abilas.gxtube.ui.components.LoadingBox
import de.abilas.gxtube.ui.components.PagedList
import de.abilas.gxtube.ui.components.PillButton
import de.abilas.gxtube.ui.components.PrimaryPill
import de.abilas.gxtube.ui.components.VideoRow
import de.abilas.gxtube.ui.shareText
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.util.Fmt

class PlaylistViewModel(url: String) : ViewModel() {
    var ref by mutableStateOf<PlaylistRef?>(null)
        private set
    val videos = PagedList<VideoItem>(viewModelScope) { it.id }

    init {
        videos.start {
            val p = YouTubeRepo.playlist(url)
            ref = p.ref
            p.videos
        }
    }
}

@Composable
fun PlaylistScreen(url: String) {
    val vm: PlaylistViewModel = viewModel(key = "playlist-$url") { PlaylistViewModel(url) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val ref = vm.ref
    val videos = vm.videos.items
    LoadMoreEffect(listState, enabled = vm.videos.hasMore) { vm.videos.loadMore() }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(ref?.name ?: "Playlist") {
            IconButton(onClick = { shareText(context, ref?.url ?: url) }) {
                Icon(Icons.Outlined.Share, "Teilen", tint = Yt.colors.text)
            }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 80.dp)) {
            if (ref != null) {
                item(key = "header") {
                    Column(Modifier.padding(16.dp)) {
                        AsyncImage(
                            model = ref.thumbnail ?: videos.firstOrNull()?.thumbnailOrDefault,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Yt.colors.chip),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(ref.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Yt.colors.text)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            Fmt.joinDot(ref.uploader, if (ref.count >= 0) Fmt.videos(ref.count) else null),
                            fontSize = 13.sp,
                            color = Yt.colors.textSecondary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryPill("Alle abspielen", onClick = {
                                videos.firstOrNull()?.let { PlayerController.play(it, videos) }
                            })
                            PillButton("Zufallsmix", null, {
                                val shuffled = videos.shuffled()
                                shuffled.firstOrNull()?.let { PlayerController.play(it, shuffled) }
                            })
                        }
                    }
                }
            }
            itemsIndexed(videos, key = { _, v -> v.id }) { i, v ->
                VideoRow(v, onClick = { PlayerController.play(v, videos) }, index = i + 1)
            }
            if (vm.videos.loading) item { LoadingBox() }
            if (!vm.videos.loading && vm.videos.error != null && videos.isEmpty()) {
                item { ErrorBox(vm.videos.error.orEmpty(), onRetry = { vm.videos.reload(false) }) }
            }
        }
    }
}
