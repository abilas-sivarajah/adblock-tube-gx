package de.abilas.gxtube

import de.abilas.gxtube.player.StreamResolver
import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.VideoStream

class StreamQualityTest {

    private fun stream(itag: Int, resolution: String, width: Int, height: Int): VideoStream {
        val item = ItagItem.getItag(itag).apply {
            setWidth(width)
            setHeight(height)
        }
        return VideoStream.Builder()
            .setId(itag.toString())
            .setContent("https://example.invalid/$itag", true)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setIsVideoOnly(true)
            .setResolution(resolution)
            .setItagItem(item)
            .build()
    }

    @Test
    fun qualityLikeYouTube() {
        // Querformat
        assertEquals(1080, StreamResolver.qualityOf(stream(137, "1080p", 1920, 1080)))
        // Hochkant (Shorts): 1080 × 1920 ist "1080p", nicht "1920p"
        assertEquals(1080, StreamResolver.qualityOf(stream(137, "1080p", 1080, 1920)))
        assertEquals(720, StreamResolver.qualityOf(stream(298, "720p60", 720, 1280)))
        // Ohne Bezeichnung: kürzere Seite
        assertEquals(480, StreamResolver.qualityOf(stream(135, "", 480, 854)))
    }
}
