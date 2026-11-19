package de.abilas.gxtube

import de.abilas.gxtube.twitch.ChatBadge
import de.abilas.gxtube.twitch.ChatCommands
import de.abilas.gxtube.twitch.ChatMe
import de.abilas.gxtube.twitch.ChatPart
import de.abilas.gxtube.twitch.Irc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chat: Erwähnungen, Links, Antworten, Abzeichen und /-Befehle. */
class TwitchChatTest {
    @Test
    fun mentionsAndLinks() {
        assertEquals(
            listOf(
                ChatPart.Mention("Trymacs"),
                ChatPart.Text(" schau mal "),
                ChatPart.Link("twitch.tv/papaplatte"),
                ChatPart.Text(", ok?"),
            ),
            Irc.parts("@Trymacs schau mal twitch.tv/papaplatte, ok?", null, emptyMap()),
        )
        // Satzzeichen hinter der Erwähnung bleiben Text
        assertEquals(listOf(ChatPart.Text("hi "), ChatPart.Mention("abc_12"), ChatPart.Text("!")), Irc.parts("hi @abc_12!", null, emptyMap()))
        assertEquals(listOf(ChatPart.Link("https://example.com/a?b=1")), Irc.parts("https://example.com/a?b=1", null, emptyMap()))
        // Normale Wörter mit Punkt sind keine Links
        assertEquals(listOf(ChatPart.Text("z.B. so")), Irc.parts("z.B. so", null, emptyMap()))
    }

    @Test
    fun badgesWithVersion() {
        assertEquals(listOf(ChatBadge("moderator", "1"), ChatBadge("subscriber", "3012")), Irc.badgeList("moderator/1,subscriber/3012"))
        assertEquals(emptyList<ChatBadge>(), Irc.badgeList(""))
        assertEquals(listOf("vip"), Irc.badges("vip/1"))
    }

    @Test
    fun replyShiftsEmotes() {
        // "@papaplatte Kappa" → ohne "@papaplatte " verschiebt sich das Emote um 12 Zeichen
        assertEquals("25:0-4", Irc.shiftEmotes("25:12-16", -12))
        assertEquals("25:0-4,6-10/1902:12-16", Irc.shiftEmotes("25:12-16,18-22/1902:24-28", -12))
        assertNull(Irc.shiftEmotes(null, -3))
        val m = Irc.parse("@reply-parent-msg-id=abc;reply-parent-user-login=papaplatte;reply-parent-display-name=Papaplatte;reply-parent-msg-body=hallo\\sleute :x!x@x PRIVMSG #p :@Papaplatte hi")
        assertEquals("hallo leute", m.tags["reply-parent-msg-body"])
    }

    @Test
    fun durations() {
        assertEquals(600, ChatCommands.parseDuration("10m"))
        assertEquals(3600, ChatCommands.parseDuration("1h"))
        assertEquals(86_400, ChatCommands.parseDuration("1d"))
        assertEquals(604_800, ChatCommands.parseDuration("1w"))
        assertEquals(45, ChatCommands.parseDuration("45"))
        assertEquals(30, ChatCommands.parseDuration("30s"))
        assertNull(ChatCommands.parseDuration("bald"))
        assertNull(ChatCommands.parseDuration(null))
    }

    @Test
    fun commandsByRole() {
        val viewer = ChatMe("ich", "1", "Ich", null, emptyList(), broadcaster = false)
        val mod = viewer.copy(badges = listOf(ChatBadge("moderator")))
        val streamer = viewer.copy(broadcaster = true)
        val forViewer = ChatCommands.suggestions("", viewer).map { it.name }
        assertTrue("me" in forViewer && "color" in forViewer && "w" in forViewer)
        assertTrue("ban" !in forViewer && "raid" !in forViewer)
        val forMod = ChatCommands.suggestions("", mod).map { it.name }
        assertTrue("ban" in forMod && "timeout" in forMod && "slow" in forMod && "raid" !in forMod)
        val forStreamer = ChatCommands.suggestions("", streamer).map { it.name }
        assertTrue("raid" in forStreamer && "mod" in forStreamer && "ban" in forStreamer)
        assertEquals(listOf("timeout"), ChatCommands.suggestions("time", mod).map { it.name })
        assertEquals("unban", ChatCommands.find("unbanuser")?.name)
        assertEquals("shoutout", ChatCommands.find("so")?.name)
        assertNull(ChatCommands.find("gibtsnicht"))
    }
}

