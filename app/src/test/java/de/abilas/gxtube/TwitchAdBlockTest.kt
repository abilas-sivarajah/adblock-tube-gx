package de.abilas.gxtube

import de.abilas.gxtube.data.Http
import de.abilas.gxtube.twitch.Irc
import de.abilas.gxtube.twitch.ChatPart
import de.abilas.gxtube.twitch.TwitchAdBlock
import de.abilas.gxtube.twitch.TwitchApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Der Kotlin-Werbeblocker muss genau wie twitch_worker.js aus Adblock GX rechnen – die erwarteten
 * Werte stammen aus dem JavaScript (GX Tube Desktop, third_party/adblock-gx).
 */
class TwitchAdBlockTest {

    private val playlist = listOf(
        "#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:6", "#EXT-X-MEDIA-SEQUENCE:100", "#EXT-X-TWITCH-ELAPSED-SECS:200.0",
        "#EXT-X-PROGRAM-DATE-TIME:2026-10-24T20:00:00.000Z", "#EXTINF:2.000,live", "https://video-edge.ttvnw.net/v1/segment/a.ts",
        "#EXT-X-DATERANGE:ID=\"stitched-ad-1\",CLASS=\"twitch-stitched-ad\",START-DATE=\"2026-10-24T20:00:02.000Z\",DURATION=30.0",
        "#EXT-X-DISCONTINUITY", "#EXT-X-PROGRAM-DATE-TIME:2026-10-24T20:00:02.000Z", "#EXTINF:2.000,Amazon|123", "https://video-edge.ttvnw.net/v1/segment/ad1.ts",
        "#EXTINF:2.000,Amazon|123", "ad2.ts",
        "#EXT-X-DISCONTINUITY", "#EXT-X-PROGRAM-DATE-TIME:2026-10-24T20:00:06.000Z", "#EXTINF:2.000,live", "https://video-edge.ttvnw.net/v1/segment/b.ts",
        "#EXTINF:2.000,live", "https://video-edge.ttvnw.net/v1/segment/c.ts",
        "#EXT-X-TWITCH-PREFETCH:https://video-edge.ttvnw.net/v1/segment/d.ts",
    ).joinToString("\n")
    private val base = "https://video-weaver.fra05.hls.ttvnw.net/v1/playlist/x.m3u8"

    @Test
    fun parsesLikeTheJavaScript() {
        assertTrue(TwitchAdBlock.hasAds(playlist))
        val p = TwitchAdBlock.parseMedia(playlist, base)
        val segs = p.segs.map { listOf(it.seq, it.ds, it.disc, it.uri, it.dur, it.pdt, it.ad) }
        assertEquals(
            listOf(
                listOf(100L, 0L, false, "https://video-edge.ttvnw.net/v1/segment/a.ts", 2.0, 1792872000000L, false),
                listOf(101L, 1L, true, "https://video-edge.ttvnw.net/v1/segment/ad1.ts", 2.0, 1792872002000L, true),
                listOf(102L, 1L, false, "https://video-weaver.fra05.hls.ttvnw.net/v1/playlist/ad2.ts", 2.0, 1792872004000L, true),
                listOf(103L, 2L, true, "https://video-edge.ttvnw.net/v1/segment/b.ts", 2.0, 1792872006000L, false),
                listOf(104L, 2L, false, "https://video-edge.ttvnw.net/v1/segment/c.ts", 2.0, 1792872008000L, false),
            ),
            segs,
        )
        assertEquals(listOf("#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:6", "#EXT-X-MEDIA-SEQUENCE:", "#EXT-X-TWITCH-ELAPSED-SECS:200.0"), p.header)
        assertEquals(listOf("#EXT-X-TWITCH-PREFETCH:https://video-edge.ttvnw.net/v1/segment/d.ts"), p.tail)
        assertFalse(TwitchAdBlock.hasAds(playlist.lines().filterNot { TwitchAdBlock.isAdLine(it) || "Amazon" in it || "ad1" in it || "ad2" in it }.joinToString("\n")))
    }

