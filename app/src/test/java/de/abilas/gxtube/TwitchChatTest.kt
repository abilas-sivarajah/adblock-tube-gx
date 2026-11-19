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