/** Clips und Chat-Wiedergabe (VODs). */
class TwitchClipsReplayTest {
    private fun obj(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject
    private val online = System.getenv("GXTUBE_ONLINE_TESTS") == "1"

    @Test
    fun clipIds() {
        assertEquals("twitch-clip:UgliestCrepuscularDiscMcaT-Yj5cqJ_xhiFNc9yE", de.abilas.gxtube.twitch.TwitchApi.clipId("UgliestCrepuscularDiscMcaT-Yj5cqJ_xhiFNc9yE"))
        assertEquals("Abc-1_x", de.abilas.gxtube.twitch.TwitchApi.clipSlug("twitch-clip:Abc-1_x"))
        assertNull(de.abilas.gxtube.twitch.TwitchApi.clipSlug("twitch-clip:../böse"))
        assertTrue(de.abilas.gxtube.twitch.TwitchApi.isTwitch("twitch-clip:Abc-1_x"))
        assertNull("Clips haben weder Login noch VOD", de.abilas.gxtube.twitch.TwitchApi.target("twitch-clip:Abc-1_x"))
        val v = de.abilas.gxtube.data.VideoItem(id = "twitch-clip:Abc", title = "")
        assertTrue(v.isTwitch && v.isTwitchClip)
        assertEquals("https://clips.twitch.tv/Abc", v.url)
        val clip = de.abilas.gxtube.twitch.TwitchApi.clip(
            obj(
                """{"id":"1","slug":"Abc","title":"wow","viewCount":8701,"durationSeconds":43,"createdAt":"2026-11-17T17:29:00Z","thumbnailURL":"https://t/x.jpg",
                "game":{"name":"Just Chatting","displayName":"Just Chatting"},"broadcaster":{"login":"Papaplatte","displayName":"Papaplatte","profileImageURL":"https://a.png"}}""",
            ),
        )!!
        assertEquals("twitch-clip:Abc", clip.id)
        assertEquals("twitch:papaplatte", clip.channelUrl)
        assertEquals(43L, clip.durationSec.toLong())
        assertEquals(8701L, clip.viewCount)
    }

    @Test
    fun replayParse() {
        val page = de.abilas.gxtube.twitch.TwitchChatReplay.parse(
            obj(
                """{"edges":[
                  {"cursor":"c1","node":{"id":"a","contentOffsetSeconds":598,"commenter":{"login":"Max","displayName":"Max"},"message":{"fragments":[{"text":"hallo ","emote":null},{"text":"Kappa","emote":{"emoteID":"25"}},{"text":" KEKW @Moritz","emote":null}],"userBadges":[{"setID":"subscriber","version":"12"},{"setID":"","version":""}],"userColor":"#FF0000"}}},
                  {"cursor":"c2","node":{"id":"b","contentOffsetSeconds":604.5,"commenter":null,"message":{"fragments":[{"text":"\u0001ACTION tanzt\u0001","emote":null}],"userBadges":[],"userColor":null}}}
                ],"pageInfo":{"hasNextPage":true}}""",
            ),
            mapOf("KEKW" to "https://7tv/kekw"),
        )
        assertEquals("c2", page.next)
        val (a, b) = page.messages
        assertEquals(598, a.offset)
        assertEquals("max", a.message.login)
        assertEquals(listOf(de.abilas.gxtube.twitch.ChatBadge("subscriber", "12")), a.message.badges)
        assertEquals(
            listOf(
                ChatPart.Text("hallo "),
                ChatPart.Emote("Kappa", "https://static-cdn.jtvnw.net/emoticons/v2/25/default/dark/2.0"),
                ChatPart.Text(" "),
                ChatPart.Emote("KEKW", "https://7tv/kekw"),
                ChatPart.Text(" "),
                ChatPart.Mention("Moritz"),
            ),
            a.message.parts,
        )
        assertEquals(604, b.offset)
        assertTrue(b.message.action)
        assertEquals("tanzt", b.message.text)
    }

    /** Echte Abfragen an gql.twitch.tv (nur mit GXTUBE_ONLINE_TESTS=1). */
    @Test
    fun onlineClipsReplayBadges() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(online)
        val clips = de.abilas.gxtube.twitch.TwitchApi.clips("papaplatte", de.abilas.gxtube.twitch.ClipPeriod.ALL)
        assertTrue("Clips gefunden", clips.isNotEmpty())
        val slug = de.abilas.gxtube.twitch.TwitchApi.clipSlug(clips.first().id)!!
        val play = de.abilas.gxtube.twitch.TwitchApi.clipPlayback(slug)
        assertTrue(play.clip && play.url.contains("sig=") && play.url.contains("token="))
        val info = de.abilas.gxtube.twitch.TwitchApi.watchInfo(clips.first().id)
        assertEquals("papaplatte", info.login)
        // Datei antwortet (nur Anfang laden)
        de.abilas.gxtube.data.Http.client.newCall(okhttp3.Request.Builder().url(play.url).header("Range", "bytes=0-100").build()).execute().use {
            assertTrue("Clip-Datei: HTTP ${it.code}", it.code in 200..299)
        }
        val vod = de.abilas.gxtube.twitch.TwitchApi.channelPage("papaplatte").videos.items.firstOrNull()?.id?.removePrefix("twitch-vod:")
        if (vod != null) {
            val page = de.abilas.gxtube.twitch.TwitchChatReplay.page(vod, 600, null, emptyMap())
            assertTrue("Chat-Wiedergabe hat Nachrichten", page.messages.isNotEmpty())
            val next = de.abilas.gxtube.twitch.TwitchChatReplay.page(vod, null, page.next!!, emptyMap())
            assertTrue(next.messages.first().offset >= page.messages.first().offset)
        }
        val badges = de.abilas.gxtube.twitch.TwitchBadges.load("papaplatte")
        assertTrue(badges.keys.any { it.startsWith("moderator/") } && badges.keys.any { it.startsWith("subscriber/") })
        val user = de.abilas.gxtube.twitch.ChatUsers.load("trymacs", "papaplatte")!!
        assertEquals("Trymacs", user.name)
        assertTrue(user.createdAt != null)
    }
}
