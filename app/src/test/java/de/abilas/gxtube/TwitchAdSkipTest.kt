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
import java.util.concurrent.ConcurrentHashMap

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
        val ads: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /** Sitzungen, bei denen oben im HLS-Fenster noch alte Werbung steht, am Ende aber schon wieder Live-Stücke */
        val tailLive: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /** ... so viele Live-Stücke am Ende */
        var liveTail = 2
        /** Sitzungen, bei denen nur noch die Markierung der Werbung im Fenster steht (alle Stücke live) */
        val tagsOnly: MutableSet<String> = ConcurrentHashMap.newKeySet()
        /** Dauer der Werbung laut Markierung (sie beginnt lange vor den Stücken im Fenster) */
        var adSeconds = 30.0
        /** fMP4-Stream (Bild und Ton in MP4-Stücken) statt MPEG-TS */
        var fmp4 = false
        /** Sitzungen, deren Playlist so viele Millisekunden auf sich warten lässt */
        val slow = ConcurrentHashMap<String, Long>()
        /** Wie oft die Playlist einer Sitzung geholt wurde */
        val fetched = ConcurrentHashMap<String, Int>()
        /** So lange lässt Twitch auf die Antwort zu RecordAdEvent warten */
        var slowSpoofMs = 0L
        /** Ob Ad-Spoofing (RecordAdEvent) die Werbung auf der Hauptsitzung sofort beendet */
        var spoofClearsNativeAds = false
        var spoofTailLiveNative = false
        /** Twitch lehnt RecordAdEvent ab */
        var spoofFails = false
        var spoofCalls = 0
        /** Stücke, die eine Sitzung hinter der Live-Kante liegt */
        val lag = HashMap<String, Int>()
        val tokenRequests = ArrayList<String>()
        val adEvents = ArrayList<String>()

        fun playlist(session: String): String {
            val end = head - (lag[session] ?: 0)
            val offset = session.hashCode().mod(1000) * 10L // jede Sitzung nummeriert anders
            val b = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:${end - 5 + offset}\n")
            val ad = session in ads
            val tl = session in tailLive
            if (ad || tl || session in tagsOnly) {
                b.append(
                    "#EXT-X-DATERANGE:ID=\"stitched-ad-$head\",CLASS=\"twitch-stitched-ad\",START-DATE=\"${Instant.ofEpochMilli(T0)}\",DURATION=$adSeconds," +
                        "X-TV-TWITCH-AD-RADS-TOKEN=\"rads-token-$session\",X-TV-TWITCH-AD-COMMERCIAL-ID=\"comm-$head\"," +
                        "X-TV-TWITCH-AD-CREATIVE-ID=\"creative-1\",X-TV-TWITCH-AD-LINE-ITEM-ID=\"line-1\",X-TV-TWITCH-AD-ORDER-ID=\"order-1\",X-TV-TWITCH-AD-ROLL-TYPE=\"MIDROLL\"\n",
                )
            }
            if (fmp4) b.append("#EXT-X-MAP:URI=\"https://video-edge.example/$session/init.mp4\"\n")
            for (n in end - 5 until end) {
                val segAd = ad || (tl && n < end - liveTail)
                b.append("#EXT-X-PROGRAM-DATE-TIME:${Instant.ofEpochMilli(T0 + n * 2000L)}\n")
                b.append(if (segAd) "#EXTINF:2.000,Amazon|1\n" else "#EXTINF:2.000,live\n")
                b.append("https://video-edge.example/$session/$n.${if (fmp4) "mp4" else "ts"}\n")
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
                    if (sent.contains("ClientSideAdEventHandling_RecordAdEvent")) {
                        synchronized(adEvents) { spoofCalls++ }
                        if (slowSpoofMs > 0) Thread.sleep(slowSpoofMs)
                        if (spoofFails) return@Interceptor Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body("[{\"errors\":[{\"message\":\"service error\"}]}]".toResponseBody("application/json".toMediaType())).build()
                        synchronized(adEvents) {
                            Regex("\"eventName\":\"([^\"]+)\"").findAll(sent).forEach { adEvents += it.groupValues[1] }
                        }
                        if (spoofClearsNativeAds) ads.remove("native")
                        if (spoofTailLiveNative) {
                            ads.remove("native")
                            tailLive += "native"
                        }
                        "[{\"data\":{\"recordAdEvent\":{\"__typename\":\"RecordAdEventPayload\"}}}]"
                    } else {
                        val type = Regex("\"playerType\":\"([^\"]+)\"").find(sent)!!.groupValues[1]
                        val platform = Regex("\"platform\":\"([^\"]+)\"").find(sent)!!.groupValues[1]
                        synchronized(tokenRequests) { tokenRequests += "$type/$platform" }
                        "{\"data\":{\"streamPlaybackAccessToken\":{\"value\":\"$type\",\"signature\":\"sig\",\"authorization\":{\"isForbidden\":false}}}}"
                    }
                }
                url.contains("usher.ttvnw.net/") -> master(req.url.queryParameter("token")!!)
                url.contains("/v1/playlist/") -> {
                    val session = url.substringAfterLast('/').substringBefore("-720")
                    fetched.merge(session, 1, Int::plus)
                    slow[session]?.let { Thread.sleep(it) }
                    playlist(session)
                }
                else -> error("unerwartet: $url")
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/vnd.apple.mpegurl".toMediaType())).build()
        }).build()
    }

    private val usher = "https://usher.ttvnw.net/api/channel/hls/streamer.m3u8?token=native&sig=x&supported_codecs=avc1"
    private val media = "https://video-weaver.example.hls.ttvnw.net/v1/playlist/native-720.m3u8"

    private fun player(tw: FakeTwitch, events: MutableList<String>, spoofing: () -> Boolean = { true }): TwitchAdBlock {
        val ab = TwitchAdBlock(tw.client, { ByteArray(188) }, spoofing) { e -> synchronized(events) { events += e.toString() } }
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
        assertTrue(tw.adEvents.toString(), "video_ad_pod_complete" in tw.adEvents)
        assertTrue(tw.tokenRequests.toString(), "picture-by-picture/ios" in tw.tokenRequests && "autoplay/android" in tw.tokenRequests)
    }

    @Test
    fun adSpoofingClearsNativeAdsBeforeBridgeFallback() {
        val tw = FakeTwitch()
        tw.spoofClearsNativeAds = true
        val events = ArrayList<String>()
        val ab = player(tw, events)
        refresh(ab)
        tw.ads += "native"
        tw.head++
        val p = refresh(ab)
        assertEquals("kein schwarzes Bild", 0, hold(p))
        assertTrue("bleibt auf der Hauptsitzung: ${p.segs.map { it.uri }}", p.segs.last().uri.contains("/native/"))
        assertFalse(p.segs.any { it.ad })
        assertTrue("Ad-Spoofing wurde gesendet: ${tw.adEvents}", "video_ad_impression" in tw.adEvents && "video_ad_pod_complete" in tw.adEvents)
        assertTrue("Keine Ersatz-Sitzung nötig, wenn Ad-Spoofing klappt", tw.tokenRequests.isEmpty())
        assertTrue(events.toString(), events.any { it.contains("AdSpoofed") })
        assertFalse(events.toString(), events.any { it.contains("Bridge(on=true)") })
    }

    @Test
    fun failedAdSpoofingIsNotRetriedOnEveryRefresh() {
        val tw = FakeTwitch()
        tw.spoofFails = true
        val events = ArrayList<String>()
        val ab = player(tw, events)
        refresh(ab)
        tw.ads += "native"
        tw.head++
        val p = refresh(ab)
        val calls = tw.spoofCalls
        assertTrue("einmal versucht (anonym)", calls >= 1)
        assertFalse("Werbung trotzdem weg (Ersatz-Sitzung)", p.segs.any { it.ad })
        assertFalse(events.toString(), events.any { it.contains("AdSpoofed") })
        refresh(ab)
        refresh(ab)
        assertEquals("gleiche Werbung nicht sofort wieder gemeldet", calls, tw.spoofCalls)
    }

    @Test
    fun adSpoofingSwitchedOffSendsNothing() {
        val tw = FakeTwitch()
        tw.spoofClearsNativeAds = true
        val events = ArrayList<String>()
        val ab = player(tw, events, spoofing = { false })
        refresh(ab)
        tw.ads += "native"
        tw.head++
        val p = refresh(ab)
        assertEquals(0, tw.spoofCalls)
        assertFalse(p.segs.any { it.ad })
    }

    @Test
    fun adSpoofingStripsRemainingWindowAdsWhenTailIsLive() {
        val tw = FakeTwitch()
        tw.spoofTailLiveNative = true
        val events = ArrayList<String>()
        val ab = player(tw, events)
        val first = refresh(ab)
        val prevLastSeq = first.segs.last().seq
        tw.ads += "native"
        tw.head += 2
        val p = refresh(ab)
        assertEquals("kein schwarzes Bild", 0, hold(p))
        assertTrue("bleibt auf der Hauptsitzung", p.segs.last().uri.contains("/native/"))
        assertFalse("alte Werbung im Fenster wurde herausgeschnitten", p.segs.any { it.ad })
        assertTrue("Keine Ersatz-Sitzung nötig", tw.tokenRequests.isEmpty())
        assertTrue("Nummern steigen weiter", p.segs.last().seq > prevLastSeq)
        for (i in 1 until p.segs.size) {
            assertEquals("lückenlose Nummerierung", p.segs[i - 1].seq + 1, p.segs[i].seq)
        }
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

    // ---- Nach der Werbung gleich zurück zur eigenen Sitzung (auch wenn die Werbung noch im Fenster steht)

    private val hq = listOf("popout", "frontpage", "mobile_web", "site")

    private fun isBridge(p: TwitchAdBlock.Media) = p.segs.last().uri.let { it.contains("/autoplay/") || it.contains("/picture-by-picture/") }

    /** Werbepause in der eigenen Sitzung und allen vollen Qualitäten: Der Player ist auf einer 360p-Brücke. */
    private fun onBridge(tw: FakeTwitch, events: MutableList<String>): Pair<TwitchAdBlock, Long> {
        val ab = player(tw, events)
        refresh(ab)
        tw.ads += listOf("native") + hq
        tw.head++
        val p = refresh(ab)
        assertTrue(p.segs.map { it.uri }.toString(), isBridge(p))
        return ab to p.segs.last().seq
    }

    @Test
    fun backToOwnSessionWhileTheAdIsStillInTheWindow() {
        val tw = FakeTwitch()
        val events = ArrayList<String>()
        val (ab, seq) = onBridge(tw, events)
        tw.ads -= "native"
        tw.tailLive += "native" // am Ende wieder Live-Stücke, davor noch die Werbung
        tw.head++
        val p = refresh(ab)
        assertTrue("eigene Sitzung statt weiter 360p: ${p.segs.map { it.uri }}", p.segs.last().uri.contains("/native/"))
        assertFalse("ohne die Werbestücke", p.segs.any { it.ad })
        assertFalse("und ohne die Werbe-Markierung", p.text.contains("stitched-ad"))
        assertEquals(0, hold(p))
        assertTrue("Nummern steigen weiter", p.segs.last().seq > seq)
        assertTrue(events.toString(), events.contains("AdEnd"))
        assertTrue("Brücken-Hinweis verschwindet: $events", events.any { it.contains("Bridge(on=false)") })
        // danach läuft es auf der eigenen Sitzung weiter
        tw.tailLive.clear()
        tw.ads.clear()
        var before = p.segs.last().seq
        repeat(3) {
            tw.head++
            val q = refresh(ab)
            assertTrue(q.segs.last().uri.contains("/native/"))
            assertTrue("Nummern steigen nur", q.segs.last().seq > before)
            before = q.segs.last().seq
        }
    }

    @Test
    fun staysOnTheBridgeUntilEnoughLiveSegmentsFollowTheAd() {
        val tw = FakeTwitch()
        val (ab, _) = onBridge(tw, ArrayList())
        tw.ads -= "native"
        tw.tailLive += "native"
        tw.liveTail = 1 // erst ein Live-Stück hinter der Werbung
        tw.head++
        val p = refresh(ab)
        assertTrue("bleibt vorerst auf der Brücke: ${p.segs.map { it.uri }}", isBridge(p))
        assertFalse(p.segs.any { it.ad })
        tw.liveTail = 2
        tw.head++
        val q = refresh(ab)
        assertTrue("mit zwei Live-Stücken zurück: ${q.segs.map { it.uri }}", q.segs.last().uri.contains("/native/"))
        assertFalse(q.segs.any { it.ad })
    }

    @Test
    fun onlyTheAdMarkerLeftAndTheAdTimeIsOver() {
        val tw = FakeTwitch()
        val ab = player(tw, ArrayList())
        refresh(ab)
        tw.tagsOnly += "native"
        tw.head++
        val p = refresh(ab)
        assertTrue(p.segs.last().uri.contains("/native/"))
        assertFalse("Markierung herausgefiltert", p.text.contains("stitched-ad"))
        assertTrue("keine Ersatz-Sitzung nötig: ${tw.tokenRequests}", tw.tokenRequests.isEmpty())
        assertEquals("alte Werbung wird nicht noch einmal gemeldet", 0, tw.spoofCalls)
    }

    @Test
    fun adMarkerWhoseTimeIsStillRunning() {
        val tw = FakeTwitch()
        tw.adSeconds = 100_000.0 // Werbung läuft laut Markierung noch – auch wenn kein Stück markiert ist
        val ab = player(tw, ArrayList())
        refresh(ab)
        tw.tagsOnly += "native"
        tw.head++
        val p = refresh(ab)
        assertFalse("vorsichtshalber nicht die eigene Sitzung", p.segs.last().uri.contains("/native/"))
        assertTrue(tw.tokenRequests.isNotEmpty())
    }

    @Test
    fun oldAdFurtherUpInTheWindowIsNotReportedAgain() {
        val tw = FakeTwitch()
        val ab = player(tw, ArrayList())
        refresh(ab)
        tw.tailLive += "native"
        tw.head++
        val p = refresh(ab)
        assertEquals(0, tw.spoofCalls)
        assertTrue(p.segs.last().uri.contains("/native/"))
        assertFalse(p.segs.any { it.ad })
    }

    @Test
    fun formatIsKnownWhileAnOldAdIsStillInTheWindow() {
        // fMP4-Stream, der mit einer Start-Werbung beginnt, die noch im Fenster steht: Kommt gleich die nächste
        // Werbung und ist nichts werbefrei, passen die schwarzen MPEG-TS-Ersatzstücke nicht – also abdecken
        val tw = FakeTwitch()
        tw.fmp4 = true
        tw.tailLive += "native"
        val events = ArrayList<String>()
        val ab = player(tw, events)
        assertTrue(refresh(ab).segs.last().uri.contains("/native/"))
        tw.tailLive.clear()
        tw.ads += listOf("native", "autoplay", "picture-by-picture") + hq
        tw.head++
        val p = refresh(ab)
        assertEquals("keine MPEG-TS-Ersatzstücke im fMP4-Stream", 0, hold(p))
        assertTrue(events.toString(), events.any { it.contains("Masked(hold=false)") })
    }

    // ---- Der Player darf nicht auf langsame Anfragen warten (sonst läuft sein Puffer leer und das Bild steht)

    @Test
    fun hangingBridgeDoesNotHoldUpThePlayer() {
        val tw = FakeTwitch()
        val ab = player(tw, ArrayList())
        refresh(ab)
        tw.ads += listOf("native") + hq
        tw.slow["autoplay"] = 3000
        tw.head++
        val t0 = System.nanoTime()
        val p = refresh(ab)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("Antwort nach $ms ms", ms < 2000)
        assertTrue("die andere Brücke übernimmt: ${p.segs.map { it.uri }}", p.segs.last().uri.contains("/picture-by-picture/"))
        assertEquals("kein schwarzes Bild", 0, hold(p))
        assertFalse(p.segs.any { it.ad })
        // Solange die hängende Anfrage läuft, wird diese Brücke nicht noch einmal gefragt
        tw.ads += "picture-by-picture"
        tw.head++
        refresh(ab)
        assertEquals(1, tw.fetched["autoplay"])
    }

    @Test
    fun slowAdReportDoesNotHoldUpThePlayer() {
        val tw = FakeTwitch()
        tw.slowSpoofMs = 3000
        val ab = player(tw, ArrayList())
        refresh(ab)
        tw.ads += listOf("native") + hq
        tw.head++
        val t0 = System.nanoTime()
        val p = refresh(ab)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("Antwort nach $ms ms", ms < 2700)
        assertEquals("gemeldet wird trotzdem (im Hintergrund)", 1, tw.spoofCalls)
        assertFalse("Werbung weg (Brücke)", p.segs.any { it.ad })
        assertEquals(0, hold(p))
        refresh(ab)
        assertEquals("dieselbe Werbung nicht doppelt gemeldet", 1, tw.spoofCalls)
    }

    private companion object {
        const val T0 = 1_792_872_000_000L
    }
}
