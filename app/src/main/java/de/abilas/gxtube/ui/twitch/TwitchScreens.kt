package de.abilas.gxtube.ui.twitch

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.abilas.gxtube.R
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.twitch.TwitchAccount
import de.abilas.gxtube.twitch.TwitchApi
import de.abilas.gxtube.twitch.TwitchChannel
import de.abilas.gxtube.twitch.TwitchFollows
import de.abilas.gxtube.twitch.TwitchGame
import de.abilas.gxtube.ui.LocalNav
import de.abilas.gxtube.ui.components.AppTopBar
import de.abilas.gxtube.ui.components.Avatar
import de.abilas.gxtube.ui.components.BackTopBar
import de.abilas.gxtube.ui.components.Chip
import de.abilas.gxtube.ui.components.CollapsingHeaderLayout
import de.abilas.gxtube.ui.components.EmptyBox
import de.abilas.gxtube.ui.components.ErrorBox
import de.abilas.gxtube.ui.components.LoadMoreEffect
import de.abilas.gxtube.ui.components.LoadingBox
import de.abilas.gxtube.ui.components.PrimaryPill
import de.abilas.gxtube.ui.components.SectionHeader
import de.abilas.gxtube.ui.components.VideoCard
import de.abilas.gxtube.ui.components.VideoRow
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.util.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Twitch-Lila */
val TwitchPurple = Color(0xFF9147FF)

private fun errorText(t: Throwable) = t.message ?: "Twitch ist gerade nicht erreichbar"

/** Seite mit Laden, Fehler und Nachladen (Cursor von Twitch). */
class TwitchList(private val scope: kotlinx.coroutines.CoroutineScope) {
    var items by mutableStateOf<List<VideoItem>>(emptyList())
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var next by mutableStateOf<String?>(null)
    private var more: (suspend (String) -> Pair<List<VideoItem>, String?>)? = null
    private var job: Job? = null

