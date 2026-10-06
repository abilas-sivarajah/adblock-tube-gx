package de.abilas.gxtube.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NorthWest
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.ListEntry
import de.abilas.gxtube.data.SearchFilter
import de.abilas.gxtube.data.YouTubeRepo
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.components.ChannelRow
import de.abilas.gxtube.ui.components.Chip
import de.abilas.gxtube.ui.components.ErrorBox
import de.abilas.gxtube.ui.components.LoadMoreEffect
import de.abilas.gxtube.ui.components.LoadingBox
import de.abilas.gxtube.ui.components.PagedList
import de.abilas.gxtube.ui.components.PlaylistRow
import de.abilas.gxtube.ui.components.VideoCard
import de.abilas.gxtube.ui.theme.Yt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchViewModel(initial: String) : ViewModel() {
    var field by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    var submitted by mutableStateOf<String?>(initial.ifBlank { null })
        private set
    var filter by mutableStateOf(SearchFilter.ALL)
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var correction by mutableStateOf<String?>(null)
        private set
    val results = PagedList<ListEntry>(viewModelScope) { it.key }
    private var suggestJob: Job? = null

    init {
        submitted?.let { submit(it) }
    }

    fun onChange(value: TextFieldValue) {
        val changed = value.text != field.text
        field = value
        if (!changed) return
        suggestJob?.cancel()
        val q = value.text
        suggestJob = viewModelScope.launch {
            delay(180)
            suggestions = if (q.isBlank()) emptyList() else YouTubeRepo.suggestions(q)
        }
    }

    fun submit(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        field = TextFieldValue(q, TextRange(q.length))
        submitted = q
        correction = null
        Library.addSearch(q)
        results.start {
            val (suggestion, page) = YouTubeRepo.search(q, filter)
            correction = suggestion
            page
        }
    }

    fun select(f: SearchFilter) {
        filter = f
        submitted?.let { submit(it) }
    }
}

@Composable
fun SearchScreen(initial: String) {
    val vm: SearchViewModel = viewModel(key = "search-$initial") { SearchViewModel(initial) }
    val nav = LocalNav.current
    val lib by Library.data.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val editing = focused && (vm.submitted == null || vm.field.text != vm.submitted)

    LaunchedEffect(Unit) { if (vm.submitted == null) focus.requestFocus() }
    LoadMoreEffect(listState, enabled = vm.results.hasMore && !editing) { vm.results.loadMore() }

    fun doSubmit(q: String) {
        vm.submit(q)
        focusManager.clearFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Yt.colors.background)
            .statusBarsPadding(),
    ) {
        // Suchleiste
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Yt.colors.text)
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Yt.colors.chip)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (vm.field.text.isEmpty()) {
                        Text("Suchen", color = Yt.colors.textSecondary, fontSize = 16.sp)
                    }
                    BasicTextField(
                        value = vm.field,
                        onValueChange = { vm.onChange(it) },
                        singleLine = true,
                        textStyle = TextStyle(color = Yt.colors.text, fontSize = 16.sp),
                        cursorBrush = SolidColor(Yt.colors.text),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSubmit(vm.field.text) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused },
                    )
                }
                if (vm.field.text.isNotEmpty()) {
                    IconButton(onClick = {
                        vm.onChange(TextFieldValue(""))
                        focus.requestFocus()
                    }) {
                        Icon(Icons.Filled.Close, "Leeren", tint = Yt.colors.text)
                    }
                }
            }
        }

        if (editing) {
            val q = vm.field.text.trim()
            val history = lib.searchHistory.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }.take(8)
            val remote = vm.suggestions.filterNot { s -> history.any { it.equals(s, true) } }
            LazyColumn(Modifier.fillMaxSize()) {
                items(history, key = { "h-$it" }) { h ->
                    SuggestionRow(h, isHistory = true, onFill = { vm.onChange(TextFieldValue(h, TextRange(h.length))) }) {
                        doSubmit(h)
                    }
                }
                items(remote, key = { "s-$it" }) { s ->
                    SuggestionRow(s, isHistory = false, onFill = { vm.onChange(TextFieldValue("$s ", TextRange(s.length + 1))) }) {
                        doSubmit(s)
                    }
                }
            }
            return@Column
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SearchFilter.entries.toList()) { f -> Chip(f.label, vm.filter == f, { vm.select(f) }) }
        }

        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 80.dp), modifier = Modifier.fillMaxSize()) {
            vm.correction?.let { c ->
                item(key = "correction") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { doSubmit(c) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("Meintest du: ", color = Yt.colors.textSecondary, fontSize = 14.sp)
                        Text(c, color = Yt.colors.link, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            val entries = vm.results.items.filter {
                lib.settings.shortsEnabled || !(it is ListEntry.Video && it.video.looksLikeShort)
            }
            when {
                entries.isEmpty() && vm.results.loading -> item { LoadingBox(Modifier.padding(top = 48.dp)) }
                entries.isEmpty() && vm.results.error != null ->
                    item { ErrorBox(vm.results.error.orEmpty(), onRetry = { vm.submitted?.let { vm.submit(it) } }) }
                entries.isEmpty() && vm.submitted != null ->
                    item { ErrorBox("Keine Ergebnisse für \"${vm.submitted}\".", null) }
            }
            items(entries, key = { it.key }) { e ->
                when (e) {
                    is ListEntry.Video -> VideoCard(e.video, onClick = { nav.openVideo(e.video) })
                    is ListEntry.Channel -> ChannelRow(e.channel, onClick = { nav.openChannel(e.channel.url) })
                    is ListEntry.Playlist -> PlaylistRow(e.playlist, onClick = { nav.openPlaylist(e.playlist.url) })
                }
            }
            if (entries.isNotEmpty() && vm.results.loading) item { LoadingBox() }
        }
    }
}

@Composable
private fun SuggestionRow(text: String, isHistory: Boolean, onFill: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isHistory) Icons.Outlined.History else Icons.Outlined.Search,
            null,
            tint = Yt.colors.text,
        )
        Spacer(Modifier.width(20.dp))
        Text(
            text,
            color = Yt.colors.text,
            fontSize = 16.sp,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 14.dp),
        )
        if (isHistory) {
            IconButton(onClick = { Library.removeSearch(text) }) {
                Icon(Icons.Filled.Close, "Aus Suchverlauf entfernen", tint = Yt.colors.textSecondary)
            }
        }
        IconButton(onClick = onFill) {
            Icon(Icons.Outlined.NorthWest, "Übernehmen", tint = Yt.colors.text)
        }
    }
}
