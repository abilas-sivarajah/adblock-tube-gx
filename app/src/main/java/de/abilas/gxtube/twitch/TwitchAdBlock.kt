package de.abilas.gxtube.twitch

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Twitch-Werbeblocker für ExoPlayer – die Kotlin-Fassung von `twitch_worker.js` aus Adblock GX
 * (dort für den Web-Player von twitch.tv, in GX Tube Desktop vor Shaka). Techniken nach TTV-AB von
 * GosuDRM (https://github.com/GosuDRM/TTV-AB, MIT).
 *
 * Twitch schneidet Werbung in die Live-Playlist. Eine zweite Sitzung desselben Streams (anderer
 * "playerType") ist im selben Moment oft werbefrei:
 *  - Während der Werbung bekommt der Player die Playlist einer werbefreien Ersatz-Sitzung: sofort die
 *    360p-Brücke ("autoplay"), volle Qualität, sobald eine Sitzung zweimal (1,5 s auseinander) frei war.
 *  - Ist die eigene Sitzung drei Aktualisierungen lang frei, geht es dorthin zurück.
 *  - Alles läuft auf einer Zeitleiste: Segmentnummern steigen nur, ein Quellwechsel ist eine
 *    Diskontinuität, ausgerichtet nach EXT-X-PROGRAM-DATE-TIME (nichts doppelt, nichts übersprungen).
 *  - Ist nichts werbefrei, ersetzen schwarze, stumme 1,024-s-Stücke ("hold") die Werbung.
 * Werbung wird nie als "gesehen" gemeldet.
 *
 * Die Playlists holt [TwitchDataSource] hierüber; die Videostücke lädt ExoPlayer selbst.
 */
class TwitchAdBlock(
    client: OkHttpClient,
    private val holdTs: () -> ByteArray,
    private val events: (Event) -> Unit = {},
) {
    sealed interface Event {
        data object AdStart : Event
        data object AdEnd : Event
        data class Masked(val hold: Boolean) : Event
        data object Unmasked : Event
        data class Bridge(val on: Boolean) : Event
        data class BackupUsed(val type: String) : Event
    }

    private object T {
        const val FETCH = 3500L
        const val SEARCH = 1500L
        const val PROOF = 1500L
        const val AD_COOLDOWN = 15000L
        const val ERROR_COOLDOWN = 5000L
        const val FORBIDDEN = 300000L
        const val STALLED = 10000L
        const val HQ_DWELL = 8000L
        const val NATIVE_CLEAN = 3
    }

    private val http = client.newBuilder().callTimeout(T.FETCH, TimeUnit.MILLISECONDS).build()
    private val scanner = Executors.newCachedThreadPool { r -> Thread(r, "twitch-adblock").apply { isDaemon = true } }

    private val contexts = ConcurrentHashMap<String, Ctx>()
    private val variantIndex = ConcurrentHashMap<String, Pair<Ctx, Variant>>()
    private val tokens = ConcurrentHashMap<String, Token>()
    private var sessionSeq = 0
    @Volatile private var fallbackMasked = false

    // ------------------------------------------------------------ Einstieg (aus TwitchDataSource)

    /**
     * Antwort für eine Adresse, die der Werbeblocker selbst beantwortet (Master- und Media-Playlists,
     * schwarzes Ersatz-Stück) – null: ganz normal laden.
     */
    fun handle(url: String): ByteArray? {
        if (url.startsWith(HOLD_URL)) {
            val n = Regex("[?&]n=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: 1
            return holdSegment(n)
        }
        val usher = usherContext(url)
        val media = usher == null && (url.contains(".ttvnw.net/v1/playlist/") || lookupVariant(url) != null)
        if (usher == null && !media) return null
        val text = fetchText(url)
        val out = try {
            if (usher != null) {
                registerMaster(usher, url, text)
                withoutAudioOnly(text)
            } else processMediaPlaylist(url, text)
        } catch (e: Exception) {
            Log.w(TAG, "Playlist nicht umgeschrieben", e)
            text
        }
        return out.toByteArray()
    }

    /** Die Variante "nur Ton" weglassen – sonst schaltet die Automatik bei schlechtem Netz aufs schwarze Bild. */
    private fun withoutAudioOnly(master: String): String {
        if (!master.contains("audio_only")) return master
        val lines = master.split('\n')
        val out = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF:") && l.contains("audio_only")) {
                i += 2
                continue
            }
            if (l.startsWith("#EXT-X-MEDIA:") && l.contains("audio_only")) {
                i++
                continue
            }
            out += l
            i++
        }
        return out.joinToString("\n")
    }

    /** Qualitätsstufen aus der zuletzt gesehenen Master-Playlist (für das Qualitäts-Menü). */
    fun variants(key: String): List<Variant> = contexts[key]?.variants.orEmpty()

    /** Der Player hängt: die gerade benutzte Ersatz-Sitzung beiseitelegen. */
    fun onStalled() {
        contexts.values.forEach { ctx ->
            val type = typeOf(ctx.tl.source) ?: return@forEach
            val s = ctx.sessions[type] ?: return@forEach
            s.stalledAt = now()
            s.trusted = false
        }
    }

    // ------------------------------------------------------------ Netz

    private fun fetchText(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (body != null) b.post(body.toRequestBody("text/plain;charset=UTF-8".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code)
            return r.body.string()
        }
    }

    class HttpError(val code: Int) : IOException("HTTP $code")

    // ------------------------------------------------------------ Playlists lesen

    data class Variant(
        val uri: String,
        val raw: String,
        val res: String,
        val fps: Double,
        val height: Int,
        val family: String,
        val name: String,
    )

    class Seg(
        val seq: Long,
        val ds: Long,
        val disc: Boolean,
        val map: String?,
        val key: String?,
        val tags: List<String>,
        val uri: String,
        val dur: Double,
        val pdt: Long?,
        val ad: Boolean,
    )

    class Media(val header: MutableList<String>, val segs: MutableList<Seg>, val tail: MutableList<String>, var ended: Boolean, val text: String)

    class Entry(
        val out: Long,
        val ds: Long,
        val disc: Boolean,
        val map: String?,
        val key: String?,
        val seq: Long,
        val tags: List<String>,
        val uri: String,
        val dur: Double,
        val pdt: Long?,
        val ad: Boolean,
        val epoch: Int,
    )

    private class Timeline(var source: String) {
        var switched = false
        var epoch = 0
        var offset = 0L
        var dsShift = 0L
        var boundary = Long.MIN_VALUE
        var lastOut = -1L
        var lastDs = 0L
        var lastEnd = 0L
        var lastHoldAt = 0L
        var holdSeq = 0L
        val hist = HashMap<String, List<Entry>>()
    }

    private class Session(
        val id: Int,
        val type: String,
        var variants: List<Variant>?,
        var errors: Int,
        var last: String?,
    ) {
        var retryAt = 0L
        var cleanLooks = 0
        var firstClean = 0L
        var lastLook = 0L
        @Volatile var trusted = false
        @Volatile var stalledAt = 0L
    }

    private class Ctx(val key: String, val login: String?, val vod: String?) {
        val sessions = ConcurrentHashMap<String, Session>()
        val lock = Any()
        var gen = 0
        var tl = Timeline("native:0")
        var inAd = false
        var masked = false
        @Volatile var bridgeSince = 0L
        @Volatile var scan: Future<Backup?>? = null
        var cleanStreak = 0
        var natLastSeq: Long? = null
        var fmp4: Boolean? = null
        var wait: Pair<String, Long>? = null
        var usher = ""
        var nativeId = "native:0"
        var variants: List<Variant> = emptyList()
    }

    private class Backup(
        val type: String,
        val id: String,
        val session: Session,
        val parsed: Media,
        val text: String,
        val ads: Boolean,
        val lq: Boolean,
        val res: String,
        val want: Variant,
        val proven: Boolean,
    )

    private class Token(val value: String, val signature: String, val until: Long)

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------ Master-Playlist

    private class Usher(val key: String, val login: String?, val vod: String?)

    private fun usherContext(url: String): Usher? {
        if (!url.contains("usher.ttvnw.net/")) return null
        Regex("/channel/hls/([^/.?]+)\\.m3u8").find(url)?.let {
            val login = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8").lowercase()
            return Usher("live:$login", login, null)
        }
        return Regex("/vod/(?:v2/)?(\\d+)\\.m3u8").find(url)?.let { Usher("vod:${it.groupValues[1]}", null, it.groupValues[1]) }
    }

    private fun registerMaster(u: Usher, url: String, text: String) {
        val ctx = contexts.getOrPut(u.key) { Ctx(u.key, u.login, u.vod) }
        synchronized(ctx.lock) {
            ctx.usher = url
            ctx.gen++
            ctx.nativeId = "native:${ctx.gen}"
            ctx.natLastSeq = null
            // Neue eigene Sitzung des Players: neu nummeriert – war er schon auf unserer Zeitleiste, läuft sie weiter
            if (!ctx.tl.switched) ctx.tl = Timeline(ctx.nativeId)
            val list = parseVariants(text, url)
            ctx.variants = list
            list.forEach { v ->
                variantIndex[v.uri] = ctx to v
                if (v.raw != v.uri) variantIndex[v.raw] = ctx to v
            }
        }
        while (variantIndex.size > 400) variantIndex.keys().nextElement()?.let { variantIndex.remove(it) }
    }

    private fun lookupVariant(url: String): Pair<Ctx, Variant>? {
        variantIndex[url]?.let { return it }
        if (!url.contains("_HLS_")) return null
        return variantIndex[url.replace(Regex("([?&])_HLS_[a-z]+=[^&]*&?", RegexOption.IGNORE_CASE), "$1").trimEnd('?', '&')]
    }

    private fun processMediaPlaylist(url: String, text: String): String {
        val v = lookupVariant(url)
        if (v != null) {
            val (ctx, want) = v
            return synchronized(ctx.lock) {
                if (ctx.vod != null) processVod(text) else processLive(ctx, url, want, text)
            }
        }
        // Playlist, die in keiner Master-Playlist stand (sollte nicht vorkommen): Werbung nur abdecken
        if (!hasAds(text)) {
            if (fallbackMasked) events(Event.Unmasked)
            fallbackMasked = false
            return text
        }
        fallbackMasked = true
        events(Event.Masked(hold = false))
        return text
    }

    // ------------------------------------------------------------ Live

    private fun processLive(ctx: Ctx, url: String, want: Variant, text: String): String {
        val tl = ctx.tl
        val nat = parseMedia(text, url)
        if (nat.segs.isEmpty() || nat.ended) return text
        val natAds = hasAds(text)
        trackNative(ctx, nat, natAds)
        val nid = ctx.nativeId

        // Die eigene Sitzung des Players, sobald sie (wieder) werbefrei ist
        if (!natAds && (tl.source == nid || ctx.cleanStreak >= T.NATIVE_CLEAN)) {
            val wasBackup = typeOf(tl.source)
            serve(ctx, url, nid, nat)?.let { out ->
                if (wasBackup != null) Log.i(TAG, "zurück zur eigenen Sitzung (von $wasBackup)")
                setBridge(ctx, false)
                endAd(ctx)
                return out
            }
        }
        if (natAds) startAd(ctx)

        // Die Ersatz-Sitzung, die gerade läuft
        val curType = typeOf(ctx.tl.source)
        val cur = curType?.let { backupPlaylist(ctx, it, want, false) }
        val curOk = cur != null && !cur.ads
        if (!curOk && !natAds) {
            // Ersatz weg oder mit Werbung, eigene Sitzung frei: zurück
            serve(ctx, url, nid, nat, force = true)?.let { out ->
                setBridge(ctx, false)
                endAd(ctx)
                return out
            }
        }

        // Sonst / besser: sofort die 360p-Brücke, volle Qualität, sobald sie sich als frei erwiesen hat
        val lqF: Future<Backup?>? = if (!curOk && curType != LQ_TYPE) scanner.submit<Backup?> { backupPlaylist(ctx, LQ_TYPE, want, true) } else null
        var hq: Backup? = null
        if (!curOk || cur!!.lq) {
            hq = provenHq(ctx, want, curType)
            if (hq == null) {
                val scan = searchHq(ctx, want, curType)
                hq = if (curOk || scan == null) null else within(scan, T.SEARCH)
                if (hq != null && hq.want != want) hq = backupPlaylist(ctx, hq.type, want, true)
                if (hq != null && hq.ads) hq = null
            }
        }
        val lq = lqF?.let { runCatching { it.get(T.FETCH * 2, TimeUnit.MILLISECONDS) }.getOrNull() }
        val picks = ArrayList<Backup>()
        if (curOk && !cur!!.lq) picks += cur
        if (hq != null && hq.proven) picks += hq
        if (curOk) picks += cur!!
        if (lq != null && !lq.ads) picks += lq
        if (hq != null) picks += hq
        for (b in picks) {
            val before = ctx.tl.source
            val out = serve(ctx, url, b.id, b.parsed) ?: continue // noch nichts Neueres als beim Player
            if (before != b.id) {
                Log.i(TAG, "Ersatz-Sitzung ${b.type} (${b.res})")
                events(Event.BackupUsed(b.type))
            }
            b.session.trusted = true
            setBridge(ctx, b.lq)
            unmask(ctx)
            return out
        }

        // Nichts werbefrei
        setBridge(ctx, false)
        if (canHold(ctx, want)) {
            // Neue Live-Stücke aus der eigenen Playlist, sonst schwarze Ersatz-Stücke
            val tl2 = ctx.tl
            val fresh = liveRun(nat.segs).any { s ->
                tl2.lastOut < 0 || if (tl2.source == nid) s.seq + tl2.offset > tl2.lastOut else s.pdt != null && s.pdt >= tl2.lastEnd - 500
            }
            val live = if (fresh) serve(ctx, url, nid, nat, strip = true) else null
            if (live != null) {
                unmask(ctx) // die Werbung ist vorbei, nur ihre Tags stehen noch in Twitchs Fenster
                return live
            }
            return mask(ctx, hold(ctx, url, nat), true)
        }
        // Beim Bisherigen bleiben, die Oberfläche deckt die Werbung ab
        if (cur != null) serve(ctx, url, cur.id, cur.parsed)?.let { return mask(ctx, it, false) }
        return mask(ctx, serve(ctx, url, nid, nat, force = true) ?: text, false)
    }

    /** VODs: Die Playlist ist komplett, Werbestücke fallen einfach weg. */
    private fun processVod(text: String): String {
        if (!hasAds(text)) return text
        val p = parseMedia(text, null)
        val keep = p.segs.filter { !it.ad }
        if (keep.isEmpty() || keep.size == p.segs.size) return text
        val first = p.segs[0].seq
        val entries = keep.mapIndexed { i, s ->
            Entry(first + i, s.ds, s.disc, s.map, s.key, s.seq, s.tags.filterNot { isAdLine(it) }, s.uri, s.dur, s.pdt, false, 0)
        }
        return render(p.header.filterNot { isAdLine(it) }, entries, if (p.ended) listOf("#EXT-X-ENDLIST") else emptyList())
    }

    private fun trackNative(ctx: Ctx, nat: Media, ads: Boolean) {
        val lastSeq = nat.segs.last().seq
        if (ads) ctx.cleanStreak = 0
        else {
            if (ctx.natLastSeq == null || lastSeq > ctx.natLastSeq!!) ctx.cleanStreak++ // nur neue Playlists zählen
            ctx.fmp4 = nat.segs.any { it.map != null }
        }
        ctx.natLastSeq = lastSeq
    }

    private fun canHold(ctx: Ctx, want: Variant) =
        ctx.vod == null && want.family != "hevc" && want.family != "av1" && ctx.fmp4 != true

    private fun startAd(ctx: Ctx) {
        if (ctx.inAd) return
        ctx.inAd = true
        ctx.sessions.values.forEach { if (it.last == "werbung") it.retryAt = 0 } // neue Werbepause: neu fragen
        events(Event.AdStart)
    }

    private fun endAd(ctx: Ctx) {
        unmask(ctx)
        if (!ctx.inAd) return
        ctx.inAd = false
        events(Event.AdEnd)
    }

    private fun mask(ctx: Ctx, out: String, hold: Boolean): String {
        ctx.masked = true
        events(Event.Masked(hold))
        return out
    }

    private fun unmask(ctx: Ctx) {
        if (!ctx.masked) return
        ctx.masked = false
        events(Event.Unmasked)
    }

    private fun setBridge(ctx: Ctx, on: Boolean) {
        if ((ctx.bridgeSince != 0L) == on) return
        ctx.bridgeSince = if (on) now() else 0
        events(Event.Bridge(on))
    }

    private fun typeOf(source: String): String? {
        val i = source.indexOf(':')
        val type = if (i < 0) "" else source.substring(0, i)
        return type.takeIf { it.isNotEmpty() && it != "native" }
    }

    private fun <T> within(f: Future<T>, ms: Long): T? = try {
        f.get(ms, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------ Ersatz-Sitzungen

    private fun gqlHeaders() = mapOf("Client-ID" to WEB_CLIENT, "Device-ID" to deviceId, "X-Device-Id" to deviceId)

    private val deviceId = randomHex(32)

    private fun tokenUntil(value: String): Long = runCatching {
        val exp = kotlinx.serialization.json.Json.parseToJsonElement(value).jsonObject["expires"]?.jsonPrimitive?.content?.toLong()
        if (exp != null) min(exp * 1000 - 60000, now() + 15 * 60000) else null
    }.getOrNull() ?: (now() + 5 * 60000)

    private fun getToken(ctx: Ctx, type: String): Token {
        val key = ctx.key + "|" + type
        tokens[key]?.let { if (it.until > now()) return it }
        val variables = buildJsonObject {
            put("isLive", ctx.vod == null)
            put("login", ctx.login ?: "")
            put("isVod", ctx.vod != null)
            put("vodID", ctx.vod ?: "")
            put("playerType", type)
            put("platform", if (type == LQ_TYPE) "android" else "web")
        }
        val body = buildJsonObject {
            put("operationName", "PlaybackAccessToken_Template")
            put("query", TwitchApi.TOKEN_QUERY)
            put("variables", variables)
        }.toString()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(fetchText(GQL_URL, body, gqlHeaders())).jsonObject
        val data = json["data"] as? JsonObject
        val t = (data?.get("streamPlaybackAccessToken") ?: data?.get("videoPlaybackAccessToken")) as? JsonObject
        val value = t?.get("value")?.jsonPrimitive?.content
        val sig = t?.get("signature")?.jsonPrimitive?.content
        if (value == null || sig == null) throw IOException("kein Token")
        val auth = t["authorization"] as? JsonObject
        if (auth?.get("isForbidden")?.jsonPrimitive?.content == "true") throw IOException("forbidden ${auth["forbiddenReasonCode"]?.jsonPrimitive?.content}")
        return Token(value, sig, tokenUntil(value)).also { tokens[key] = it }
    }

    private fun openSession(ctx: Ctx, type: String, old: Session?): Session {
        val token = getToken(ctx, type)
        // Wie die eigene Anfrage des Players – Codecs, niedrige Latenz usw. passen dann
        val u = ctx.usher.toHttpUrlOrNull()?.newBuilder() ?: throw IOException("keine Usher-Adresse")
        u.setQueryParameter("sig", token.signature)
        u.setQueryParameter("token", token.value)
        u.setQueryParameter("p", Random.nextInt(10_000_000).toString())
        u.setQueryParameter("play_session_id", randomHex(32))
        val href = u.build().toString()
        val variants = parseVariants(fetchText(href), href)
        if (variants.isEmpty()) throw IOException("keine Varianten")
        synchronized(this) { sessionSeq++ }
        return Session(sessionSeq, type, variants, old?.errors ?: 0, old?.last)
    }

    private fun proven(s: Session) = s.type == LQ_TYPE || s.trusted || (s.cleanLooks >= 2 && now() - s.firstClean >= T.PROOF)

    private fun fail(ctx: Ctx, type: String, old: Session?, error: String, cooldown: Long = 0) {
        val s = old ?: Session(0, type, null, 0, null)
        s.variants = null
        s.errors++
        s.cleanLooks = 0
        s.retryAt = now() + (cooldown.takeIf { it > 0 } ?: min(60000L, (T.ERROR_COOLDOWN * 2.0.pow(s.errors - 1)).toLong()))
        s.last = "fehler"
        ctx.sessions[type] = s
        Log.d(TAG, "Ersatz $type: $error")
    }

    /** Die Playlist der Ersatz-Sitzung für die Variante, die der Player will (null: gerade nicht nutzbar). */
    private fun backupPlaylist(ctx: Ctx, type: String, want: Variant, respectCooldown: Boolean): Backup? {
        var s = ctx.sessions[type]
        val t = now()
        if (s != null && s.stalledAt != 0L && t - s.stalledAt < T.STALLED) return null
        if (respectCooldown && s != null && s.retryAt > t) return null
        repeat(2) {
            val fresh = s == null || s!!.variants == null
            try {
                if (fresh) {
                    s = openSession(ctx, type, s).also { ctx.sessions[type] = it }
                }
                val sess = s!!
                val v = pickVariant(sess.variants!!, want)
                if (v == null) {
                    fail(ctx, type, sess, "kein passender Codec (${want.family})", T.FORBIDDEN)
                    return null
                }
                val text = fetchText(v.uri)
                val parsed = parseMedia(text, v.uri)
                if (parsed.segs.isEmpty() || parsed.ended) throw IOException("leere Playlist")
                val ads = hasAds(text)
                sess.lastLook = now()
                sess.errors = 0
                if (ads) {
                    sess.cleanLooks = 0
                    sess.trusted = false
                    sess.retryAt = now() + T.AD_COOLDOWN
                    sess.last = "werbung"
                } else {
                    if (sess.cleanLooks == 0) sess.firstClean = sess.lastLook
                    sess.cleanLooks++
                    sess.retryAt = 0
                    sess.last = "frei"
                }
                return Backup(type, "$type:${sess.id}", sess, parsed, text, ads, type == LQ_TYPE, v.res, want, !ads && proven(sess))
            } catch (e: Exception) {
                if (!fresh) { // die alte Sitzung ist abgelaufen: einmal mit einer neuen
                    s!!.variants = null
                    return@repeat
                }
                val msg = e.message ?: e.toString()
                fail(ctx, type, s, msg, if (msg.contains("forbidden", true)) T.FORBIDDEN else 0)
                return null
            }
        }
        return null
    }

    /** Zweiter Blick auf eine volle Qualität, die vorher frei war (null: keine bereit). */
    private fun provenHq(ctx: Ctx, want: Variant, exclude: String?): Backup? {
        val t = now()
        val candidate = HQ_TYPES.mapNotNull { ctx.sessions[it] }.filter { s ->
            s.type != exclude && s.variants != null && s.cleanLooks > 0 && s.retryAt <= t && (proven(s) || t - s.firstClean >= T.PROOF)
        }.maxByOrNull { it.lastLook } ?: return null
        val b = backupPlaylist(ctx, candidate.type, want, true)
        return if (b != null && !b.ads && b.proven) b else null
    }

    /** Die vollen Qualitäten nacheinander durchsehen (immer nur eine Suche zugleich). */
    private fun searchHq(ctx: Ctx, want: Variant, exclude: String?): Future<Backup?>? {
        ctx.scan?.let { if (!it.isDone) return it }
        if (ctx.bridgeSince != 0L && now() - ctx.bridgeSince < T.HQ_DWELL) return null
        val order = HQ_TYPES.filter { it != exclude }
        val f = scanner.submit<Backup?> {
            try {
                for (type in order) {
                    val b = backupPlaylist(ctx, type, want, true)
                    if (b != null && !b.ads) return@submit b
                }
                null
            } catch (_: Exception) {
                null
            }
        }
        ctx.scan = f
        return f
    }

    // ------------------------------------------------------------ Eine Zeitleiste für alles, was der Player bekommt

    /** Das erste Stück aus [segs], das der Player noch nicht hat (nach Zeit), oder null. */
    private fun align(tl: Timeline, segs: List<Seg>): Seg? {
        if (tl.lastOut < 0) return segs.firstOrNull()
        if (tl.lastEnd != 0L && segs.any { it.pdt != null }) return segs.firstOrNull { it.pdt != null && it.pdt >= tl.lastEnd - 500 }
        return segs.lastOrNull()
    }

    /** Zeitleiste mit Quelle [id] fortsetzen: ihr nächstes neues Stück bekommt die nächste Nummer, hinter einer Diskontinuität. */
    private fun switchTo(tl: Timeline, id: String, segs: List<Seg>, force: Boolean): Boolean {
        var cand = align(tl, segs)
        if (cand == null) {
            if (!force || segs.isEmpty()) return false
            cand = segs.last()
        }
        tl.epoch++
        tl.source = id
        tl.lastHoldAt = 0
        if (tl.lastOut < 0) {
            tl.offset = 0
            tl.dsShift = 0
            tl.boundary = Long.MIN_VALUE
        } else {
            tl.boundary = tl.lastOut + 1
            tl.offset = tl.boundary - cand.seq
            tl.dsShift = tl.lastDs + 1 - cand.ds
            tl.switched = true
        }
        return true
    }

    /** Die Playlist für die Anfrage [url] aus Quelle [id] (null: Quelle noch nicht nutzbar). */
    private fun serve(ctx: Ctx, url: String, id: String, src: Media, strip: Boolean = false, force: Boolean = false): String? {
        val tl = ctx.tl
        val segs = if (strip) liveRun(src.segs) else src.segs
        if (segs.isEmpty()) return null
        if (tl.source != id) {
            // Eine Quelle, die lange zurückbleibt, wird trotzdem genommen (ein paar Sekunden doppelt)
            val waited = ctx.wait?.let { it.first == id && now() - it.second > 6000 } == true
            if (!switchTo(tl, id, segs, force || waited)) {
                if (ctx.wait?.first != id) ctx.wait = id to now()
                return null
            }
            ctx.wait = null
        } else if (tl.switched && tl.lastOut >= 0) {
            // Gleiche Quelle, aber herausgeschnittene Werbung lässt eine Lücke: hinter einer Diskontinuität weiter
            val next = segs.firstOrNull { it.seq + tl.offset > tl.lastOut }
            if (next != null && next.seq + tl.offset > tl.lastOut + 1) switchTo(tl, id, segs, true)
        }
        val verbatim = !tl.switched && id == ctx.nativeId && !strip
        return emit(tl, url, src, segs, strip, if (verbatim) src.text else null)
    }

    private fun emit(tl: Timeline, url: String, src: Media, segs: List<Seg>, strip: Boolean, verbatim: String?): String? {
        val cur = ArrayList<Entry>()
        for (s in segs) {
            val out = s.seq + tl.offset
            if (out < tl.boundary) continue
            cur += Entry(out, s.ds + tl.dsShift, s.disc || out == tl.boundary, s.map, s.key, s.seq,
                if (strip) s.tags.filterNot { isAdLine(it) } else s.tags, s.uri, s.dur, s.pdt, s.ad, tl.epoch)
        }
        val hist = tl.hist[url].orEmpty()
        var entries = hist
        if (cur.isNotEmpty()) {
            // Das Ende dessen behalten, was der Player von der vorigen Quelle hat – so ist der Wechsel nahtlos
            val prev = ArrayDeque<Entry>()
            for (i in hist.indices.reversed()) {
                val e = hist[i]
                if (e.epoch == tl.epoch) continue
                if (e.ad || e.out != (prev.firstOrNull()?.out ?: cur[0].out) - 1) break
                prev.addFirst(e)
            }
            val room = max(src.segs.size, 4) - cur.size
            entries = (if (room > 0) prev.toList().takeLast(room) else emptyList()) + cur
        }
        if (entries.isEmpty()) return null
        tl.hist[url] = entries
        val last = entries.last()
        if (last.out > tl.lastOut) {
            tl.lastOut = last.out
            tl.lastDs = last.ds
        }
        if (last.pdt != null) tl.lastEnd = max(tl.lastEnd, last.pdt + (last.dur * 1000).toLong())
        if (verbatim != null) return verbatim
        val header = if (strip) src.header.filterNot { isAdLine(it) } else src.header
        return render(header, entries, if (cur.isNotEmpty() && !strip) src.tail else emptyList())
    }

    /**
     * Schwarze, stumme Stücke statt der Werbung – so viele, wie Zeit vergangen ist, damit der Puffer
     * weder leerläuft noch wächst. Die Zeit der Werbung fällt weg: danach geht es live weiter.
     */
    private fun hold(ctx: Ctx, url: String, src: Media): String {
        val tl = ctx.tl
        val t = now()
        if (tl.lastOut < 0) {
            tl.lastOut = src.segs[0].seq - 1
            tl.lastDs = src.segs[0].ds
        }
        val count = if (tl.lastHoldAt != 0L) min(3, max(1, ((t - tl.lastHoldAt) / 1024.0).roundToInt())) else 2
        tl.lastHoldAt = t
        tl.epoch++
        val entries = ArrayList<Entry>()
        repeat(count) {
            tl.holdSeq++
            tl.lastOut++
            tl.lastDs++
            entries += Entry(tl.lastOut, tl.lastDs, true, null, null, tl.lastOut, listOf("#EXTINF:1.024,live"), "$HOLD_URL?n=${tl.holdSeq}", 1.024, null, true, tl.epoch)
        }
        tl.source = "hold"
        tl.switched = true
        val last = src.segs.last()
        if (last.pdt != null) tl.lastEnd = max(tl.lastEnd, last.pdt + (last.dur * 1000).toLong())
        tl.hist[url] = entries
        val td = max(2, ceil(src.header.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }?.substringAfter(':')?.toDoubleOrNull() ?: 2.0).toInt())
        return render(listOf("#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:$td", "#EXT-X-MEDIA-SEQUENCE:"), entries, emptyList())
    }

    @Volatile private var holdBytes: ByteArray? = null

    /** Das Ersatz-Stück mit um n × 1,024 s verschobenen Zeitstempeln (90-kHz-Takt, 33 Bit). */
    fun holdSegment(n: Long): ByteArray {
        val base = holdBytes ?: holdTs().also { holdBytes = it }
        return shiftTimestamps(base, n)
    }

    companion object {
        private const val TAG = "TwitchAdBlock"
        const val WEB_CLIENT = "kimne78kx3ncx6brgo4mv6wki5h1ko"
        const val GQL_URL = "https://gql.twitch.tv/gql"
        const val HOLD_URL = "https://www.twitch.tv/__adblock_gx_hold.ts"
        private const val LQ_TYPE = "autoplay" // nur 360p, aber schnell und meist werbefrei
        private val TYPES = listOf("popout", "frontpage", "mobile_web", "site", "autoplay")
        private val HQ_TYPES = TYPES.filter { it != LQ_TYPE }

        private val AD_TEXT = Regex(
            "stitched-ad|X-TV-TWITCH-AD|/adsquared/|SCTE35-OUT|EXT-X-CUE-OUT|CLASS=\"twitch-(?:stitched-)?ad(?:-|\")|\"MIDROLL\"",
            RegexOption.IGNORE_CASE,
        )
        private val SEG_TAG = Regex("^#EXT(INF|-X-(DISCONTINUITY$|PROGRAM-DATE-TIME|MAP|KEY|BYTERANGE|GAP|TWITCH-PREFETCH|PART|PRELOAD-HINT))")

        fun randomHex(n: Int): String = buildString { repeat(n) { append("0123456789abcdef"[Random.nextInt(16)]) } }

        private fun attr(line: String, name: String): String =
            Regex("[:,]$name=(\"[^\"]*\"|[^,]*)").find(line)?.groupValues?.get(1)?.trim('"').orEmpty()

        private fun absolute(uri: String, base: String?): String {
            if (base == null || Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(uri)) return uri
            return base.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: uri
        }

        private fun absoluteTag(line: String, base: String?): String =
            line.replace(Regex("URI=\"([^\"]+)\"")) { "URI=\"" + absolute(it.groupValues[1], base) + "\"" }

        private fun isAdTitle(extinf: String): Boolean {
            val i = extinf.indexOf(',')
            val title = if (i < 0) "" else extinf.substring(i + 1).trim()
            return title.isNotEmpty() && title != "live"
        }

        private fun isAdUri(uri: String) = Regex("stitched|/adsquared/|/_404/").containsMatchIn(uri)

        private fun isAdMarker(line: String): Boolean {
            if (!line.startsWith("#EXT-X-DATERANGE")) return false
            if (line.contains("stitched-ad") || line.contains("X-TV-TWITCH-AD")) return true
            // Quelle "Amazon|…" statt "live" steuert beim Web-Player die Werbe-Einblendung
            val m = Regex("X-TV-TWITCH-STREAM-SOURCE=\"([^\"]*)\"").find(line)
            return m != null && m.groupValues[1] != "live"
        }

        fun isAdLine(line: String) = line.startsWith("#") && (AD_TEXT.containsMatchIn(line) || isAdMarker(line))

        fun hasAds(text: String): Boolean {
            if (AD_TEXT.containsMatchIn(text)) return true
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if ((line.startsWith("#EXTINF:") && isAdTitle(line)) || isAdMarker(line)) return true
                if (line.isNotEmpty() && !line.startsWith("#") && isAdUri(line)) return true
            }
            return false
        }

        private fun codecFamily(codecs: String): String {
            val c = codecs.lowercase()
            return when {
                Regex("(^|,)\\s*(hev1|hvc1)").containsMatchIn(c) -> "hevc"
                Regex("(^|,)\\s*av01").containsMatchIn(c) -> "av1"
                Regex("(^|,)\\s*avc[13]").containsMatchIn(c) -> "avc"
                else -> ""
            }
        }

        fun parseVariants(master: String, base: String?): List<Variant> {
            val lines = master.split(Regex("\r?\n"))
            val list = ArrayList<Variant>()
            for (i in 0 until lines.size - 1) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
                val uri = lines[i + 1].trim()
                if (uri.isEmpty() || uri.startsWith("#")) continue
                val res = attr(lines[i], "RESOLUTION")
                list += Variant(
                    uri = absolute(uri, base),
                    raw = uri,
                    res = res,
                    fps = attr(lines[i], "FRAME-RATE").toDoubleOrNull() ?: 0.0,
                    height = res.substringAfter('x', "0").toIntOrNull() ?: 0,
                    family = codecFamily(attr(lines[i], "CODECS")),
                    name = attr(lines[i], "IVS-NAME").ifEmpty { attr(lines[i], "STABLE-VARIANT-ID") },
                )
            }
            return list
        }

        /** Ersatz-Variante für das, was der Player spielt: gleiche Codec-Familie, dann Name/Auflösung/Bildrate, sonst die beste darunter. */
        fun pickVariant(list: List<Variant>, want: Variant): Variant? {
            var pool = list
            if (want.family.isNotEmpty()) {
                pool = list.filter { it.family.isEmpty() || it.family == want.family }
                if (pool.isEmpty()) return null
            }
            if (want.name.isNotEmpty()) pool.firstOrNull { it.name == want.name }?.let { return it }
            val exact = pool.filter { it.res.isNotEmpty() && it.res == want.res }
            if (exact.isNotEmpty()) return exact.minBy { abs(it.fps - want.fps) }
            val lower = pool.filter { it.height <= want.height }
            return (lower.ifEmpty { pool }).sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.fps }).firstOrNull()
        }

        /** Media-Playlist → Kopf, Stücke (mit MAP/KEY, Diskontinuitäts-Nummer und Startzeit), Rest. */
        fun parseMedia(text: String, base: String?): Media {
            val lines = text.split(Regex("\r?\n"))
            var lastUri = -1
            for (i in lines.indices.reversed()) {
                val l = lines[i].trim()
                if (l.isNotEmpty() && !l.startsWith("#")) {
                    lastUri = i
                    break
                }
            }
            val p = Media(ArrayList(), ArrayList(), ArrayList(), false, text)
            var seq = 0L
            var ds = 0L
            var inHeader = true
            var tags = ArrayList<String>()
            var disc = false
            var map: String? = null
            var key: String? = null
            var pdt: Long? = null
            var nextPdt: Long? = null
            var extinf: String? = null
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                if (line == "#EXT-X-ENDLIST") {
                    p.ended = true
                    continue
                }
                if (inHeader) {
                    if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                        seq = line.substring(22).trim().toLongOrNull() ?: 0
                        p.header += "#EXT-X-MEDIA-SEQUENCE:" // füllt render() aus
                        continue
                    }
                    if (line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")) {
                        ds = line.substring(30).trim().toLongOrNull() ?: 0
                        continue
                    }
                    if (line.startsWith("#") && !SEG_TAG.containsMatchIn(line)) {
                        p.header += line
                        continue
                    }
                    inHeader = false
                }
                if (lastUri >= 0 && i > lastUri) {
                    p.tail += line // Prefetch-Hinweise usw.
                    continue
                }
                if (line == "#EXT-X-DISCONTINUITY") {
                    disc = true
                    map = null // Twitch: fMP4-Werbung in MPEG-TS-Streams – ihre MAP darf nicht an Live-Stücken hängen
                    continue
                }
                if (line.startsWith("#EXT-X-MAP:")) {
                    map = absoluteTag(line, base)
                    continue
                }
                if (line.startsWith("#EXT-X-KEY:")) {
                    key = if (line.contains("METHOD=NONE")) null else absoluteTag(line, base)
                    continue
                }
                if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) {
                    runCatching { Instant.parse(line.substring(25).trim()).toEpochMilli() }.getOrNull()?.let { pdt = it }
                    tags += line
                    continue
                }
                if (line.startsWith("#EXTINF:")) extinf = line
                if (line.startsWith("#")) {
                    tags += line
                    continue
                }
                val dur = extinf?.substring(8)?.substringBefore(',')?.toDoubleOrNull() ?: 0.0
                val uri = absolute(line, base)
                val start = pdt ?: nextPdt
                if (disc) ds++
                p.segs += Seg(seq, ds, disc, map, key, tags, uri, dur, start, (extinf != null && isAdTitle(extinf!!)) || isAdUri(uri))
                seq++
                nextPdt = start?.let { it + (dur * 1000).toLong() }
                tags = ArrayList()
                disc = false
                pdt = null
                extinf = null
            }
            return p
        }

        private fun keyLine(e: Entry): String? {
            val k = e.key ?: return null
            if (e.out == e.seq || Regex("[:,]IV=").containsMatchIn(k)) return k
            // AES-128 ohne IV nimmt die Stücknummer als IV: die ursprüngliche behalten
            return k + ",IV=0x" + e.seq.toString(16).padStart(32, '0')
        }

        fun render(header: List<String>, entries: List<Entry>, tail: List<String>): String {
            val first = entries[0]
            val ds = max(0, first.ds - if (first.disc) 1 else 0)
            val seqLines = mutableListOf("#EXT-X-MEDIA-SEQUENCE:${first.out}")
            if (ds > 0) seqLines += "#EXT-X-DISCONTINUITY-SEQUENCE:$ds"
            val out = if (header.firstOrNull() == "#EXTM3U") ArrayList<String>() else arrayListOf("#EXTM3U")
            var seqWritten = false
            for (l in header) {
                if (l == "#EXT-X-MEDIA-SEQUENCE:") {
                    if (!seqWritten) out += seqLines
                    seqWritten = true
                } else out += l
            }
            if (!seqWritten) out.addAll(1, seqLines)
            var map: String? = null
            var key: String? = null
            for (e in entries) {
                if (e.disc) out += "#EXT-X-DISCONTINUITY"
                if (e.map != null && e.map != map) out += e.map
                map = e.map
                val k = keyLine(e)
                if (k != key) {
                    if (k != null || key != null) out += (k ?: "#EXT-X-KEY:METHOD=NONE")
                    key = k
                }
                out += e.tags
                out += e.uri
            }
            out += tail
            return out.joinToString("\n") + "\n"
        }

        /** Ohne Werbung: die Live-Stücke nach der Werbung (vorbei) oder davor (läuft noch) – zusammenhängend. */
        fun liveRun(segs: List<Seg>): List<Seg> {
            var firstAd = -1
            var lastAd = -1
            segs.forEachIndexed { i, s ->
                if (!s.ad) return@forEachIndexed
                if (firstAd < 0) firstAd = i
                lastAd = i
            }
            if (lastAd < 0) return segs
            return if (lastAd < segs.size - 1) segs.subList(lastAd + 1, segs.size) else segs.subList(0, firstAd)
        }

        fun shiftTimestamps(base: ByteArray, n: Long): ByteArray {
            val b = base.copyOf()
            val shift = (n % 80000) * 92160L
            val wrap = 8589934592L // 2^33
            fun u(i: Int) = b[i].toInt() and 0xFF
            fun moveTs(o: Int) {
                var v = ((u(o) shr 1) and 7).toLong() * 1073741824L + u(o + 1) * 4194304L + (u(o + 2) shr 1) * 32768L +
                    u(o + 3) * 128L + (u(o + 4) shr 1)
                v = (v + shift) % wrap
                b[o] = ((u(o) and 0xF1) or (((v / 1073741824L).toInt() and 7) shl 1)).toByte()
                b[o + 1] = ((v / 4194304L).toInt() and 0xFF).toByte()
                b[o + 2] = ((((v / 32768L).toInt() and 0x7F) shl 1) or 1).toByte()
                b[o + 3] = ((v / 128L).toInt() and 0xFF).toByte()
                b[o + 4] = ((((v % 128L).toInt()) shl 1) or 1).toByte()
            }
            fun movePcr(o: Int) {
                var v = u(o) * 33554432L + u(o + 1) * 131072L + u(o + 2) * 512L + u(o + 3) * 2L + (u(o + 4) shr 7)
                v = (v + shift) % wrap
                b[o] = ((v / 33554432L).toInt() and 0xFF).toByte()
                b[o + 1] = ((v / 131072L).toInt() and 0xFF).toByte()
                b[o + 2] = ((v / 512L).toInt() and 0xFF).toByte()
                b[o + 3] = ((v / 2L).toInt() and 0xFF).toByte()
                b[o + 4] = ((u(o + 4) and 0x7F) or (((v % 2L).toInt()) shl 7)).toByte()
            }
            var i = 0
            while (i + 188 <= b.size) {
                if (u(i) != 0x47) break
                var p = i + 4
                val afc = (u(i + 3) shr 4) and 3
                if (afc and 2 != 0) {
                    val len = u(p)
                    if (len >= 7 && (u(p + 1) and 0x10) != 0) movePcr(p + 2)
                    p += 1 + len
                }
                // keine Nutzdaten / kein Anfang eines PES-Pakets
                if ((afc and 1) != 0 && (u(i + 1) and 0x40) != 0 && p + 19 <= i + 188 && u(p) == 0 && u(p + 1) == 0 && u(p + 2) == 1) {
                    val flags = u(p + 7) shr 6
                    if (flags and 2 != 0) moveTs(p + 9)
                    if (flags == 3) moveTs(p + 14)
                }
                i += 188
            }
            return b
        }
    }
}
