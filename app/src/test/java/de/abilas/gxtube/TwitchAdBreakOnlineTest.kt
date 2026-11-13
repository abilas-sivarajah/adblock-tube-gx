package de.abilas.gxtube

import de.abilas.gxtube.data.Http
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.twitch.TwitchAdBlock
import de.abilas.gxtube.twitch.TwitchApi
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Werbepausen auf echten Twitch-Streams nachgestellt – ohne Player und ohne Fenster: Ein Interceptor
 * markiert in den echten Playlists die Stücke eines Zeitfensters als Werbung (in der eigenen Sitzung
 * oder in allen), der Werbeblocker wird wie von ExoPlayer alle 2 s nach der Playlist gefragt.
 *
 * Geprüft wird, was beim Player ankäme: keine Werbung, Nummern steigen nur, während der Werbung eine
 * Ersatz-Sitzung (bzw. das schwarze Ersatzbild) und danach schnell zurück zur eigenen Sitzung – auch
 * solange die Werbestücke noch in Twitchs Fenster stehen. Je ein MPEG-TS- und ein fMP4-Kanal.
 *
 * Nur mit GXTUBE_ONLINE_TESTS=1 (dauert ein paar Minuten); GXTUBE_AD_FORMAT=ts|mp4 für nur ein Format.
 */
class TwitchAdBreakOnlineTest {

    /** Sitzt zwischen Werbeblocker und Twitch: weiß, welche Playlist zu welcher Sitzung gehört, und setzt die Werbung ein. */
    private class Sim : Interceptor {
        /** play_session_id der Sitzung des Players */
        @Volatile var nativeSession: String? = null
        /** Werbung von/bis (Uhrzeit des Streams) in diesen Sitzungen ("native", "hq", "lq") */
        @Volatile var from = Long.MAX_VALUE
        @Volatile var until = Long.MAX_VALUE
        @Volatile var kinds: Set<String> = emptySet()
        /** Live-Kante der eigenen Sitzung (Uhrzeit des Streams) */
        @Volatile var nativeEdge = 0L
        /** Wann die eigene Playlist zum ersten Mal wieder ein Live-Stück hinter der Werbung hatte */
        @Volatile var liveAgainAt = 0L
        val kindOf = ConcurrentHashMap<String, String>()
        val segKind = ConcurrentHashMap<String, String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            val resp = chain.proceed(chain.request())
            val usher = url.host == "usher.ttvnw.net"
            val kind = kindOf[url.toString()]
            if (!resp.isSuccessful || (!usher && kind == null)) return resp
            val type = resp.body.contentType()
            val text = resp.body.string()
            val out = if (usher) text.also { register(url, it) } else mark(url.toString(), kind!!, text)
            return resp.newBuilder().body(out.toResponseBody(type)).build()
        }

        private fun register(url: HttpUrl, master: String) {
            val kind = if (url.queryParameter("play_session_id") == nativeSession) "native" else {
                val type = Regex("\"player_type\":\"([^\"]+)\"").find(url.queryParameter("token").orEmpty())?.groupValues?.get(1)
                if (type == "autoplay" || type == "picture-by-picture") "lq" else "hq"
            }
            TwitchAdBlock.parseVariants(master, url.toString()).forEach { kindOf[it.uri.toHttpUrlOrNull()?.toString() ?: it.uri] = kind }
        }

        private fun mark(url: String, kind: String, text: String): String {
            val lines = text.split('\n').map { it.trimEnd('\r') }
            val out = ArrayList<String>(lines.size + 1)
            var pdt: Long? = null
            var next: Long? = null
            var marked = false
            var liveAfter = false
            var edge = 0L
            for (line in lines) {
                if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:")) pdt = runCatching { Instant.parse(line.substring(25).trim()).toEpochMilli() }.getOrNull()
                if (line.startsWith("#EXTINF:")) {
                    val start = pdt ?: next
                    val dur = line.substring(8).substringBefore(',').toDoubleOrNull() ?: 0.0
                    next = start?.let { it + (dur * 1000).toLong() }
                    pdt = null
                    if (next != null) edge = next
                    if (start != null && start >= until) liveAfter = true
                    if (kind in kinds && start != null && start >= from && start < until) {
                        out += line.substringBefore(',') + ",Amazon|4242"
                        marked = true
                        continue
                    }
                }
                out += line
            }
            TwitchAdBlock.parseMedia(text, url).segs.forEach { segKind[it.uri] = kind }
            if (kind == "native") {
                nativeEdge = edge
                if (liveAfter && liveAgainAt == 0L) liveAgainAt = System.currentTimeMillis()
            }
            if (!marked) return text
            // Twitch lässt die Markierung der Werbung stehen, solange ihre Stücke im Fenster sind
            val i = out.indexOfFirst { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            out.add(
                i + 1,
                "#EXT-X-DATERANGE:ID=\"stitched-ad-test-$from\",CLASS=\"twitch-stitched-ad\",START-DATE=\"${Instant.ofEpochMilli(from)}\"," +
                    "DURATION=${(until - from) / 1000.0},X-TV-TWITCH-AD-ROLL-TYPE=\"MIDROLL\"",
            )
            return out.joinToString("\n")
        }
    }

