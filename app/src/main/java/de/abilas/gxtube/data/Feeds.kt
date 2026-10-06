package de.abilas.gxtube.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import java.io.File

/** Neueste Videos aller abonnierten Kanäle (RSS, parallel geladen, auf dem Handy zwischengespeichert). */
object SubscriptionFeed {
    private const val TAG = "SubscriptionFeed"
    private const val MAX_AGE_MS = 15 * 60 * 1000L

    @Serializable
    private data class Cache(val updatedAt: Long = 0, val items: List<VideoItem> = emptyList())

    data class State(
        val items: List<VideoItem> = emptyList(),
        val loading: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val failed: Int = 0,
        val updatedAt: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val mutex = Mutex()
    private var cacheFile: File? = null

    fun init(context: Context) {
        val f = File(context.cacheDir, "subscription_feed.json")
        cacheFile = f
        if (f.exists()) {
            runCatching { Library.json.decodeFromString<Cache>(f.readText()) }
                .onSuccess { _state.value = State(items = it.items, updatedAt = it.updatedAt) }
        }
    }

    suspend fun refresh(force: Boolean = false) {
        if (!mutex.tryLock()) return
        try {
            val current = _state.value
            val subs = Library.data.value.subscriptions
            if (subs.isEmpty()) {
                _state.value = State()
                return
            }
            if (!force && current.items.isNotEmpty() &&
                System.currentTimeMillis() - current.updatedAt < MAX_AGE_MS
            ) return

            _state.update { it.copy(loading = true, done = 0, total = subs.size, failed = 0) }
            val semaphore = Semaphore(6)
            val results = coroutineScope {
                subs.map { sub ->
                    async {
                        semaphore.withPermit {
                            val r = runCatching { YouTubeRepo.feed(sub.url) }
                                .onFailure { Log.w(TAG, "Feed von ${sub.name} fehlgeschlagen: $it") }
                                .getOrNull()
                                ?.map { v ->
                                    v.copy(
                                        channelName = v.channelName.ifBlank { sub.name },
                                        channelUrl = v.channelUrl ?: sub.url,
                                        channelAvatar = v.channelAvatar ?: sub.avatar,
                                    )
                                }
                            _state.update { s -> s.copy(done = s.done + 1, failed = s.failed + if (r == null) 1 else 0) }
                            r
                        }
                    }
                }.awaitAll()
            }
            val items = results.filterNotNull().flatten()
                .distinctBy { it.id }
                .sortedByDescending { it.uploadedAt ?: 0L }
                .take(800)
            val now = System.currentTimeMillis()
            _state.update { it.copy(items = items, loading = false, updatedAt = now) }
            cacheFile?.let { f ->
                runCatching { f.writeText(Library.json.encodeToString(Cache.serializer(), Cache(now, items))) }
            }
        } finally {
            _state.update { it.copy(loading = false) }
            mutex.unlock()
        }
    }

    /** Fehlende Kanalbilder (z. B. nach dem Takeout-Import) im Hintergrund nachladen. */
    suspend fun fillMissingAvatars(limit: Int = 40) {
        val missing = Library.data.value.subscriptions.filter { it.avatar == null }.take(limit)
        if (missing.isEmpty()) return
        val semaphore = Semaphore(4)
        coroutineScope {
            missing.map { sub ->
                async {
                    semaphore.withPermit {
                        runCatching { YouTubeRepo.channelAvatar(sub.url) }.getOrNull()
                            ?.let { Library.setSubscriptionAvatar(sub.url, it) }
                    }
                }
            }.awaitAll()
        }
    }
}

/**
 * Startseite "Alle": Mischung aus Videos ähnlich zu dem, was du geschaut hast,
 * neuen Videos deiner Abos und den aktuellen Trends (Gaming, Musik).
 */
object HomeFeed {

