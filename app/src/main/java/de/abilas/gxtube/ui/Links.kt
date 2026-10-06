package de.abilas.gxtube.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.platform.LocalContext
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.data.videoIdOf
import de.abilas.gxtube.player.PlayerController
import org.schabi.newpipe.extractor.stream.Description

private val TIME_PARAM = Regex("""[?&#]t=(\d+)""")

/**
 * Links in Beschreibungen/Kommentaren: YouTube-Links bleiben in GX Tube
 * (Zeitstempel springen im laufenden Video), alles andere öffnet den Browser.
 */
fun openLink(context: Context, nav: AppNav?, url: String) {
    val id = videoIdOf(url)
    val seconds = TIME_PARAM.find(url)?.groupValues?.get(1)?.toLongOrNull()
    val current = PlayerController.now.value?.video?.id
    when {
        id != null && id == current && seconds != null -> PlayerController.player.seekTo(seconds * 1000)
        id != null -> PlayerController.play(VideoItem(id = id, title = ""), startMs = seconds?.times(1000))
        nav != null && (url.contains("youtube.com/@") || url.contains("youtube.com/channel/") ||
            url.contains("youtube.com/c/")) -> nav.openChannel(url)
        nav != null && url.contains("youtube.com/playlist") -> nav.openPlaylist(url)
        else -> runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
fun rememberLinkListener(): LinkInteractionListener {
    val context = LocalContext.current
    val nav = LocalNav.current
    return LinkInteractionListener { link ->
        val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
        openLink(context, nav, url)
    }
}

fun Description.toAnnotated(linkColor: Color, listener: LinkInteractionListener?): AnnotatedString {
    val text = content()
    return when (type()) {
        Description.Type.HTML -> AnnotatedString.fromHtml(
            text,
            linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor)),
            linkInteractionListener = listener,
        )
        else -> AnnotatedString(text)
    }
}