    private class Outcome(
        /** Woher das letzte Stück beim Player kam, solange die Werbung lief: native, lq, hq, hold */
        val sources: Set<String>,
        val detected: Boolean,
        val holds: Int,
        val adsDelivered: Int,
        /** Sekunden vom ersten Live-Stück nach der Werbung bis der Player wieder die eigene Sitzung bekam */
        val backSec: Double?,
        val events: List<String>,
    )

    private fun get(url: String): String = Http.client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

    /** Je ein Kanal mit MPEG-TS ("ts") und mit fMP4 ("mp4") aus den beliebten Streams. */
    private fun channels(): Map<String, VideoItem> = runBlocking {
        val only = System.getenv("GXTUBE_AD_FORMAT")?.takeIf { it.isNotBlank() }
        val found = LinkedHashMap<String, VideoItem>()
        for (s in TwitchApi.home("de").streams.take(16)) {
            if (found.size == (if (only != null) 1 else 2)) break
            val format = runCatching {
                val play = TwitchApi.playback(s.id)
                val v = TwitchAdBlock.parseVariants(get(play.url), play.url).first()
                val live = TwitchAdBlock.parseMedia(get(v.uri), v.uri).segs.filter { !it.ad }
                if (live.isEmpty()) null else if (live.any { it.map != null }) "mp4" else "ts"
            }.getOrNull() ?: continue
            if (only == null || only == format) found.putIfAbsent(format, s)
        }
        println("Kanäle: " + found.map { "${it.key}=${it.value.twitchLogin}" })
        found
    }

    private fun adBreak(stream: VideoItem, kinds: Set<String>, label: String): Outcome = runBlocking {
        val sim = Sim()
        val events = ArrayList<String>()
        val hold = File("src/main/assets/twitch_hold.ts").readBytes()
        // Ad-Spoofing aus: Die nachgestellte Werbung gibt es bei Twitch nicht, dorthin wird nichts gemeldet
        val ab = TwitchAdBlock(Http.client.newBuilder().addInterceptor(sim).build(), { hold }, spoofingEnabled = { false }) { e ->
            synchronized(events) { events += e.toString() }
        }
        val play = TwitchApi.playback(stream.id)
        sim.nativeSession = play.url.toHttpUrl().queryParameter("play_session_id")
        val variants = TwitchAdBlock.parseVariants(String(ab.handle(play.url)!!), play.url)
        val variant = variants.firstOrNull { it.height in 480..720 } ?: variants.first()
        val t0 = System.currentTimeMillis()
        val seen = HashMap<Long, String>()
        var lastFirst = -1L
        var holds = 0
        var adsDelivered = 0

        /** Eine Aktualisierung wie von ExoPlayer; zurück kommt, woher das letzte Stück stammt. */
        fun tick(): String {
            val p = TwitchAdBlock.parseMedia(String(ab.handle(variant.uri)!!), variant.uri)
            assertTrue("$label: Playlist leer", p.segs.isNotEmpty())
            assertTrue("$label: Nummern laufen rückwärts (${p.segs.first().seq} < $lastFirst)", p.segs.first().seq >= lastFirst)
            lastFirst = p.segs.first().seq
            for (s in p.segs) {
                val before = seen.put(s.seq, s.uri)
                assertTrue("$label: Stück ${s.seq} hat die Adresse gewechselt", before == null || before == s.uri)
                if (before != null) continue
                if (s.uri.startsWith(TwitchAdBlock.HOLD_URL)) holds++ else if (s.ad) adsDelivered++
            }
            val last = p.segs.last()
            val kind = if (last.uri.startsWith(TwitchAdBlock.HOLD_URL)) "hold" else sim.segKind[last.uri] ?: "?"
            val fresh = synchronized(events) { events.toList() }
            println("[$label +${(System.currentTimeMillis() - t0) / 1000}s] $kind ${p.segs.first().seq}..${last.seq} ${fresh.takeLast(4)}")
            return kind
        }

        // Erst ruhig auf der eigenen Sitzung (eine echte Start-Werbung abwarten)
        var calm = 0
        while (calm < 5) {
            assumeTrue("$label: die eigene Sitzung kam in 150 s nicht zur Ruhe", System.currentTimeMillis() - t0 < 150_000)
            calm = if (tick() == "native") calm + 1 else 0
            Thread.sleep(2000)
        }
        synchronized(events) { events.clear() }

        // Werbepause: beginnt 4 s hinter der Live-Kante
        sim.from = sim.nativeEdge + 4000
        sim.until = sim.from + AD_MS
        sim.kinds = kinds
        val sources = LinkedHashSet<String>()
        val start = System.currentTimeMillis()
        var backAt = 0L
        while (System.currentTimeMillis() - start < AD_MS + 90_000) {
            val kind = tick()
            if (sim.liveAgainAt == 0L) sources += kind
            else if (backAt == 0L && kind == "native" && synchronized(events) { "AdEnd" in events }) backAt = System.currentTimeMillis()
            if (backAt != 0L && System.currentTimeMillis() - backAt > 8000) break
            Thread.sleep(2000)
        }
        val all = synchronized(events) { events.toList() }
        val backSec = if (backAt != 0L) (backAt - sim.liveAgainAt) / 1000.0 else null
        println("ERGEBNIS $label ${stream.twitchLogin}: während der Werbung $sources, Ersatzbild-Stücke $holds, Werbestücke beim Player $adsDelivered, zurück nach $backSec s, $all")
        Outcome(sources, "AdStart" in all, holds, adsDelivered, backSec, all)
    }

