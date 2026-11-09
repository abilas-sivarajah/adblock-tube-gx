package de.abilas.gxtube

import de.abilas.gxtube.data.Http
import de.abilas.gxtube.data.Kiosk
import de.abilas.gxtube.data.ListEntry
import de.abilas.gxtube.data.NewPipeDownloader
import de.abilas.gxtube.data.SearchFilter
import de.abilas.gxtube.data.YouTubeRepo
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Prüft gegen das echte YouTube, ob Startseite, Suche und Video-Streams funktionieren.
 * Läuft nur mit GXTUBE_ONLINE_TESTS=1 (im GitHub-Build als eigener Schritt).
 * Gibt aus, was geht – schlägt nicht wegen einzelner YouTube-Sperren fehl.
 */
class OnlineSmokeTest {

    @Before
    fun setUp() {
        assumeTrue(System.getenv("GXTUBE_ONLINE_TESTS") == "1")
        NewPipe.init(NewPipeDownloader.instance, Localization("de", "DE"), ContentCountry("DE"))
    }

    private fun report(name: String, block: () -> String) {
        val result = runCatching(block)
        println("[$name] " + result.getOrElse { "FEHLER: ${it.javaClass.simpleName}: ${it.message}" })
    }

    @Test
    fun youtubeIsReachable() = runBlocking {
        Kiosk.entries.forEach { k ->
            report("Kiosk ${k.label}") {
                val page = runBlocking { YouTubeRepo.kiosk(k) }
                "${page.items.size} Videos, erstes: ${page.items.firstOrNull()?.title}"
            }
        }
        var firstVideoUrl: String? = null
        report("Suche") {
            val (_, page) = runBlocking { YouTubeRepo.search("nasa", SearchFilter.ALL) }
            val videos = page.items.filterIsInstance<ListEntry.Video>()
            firstVideoUrl = videos.firstOrNull()?.video?.url
            "${page.items.size} Treffer, davon ${videos.size} Videos, weitere Seite: ${page.loadMore != null}"
        }
        report("Vorschläge") { runBlocking { YouTubeRepo.suggestions("minecr") }.take(5).joinToString() }

        val url = firstVideoUrl ?: "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        report("Video $url") {
            val info = runBlocking { YouTubeRepo.stream(url) }
            val vo = info.videoOnlyStreams
            val au = info.audioStreams
            val stream = vo.firstOrNull { it.height in 1..720 } ?: info.videoStreams.firstOrNull()
            val status = stream?.let { s ->
                val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(s.content)) {
                    YoutubeParsingHelper.getVisionOsUserAgent(null)
                } else Http.USER_AGENT
                val req = okhttp3.Request.Builder()
                    .url(s.content + "&range=0-99999")
                    .header("User-Agent", ua)
                    .build()
                Http.client.newCall(req).execute().use { r -> "HTTP ${r.code}, ${r.body.bytes().size} Bytes" }
            }
            "\"${info.name}\": ${info.videoStreams.size} kombiniert, ${vo.size} nur Video " +
                "(${vo.map { it.resolution }.distinct().joinToString()}), ${au.size} Audio, " +
                "ähnliche: ${info.relatedItems.size}, Stream-Abruf: $status"
        }
        report("Kommentare") {
            val c = runBlocking { YouTubeRepo.comments(url) }
            "${c.page.items.size} geladen, insgesamt ${c.count}, deaktiviert: ${c.disabled}"
        }
        report("Kanal") {
            val ch = runBlocking { YouTubeRepo.channel("https://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ") }
            val tab = ch.tabs.firstOrNull()
            val n = tab?.let { runBlocking { YouTubeRepo.channelTab(it) }.items.size }
            "${ch.ref.name}, ${ch.ref.subscriberCount} Abos, Tabs ${ch.tabs.map { it.label }}, erster Tab $n Einträge"
        }
        report("RSS-Feed") {
            val f = runBlocking { YouTubeRepo.feed("https://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ") }
            "${f.size} Videos, neuestes: ${f.firstOrNull()?.title}"
        }
        // Ersatz ohne RSS und Shorts: Tab direkt aus der Kanal-ID (ohne Kanalseite vorab)
        report("Kanal-Videos (Ersatz für RSS)") {
            val v = runBlocking { YouTubeRepo.channelVideos("https://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ") }
            "${v.size} Videos, neuestes: ${v.firstOrNull()?.title}"
        }
        report("Kanal-Shorts") {
            val v = runBlocking { YouTubeRepo.channelShorts("https://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ") }
            "${v.size} Shorts"
        }
    }

    /** Kapitel, Untertitel (WebVTT abrufbar?) und Vorschaubilder eines Videos mit allem. */
    @Test
    fun chaptersCaptionsPreviews() = runBlocking {
        val url = "https://www.youtube.com/watch?v=ZAzWPE9CItg"
        val info = YouTubeRepo.stream(url)
        report("Kapitel") { "${info.streamSegments.size}: ${info.streamSegments.take(3).joinToString { "${it.startTimeSeconds}s ${it.title}" }}" }
        report("Untertitel") {
            val tracks = de.abilas.gxtube.player.StreamResolver.captionTracks(info)
            val first = tracks.firstOrNull()
            val body = first?.let { t ->
                Http.client.newCall(okhttp3.Request.Builder().url(t.url).header("User-Agent", Http.USER_AGENT).build()).execute().use { r -> "HTTP ${r.code}, ${r.body.string().take(30).replace('\n', ' ')}" }
            }
            "${tracks.size} Spuren (${tracks.joinToString { it.label }}), erste: $body; roh: ${info.subtitles.size} (${info.subtitles.take(6).joinToString { "${it.format}/${it.languageTag}/${it.isAutoGenerated}/${it.isUrl}" }})"
        }
        report("Vorschaubilder") {
            val f = de.abilas.gxtube.ui.watch.bestFrameset(info.previewFrames)
            val b = f?.getFrameBoundsAt(600_000)
            "${info.previewFrames.size} Raster, gewählt ${f?.frameWidth}x${f?.frameHeight}, bei 10:00 Blatt ${b?.get(0)} (${b?.drop(1)})"
        }
        report("SponsorBlock") {
            val sb = runBlocking { de.abilas.gxtube.data.SponsorBlock.info("vDmjw-c_R2k", setOf("sponsor", "selfpromo")) }
            "${sb.segments.size} Abschnitte, Highlight ${sb.highlightMs}, ganzes Video: ${sb.fullLabel}"
        }
    }
}
