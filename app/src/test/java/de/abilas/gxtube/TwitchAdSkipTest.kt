package de.abilas.gxtube

import de.abilas.gxtube.twitch.TwitchAdBlock
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Werbepause gegen ein nachgebautes Twitch (ohne Netz): Der Player soll statt Werbung oder schwarzem
 * Bild gleich eine werbefreie Sitzung bekommen.
 */
class TwitchAdSkipTest {

    /** Nachgebautes Twitch: jede Sitzung (playerType) hat ihre eigene Playlist, mit oder ohne Werbung. */
    private class FakeTwitch {
        /** Live-Kante in Stücken (2 s) – für alle Sitzungen gleich */
        var head = 50
        /** Sitzungen mit Werbung */
        val ads = HashSet<String>()
        /** Stücke, die eine Sitzung hinter der Live-Kante liegt */
        val lag = HashMap<String, Int>()
        val tokenRequests = ArrayList<String>()

        fun playlist(session: String): String {
            val end = head - (lag[session] ?: 0)
            val offset = session.hashCode().mod(1000) * 10L // jede Sitzung nummeriert anders
            val b = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:${end - 5 + offset}\n")
            val ad = session in ads
            if (ad) b.append("#EXT-X-DATERANGE:ID=\"stitched-ad-1\",CLASS=\"twitch-stitched-ad\",START-DATE=\"${Instant.ofEpochMilli(T0)}\",DURATION=30.0\n")
            for (n in end - 5 until end) {
                b.append("#EXT-X-PROGRAM-DATE-TIME:${Instant.ofEpochMilli(T0 + n * 2000L)}\n")
                b.append(if (ad) "#EXTINF:2.000,Amazon|1\n" else "#EXTINF:2.000,live\n")
                b.append("https://video-edge.example/$session/$n.ts\n")
            }
            return b.toString()
        }

        fun master(session: String) = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=3422999,RESOLUTION=1280x720,CODECS=\"avc1.4D401F,mp4a.40.2\",VIDEO=\"720p60\",FRAME-RATE=60.000\n" +
            "https://video-weaver.example.hls.ttvnw.net/v1/playlist/$session-720.m3u8\n"

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            val body = when {
                url.startsWith("https://gql.twitch.tv/") -> {
                    val sent = Buffer().also { req.body!!.writeTo(it) }.readUtf8()
                    val type = Regex("\"playerType\":\"([^\"]+)\"").find(sent)!!.groupValues[1]
                    val platform = Regex("\"platform\":\"([^\"]+)\"").find(sent)!!.groupValues[1]
                    synchronized(tokenRequests) { tokenRequests += "$type/$platform" }
                    "{\"data\":{\"streamPlaybackAccessToken\":{\"value\":\"$type\",\"signature\":\"sig\",\"authorization\":{\"isForbidden\":false}}}}"
                }
                url.contains("usher.ttvnw.net/") -> master(req.url.queryParameter("token")!!)
                url.contains("/v1/playlist/") -> playlist(url.substringAfterLast('/').substringBefore("-720"))
                else -> error("unerwartet: $url")
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/vnd.apple.mpegurl".toMediaType())).build()
        }).build()
    }

    private val usher = "https://usher.ttvnw.net/api/channel/hls/streamer.m3u8?token=native&sig=x&supported_codecs=avc1"
    private val media = "https://video-weaver.example.hls.ttvnw.net/v1/playlist/native-720.m3u8"

    private fun player(tw: FakeTwitch, events: MutableList<String>): TwitchAdBlock {
        val ab = TwitchAdBlock(tw.client, { ByteArray(188) }) { e -> synchronized(events) { events += e.toString() } }
        ab.handle(usher)
        return ab
    }

    private fun refresh(ab: TwitchAdBlock) = TwitchAdBlock.parseMedia(String(ab.handle(media)!!), media)

    private fun hold(p: TwitchAdBlock.Media) = p.segs.count { it.uri.startsWith(TwitchAdBlock.HOLD_URL) }

    @Test
    fun secondBridgeWhenTheFirstHasAds() {
        val tw = FakeTwitch()
        val events = ArrayList<String>()
        val ab = player(tw, events)
        refresh(ab)
        // Werbepause: die eigene Sitzung, alle vollen Qualitäten und "autoplay" mit Werbung
        tw.ads += listOf("native", "popout", "frontpage", "mobile_web", "site", "autoplay")
        tw.head++
        val p = refresh(ab)
        assertEquals("kein schwarzes Bild", 0, hold(p))
        assertTrue("Stücke aus picture-by-picture: ${p.segs.map { it.uri }}", p.segs.last().uri.contains("/picture-by-picture/"))
        assertFalse(p.segs.any { it.ad })
        assertTrue(events.toString(), events.any { it.contains("Bridge(on=true)") })
        assertTrue(tw.tokenRequests.toString(), "picture-by-picture/ios" in tw.tokenRequests && "autoplay/android" in tw.tokenRequests)
    }

    @Test
    fun laggingBridgeRightAwayInsteadOfBlack() {
        val tw = FakeTwitch()
        val events = ArrayList<String>()
        val ab = player(tw, events)
        refresh(ab)
        tw.ads += listOf("native", "popout", "frontpage", "mobile_web", "site", "picture-by-picture")
        tw.lag["autoplay"] = 3 // 6 s hinter dem, was der Player schon hat
        tw.head++
        val p = refresh(ab)
        assertEquals("kein schwarzes Bild", 0, hold(p))
        assertTrue(p.segs.map { it.uri }.toString(), p.segs.last().uri.contains("/autoplay/"))
        // Danach geht es auf der Brücke lückenlos weiter, die Nummern steigen nur
        var last = p.segs.last().seq
        repeat(5) {
            tw.head++
            val q = refresh(ab)
            assertEquals(0, hold(q))
            assertTrue(q.segs.last().seq > last)
            last = q.segs.last().seq
        }
        // Werbung vorbei: zurück zur eigenen Sitzung
        tw.ads.clear()
        repeat(4) {
            tw.head++
            refresh(ab)
        }
        val back = refresh(ab)
        assertTrue(back.segs.last().uri.contains("/native/"))
        assertTrue(events.toString(), events.contains("AdEnd"))
    }

    @Test
    fun everythingWithAdsIsHeldBlack() {
        val tw = FakeTwitch()
        val ab = player(tw, ArrayList())
        refresh(ab)
        tw.ads += listOf("native", "popout", "frontpage", "mobile_web", "site", "autoplay", "picture-by-picture")
        tw.head++
        val p = refresh(ab)
        assertTrue("Werbung kommt nie beim Player an", p.segs.none { it.ad && !it.uri.startsWith(TwitchAdBlock.HOLD_URL) })
        assertTrue(hold(p) > 0)
    }

    private companion object {
        const val T0 = 1_792_872_000_000L
    }
}
