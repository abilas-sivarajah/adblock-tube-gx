package de.abilas.gxtube

import de.abilas.gxtube.data.DeArrow
import de.abilas.gxtube.data.SponsorBlock
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SponsorBlock-Extras (Stummschalten, Highlight, ganzes Video, Kapitel) und DeArrow – ohne Netz. */
class YouTubeExtrasTest {

    private val id = "dQw4w9WgXcQ"

    @Test
    fun sponsorBlockSkipMuteHighlightFullChapters() {
        val json = """[{"videoID":"$id","segments":[
            {"segment":[10,20],"category":"sponsor","actionType":"skip","UUID":"a"},
            {"segment":[30,40],"category":"music_offtopic","actionType":"mute","UUID":"b"},
            {"segment":[50,60],"category":"intro","actionType":"skip","UUID":"c"},
            {"segment":[95.5,95.5],"category":"poi_highlight","actionType":"poi","UUID":"d"},
            {"segment":[0,0],"category":"selfpromo","actionType":"full","UUID":"e"},
            {"segment":[0,100],"category":"chapter","actionType":"chapter","description":"Anfang","UUID":"f"},
            {"segment":[100,200],"category":"chapter","actionType":"chapter","description":"Ende","UUID":"g"}
        ]},{"videoID":"anderesVid1","segments":[{"segment":[1,2],"category":"sponsor","actionType":"skip","UUID":"x"}]}]"""
        val info = SponsorBlock.parse(json, id, setOf("sponsor", "music_offtopic"))
        assertEquals(listOf("sponsor" to false, "music_offtopic" to true), info.segments.map { it.category to it.isMute })
        assertEquals(10_000L, info.segments[0].startMs)
        assertEquals(95_500L, info.highlightMs)
        assertEquals("Eigenwerbung", info.fullLabel)
        assertEquals(listOf("Anfang" to 0L, "Ende" to 100_000L), info.chapters.map { it.title to it.startMs })
    }

    @Test
    fun sponsorBlockWithoutDataIsEmpty() {
        val info = SponsorBlock.parse("[]", id, setOf("sponsor"))
        assertTrue(info.segments.isEmpty())
        assertNull(info.highlightMs)
        assertNull(info.fullLabel)
        assertTrue(info.chapters.isEmpty())
    }

    @Test
    fun deArrowOnlyTrustedCommunityTitlesAndThumbnails() {
        val entry = Json.parseToJsonElement(
            """{"titles":[{"title":"Original","original":true,"votes":5},{"title":">  Besserer  Titel","original":false,"votes":0}],
               "thumbnails":[{"timestamp":12.5,"original":false,"votes":1}]}""",
        )
        val b = DeArrow.parse(entry, id)!!
        assertEquals("Besserer Titel", b.title)
        assertEquals("https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=$id&time=12.5", b.thumbnail)
        // Schlecht bewertet und nur Originale: nichts
        val bad = Json.parseToJsonElement("""{"titles":[{"title":"Clickbait","original":false,"votes":-1}],"thumbnails":[{"original":true,"votes":3}]}""")
        assertNull(DeArrow.parse(bad, id))
        // Gesperrt (vom Team bestätigt) zählt auch bei negativen Stimmen
        val locked = Json.parseToJsonElement("""{"titles":[{"title":"Gesperrt","original":false,"votes":-2,"locked":true}]}""")
        assertEquals("Gesperrt", DeArrow.parse(locked, id)?.title)
        assertNull(DeArrow.parse(null, id))
    }
}
