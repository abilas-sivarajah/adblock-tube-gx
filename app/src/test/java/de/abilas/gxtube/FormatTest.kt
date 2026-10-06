package de.abilas.gxtube

import de.abilas.gxtube.data.HomeFeed
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.data.filterShorts
import de.abilas.gxtube.data.channelKey
import de.abilas.gxtube.data.videoIdOf
import de.abilas.gxtube.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {

    @Test
    fun duration() {
        assertEquals("0:05", Fmt.duration(5))
        assertEquals("12:34", Fmt.duration(754))
        assertEquals("1:02:03", Fmt.duration(3723))
        assertEquals("", Fmt.duration(-1))
    }

    @Test
    fun counts() {
        assertEquals("999", Fmt.count(999))
        assertEquals("9999", Fmt.count(9_999))
        assertEquals("12.345", Fmt.count(12_345))
        assertEquals("1,2 Mio.", Fmt.count(1_234_567))
        assertEquals("2 Mio.", Fmt.count(2_000_000))
        assertEquals("345 Mio.", Fmt.count(345_000_000))
        assertEquals("3,4 Mrd.", Fmt.count(3_400_000_000))
        assertEquals("1 Aufruf", Fmt.views(1))
        assertEquals("1,5 Mio. Aufrufe", Fmt.views(1_500_000))
    }

    @Test
    fun ago() {
        val now = System.currentTimeMillis()
        assertEquals("vor 1 Tag", Fmt.ago(now - 86_400_000L - 1000))
        assertEquals("vor 3 Tagen", Fmt.ago(now - 3 * 86_400_000L - 1000))
        assertEquals("vor 2 Stunden", Fmt.ago(now - 2 * 3_600_000L - 1000))
        assertEquals("vor 1 Jahr", Fmt.ago(now - 400 * 86_400_000L))
        assertEquals("gestern live", Fmt.ago(null, "gestern live"))
    }

    @Test
    fun videoIds() {
        assertEquals("dQw4w9WgXcQ", videoIdOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))
        assertEquals("dQw4w9WgXcQ", videoIdOf("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("dQw4w9WgXcQ", videoIdOf("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", videoIdOf("https://m.youtube.com/live/dQw4w9WgXcQ"))
        assertNull(videoIdOf("https://www.youtube.com/@kanal"))
    }

    @Test
    fun channelKeys() {
        assertEquals(
            "UCuAXFkgsw1L7xaCfnd5JJOw",
            channelKey("https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw/videos"),
        )
        assertEquals(channelKey("https://www.youtube.com/@Kanal/"), channelKey("https://www.youtube.com/@kanal"))
    }

    @Test
    fun interleave() {
        val mixed = HomeFeed.interleave(listOf(listOf(1, 2, 3, 4), listOf(10, 20)), weights = listOf(2, 1))
        assertEquals(listOf(1, 2, 10, 3, 4, 20), mixed)
    }

    @Test
    fun shortsFilter() {
        val normal = VideoItem(id = "aaaaaaaaaaa", title = "Langes Video", durationSec = 600)
        val short = VideoItem(id = "bbbbbbbbbbb", title = "Kurz", isShort = true)
        val tagged = VideoItem(id = "ccccccccccc", title = "Witzig #Shorts", durationSec = 45)
        val list = listOf(normal, short, tagged)
        assertEquals(list, list.filterShorts(enabled = true))
        assertEquals(listOf(normal), list.filterShorts(enabled = false))
    }
}
