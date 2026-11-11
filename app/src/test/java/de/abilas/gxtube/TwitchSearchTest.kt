package de.abilas.gxtube

import de.abilas.gxtube.twitch.TwitchApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Twitch-Suche: Vorschläge beim Tippen und Ergebnisse (Antworten wie von gql.twitch.tv). */
class TwitchSearchTest {

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun suggestions() {
        val list = TwitchApi.suggestionsFrom(
            obj(
                """{"edges": [
                  {"node": {"text": "xRohat", "matchingCharacters": {"start": 0, "end": 1}, "content": {"__typename": "SearchSuggestionChannel", "isLive": true, "isVerified": true, "login": "xrohat", "profileImageURL": "https://x/50.png",
                    "user": {"id": "430023505", "login": "xrohat", "displayName": "xRohat", "profileImageURL": "https://x/70.png", "stream": {"title": "Charity", "viewersCount": 2208, "game": {"name": "Fortnite", "displayName": "Fortnite"}}}}}},
                  {"node": {"text": "MontanaBlack88", "matchingCharacters": {"start": 0, "end": 0}, "content": {"__typename": "SearchSuggestionChannel", "isLive": false, "isVerified": false, "login": "montanablack88", "profileImageURL": "https://x/m.png",
                    "user": {"id": "1", "login": "montanablack88", "displayName": "MontanaBlack88", "stream": null}}}},
                  {"node": {"text": "Minecraft", "matchingCharacters": {"start": 0, "end": 4}, "content": {"__typename": "SearchSuggestionCategory", "game": {"id": "27471", "name": "Minecraft", "displayName": "Minecraft", "viewersCount": 13642, "boxArtURL": "https://b"}}}},
                  {"node": {"text": "minecraft cape", "matchingCharacters": {"start": 0, "end": 99}, "content": null}},
                  {"node": {"text": "Minecraft Cape", "content": null}},
                  {"node": {"text": "  ", "content": null}},
                  {"node": {"text": "Kaputt", "content": {"__typename": "SearchSuggestionChannel", "login": "nicht gültig!"}}},
                  {"node": {"text": "Ohne Spiel", "content": {"__typename": "SearchSuggestionCategory", "game": null}}}
                ]}""",
            ),
        )
        assertEquals(listOf("xRohat", "MontanaBlack88", "Minecraft", "minecraft cape"), list.map { it.text })
        val (live, offline, cat, query) = list
        assertEquals(0 until 1, live.match)
        assertTrue(live.verified)
        assertEquals("xrohat", live.channel?.login)
        assertEquals("https://x/70.png", live.channel?.avatar)
        assertEquals("twitch:xrohat", live.channel?.live?.id)
        assertEquals(2208L, live.channel?.live?.viewCount)
        assertEquals("Fortnite", live.channel?.live?.game)
        assertNull(offline.channel?.live)
        assertFalse(offline.verified)
        assertNull("Leere Stelle → nichts hervorheben", offline.match)
        assertEquals("Minecraft", cat.game?.name)
        assertEquals(13642L, cat.game?.viewers)
        assertNull(query.channel)
        assertNull(query.game)
        assertNull("Stelle außerhalb des Texts → nichts hervorheben", query.match)
        assertEquals(emptyList<Any>(), TwitchApi.suggestionsFrom(null))
    }

    @Test
    fun search() {
        val r = TwitchApi.searchFrom(
            obj(
                """{
                  "channels": {"channels": {"cursor": "MTU=", "items": [
                    {"id": "1", "login": "papaplatte_afk", "displayName": "papaplatte_afk", "stream": null},
                    {"id": "2", "login": "fan", "displayName": "fan", "stream": {"viewersCount": 5}},
                    {"id": "3", "login": "papaplatte", "displayName": "Papaplatte", "stream": null},
                    {"id": "4", "login": "big", "displayName": "big", "stream": {"viewersCount": 900}}]}},
                  "more": {
                    "channelsWithTag": {"items": [
                      {"login": "a", "displayName": "A", "stream": {"viewersCount": 10, "freeformTags": [{"name": "Deutsch"}]}},
                      {"login": "b", "displayName": "B", "stream": {"viewersCount": 99, "freeformTags": [{"name": "German"}]}},
                      {"login": "offline", "stream": null}]},
                    "videos": {"items": [{"id": "42", "title": "VOD", "lengthSeconds": 60, "owner": {"login": "papaplatte", "displayName": "Papaplatte"}}, {"id": "kaputt"}]}},
                  "searchCategories": {"edges": [{"node": {"name": "Just Chatting", "displayName": "Just Chatting"}}]}
                }""",
            ),
            "deutsch",
        )
        assertEquals(listOf("big", "fan", "papaplatte_afk", "papaplatte"), r.channels.map { it.login })
        assertEquals("MTU=", r.next)
        assertEquals(listOf("Just Chatting"), r.games.map { it.name })
        assertEquals(listOf("twitch:b" to 99L, "twitch:a" to 10L), r.live.map { it.id to it.viewCount })
        assertEquals("Deutsch", r.tag)
        assertEquals(listOf("twitch-vod:42"), r.videos.map { it.id })
        assertEquals("twitch:papaplatte", r.videos.first().channelUrl)

        // Genau der gesuchte Kanal zuerst
        val exact = TwitchApi.searchFrom(obj("""{"channels": {"channels": {"cursor": "", "items": [{"login": "fan", "stream": {"viewersCount": 5}}, {"login": "papaplatte"}]}}}"""), "Papaplatte")
        assertEquals(listOf("papaplatte", "fan"), exact.channels.map { it.login })
        assertNull("Leerer Cursor → keine weitere Seite", exact.next)
        assertNull(exact.tag)
        assertTrue(exact.live.isEmpty() && exact.videos.isEmpty() && exact.games.isEmpty())
    }

    /** Gegen das echte Twitch – nur mit GXTUBE_ONLINE_TESTS=1. */
    @Test
    fun online() = runBlocking {
        assumeTrue(System.getenv("GXTUBE_ONLINE_TESTS") == "1")
        val sugg = TwitchApi.suggestions("montanabl")
        println("[vorschläge] " + sugg.joinToString(" | ") { (if (it.channel != null) "Kanal " else if (it.game != null) "Kategorie " else "") + it.text })
        assertTrue(sugg.any { it.channel?.login == "montanablack88" })
        assertTrue(TwitchApi.suggestions("just chatt").any { it.game?.name == "Just Chatting" })

        val r = TwitchApi.search("deutsch")
        println("[suche deutsch] ${r.channels.size} Kanäle, ${r.games.size} Kategorien, ${r.live.size} live (Tag ${r.tag}), ${r.videos.size} VODs")
        assertTrue(r.channels.isNotEmpty() && r.live.isNotEmpty())
        val next = r.next
        assertNotNull("Weitere Kanäle fehlen", next)
        val page = TwitchApi.searchChannels("deutsch", next!!)
        println("[weitere Kanäle] ${page.items.size}, weiter: ${page.next}")
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.items.none { c -> r.channels.any { it.login == c.login } })
    }
}
