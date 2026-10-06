package de.abilas.gxtube.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.abilas.gxtube.data.Paged
import de.abilas.gxtube.data.YouTubeRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Liste mit Laden, Neu-Laden (Pull-to-Refresh) und Nachladen weiterer Seiten. */
class PagedList<T>(private val scope: CoroutineScope, private val keyOf: (T) -> String) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set

    private var source: (suspend () -> Paged<T>)? = null
    private var next: (suspend () -> Paged<T>)? = null
    private var job: Job? = null

    fun start(src: suspend () -> Paged<T>) {
        source = src
        items = emptyList()
        reload(pull = false)
    }

    fun reload(pull: Boolean = true) {
        val src = source ?: return
        job?.cancel()
        job = scope.launch {
            refreshing = pull
            loading = true
            error = null
            try {
                val page = src()
                items = page.items.distinctBy(keyOf)
                next = page.loadMore
                hasMore = next != null
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                error = YouTubeRepo.errorText(t)
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    fun loadMore() {
        val n = next ?: return
        if (loading) return
        job = scope.launch {
            loading = true
            try {
                val page = n()
                val known = items.map(keyOf).toHashSet()
                items = items + page.items.filter { known.add(keyOf(it)) }
                next = page.loadMore
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                next = null
            } finally {
                hasMore = next != null
                loading = false
            }
        }
    }

    fun remove(predicate: (T) -> Boolean) {
        items = items.filterNot(predicate)
    }
}
