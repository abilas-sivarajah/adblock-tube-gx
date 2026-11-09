package de.abilas.gxtube.ui.watch

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import de.abilas.gxtube.data.Chapter
import de.abilas.gxtube.util.Fmt
import org.schabi.newpipe.extractor.stream.Frameset
import kotlin.math.abs
import kotlin.math.roundToInt

/** Raster der Vorschaubilder (YouTube-Storyboards) – ein paar Blätter zwischenspeichern. */
private object StoryboardCache {
    private val cache = LruCache<String, ImageBitmap>(12)

    suspend fun load(context: Context, url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        val image = result.image.toBitmap().asImageBitmap()
        cache.put(url, image)
        return image
    }
}

/** Das Raster mit Bildern um 160 px Breite (klein genug zum schnellen Laden, groß genug zum Erkennen). */
fun bestFrameset(frames: List<Frameset>?): Frameset? =
    frames.orEmpty().filter { it.urls.isNotEmpty() && it.frameWidth > 0 && it.totalCount > 0 }
        .minByOrNull { abs(it.frameWidth - 160) }

/** Kapitel an der Stelle [ms] (oder null vor dem ersten). */
fun chapterAt(chapters: List<Chapter>, ms: Long): Chapter? = chapters.lastOrNull { it.startMs <= ms }

/**
 * Vorschau über der Zeitleiste beim Spulen: Bild aus dem Storyboard, Kapitel und Zeit.
 * Steht über der Stelle, an der der Finger ist (am Rand nicht abgeschnitten).
 */
@Composable
fun SeekPreview(
    frames: Frameset?,
    chapters: List<Chapter>,
    positionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        var width by remember { mutableIntStateOf(0) }
        val total = with(density) { maxWidth.toPx() }
        val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val x = (fraction * total - width / 2f).coerceIn(0f, (total - width).coerceAtLeast(0f))
        Column(
            Modifier
                .offset { IntOffset(x.roundToInt(), 0) }
                .onSizeChanged { width = it.width },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (frames != null) StoryboardFrame(frames, positionMs)
            val chapter = chapterAt(chapters, positionMs)
            if (chapter != null) {
                Text(
                    chapter.title,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .widthIn(max = 220.dp),
                )
            }
            Text(
                Fmt.durationMs(positionMs),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun StoryboardFrame(frames: Frameset, positionMs: Long) {
    val context = LocalContext.current
    // [Blatt, links, oben, rechts, unten]
    val bounds = remember(frames, positionMs / frames.durationPerFrame.coerceAtLeast(1)) { frames.getFrameBoundsAt(positionMs) }
    val url = frames.urls.getOrNull(bounds[0])
    val image by produceState<ImageBitmap?>(null, url) { value = url?.let { StoryboardCache.load(context, it) } }
    val ratio = frames.frameWidth.toFloat() / frames.frameHeight.coerceAtLeast(1)
    Box(
        Modifier
            .size(width = 160.dp, height = (160 / ratio).dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black)
            .border(2.dp, Color.White, RoundedCornerShape(6.dp)),
    ) {
        val img = image
        if (img != null) {
            Canvas(Modifier.size(width = 160.dp, height = (160 / ratio).dp)) {
                drawImage(
                    img,
                    srcOffset = IntOffset(bounds[1], bounds[2]),
                    srcSize = IntSize((bounds[3] - bounds[1]).coerceAtLeast(1), (bounds[4] - bounds[2]).coerceAtLeast(1)),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
            }
        }
    }
}
