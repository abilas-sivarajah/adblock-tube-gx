package de.abilas.gxtube.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.player.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val HOME = "home"
    const val SHORTS = "shorts"
    const val SUBSCRIPTIONS = "subscriptions"
    const val YOU = "you"
    const val SEARCH = "search?q={q}"
    const val CHANNEL = "channel?url={url}"
    const val PLAYLIST = "playlist?url={url}"
    const val LIBRARY = "library/{kind}"
    const val SETTINGS = "settings"
    const val MANAGE_SUBS = "manage_subscriptions"
    const val LOGIN = "login"
    const val TWITCH_SEARCH = "twitch/search?q={q}"
    const val TWITCH_CHANNEL = "twitch/channel?login={login}"
    const val TWITCH_CATEGORY = "twitch/category?name={name}"
    const val TWITCH_LOGIN = "twitch/login"

    val tabs = listOf(HOME, SHORTS, SUBSCRIPTIONS, YOU)
}

/** Navigation, die überall in der Oberfläche gebraucht wird (Video öffnen, Kanal öffnen …). */
class AppNav(private val nav: NavHostController) {

    fun openVideo(video: VideoItem, queue: List<VideoItem> = emptyList()) {
        if (video.isShort && queue.isEmpty() && Library.settings.shortsEnabled) {
            ShortsLaunch.start.value = video
            openTab(Routes.SHORTS)
        } else {
            PlayerController.play(video, queue)
        }
    }

    fun openChannel(url: String?) {
        if (url.isNullOrBlank()) return
        // Twitch-Kanäle stehen als "twitch:<login>" in den Videos
        if (url.startsWith("twitch:")) return openTwitchChannel(url.removePrefix("twitch:"))
        PlayerController.expanded.value = false
        nav.navigate("channel?url=" + Uri.encode(url))
    }

    fun openTwitchChannel(login: String) {
        PlayerController.expanded.value = false
        nav.navigate("twitch/channel?login=" + Uri.encode(login))
    }

    fun openTwitchCategory(name: String) {
        PlayerController.expanded.value = false
        nav.navigate("twitch/category?name=" + Uri.encode(name))
    }

    fun openTwitchLogin() {
        PlayerController.expanded.value = false
        nav.navigate(Routes.TWITCH_LOGIN)
    }

    fun openPlaylist(url: String) {
        PlayerController.expanded.value = false
        nav.navigate("playlist?url=" + Uri.encode(url))
    }

    /** Suche – auf YouTube oder Twitch, je nach Umschalter oben. */
    fun openSearch(query: String = "") {
        val twitch = Library.settings.platform == "twitch"
        nav.navigate((if (twitch) "twitch/search?q=" else "search?q=") + Uri.encode(query))
    }

    fun openLibrary(kind: String) = nav.navigate("library/$kind")
    fun openSettings() = nav.navigate(Routes.SETTINGS)
    fun openManageSubscriptions() = nav.navigate(Routes.MANAGE_SUBS)
    fun openLogin() = nav.navigate(Routes.LOGIN)

    fun openTab(route: String) {
        nav.navigate(route) {
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun back() {
        nav.popBackStack()
    }
}

/** Short, mit dem der Shorts-Tab starten soll (z. B. aus der Startseite angetippt). */
object ShortsLaunch {
    val start = MutableStateFlow<VideoItem?>(null)
}

val LocalNav = staticCompositionLocalOf<AppNav> { error("AppNav fehlt") }

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
