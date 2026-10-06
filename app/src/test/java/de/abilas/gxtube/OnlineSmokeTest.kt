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
    }
}
