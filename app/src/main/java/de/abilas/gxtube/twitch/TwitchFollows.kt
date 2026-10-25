package de.abilas.gxtube.twitch

import android.util.Log
import de.abilas.gxtube.data.Library
import de.abilas.gxtube.data.LibraryData
import de.abilas.gxtube.data.TwitchFollow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Twitch-Kanäle, denen man folgt: auf dem Handy, mit Twitch-Konto auch im Konto. */
object TwitchFollows {
    private const val TAG = "TwitchFollows"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Abgleich mit dem Twitch-Konto: läuft, Fehler oder Anzahl übernommener Kanäle. */
    data class SyncState(val running: Boolean = false, val error: String? = null, val count: Int? = null)

    private val _sync = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    fun isFollowing(data: LibraryData, login: String?) = login != null && data.twitchFollows.any { it.login == login }

    fun toggle(login: String, userId: String?, name: String, avatar: String?) {
        val on = !isFollowing(Library.data.value, login)
        Library.update { d ->
            val rest = d.twitchFollows.filterNot { it.login == login }
            d.copy(twitchFollows = if (on) listOf(TwitchFollow(login, userId, name, avatar)) + rest else rest)
        }
        _messages.tryEmit(if (on) "Du folgst $name" else "Du folgst $name nicht mehr")
        if (!TwitchAccount.loggedIn) return
        scope.launch {
            runCatching {
                TwitchAccount.ensureFresh()
                val id = userId ?: TwitchApi.users(listOf(login)).firstOrNull()?.userId ?: throw TwitchException("Kanal nicht gefunden")
                TwitchApi.follow(id, on)
            }.onFailure {
                Log.w(TAG, "Folgen im Konto fehlgeschlagen", it)
                _messages.tryEmit("Nur auf dem Handy gespeichert (Twitch: ${it.message})")
            }
        }
    }

    /** Gefolgte aus dem Twitch-Konto dazunehmen (Namen und Bilder auffrischen). */
    fun syncFromAccount() {
        if (!TwitchAccount.loggedIn || _sync.value.running) return
        _sync.value = SyncState(running = true)
        scope.launch {
            runCatching {
                TwitchAccount.ensureFresh()
                val account = TwitchAccount.state.value ?: throw TwitchException("Nicht bei Twitch angemeldet", signIn = true)
                // Konto-Nummer fehlt (ältere Anmeldung)? Bei Twitch nachfragen
                val userId = account.user.id ?: TwitchApi.validate(account.tokens.accessToken)?.userId
                TwitchApi.followedChannels(userId).also { merge(it) }
            }.onSuccess { list ->
                Log.i(TAG, "${list.size} gefolgte Kanäle aus dem Konto")
                _sync.value = SyncState(count = list.size)
            }.onFailure {
                Log.w(TAG, "Gefolgte nicht geladen", it)
                _sync.value = SyncState(error = it.message ?: it.toString())
            }
        }
    }

    fun merge(list: List<TwitchChannel>) {
        if (list.isEmpty()) return
        Library.update { d ->
            val known = d.twitchFollows.associateBy { it.login }.toMutableMap()
            for (c in list) {
                val old = known[c.login]
                known[c.login] = old?.copy(name = c.name, avatar = c.avatar ?: old.avatar, userId = c.userId ?: old.userId)
                    ?: TwitchFollow(c.login, c.userId, c.name, c.avatar)
            }
            // Reihenfolge: bisherige zuerst, neue dahinter
            val order = d.twitchFollows.map { it.login } + list.map { it.login }
            d.copy(twitchFollows = order.distinct().mapNotNull { known[it] })
        }
    }

    /** Gefolgte mit Live-Status: live zuerst (nach Zuschauern), dann der Rest. */
    suspend fun withLiveStatus(): List<TwitchChannel> {
        val follows = Library.data.value.twitchFollows
        if (follows.isEmpty()) return emptyList()
        val byLogin = TwitchApi.users(follows.map { it.login }).associateBy { it.login }
        merge(byLogin.values.toList())
        return follows.map { f -> byLogin[f.login] ?: TwitchChannel(f.login, f.userId, f.name, f.avatar) }
            .sortedByDescending { it.live?.viewCount ?: -1 }
    }
}
