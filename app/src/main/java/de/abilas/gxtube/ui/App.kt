package de.abilas.gxtube.ui

import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.abilas.gxtube.R
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.Sync
import de.abilas.gxtube.player.PlayerController
import de.abilas.gxtube.player.PlayerMode
import de.abilas.gxtube.ui.components.LocalWatchProgress
import de.abilas.gxtube.ui.screens.ChannelScreen
import de.abilas.gxtube.ui.screens.HomeScreen
import de.abilas.gxtube.ui.screens.LibraryScreen
import de.abilas.gxtube.ui.screens.LoginScreen
import de.abilas.gxtube.ui.screens.ManageSubscriptionsScreen
import de.abilas.gxtube.ui.screens.PlaylistScreen
import de.abilas.gxtube.ui.screens.SearchScreen
import de.abilas.gxtube.ui.screens.SettingsScreen
import de.abilas.gxtube.ui.screens.ShortsScreen
import de.abilas.gxtube.ui.screens.SubscriptionsScreen
import de.abilas.gxtube.ui.screens.YouScreen
import de.abilas.gxtube.ui.theme.GxTubeTheme
import de.abilas.gxtube.ui.theme.Yt
import de.abilas.gxtube.ui.theme.isDarkTheme
import de.abilas.gxtube.ui.watch.WatchOverlay
import kotlinx.coroutines.flow.MutableStateFlow

/** Links aus anderen Apps ("Teilen" / "Öffnen mit"), die eine Navigation brauchen. */
object IntentRouter {
    val pending = MutableStateFlow<String?>(null)
}

val BottomBarHeight = 52.dp

@Composable
fun AppRoot(inPip: Boolean) {
    val lib by Library.data.collectAsStateWithLifecycle()
    val dark = isDarkTheme(lib.settings.theme)
    val progress = remember(lib.history) {
        lib.history.filter { it.durationMs > 0 }
            .associate { it.video.id to (it.positionMs.toFloat() / it.durationMs) }
    }

    GxTubeTheme(dark = dark) {
        val navController = rememberNavController()
        val nav = remember(navController) { AppNav(navController) }
        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val mode by PlayerController.mode.collectAsStateWithLifecycle()
        val expanded by PlayerController.expanded.collectAsStateWithLifecycle()
        val manualFullscreen by PlayerController.fullscreen.collectAsStateWithLifecycle()
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val watchOpen = mode == PlayerMode.WATCH
        val fullscreen = watchOpen && expanded && (manualFullscreen || landscape)
        val snackbar = remember { SnackbarHostState() }

        SystemBars(
            darkIcons = !dark && route != Routes.SHORTS && !(watchOpen && expanded),
            hidden = fullscreen || inPip,
        )

        LaunchedEffect(Unit) {
            PlayerController.messages.collect { snackbar.showSnackbar(it) }
        }
        LaunchedEffect(Unit) {
            Sync.messages.collect { snackbar.showSnackbar(it) }
        }
        LaunchedEffect(Unit) {
            IntentRouter.pending.collect { target ->
                if (target == null) return@collect
                IntentRouter.pending.value = null
                when {
                    target.startsWith("channel:") -> nav.openChannel(target.removePrefix("channel:"))
                    target.startsWith("playlist:") -> nav.openPlaylist(target.removePrefix("playlist:"))
                    target.startsWith("search:") -> nav.openSearch(target.removePrefix("search:"))
                    target.startsWith("route:") -> when (val r = target.removePrefix("route:")) {
                        Routes.SHORTS -> if (Library.settings.shortsEnabled) nav.openTab(r)
                        in Routes.tabs -> nav.openTab(r)
                        "settings" -> nav.openSettings()
                        else -> nav.openLibrary(r)
                    }
                    target == "shorts" -> if (Library.settings.shortsEnabled) nav.openTab(Routes.SHORTS)
                }
            }
        }

        CompositionLocalProvider(LocalNav provides nav, LocalWatchProgress provides progress) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Yt.colors.background),
            ) {
                if (!inPip) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            AppNavHost(navController)
                        }
                        if (!fullscreen) {
                            BottomBar(route, lib.settings.shortsEnabled) { nav.openTab(it) }
                        }
                    }
                }
                WatchOverlay(inPip = inPip, fullscreen = fullscreen)
                if (!inPip) UpdateDialog()
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 140.dp),
                ) { data ->
                    Snackbar(data)
                }
            }
        }
    }
}

@Composable
private fun SystemBars(darkIcons: Boolean, hidden: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    SideEffect {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = darkIcons
        controller.isAppearanceLightNavigationBars = darkIcons
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun AppNavHost(navController: androidx.navigation.NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen() }
        composable(Routes.SHORTS) { ShortsScreen() }
        composable(Routes.SUBSCRIPTIONS) { SubscriptionsScreen() }
        composable(Routes.YOU) { YouScreen() }
        composable(
            Routes.SEARCH,
            arguments = listOf(navArgument("q") { type = NavType.StringType; defaultValue = "" }),
        ) { entry -> SearchScreen(entry.arguments?.getString("q").orEmpty()) }
        composable(
            Routes.CHANNEL,
            arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" }),
        ) { entry -> ChannelScreen(entry.arguments?.getString("url").orEmpty()) }
        composable(
            Routes.PLAYLIST,
            arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" }),
        ) { entry -> PlaylistScreen(entry.arguments?.getString("url").orEmpty()) }
        composable(Routes.LIBRARY) { entry -> LibraryScreen(entry.arguments?.getString("kind").orEmpty()) }
        composable(Routes.SETTINGS) { SettingsScreen() }
        composable(Routes.MANAGE_SUBS) { ManageSubscriptionsScreen() }
        composable(Routes.LOGIN) { LoginScreen() }
    }
}

@Composable
private fun BottomBar(route: String?, shortsEnabled: Boolean, onSelect: (String) -> Unit) {
    val c = Yt.colors
    Column(Modifier.background(c.background)) {
        HorizontalDivider(color = c.divider, thickness = 0.5.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .height(BottomBarHeight),
        ) {
            BottomItem("Startseite", route == Routes.HOME,
                rememberVectorPainter(Icons.Filled.Home), rememberVectorPainter(Icons.Outlined.Home)) { onSelect(Routes.HOME) }
            if (shortsEnabled) {
                BottomItem("Shorts", route == Routes.SHORTS,
                    painterResource(R.drawable.ic_shorts_filled), painterResource(R.drawable.ic_shorts)) { onSelect(Routes.SHORTS) }
            }
            BottomItem("Abos", route == Routes.SUBSCRIPTIONS,
                rememberVectorPainter(Icons.Filled.Subscriptions), rememberVectorPainter(Icons.Outlined.Subscriptions)) { onSelect(Routes.SUBSCRIPTIONS) }
            BottomItem("Du", route == Routes.YOU,
                rememberVectorPainter(Icons.Filled.AccountCircle), rememberVectorPainter(Icons.Outlined.AccountCircle)) { onSelect(Routes.YOU) }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomItem(
    label: String,
    selected: Boolean,
    selectedIcon: Painter,
    icon: Painter,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            painter = if (selected) selectedIcon else icon,
            contentDescription = label,
            tint = Yt.colors.text,
            modifier = Modifier.size(24.dp),
        )
        Text(label, fontSize = 10.sp, color = Yt.colors.text, lineHeight = 12.sp)
    }
}
