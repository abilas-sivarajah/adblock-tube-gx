package de.abilas.gxtube.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Subscription(
    val url: String,
    val name: String,
    val avatar: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class HistoryEntry(
    val video: VideoItem,
    val watchedAt: Long,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

object Quality {
    val choices = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
    fun label(h: Int) = "${h}p"
}

@Serializable
data class Settings(
    val qualityWifi: Int = 1080,
    val qualityMobile: Int = 720,
    val backgroundPlay: Boolean = true,
    val pipOnLeave: Boolean = true,
    val autoplay: Boolean = true,
    val sponsorBlock: Boolean = true,
    val sponsorCategories: Set<String> = setOf("sponsor", "selfpromo", "interaction"),
    val country: String = "DE",
    val language: String = "de",
    /** "system", "dark" oder "light" */
    val theme: String = "system",
    val historyEnabled: Boolean = true,
    val searchHistoryEnabled: Boolean = true,
    val shortsOnHome: Boolean = true,
    /** Aus = Shorts überall ausblenden (Tab, Startseite, Suche, Abos, Kanäle …) */
    val shortsEnabled: Boolean = true,
    /** Beim Öffnen der App auf GitHub nach einer neuen Version suchen */
    val autoUpdateCheck: Boolean = true,
)

/** Shorts herausfiltern, wenn sie in den Einstellungen ausgeschaltet sind. */
fun List<VideoItem>.filterShorts(enabled: Boolean = Library.settings.shortsEnabled): List<VideoItem> =
    if (enabled) this else filterNot { it.looksLikeShort }

@Serializable
data class LibraryData(
    val subscriptions: List<Subscription> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val watchLater: List<VideoItem> = emptyList(),
    val liked: List<VideoItem> = emptyList(),
    val disliked: Set<String> = emptySet(),
    val searchHistory: List<String> = emptyList(),
    /** Ähnliche Videos aus dem, was du geschaut hast – daraus entsteht die Startseite. */
    val recommendations: List<VideoItem> = emptyList(),
    /** "Kein Interesse" */
    val hidden: Set<String> = emptySet(),
    val settings: Settings = Settings(),
)

/**
 * Alles, was sonst im Google-Konto liegt, bleibt hier lokal auf dem Handy
 * (files/library.json): Abos, Verlauf, "Später ansehen", "Mag ich", Einstellungen.
 */
object Library {
    private const val TAG = "Library"
    private const val MAX_HISTORY = 1000
    private const val MAX_RECOMMENDATIONS = 400
    private const val MAX_SEARCH_HISTORY = 50

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)
    private val _data = MutableStateFlow(LibraryData())
    val data: StateFlow<LibraryData> = _data.asStateFlow()
    val settings: Settings get() = _data.value.settings

    fun init(context: Context) {
        file = File(context.filesDir, "library.json")
        if (file.exists()) {
            runCatching { json.decodeFromString<LibraryData>(file.readText()) }
                .onSuccess { _data.value = it }
                .onFailure { Log.e(TAG, "Bibliothek konnte nicht gelesen werden", it) }
        }
        scope.launch {
            for (signal in saveSignal) {
                delay(400)
                writeNow()
            }
        }
    }

    private fun writeNow() {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(LibraryData.serializer(), _data.value))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.e(TAG, "Bibliothek konnte nicht gespeichert werden", it) }
    }

    fun update(transform: (LibraryData) -> LibraryData) {
        _data.update(transform)
        saveSignal.trySend(Unit)
    }

    fun updateSettings(transform: (Settings) -> Settings) = update { it.copy(settings = transform(it.settings)) }

    // ---------------------------------------------------------------- Abos

    fun isSubscribed(data: LibraryData, channelUrl: String?): Boolean {
        val key = channelKey(channelUrl)
        return key.isNotEmpty() && data.subscriptions.any { channelKey(it.url) == key }
    }

    fun subscribe(url: String, name: String, avatar: String?) = update { d ->
        if (isSubscribed(d, url)) d
        else d.copy(subscriptions = listOf(Subscription(url, name, avatar)) + d.subscriptions)
    }

    fun unsubscribe(url: String) = update { d ->
        val key = channelKey(url)
        d.copy(subscriptions = d.subscriptions.filterNot { channelKey(it.url) == key })
    }

    fun toggleSubscription(url: String, name: String, avatar: String?) {
        val subscribed = !isSubscribed(_data.value, url)
        if (subscribed) subscribe(url, name, avatar) else unsubscribe(url)
        Sync.subscription(url, subscribed)
    }

    fun addSubscriptions(items: List<Subscription>): Int {
        var added = 0
        update { d ->
            val known = d.subscriptions.map { channelKey(it.url) }.toMutableSet()
            val fresh = items.filter { known.add(channelKey(it.url)) }
            added = fresh.size
            d.copy(subscriptions = d.subscriptions + fresh)
        }
        return added
    }

    fun setSubscriptionAvatar(url: String, avatar: String) = update { d ->
        val key = channelKey(url)
        d.copy(subscriptions = d.subscriptions.map { if (channelKey(it.url) == key) it.copy(avatar = avatar) else it })
    }

    // ---------------------------------------------------------------- Verlauf

    fun recordWatch(video: VideoItem, positionMs: Long, durationMs: Long) {
        if (!settings.historyEnabled || video.id.isBlank()) return
        update { d ->
            val rest = d.history.filterNot { it.video.id == video.id }
            val entry = HistoryEntry(video, System.currentTimeMillis(), positionMs, durationMs)
            d.copy(history = (listOf(entry) + rest).take(MAX_HISTORY))
        }
    }

    fun updateProgress(videoId: String, positionMs: Long, durationMs: Long) {
        if (!settings.historyEnabled) return
        update { d ->
            d.copy(history = d.history.map {
                if (it.video.id == videoId) it.copy(positionMs = positionMs, durationMs = durationMs) else it
            })
        }
    }

    fun historyEntry(videoId: String): HistoryEntry? = _data.value.history.firstOrNull { it.video.id == videoId }

    fun removeFromHistory(videoId: String) = update { d -> d.copy(history = d.history.filterNot { it.video.id == videoId }) }

    fun clearHistory() = update { it.copy(history = emptyList()) }

    // ---------------------------------------------------------------- Listen

    fun isInWatchLater(data: LibraryData, id: String) = data.watchLater.any { it.id == id }

    fun toggleWatchLater(video: VideoItem) {
        val add = !isInWatchLater(_data.value, video.id)
        update { d ->
            if (!add) d.copy(watchLater = d.watchLater.filterNot { it.id == video.id })
            else d.copy(watchLater = listOf(video) + d.watchLater)
        }
        Sync.watchLater(video.id, add)
    }

    fun isLiked(data: LibraryData, id: String) = data.liked.any { it.id == id }

    fun toggleLike(video: VideoItem) {
        val like = !isLiked(_data.value, video.id)
        update { d ->
            if (!like) d.copy(liked = d.liked.filterNot { it.id == video.id })
            else d.copy(liked = listOf(video) + d.liked, disliked = d.disliked - video.id)
        }
        Sync.rating(video.id, if (like) Account.Rating.LIKE else Account.Rating.NONE)
    }

    fun toggleDislike(video: VideoItem) {
        val dislike = video.id !in _data.value.disliked
        update { d ->
            if (!dislike) d.copy(disliked = d.disliked - video.id)
            else d.copy(disliked = d.disliked + video.id, liked = d.liked.filterNot { it.id == video.id })
        }
        Sync.rating(video.id, if (dislike) Account.Rating.DISLIKE else Account.Rating.NONE)
    }

    fun hide(video: VideoItem) = update { d ->
        d.copy(
            hidden = d.hidden + video.id,
            recommendations = d.recommendations.filterNot { it.id == video.id },
        )
    }

    // ---------------------------------------------------------------- Suche

    fun addSearch(query: String) {
        if (!settings.searchHistoryEnabled || query.isBlank()) return
        update { d ->
            d.copy(searchHistory = (listOf(query.trim()) + d.searchHistory.filterNot { it.equals(query.trim(), true) })
                .take(MAX_SEARCH_HISTORY))
        }
    }

    fun removeSearch(query: String) = update { d -> d.copy(searchHistory = d.searchHistory - query) }

    fun clearSearchHistory() = update { it.copy(searchHistory = emptyList()) }

    // ---------------------------------------------------------------- Empfehlungen

    fun addRecommendations(items: List<VideoItem>) {
        if (items.isEmpty()) return
        update { d ->
            val ids = items.map { it.id }.toSet()
            d.copy(recommendations = (items + d.recommendations.filterNot { it.id in ids }).take(MAX_RECOMMENDATIONS))
        }
    }
}