    @Test
    fun rendersLikeTheJavaScript() {
        val p = TwitchAdBlock.parseMedia(playlist, base)
        val keep = p.segs.filter { !it.ad }.mapIndexed { i, s ->
            TwitchAdBlock.Entry(500L + i, s.ds, s.disc, s.map, s.key, s.seq, s.tags, s.uri, s.dur, s.pdt, s.ad, 0)
        }
        val expected = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:500\n#EXT-X-TWITCH-ELAPSED-SECS:200.0\n" +
            "#EXT-X-PROGRAM-DATE-TIME:2026-10-24T20:00:00.000Z\n#EXTINF:2.000,live\nhttps://video-edge.ttvnw.net/v1/segment/a.ts\n" +
            "#EXT-X-DISCONTINUITY\n#EXT-X-PROGRAM-DATE-TIME:2026-10-24T20:00:06.000Z\n#EXTINF:2.000,live\nhttps://video-edge.ttvnw.net/v1/segment/b.ts\n" +
            "#EXTINF:2.000,live\nhttps://video-edge.ttvnw.net/v1/segment/c.ts\n#EXT-X-TWITCH-PREFETCH:https://video-edge.ttvnw.net/v1/segment/d.ts\n"
        assertEquals(expected, TwitchAdBlock.render(p.header, keep, p.tail))
        // Ohne Werbung: die Live-Stücke nach der Werbung
        assertEquals(listOf(103L, 104L), TwitchAdBlock.liveRun(p.segs).map { it.seq })
    }

    @Test
    fun variantsAndBackupChoice() {
        val master = listOf(
            "#EXTM3U",
            "#EXT-X-STREAM-INF:BANDWIDTH=6951611,RESOLUTION=1920x1080,CODECS=\"avc1.64002A,mp4a.40.2\",VIDEO=\"chunked\",FRAME-RATE=60.000", "chunked.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=3422999,RESOLUTION=1280x720,CODECS=\"avc1.4D401F,mp4a.40.2\",VIDEO=\"720p60\",FRAME-RATE=60.000", "https://x/720p60.m3u8",
            "#EXT-X-STREAM-INF:BANDWIDTH=630000,RESOLUTION=640x360,CODECS=\"avc1.4D401F,mp4a.40.2\",VIDEO=\"360p30\",FRAME-RATE=30.000", "https://x/360p30.m3u8",
        ).joinToString("\n")
        val vs = TwitchAdBlock.parseVariants(master, "https://usher.ttvnw.net/api/channel/hls/x.m3u8")
        assertEquals(
            listOf(
                listOf("https://usher.ttvnw.net/api/channel/hls/chunked.m3u8", "1920x1080", 60.0, 1080, "avc"),
                listOf("https://x/720p60.m3u8", "1280x720", 60.0, 720, "avc"),
                listOf("https://x/360p30.m3u8", "640x360", 30.0, 360, "avc"),
            ),
            vs.map { listOf(it.uri, it.res, it.fps, it.height, it.family) },
        )
        val want = TwitchAdBlock.Variant("", "", "1280x720", 60.0, 720, "avc", "")
        assertEquals("https://x/360p30.m3u8", TwitchAdBlock.pickVariant(vs.drop(2), want)?.uri)
        assertEquals("https://x/720p60.m3u8", TwitchAdBlock.pickVariant(vs, want)?.uri)
    }

    @Test
    fun holdSegmentTimestampsLikeTheJavaScript() {
        val ts = File("src/main/assets/twitch_hold.ts").readBytes()
        fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        assertEquals("a48e871a79f913b1f43d92fe4fe8a1e8a83cdc2497d2d3be0a3b5ba4a9c6edd8", sha(TwitchAdBlock.shiftTimestamps(ts, 1)))
        assertEquals("433723d4720242ea247801c435013b9baf5843e06cc66d14068ac0930c818155", sha(TwitchAdBlock.shiftTimestamps(ts, 5)))
        assertEquals("0fb778de96c31e8088a1ecdfcf83b811bf953bb22fe55df397f9aa449bfd5d58", sha(TwitchAdBlock.shiftTimestamps(ts, 79999)))
        assertEquals("fa9fd4885acad11fd127979906431456d4cc9f6150cd9cca61885c37c48afcc1", sha(TwitchAdBlock.shiftTimestamps(ts, 123456)))
    }

    @Test
    fun twitchIds() {
        assertEquals("trymacs" to null, TwitchApi.target("twitch:trymacs"))
        assertEquals(null to "2906596560", TwitchApi.target("twitch-vod:2906596560"))
        assertEquals("v123" to null, TwitchApi.target("twitch:v123"))
        assertEquals(null, TwitchApi.target("dQw4w9WgXcQ"))
        assertEquals("trymacs", TwitchApi.loginOfChannelUrl("twitch:trymacs"))
        assertEquals(null, TwitchApi.loginOfChannelUrl("https://www.youtube.com/channel/UC123"))
        val url = TwitchApi.usherUrl("trymacs", null, "{\"a\":1}", "abc")
        assertTrue(url.startsWith("https://usher.ttvnw.net/api/channel/hls/trymacs.m3u8?"))
        assertTrue("supported_codecs=avc1" in url && "sig=abc" in url && "token=%7B%22a%22%3A1%7D" in url)
    }