    fun start(pull: Boolean = false, first: suspend () -> Pair<List<VideoItem>, String?>, more: suspend (String) -> Pair<List<VideoItem>, String?>) {
        this.more = more
        job?.cancel()
        job = scope.launch {
            loading = true
            refreshing = pull
            error = null
            try {
                val (list, n) = first()
                items = list.distinctBy { it.id }
                next = n
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                error = errorText(t)
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    fun loadMore() {
        val cursor = next ?: return
        val m = more ?: return
        if (loading) return
        job = scope.launch {
            loading = true
            try {
                val (list, n) = m(cursor)
                val known = items.map { it.id }.toHashSet()
                items = items + list.filter { known.add(it.id) }
                next = n
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                next = null
            } finally {
                loading = false
            }
        }
    }
}

// ------------------------------------------------------------ Bausteine

@Composable
fun LanguageChips(selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(listOf("de" to "Deutsch", "en" to "Englisch", "" to "Alle Sprachen")) { (code, label) ->
            Chip(label, selected == code, { onSelect(code) })
        }
    }
}

private fun setLanguage(code: String) = Library.updateSettings { it.copy(twitchLanguage = code) }

@Composable
fun GameCard(game: TwitchGame, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    Column(modifier.clickable { nav.openTwitchCategory(game.name) }) {
        AsyncImage(
            model = game.boxArt,
            contentDescription = game.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(6.dp))
                .background(Yt.colors.chip),
        )
        Text(game.displayName, color = Yt.colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        if (game.viewers >= 0) Text(Fmt.watching(game.viewers), color = Yt.colors.textSecondary, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun GameRow(games: List<TwitchGame>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(games, key = { it.name }) { g -> GameCard(g, Modifier.width(104.dp)) }
    }
}

/** Folgen-Knopf (Herz) – auf dem Handy, angemeldet auch im Twitch-Konto. */
@Composable
fun FollowButton(login: String, userId: String?, name: String, avatar: String?, modifier: Modifier = Modifier) {
    val lib by Library.data.collectAsStateWithLifecycle()
    val on = TwitchFollows.isFollowing(lib, login)
    Row(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (on) Yt.colors.chip else TwitchPurple)
            .clickable { TwitchFollows.toggle(login, userId, name, avatar) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (on) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null, tint = if (on) Yt.colors.text else Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (on) "Folge ich" else "Folgen", color = if (on) Yt.colors.text else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun TwitchChannelRow(c: TwitchChannel, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            c.avatar,
            c.name,
            52.dp,
            if (c.live != null) Modifier.border(2.dp, Color(0xFFEB0400), CircleShape) else Modifier,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Yt.colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val live = c.live
            Text(
                if (live != null) Fmt.joinDot("🔴 Live", live.game, Fmt.watching(live.viewCount)) else Fmt.joinDot(if (c.followers >= 0) "${Fmt.count(c.followers)} Follower" else null),
                color = Yt.colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (live != null && live.title.isNotBlank()) Text(live.title, color = Yt.colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        FollowButton(c.login, c.userId, c.name, c.avatar)
    }
}

/** YouTube | Twitch – oben in der Kopfzeile. */
@Composable
fun PlatformSwitch() {
    val lib by Library.data.collectAsStateWithLifecycle()
    val twitch = lib.settings.platform == "twitch"
    val nav = LocalNav.current
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Yt.colors.chip)
            .padding(2.dp),
    ) {
        for ((platform, icon, tint) in listOf(
            Triple("youtube", R.drawable.ic_youtube, Color(0xFFFF0033)),
            Triple("twitch", R.drawable.ic_twitch, if (twitch) Color.White else TwitchPurple),
        )) {
            val active = lib.settings.platform == platform || (platform == "youtube" && !twitch)
            Box(
                Modifier
                    .height(28.dp)
                    .width(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) (if (platform == "twitch") TwitchPurple else Yt.colors.background) else Color.Transparent)
                    .clickable {
                        if (lib.settings.platform != platform) {
                            Library.updateSettings { it.copy(platform = platform) }
                            nav.openTab(de.abilas.gxtube.ui.Routes.HOME)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painterResource(icon),
                    contentDescription = if (platform == "twitch") "Twitch" else "YouTube",
                    colorFilter = ColorFilter.tint(if (active || platform == "youtube") tint else Yt.colors.textSecondary),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------ Startseite

class TwitchHomeViewModel : ViewModel() {
    var language by mutableStateOf(Library.settings.twitchLanguage)
        private set
    var games by mutableStateOf<List<TwitchGame>>(emptyList())
        private set
    var followedLive by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    val streams = TwitchList(viewModelScope)

    init {
        load()
    }

    fun load(pull: Boolean = false) {
        val lang = language
        streams.start(
            pull = pull,
            first = {
                val h = TwitchApi.home(lang)
                games = h.games
                h.streams to h.next
            },
            more = { c -> TwitchApi.moreStreams(lang, c).let { it.items to it.next } },
        )
        viewModelScope.launch {
            runCatching { TwitchFollows.withLiveStatus() }.onSuccess { list -> followedLive = list.mapNotNull { it.live } }
        }
    }

    fun select(code: String) {
        if (code == language) return
        language = code
        setLanguage(code)
        load()
    }

    fun refreshFollowed() {
        viewModelScope.launch {
            runCatching { TwitchFollows.withLiveStatus() }.onSuccess { list -> followedLive = list.mapNotNull { it.live } }
        }
    }
}

@Composable
fun TwitchHomeScreen(vm: TwitchHomeViewModel = viewModel()) {
    val nav = LocalNav.current
    val listState = rememberLazyListState()
    val lib by Library.data.collectAsStateWithLifecycle()
    // Gefolgte aus dem Konto kommen nach dem Anmelden dazu
    LaunchedEffect(lib.twitchFollows.size) { if (lib.twitchFollows.isNotEmpty()) vm.refreshFollowed() }
    LoadMoreEffect(listState, enabled = vm.streams.next != null) { vm.streams.loadMore() }
    LaunchedEffect(vm.language) { listState.scrollToItem(0) }

    CollapsingHeaderLayout(
        headerHeight = 100.dp,
        header = {
            AppTopBar()
            LanguageChips(vm.language) { vm.select(it) }
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = vm.streams.refreshing, onRefresh = { vm.load(pull = true) }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 80.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (vm.followedLive.isNotEmpty()) {
                    item(key = "followed-head") { SectionHeader("Live – Kanäle, denen du folgst") }
                    items(vm.followedLive, key = { "f-" + it.id }) { v -> VideoCard(v, onClick = { nav.openVideo(v) }) }
                    item(key = "followed-div") { HorizontalDivider(color = Yt.colors.divider, thickness = 4.dp) }
                }
                if (vm.games.isNotEmpty()) {
                    item(key = "games-head") { SectionHeader("Kategorien") }
                    item(key = "games") { GameRow(vm.games) }
                    item(key = "games-div") { Spacer(Modifier.height(12.dp)) }
                }
                val title = when (vm.language) {
                    "de" -> "Beliebt auf Deutsch"
                    "en" -> "Beliebt auf Englisch"
                    else -> "Beliebte Streams"
                }
                if (vm.streams.items.isNotEmpty()) item(key = "popular-head") { SectionHeader(title) }
                when {
                    vm.streams.items.isEmpty() && vm.streams.loading -> item { LoadingBox(Modifier.padding(top = 80.dp)) }
                    vm.streams.items.isEmpty() && vm.streams.error != null -> item { ErrorBox(vm.streams.error.orEmpty(), onRetry = { vm.load() }) }
                }
                items(vm.streams.items, key = { it.id }) { v -> VideoCard(v, onClick = { nav.openVideo(v) }) }
                if (vm.streams.items.isNotEmpty() && vm.streams.loading && !vm.streams.refreshing) item { LoadingBox() }
            }
        }
    }
}

// ------------------------------------------------------------ Folge ich

class TwitchFollowingViewModel : ViewModel() {
    var channels by mutableStateOf<List<TwitchChannel>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        TwitchFollows.syncFromAccount()
        load()
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            runCatching { TwitchFollows.withLiveStatus() }
                .onSuccess { channels = it }
                .onFailure { error = errorText(it) }
            loading = false
        }
    }
}

@Composable
fun TwitchFollowingScreen(vm: TwitchFollowingViewModel = viewModel()) {
    val nav = LocalNav.current
    val lib by Library.data.collectAsStateWithLifecycle()
    val account by TwitchAccount.state.collectAsStateWithLifecycle()
    val sync by TwitchFollows.sync.collectAsStateWithLifecycle()
    // Neu gefolgt oder entfolgt (oder aus dem Konto übernommen): Liste auffrischen
    LaunchedEffect(lib.twitchFollows.size) { vm.load() }
    val follows = lib.twitchFollows.map { it.login }.toSet()
    val shown = vm.channels.filter { it.login in follows }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        AppTopBar()
        when {
            lib.twitchFollows.isEmpty() && sync.running -> Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingBox()
                Text("Deine gefolgten Kanäle werden von Twitch geholt …", color = Yt.colors.textSecondary, fontSize = 14.sp)
            }
            lib.twitchFollows.isEmpty() && account?.expired == false && sync.error != null ->
                ErrorBox("Deine gefolgten Kanäle konnten nicht von Twitch geholt werden:\n${sync.error}", onRetry = { TwitchFollows.syncFromAccount() })
            lib.twitchFollows.isEmpty() -> EmptyBox(
                Icons.Outlined.FavoriteBorder,
                "Noch keinem Kanal gefolgt",
                when {
                    account == null -> "Tippe auf einem Kanal auf „Folgen“ – oder melde dich bei Twitch an, dann kommen deine gefolgten Kanäle von selbst."
                    account?.expired == true -> "Deine Twitch-Anmeldung ist abgelaufen – melde dich neu an, dann kommen deine gefolgten Kanäle von selbst."
                    sync.count == 0 -> "Dein Twitch-Konto folgt laut Twitch noch keinem Kanal. Tippe auf einem Kanal auf „Folgen“."
                    else -> "Tippe auf einem Kanal auf „Folgen“."
                },
            ) {
                if (account == null || account?.expired == true) PrimaryPill("Bei Twitch anmelden", onClick = { nav.openTwitchLogin() })
                else PrimaryPill("Von Twitch neu laden", onClick = { TwitchFollows.syncFromAccount() })
            }
            shown.isEmpty() && vm.loading -> LoadingBox(Modifier.padding(top = 80.dp))
            shown.isEmpty() && vm.error != null -> ErrorBox(vm.error.orEmpty(), onRetry = { vm.load() })
            else -> PullToRefreshBox(
                isRefreshing = vm.loading,
                onRefresh = {
                    TwitchFollows.syncFromAccount()
                    vm.load()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(contentPadding = PaddingValues(bottom = 80.dp), modifier = Modifier.fillMaxSize()) {
                    val live = shown.mapNotNull { it.live }
                    if (live.isNotEmpty()) {
                        item(key = "live-head") { SectionHeader("Jetzt live") }
                        items(live, key = { "l-" + it.id }) { v -> VideoCard(v, onClick = { nav.openVideo(v) }) }
                    }
                    val offline = shown.filter { it.live == null }
                    if (offline.isNotEmpty()) {
                        item(key = "off-head") { SectionHeader("Offline") }
                        items(offline, key = { "o-" + it.login }) { c -> TwitchChannelRow(c) { nav.openTwitchChannel(c.login) } }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ Suche

class TwitchSearchViewModel(initial: String) : ViewModel() {
    var field by mutableStateOf(initial)
    var submitted by mutableStateOf<String?>(initial.ifBlank { null })
        private set
    var channels by mutableStateOf<List<TwitchChannel>>(emptyList())
        private set
    var games by mutableStateOf<List<TwitchGame>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    init {
        submitted?.let { submit(it) }
    }

    fun submit(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        field = q
        submitted = q
        Library.addSearch(q)
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            error = null
            try {
                val r = TwitchApi.search(q)
                channels = r.channels
                games = r.games
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                error = errorText(t)
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun TwitchSearchScreen(initial: String) {
    val vm: TwitchSearchViewModel = viewModel(key = "twitch-search-$initial") { TwitchSearchViewModel(initial) }
    val nav = LocalNav.current
    val lib by Library.data.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { if (vm.submitted == null) focus.requestFocus() }

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
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück", tint = Yt.colors.text) }
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
                    if (vm.field.isEmpty()) Text("Auf Twitch suchen", color = Yt.colors.textSecondary, fontSize = 16.sp)
                    BasicTextField(
                        value = vm.field,
                        onValueChange = { vm.field = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Yt.colors.text, fontSize = 16.sp),
                        cursorBrush = SolidColor(Yt.colors.text),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { doSubmit(vm.field) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                }
                if (vm.field.isNotEmpty()) {
                    IconButton(onClick = {
                        vm.field = ""
                        focus.requestFocus()
                    }) { Icon(Icons.Filled.Close, "Leeren", tint = Yt.colors.text) }
                }
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 80.dp), modifier = Modifier.fillMaxSize()) {
            if (vm.submitted == null) {
                items(lib.searchHistory.take(10), key = { "h-$it" }) { h ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { doSubmit(h) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.History, null, tint = Yt.colors.textSecondary)
                        Spacer(Modifier.width(16.dp))
                        Text(h, color = Yt.colors.text, fontSize = 16.sp)
                    }
                }
                return@LazyColumn
            }
            when {
                vm.loading -> item { LoadingBox(Modifier.padding(top = 60.dp)) }
                vm.error != null -> item { ErrorBox(vm.error.orEmpty(), onRetry = { vm.submitted?.let { doSubmit(it) } }) }
                vm.channels.isEmpty() && vm.games.isEmpty() -> item { ErrorBox("Keine Ergebnisse", onRetry = null) }
            }
            if (!vm.loading && vm.games.isNotEmpty()) {
                item(key = "games-head") { SectionHeader("Kategorien") }
                item(key = "games") { GameRow(vm.games) }
            }
            if (!vm.loading && vm.channels.isNotEmpty()) {
                item(key = "channels-head") { SectionHeader("Kanäle") }
                items(vm.channels, key = { "c-" + it.login }) { c ->
                    TwitchChannelRow(c) { c.live?.let { nav.openVideo(it) } ?: nav.openTwitchChannel(c.login) }
                }
            }
        }
    }
}

// ------------------------------------------------------------ Kategorie

class TwitchCategoryViewModel(private val name: String) : ViewModel() {
    var language by mutableStateOf(Library.settings.twitchLanguage)
        private set
    var game by mutableStateOf<TwitchGame?>(null)
        private set
    val streams = TwitchList(viewModelScope)

    init {
        load()
    }

    fun load(pull: Boolean = false) {
        val lang = language
        streams.start(
            pull = pull,
            first = {
                val c = TwitchApi.category(name, lang)
                game = c.game
                c.streams.items to c.streams.next
            },
            more = { cursor -> TwitchApi.category(name, lang, cursor).streams.let { it.items to it.next } },
        )
    }

    fun select(code: String) {
        if (code == language) return
        language = code
        setLanguage(code)
        load()
    }
}

@Composable
fun TwitchCategoryScreen(name: String) {
    val vm: TwitchCategoryViewModel = viewModel(key = "twitch-category-$name") { TwitchCategoryViewModel(name) }
    val nav = LocalNav.current
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, enabled = vm.streams.next != null) { vm.streams.loadMore() }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(vm.game?.displayName ?: name)
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 80.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "head") {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = vm.game?.boxArt,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(72.dp)
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Yt.colors.chip),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(vm.game?.displayName ?: name, color = Yt.colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        vm.game?.viewers?.takeIf { it >= 0 }?.let { Text(Fmt.watching(it), color = Yt.colors.textSecondary, fontSize = 14.sp) }
                    }
                }
            }
            item(key = "lang") { LanguageChips(vm.language) { vm.select(it) } }
            when {
                vm.streams.items.isEmpty() && vm.streams.loading -> item { LoadingBox(Modifier.padding(top = 40.dp)) }
                vm.streams.items.isEmpty() && vm.streams.error != null -> item { ErrorBox(vm.streams.error.orEmpty(), onRetry = { vm.load() }) }
                vm.streams.items.isEmpty() -> item { ErrorBox(if (vm.language.isNotEmpty()) "Gerade keine Streams in dieser Sprache" else "Gerade keine Streams", onRetry = null) }
            }
            items(vm.streams.items, key = { it.id }) { v -> VideoCard(v, onClick = { nav.openVideo(v) }) }
            if (vm.streams.items.isNotEmpty() && vm.streams.loading) item { LoadingBox() }
        }
    }
}

// ------------------------------------------------------------ Kanal

class TwitchChannelViewModel(private val login: String) : ViewModel() {
    var channel by mutableStateOf<TwitchChannel?>(null)
        private set
    val videos = TwitchList(viewModelScope)

    init {
        load()
    }

    fun load() {
        videos.start(
            first = {
                val p = TwitchApi.channelPage(login)
                channel = p.channel
                p.videos.items to p.videos.next
            },
            more = { cursor -> TwitchApi.channelPage(login, cursor).videos.let { it.items to it.next } },
        )
    }
}

@Composable
fun TwitchChannelScreen(login: String) {
    val vm: TwitchChannelViewModel = viewModel(key = "twitch-channel-$login") { TwitchChannelViewModel(login) }
    val nav = LocalNav.current
    val listState = rememberLazyListState()
    val c = vm.channel
    LoadMoreEffect(listState, enabled = vm.videos.next != null) { vm.videos.loadMore() }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(c?.name ?: login)
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 80.dp), modifier = Modifier.fillMaxSize()) {
            if (c?.banner != null) {
                item(key = "banner") {
                    AsyncImage(
                        model = c.banner,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(6.2f)
                            .background(Yt.colors.chip),
                    )
                }
            }
            item(key = "head") {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c?.avatar, c?.name ?: login, 72.dp, if (c?.live != null) Modifier.border(3.dp, Color(0xFFEB0400), CircleShape) else Modifier)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c?.name ?: login, color = Yt.colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                Fmt.joinDot("twitch.tv/$login", c?.followers?.takeIf { it >= 0 }?.let { "${Fmt.count(it)} Follower" }),
                                color = Yt.colors.textSecondary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    if (!c?.description.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(c?.description.orEmpty(), color = Yt.colors.textSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(12.dp))
                    FollowButton(login, c?.userId, c?.name ?: login, c?.avatar)
                }
            }
            c?.live?.let { live ->
                item(key = "live-head") { SectionHeader("Jetzt live") }
                item(key = "live") { VideoCard(live, onClick = { nav.openVideo(live) }) }
            }
            if (vm.videos.items.isNotEmpty()) item(key = "videos-head") { SectionHeader("Vergangene Streams und Videos") }
            when {
                vm.videos.items.isEmpty() && vm.videos.loading -> item { LoadingBox(Modifier.padding(top = 40.dp)) }
                vm.videos.items.isEmpty() && vm.videos.error != null -> item { ErrorBox(vm.videos.error.orEmpty(), onRetry = { vm.load() }) }
                vm.videos.items.isEmpty() && c != null && c.live == null ->
                    item { ErrorBox("Keine Videos (Twitch löscht vergangene Streams meist nach einigen Tagen)", onRetry = null) }
            }
            items(vm.videos.items, key = { it.id }) { v -> VideoRow(v, onClick = { nav.openVideo(v) }) }
            if (vm.videos.items.isNotEmpty() && vm.videos.loading) item { LoadingBox() }
        }
    }
}