    @Test
    fun adBreakInOwnSession() {
        assumeTrue(System.getenv("GXTUBE_ONLINE_TESTS") == "1")
        val channels = channels()
        assertTrue("kein Kanal gefunden", channels.isNotEmpty())
        for ((format, stream) in channels) {
            val o = adBreak(stream, setOf("native"), "$format/eigene")
            assertTrue("$format: Werbung erkannt", o.detected)
            assertEquals("$format: keine Werbung beim Player", 0, o.adsDelivered)
            assertTrue("$format: Ersatz-Sitzung während der Werbung: ${o.sources}", o.sources.any { it == "lq" || it == "hq" || it == "hold" })
            assertNotNull("$format: zurück zur eigenen Sitzung", o.backSec)
            assertTrue("$format: zurück nach ${o.backSec} s", o.backSec!! <= MAX_BACK_SEC)
        }
    }

    @Test
    fun adBreakInEverySession() {
        assumeTrue(System.getenv("GXTUBE_ONLINE_TESTS") == "1")
        val channels = channels()
        assertTrue("kein Kanal gefunden", channels.isNotEmpty())
        for ((format, stream) in channels) {
            val o = adBreak(stream, setOf("native", "hq", "lq"), "$format/ueberall")
            assertTrue("$format: Werbung erkannt", o.detected)
            if (format == "ts") {
                assertTrue("ts: schwarzes Ersatzbild statt Werbung", o.holds > 0)
                assertEquals("ts: keine Werbung beim Player", 0, o.adsDelivered)
            } else {
                // fMP4: Die MPEG-TS-Ersatzstücke passen nicht – die Werbung läuft abgedeckt und stumm
                assertTrue("mp4: abgedeckt: ${o.events}", o.events.any { it.contains("Masked(hold=false)") })
                assertEquals("mp4: keine Ersatzbild-Stücke", 0, o.holds)
            }
            assertNotNull("$format: zurück zur eigenen Sitzung", o.backSec)
            assertTrue("$format: zurück nach ${o.backSec} s", o.backSec!! <= MAX_BACK_SEC)
            assertTrue("$format: Abdeckung wieder weg: ${o.events}", o.events.lastIndexOf("Unmasked") > o.events.indexOfLast { it.startsWith("Masked") })
        }
    }

    private companion object {
        /** Länge der nachgestellten Werbepause */
        const val AD_MS = 20_000L
        /** So schnell muss der Player nach der Werbung wieder die eigene Sitzung haben */
        const val MAX_BACK_SEC = 12.0
    }
}
