package de.abilas.gxtube.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Überträgt Abonnieren, "Mag ich" und "Später ansehen" ins YouTube-Konto, wenn du angemeldet bist. */
object Sync {
    private const val TAG = "Sync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun run(what: String, block: suspend () -> Unit) {
        if (!Account.loggedIn) return
        scope.launch {
            runCatching { block() }.onFailure {
                Log.w(TAG, "$what fehlgeschlagen", it)
                _messages.tryEmit("$what konnte nicht ins YouTube-Konto übertragen werden")
            }
        }
    }

    fun subscription(channelUrl: String, subscribed: Boolean) =
        run(if (subscribed) "Abo" else "Abo beenden") { Account.setSubscribed(channelUrl, subscribed) }

    fun rating(videoId: String, rating: Account.Rating) = run("Bewertung") { Account.rate(videoId, rating) }

    fun watchLater(videoId: String, add: Boolean) = run("\"Später ansehen\"") { Account.setWatchLater(videoId, add) }

    /** Abos aus dem Konto in die lokale Liste übernehmen. Gibt die Zahl neuer Abos zurück. */
    suspend fun importSubscriptions(): Int {
        val subs = Account.subscriptions()
        val added = Library.addSubscriptions(subs)
        // Kanalbilder aus dem Konto nachtragen
        subs.forEach { s -> s.avatar?.let { Library.setSubscriptionAvatar(s.url, it) } }
        return added
    }

    fun importSubscriptionsInBackground() {
        if (!Account.loggedIn) return
        scope.launch {
            runCatching { importSubscriptions() }
                .onSuccess { if (it > 0) SubscriptionFeed.refresh(force = true) }
                .onFailure { Log.w(TAG, "Abos aus dem Konto nicht geladen", it) }
        }
    }
}