    @Test
    fun chatLines() {
        val m = Irc.parse("@badges=moderator/1,subscriber/12;color=#1E90FF;display-name=Semi_58er;emotes=25:0-4,12-16;id=abc :semi_58er!semi_58er@semi_58er.tmi.twitch.tv PRIVMSG #trymacs :Kappa hallo Kappa")
        assertEquals("PRIVMSG", m.command)
        assertEquals("semi_58er", m.nick)
        assertEquals(listOf("#trymacs"), m.params)
        assertEquals("Kappa hallo Kappa", m.text)
        assertEquals(listOf("moderator", "subscriber"), Irc.badges(m.tags["badges"]))
        assertEquals("5 Monate dabei!", Irc.parse("@system-msg=5\\sMonate\\sdabei! :tmi.twitch.tv USERNOTICE #x").tags["system-msg"])
        assertEquals("tmi.twitch.tv", Irc.parse("PING :tmi.twitch.tv").text)
        val parts = Irc.parts("Kappa 😀 KEKW nice", "25:0-4", mapOf("KEKW" to "https://cdn.7tv.app/emote/1/2x.webp"))
        assertEquals(
            listOf(
                ChatPart.Emote("Kappa", "https://static-cdn.jtvnw.net/emoticons/v2/25/default/dark/2.0"),
                ChatPart.Text(" 😀 "),
                ChatPart.Emote("KEKW", "https://cdn.7tv.app/emote/1/2x.webp"),
                ChatPart.Text(" nice"),
            ),
            parts,
        )
        // Positionen zählen Zeichen (Code Points), nicht UTF-16-Einheiten
        assertEquals(ChatPart.Emote("Kappa", "https://static-cdn.jtvnw.net/emoticons/v2/25/default/dark/2.0"), Irc.parts("😀 Kappa", "25:2-6", emptyMap())[1])
    }

    /**
     * Wie ExoPlayer: Master-Playlist, dann alle 2 s die Media-Playlist – über eine Minute.
     * Die Nummern dürfen nur steigen, keine Werbung darf beim Player ankommen.
     * Nur mit GXTUBE_ONLINE_TESTS=1.
     */
    @Test
    fun liveStreamWithoutAds() = runBlocking {
        assumeTrue(System.getenv("GXTUBE_ONLINE_TESTS") == "1")
        val events = mutableListOf<String>()
        val ts = File("src/main/assets/twitch_hold.ts").readBytes()
        val ab = TwitchAdBlock(Http.client, { ts }) { e -> synchronized(events) { events += e.toString() } }
        val stream = TwitchApi.home("de").streams.first()
        val play = TwitchApi.playback(stream.id)
        val master = String(ab.handle(play.url)!!)
        assertFalse("nur Ton ist raus", master.contains("audio_only"))
        val variant = TwitchAdBlock.parseVariants(master, play.url).first { it.height in 480..720 }
        var lastSeq = -1L
        val seen = HashMap<Long, String>()
        val end = System.currentTimeMillis() + (System.getenv("GXTUBE_TWITCH_SECONDS")?.toLongOrNull() ?: 60) * 1000
        var refreshes = 0
        while (System.currentTimeMillis() < end) {
            val text = String(ab.handle(variant.uri)!!)
            val p = TwitchAdBlock.parseMedia(text, variant.uri)
            assertTrue("Playlist leer", p.segs.isNotEmpty())
            val first = p.segs.first().seq
            assertTrue("Nummern laufen rückwärts ($first < $lastSeq)", first >= lastSeq)
            for (s in p.segs) {
                assertFalse("Werbung beim Player: ${s.uri}", s.ad && !s.uri.startsWith(TwitchAdBlock.HOLD_URL))
                val before = seen.put(s.seq, s.uri)
                assertTrue("Stück ${s.seq} hat die Adresse gewechselt", before == null || before == s.uri)
            }
            lastSeq = first
            refreshes++
            println("[$refreshes] ${stream.twitchLogin} ${p.segs.first().seq}..${p.segs.last().seq} ds=${p.segs.last().ds} hold=${p.segs.count { it.uri.startsWith(TwitchAdBlock.HOLD_URL) }} ${synchronized(events) { events.joinToString(); }}")
            synchronized(events) { events.clear() }
            Thread.sleep(2000)
        }
        assertNotNull(variant)
    }
}
