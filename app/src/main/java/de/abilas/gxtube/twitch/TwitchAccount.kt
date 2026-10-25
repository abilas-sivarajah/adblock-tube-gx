package de.abilas.gxtube.twitch

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import de.abilas.gxtube.data.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class TwitchUser(val id: String? = null, val login: String, val name: String, val avatar: String? = null)

@Serializable
data class TwitchAccountData(
    val tokens: TwitchApi.Tokens,
    val user: TwitchUser,
    val expired: Boolean = false,
    val since: Long = System.currentTimeMillis(),
)

/**
 * Twitch-Konto (freiwillig): Anmeldung per Code wie die Twitch-App auf dem Fernseher. Damit geht
 * Chat schreiben, Folgen im Konto und Streams nur für Abonnenten. Bleibt nur auf dem Handy.
 */
object TwitchAccount {
    private const val TAG = "TwitchAccount"
    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = Mutex()

    private val _state = MutableStateFlow<TwitchAccountData?>(null)
    val state: StateFlow<TwitchAccountData?> = _state.asStateFlow()
    val loggedIn: Boolean get() = _state.value?.expired == false

    fun init(context: Context) {
        val f = File(context.filesDir, "twitch-account.json")
        file = f
        if (f.exists()) {
            runCatching { Library.json.decodeFromString(TwitchAccountData.serializer(), f.readText()) }
                .onSuccess { _state.value = it }
        }
        TwitchApi.auth = { _state.value?.takeIf { !it.expired }?.let { it.tokens.accessToken to it.tokens.clientId } }
        TwitchApi.onUnauthorized = { markExpired() }
        // Twitch verlangt, das Token beim Start zu prüfen; danach die gefolgten Kanäle abgleichen
        scope.launch {
            check()
            TwitchFollows.syncFromAccount()
        }
    }

    private fun save(data: TwitchAccountData?) {
        _state.value = data
        val f = file ?: return
        runCatching {
            if (data == null) f.delete() else f.writeText(Library.json.encodeToString(TwitchAccountData.serializer(), data))
        }.onFailure { Log.w(TAG, "Twitch-Konto nicht gespeichert", it) }
    }

    /** Anmeldung per Code ist durch. */
    suspend fun login(tokens: TwitchApi.Tokens) {
        val v = TwitchApi.validate(tokens.accessToken) ?: throw TwitchException("Twitch hat die Anmeldung nicht bestätigt")
        val c = runCatching { TwitchApi.users(listOf(v.login)).firstOrNull() }.getOrNull()
        save(TwitchAccountData(tokens.copy(scopes = tokens.scopes.ifEmpty { v.scopes }), TwitchUser(v.userId ?: c?.userId, v.login, c?.name ?: v.login, c?.avatar)))
        TwitchFollows.syncFromAccount()
    }

    fun markExpired() {
        val s = _state.value ?: return
        if (!s.expired) save(s.copy(expired = true))
    }

    /** Für den Chat: (Login, Token), wenn schreiben erlaubt ist. */
    fun chatAuth(): Pair<String, String>? {
        val s = _state.value ?: return null
        if (s.expired || "chat:edit" !in s.tokens.scopes) return null
        return s.user.login to s.tokens.accessToken
    }

    /** Läuft das Token bald ab? Dann erneuern. */
    suspend fun ensureFresh() = refreshing.withLock {
        val s = _state.value ?: return@withLock
        if (s.expired) return@withLock
        val exp = s.tokens.expiresAt ?: return@withLock
        if (exp - System.currentTimeMillis() > 10 * 60_000) return@withLock
        runCatching { TwitchApi.refresh(s.tokens) }
            .onSuccess { save(s.copy(tokens = it)) }
            .onFailure { markExpired() }
    }

    /** Ist die Anmeldung noch gültig? (beim Start) */
    suspend fun check() {
        val s = _state.value ?: return
        if (s.expired) return
        runCatching {
            val v = TwitchApi.validate(s.tokens.accessToken)
            if (v == null) save(s.copy(tokens = TwitchApi.refresh(s.tokens)))
        }.onFailure { if ((it as? TwitchException)?.signIn == true) markExpired() }
    }

    suspend fun logout() {
        val s = _state.value
        save(null)
        // Anmeldung auf twitch.tv in der App vergessen (die von Google bleibt)
        runCatching {
            val cm = CookieManager.getInstance()
            for (host in listOf("https://www.twitch.tv", "https://id.twitch.tv", "https://passport.twitch.tv")) {
                cm.getCookie(host)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                    cm.setCookie(host, "$name=; Max-Age=0; Domain=.twitch.tv; Path=/")
                }
            }
            cm.flush()
        }
        s?.let { TwitchApi.revoke(it.tokens) }
    }
}