    suspend fun mixed(): Paged<VideoItem> = coroutineScope {
        val lib = Library.data.value
        val watched = lib.history.map { it.video.id }.toSet()
        val skip = watched + lib.hidden

        val gaming = async { runCatching { YouTubeRepo.kiosk(Kiosk.GAMING) }.getOrNull() }
        val music = async { runCatching { YouTubeRepo.kiosk(Kiosk.MUSIC) }.getOrNull() }

        val weekAgo = System.currentTimeMillis() - 7L * 86_400_000
        val fromSubs = SubscriptionFeed.state.value.items
            .filter { it.id !in skip && (it.uploadedAt ?: 0L) > weekAgo }
            .take(40)
            .shuffled()
        val related = lib.recommendations
            .filter { it.id !in skip && !it.isShort }
            .take(150)
            .shuffled()
            .take(60)

        val g = gaming.await()
        val m = music.await()
        val trending = interleave(listOf(g?.items.orEmpty(), m?.items.orEmpty()))
            .filter { it.id !in skip }

        val items = interleave(listOf(related, fromSubs, trending), weights = listOf(2, 1, 1))
            .filterNot { it.isShort }
            .distinctBy { it.id }

        Paged(items, moreTrending(g, m, skip + items.map { it.id }))
    }

    private fun moreTrending(a: Paged<VideoItem>?, b: Paged<VideoItem>?, skip: Set<String>): (suspend () -> Paged<VideoItem>)? {
        val la = a?.loadMore
        val lb = b?.loadMore
        if (la == null && lb == null) return null
        return suspend {
            coroutineScope {
                val na = async { la?.let { runCatching { it() }.getOrNull() } }
                val nb = async { lb?.let { runCatching { it() }.getOrNull() } }
                val pa = na.await()
                val pb = nb.await()
                val items = interleave(listOf(pa?.items.orEmpty(), pb?.items.orEmpty()))
                    .filter { it.id !in skip && !it.isShort }
                    .distinctBy { it.id }
                Paged(items, moreTrending(pa, pb, skip + items.map { it.id }))
            }
        }
    }

    fun <T> interleave(lists: List<List<T>>, weights: List<Int> = lists.map { 1 }): List<T> {
        val iters = lists.map { it.iterator() }
        val out = ArrayList<T>(lists.sumOf { it.size })
        var any = true
        while (any) {
            any = false
            iters.forEachIndexed { i, it ->
                repeat(weights.getOrElse(i) { 1 }) { _ ->
                    if (it.hasNext()) {
                        out += it.next()
                        any = true
                    }
                }
            }
        }
        return out
    }
}

/** Shorts aus deinen Abos, ähnlichen Shorts und Shorts-Suchen. */
object ShortsFeed {
    private val queries = listOf(
        "#shorts", "#shorts deutsch", "#shorts lustig", "#shorts gaming", "#shorts fußball",
        "#shorts tiere", "#shorts musik", "#shorts essen", "#shorts lifehacks", "#shorts satisfying",
        "#shorts comedy", "#shorts autos",
    )

    private val _items = MutableStateFlow<List<VideoItem>>(emptyList())
    val items: StateFlow<List<VideoItem>> = _items.asStateFlow()
    private val mutex = Mutex()

    suspend fun ensureLoaded() {
        if (_items.value.isEmpty()) loadMore()
    }

    suspend fun loadMore() = mutex.withLock {
        val known = _items.value.map { it.id }.toSet()
        val batch = batch(known)
        _items.update { (it + batch).distinctBy { v -> v.id } }
    }

    suspend fun refresh() = mutex.withLock {
        _items.value = batch(emptySet())
    }

    private suspend fun batch(exclude: Set<String>): List<VideoItem> = coroutineScope {
        val lib = Library.data.value
        val skip = exclude + lib.hidden
        val subs = lib.subscriptions.shuffled().take(3)
        val fromSubs = subs.map { sub ->
            async { runCatching { YouTubeRepo.channelShorts(sub.url).take(6) }.getOrDefault(emptyList()) }
        }
        val fromSearch = queries.shuffled().take(2).map { q ->
            async {
                runCatching {
                    YouTubeRepo.search(q, SearchFilter.VIDEOS).second.items
                        .mapNotNull { (it as? ListEntry.Video)?.video }
                        .filter { it.isShort || it.durationSec in 1..180 }
                        .map { it.copy(isShort = true) }
                }.getOrDefault(emptyList())
            }
        }
        val related = lib.recommendations.filter { it.isShort }.shuffled().take(10)
        (related + (fromSubs + fromSearch).awaitAll().flatten())
            .filter { it.id !in skip && !it.isLive }
            .distinctBy { it.id }
            .shuffled()
    }
}
