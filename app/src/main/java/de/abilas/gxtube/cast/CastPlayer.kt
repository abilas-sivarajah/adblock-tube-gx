package de.abilas.gxtube.cast

import android.net.Uri
import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Stellvertreter-Player für die Benachrichtigung und den Sperrbildschirm, solange auf den PC
 * gestreamt wird: zeigt, was am PC läuft, und schickt Abspielen/Pause/Weiter dorthin.
 * Als "Fernwiedergabe" gemeldet – dadurch steuern die Lautstärketasten die Lautstärke am PC,
 * auch bei gesperrtem Bildschirm (wie bei der YouTube-App).
 */
@UnstableApi
class CastPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = MainScope()

    init {
        scope.launch {
            combine(Cast.state, Cast.connection) { _, _ -> }.collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val s = Cast.state.value
        val device = Cast.device
        val commands = Player.Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_STOP,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_METADATA,
                COMMAND_GET_TIMELINE,
                COMMAND_GET_DEVICE_VOLUME,
                COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
        if (s?.video != null && !s.isLive) {
            commands.addAll(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD)
        }
        if (s?.video != null) commands.addAll(COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (s?.hasNext == true) commands.addAll(COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)

        val builder = State.Builder()
            .setAvailableCommands(commands.build())
            .setDeviceInfo(
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                    .setMinVolume(0)
                    .setMaxVolume(100)
                    .build(),
            )
            .setDeviceVolume(s?.volume ?: 100)
            .setIsDeviceMuted(s?.muted ?: false)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)

        val video = s?.video ?: return builder.setPlaybackState(STATE_IDLE).build()

        val item = MediaItem.Builder()
            .setMediaId(video.id)
            .setUri("https://www.youtube.com/watch?v=${video.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(video.title.ifBlank { "Video" })
                    .setArtist(video.channelName)
                    .setSubtitle(device?.let { "Auf ${it.name}" })
                    .setArtworkUri(video.thumbnail?.let { Uri.parse(it) })
                    .build(),
            )
            .build()
        val durationUs = if (s.durationMs > 0 && !s.isLive) s.durationMs * 1000 else androidx.media3.common.C.TIME_UNSET
        val data = MediaItemData.Builder(video.id)
            .setMediaItem(item)
            .setDurationUs(durationUs)
            .setIsSeekable(!s.isLive)
            .setIsDynamic(s.isLive)
            .setIsPlaceholder(false)
            .build()
        val playbackState = when (s.status) {
            "loading", "buffering" -> STATE_BUFFERING
            "ended" -> STATE_ENDED
            "idle", "error" -> STATE_IDLE
            else -> STATE_READY
        }
        return builder
            .setPlaylist(listOf(data))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(s.playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs { Cast.positionNow() }
            .setPlaybackParameters(androidx.media3.common.PlaybackParameters(s.speed))
            .build()
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) Cast.resume() else Cast.pause()
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> Cast.next()
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> Cast.previous()
            COMMAND_SEEK_BACK -> Cast.seekBy(-10_000)
            COMMAND_SEEK_FORWARD -> Cast.seekBy(10_000)
            else -> Cast.seek(positionMs)
        }
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        Cast.stop()
        return done()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        Cast.setVolume(deviceVolume)
        return done()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        Cast.volumeBy(5)
        return done()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        Cast.volumeBy(-5)
        return done()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        Cast.setMuted(muted)
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return done()
    }
}
