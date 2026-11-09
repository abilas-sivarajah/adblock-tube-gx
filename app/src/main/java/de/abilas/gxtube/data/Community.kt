package de.abilas.gxtube.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

private fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * DeArrow (dearrow.ajay.app): Titel und Vorschaubilder ohne Clickbait, von der Community gesammelt.
 * Abschaltbar, standardmäßig aus. Die Titel kommen über den Hash-Präfix der Video-ID (die ID bleibt
 * privat); eigene Vorschaubilder lädt der Bild-Server von DeArrow (der sieht dabei die Video-ID).
 * Karten melden ihre Video-ID mit [request], abgefragt wird gebündelt.
 */
object DeArrow {
    data class Branding(val title: String?, val thumbnail: String?)

    private val _branding = MutableStateFlow<Map<String, Branding?>>(emptyMap())
    /** Video-ID → Ergebnis (null: DeArrow hat nichts für das Video) */
    val branding: StateFlow<Map<String, Branding?>> = _branding.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = LinkedHashSet<String>()
    private var flushJob: Job? = null

    fun request(id: String) {
        if (!Library.settings.dearrow || !VIDEO_ID.matches(id)) return
        synchronized(this) {
            if (id in _branding.value || !pending.add(id)) return
            if (flushJob?.isActive != true) {
                flushJob = scope.launch {
                    delay(80)
                    flush()
                }
            }
        }
    }

    /** Nach dem Ausschalten: Gemerktes vergessen. */
    fun clear() {
        _branding.value = emptyMap()
    }

    private suspend fun flush() {
        val ids = synchronized(this) { pending.toList().also { pending.clear() } }
        if (ids.isEmpty()) return
        val byPrefix = ids.groupBy { sha256(it).take(4) }
        val semaphore = Semaphore(6)
        val results = coroutineScope {
            byPrefix.map { (prefix, list) ->
                async {
                    semaphore.withPermit {
                        // Server nicht erreichbar: diesmal ohne, beim nächsten Mal wieder fragen
                        val data = runCatching { fetch(prefix) }.getOrNull() ?: return@withPermit emptyList()
                        list.map { it to parse(data[it], it) }
                    }
                }
            }.awaitAll().flatten()
        }
        if (results.isEmpty()) return
        _branding.update { old ->
            val next = old + results
            if (next.size > 3000) next.entries.drop(next.size - 3000).associate { it.key to it.value } else next
        }
    }

    private fun fetch(prefix: String): JsonObject {
        val request = okhttp3.Request.Builder()
            .url("https://sponsor.ajay.app/api/branding/$prefix")
            .header("User-Agent", "GXTube")
            .build()
        Http.client.newCall(request).execute().use { r ->
            if (r.code == 404) return JsonObject(emptyMap())
            if (!r.isSuccessful) throw java.io.IOException("DeArrow ${r.code}")
            return Json.parseToJsonElement(r.body.string()) as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    /** Ein führendes ">" heißt bei DeArrow nur "nicht automatisch formatieren". */
    fun cleanTitle(title: String): String = title.replace(Regex("^>\\s*"), "").replace(Regex("\\s+"), " ").trim()

    /** Bester Titel und bestes Vorschaubild (nur von der Community, nicht das Original). */
    internal fun parse(entry: JsonElement?, videoId: String): Branding? {
        val obj = entry as? JsonObject ?: return null
        fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
        fun JsonObject.votes() = (this["votes"] as? JsonPrimitive)?.longOrNull ?: -1
        fun JsonObject.trusted() = !bool("original") && (bool("locked") || votes() >= 0)
        val title = (obj["titles"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.trusted() && !(it["title"] as? JsonPrimitive)?.content.isNullOrBlank() }
            ?.let { cleanTitle((it["title"] as JsonPrimitive).content).take(300) }
            ?.takeIf { it.isNotEmpty() }
        val time = (obj["thumbnails"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.trusted() && (it["timestamp"] as? JsonPrimitive)?.doubleOrNull != null }
            ?.let { (it["timestamp"] as JsonPrimitive).doubleOrNull }
        val thumb = time?.let { thumbnailUrl(videoId, it) }
        return if (title != null || thumb != null) Branding(title, thumb) else null
    }

    fun thumbnailUrl(videoId: String, time: Double): String =
        "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=${URLEncoder.encode(videoId, "UTF-8")}&time=$time"
}

/** Return YouTube Dislike: die Zahl der Daumen nach unten (Schätzung). Abschaltbar, standardmäßig aus. */
object ReturnDislike {
    data class Votes(val likes: Long, val dislikes: Long)

    private val cache = ConcurrentHashMap<String, Pair<Long, Votes>>()

    suspend fun votes(videoId: String): Votes? = withContext(Dispatchers.IO) {
        if (!Library.settings.returnDislike || !VIDEO_ID.matches(videoId)) return@withContext null
        cache[videoId]?.let { (at, v) -> if (System.currentTimeMillis() - at < 10 * 60_000) return@withContext v }
        runCatching {
            val request = okhttp3.Request.Builder()
                .url("https://returnyoutubedislikeapi.com/votes?videoId=$videoId")
                .header("User-Agent", "GXTube")
                .build()
            Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val obj = Json.parseToJsonElement(r.body.string()) as? JsonObject ?: return@use null
                val likes = (obj["likes"] as? JsonPrimitive)?.longOrNull ?: -1
                val dislikes = (obj["dislikes"] as? JsonPrimitive)?.longOrNull ?: return@use null
                Votes(likes, dislikes).also { cache[videoId] = System.currentTimeMillis() to it }
            }
        }.getOrNull()
    }
}
