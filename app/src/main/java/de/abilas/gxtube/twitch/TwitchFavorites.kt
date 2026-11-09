package de.abilas.gxtube.twitch

import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.LibraryData
import de.abilas.gxtube.data.TwitchFollow

/**
 * Twitch-Favoriten (Stern): Kanäle, die ganz oben stehen sollen – unabhängig vom Folgen.
 * Twitch selbst kennt keine Favoriten, sie bleiben nur auf dem Handy (wie in GX Tube Desktop).
 */
object TwitchFavorites {
    private const val MAX = 500

    fun isFavorite(data: LibraryData, login: String?) = login != null && data.twitchFavorites.any { it.login == login }

    fun toggle(login: String, userId: String?, name: String, avatar: String?) {
        val on = !isFavorite(Library.data.value, login)
        Library.update { d ->
            val old = d.twitchFavorites.firstOrNull { it.login == login }
            val rest = d.twitchFavorites.filterNot { it.login == login }
            val entry = TwitchFollow(login, userId ?: old?.userId, name.ifBlank { old?.name ?: login }, avatar ?: old?.avatar, old?.addedAt ?: System.currentTimeMillis())
            d.copy(twitchFavorites = if (on) (listOf(entry) + rest).take(MAX) else rest)
        }
        TwitchFollows.message(if (on) "★ $name ist jetzt ein Favorit" else "$name ist kein Favorit mehr")
    }

    /** Favoriten mit Live-Status: live zuerst (nach Zuschauern), dann der Rest. Namen/Bilder werden aufgefrischt. */
    suspend fun withLiveStatus(): List<TwitchChannel> {
        val favs = Library.data.value.twitchFavorites
        if (favs.isEmpty()) return emptyList()
        val byLogin = TwitchApi.users(favs.map { it.login }).associateBy { it.login }
        refresh(byLogin.values.toList())
        return favs.map { f -> byLogin[f.login] ?: TwitchChannel(f.login, f.userId, f.name, f.avatar) }
            .sortedByDescending { it.live?.viewCount ?: -1 }
    }

    private fun refresh(list: List<TwitchChannel>) {
        if (list.isEmpty()) return
        val byLogin = list.associateBy { it.login }
        val changed = Library.data.value.twitchFavorites.any { f ->
            val c = byLogin[f.login] ?: return@any false
            (c.name.isNotBlank() && c.name != f.name) || (c.avatar != null && c.avatar != f.avatar) || (c.userId != null && c.userId != f.userId)
        }
        if (!changed) return
        Library.update { d ->
            d.copy(twitchFavorites = d.twitchFavorites.map { f ->
                val c = byLogin[f.login] ?: return@map f
                f.copy(name = c.name.ifBlank { f.name }, avatar = c.avatar ?: f.avatar, userId = c.userId ?: f.userId)
            })
        }
    }
}
