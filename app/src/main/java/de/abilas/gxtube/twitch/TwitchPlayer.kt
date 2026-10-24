package de.abilas.gxtube.twitch

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import de.abilas.gxtube.data.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Werbepause im laufenden Twitch-Stream (für die Einblendung im Player). */
data class TwitchAdState(
    /** Werbung wird gerade ausgeblendet: schwarzes Bild (hold) oder stumm darunter (masked ohne hold) */
    val masked: Boolean = false,
    val hold: Boolean = false,
    /** Übergangsweise die 360p-Sitzung ohne Werbung */
    val bridge: Boolean = false,
    /** Werbepausen, die seit dem Start des Streams ausgeblendet wurden */
    val blocked: Int = 0,
)

/** Twitch-Wiedergabe für ExoPlayer: HLS über den Werbeblocker. */
@UnstableApi
object TwitchPlayer {
    private lateinit var adBlock: TwitchAdBlock

    private val _ads = MutableStateFlow(TwitchAdState())
    val ads: StateFlow<TwitchAdState> = _ads.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        adBlock = TwitchAdBlock(Http.client, holdTs = { app.assets.open("twitch_hold.ts").use { it.readBytes() } }) { e ->
            _ads.update { s ->
                when (e) {
                    TwitchAdBlock.Event.AdStart -> s.copy(blocked = s.blocked + 1)
                    TwitchAdBlock.Event.AdEnd -> s.copy(masked = false, hold = false, bridge = false)
                    is TwitchAdBlock.Event.Masked -> s.copy(masked = true, hold = e.hold)
                    TwitchAdBlock.Event.Unmasked -> s.copy(masked = false, hold = false)
                    is TwitchAdBlock.Event.Bridge -> s.copy(bridge = e.on)
                    is TwitchAdBlock.Event.BackupUsed -> s
                }
            }
        }
    }

    /** Neuer Stream: Zähler und Einblendung zurücksetzen. */
    fun reset() {
        _ads.value = TwitchAdState()
    }

    fun source(url: String, item: MediaItem): MediaSource {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        return HlsMediaSource.Factory(TwitchDataSource.Factory(upstream, adBlock))
            .createMediaSource(item.buildUpon().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
    }

    /** Qualitätsstufen (Höhe, Bildrate) aus der Master-Playlist des Streams. */
    fun variants(login: String?, vod: String?): List<Pair<Int, Int>> {
        val key = if (vod != null) "vod:$vod" else "live:$login"
        return adBlock.variants(key).filter { it.height > 0 }
            .map { it.height to it.fps.toInt() }
            .distinctBy { it.first }
            .sortedByDescending { it.first }
    }

    fun onStalled() = adBlock.onStalled()
}
