package de.abilas.gxtube.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import de.abilas.gxtube.MainActivity
import de.abilas.gxtube.cast.Cast
import de.abilas.gxtube.cast.CastPlayer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hält die Wiedergabe am Leben, wenn die App im Hintergrund ist, und zeigt die
 * Steuerung in Benachrichtigung und Sperrbildschirm. Beim Streamen auf den PC
 * steuert dieselbe Benachrichtigung den PC (inkl. Lautstärketasten).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var castPlayer: CastPlayer? = null
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        val open = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, PlayerController.player)
            .setSessionActivity(pending)
            .build()
        scope.launch { PlayerController.remote.collect { updatePlayer(it) } }
    }

    /** Lokaler Player oder Stellvertreter für den PC – je nachdem, wo das Video läuft. */
    private fun updatePlayer(remote: Boolean) {
        val s = session ?: return
        val wanted: Player = if (remote) castPlayer ?: CastPlayer().also { castPlayer = it } else PlayerController.player
        if (s.player !== wanted) s.player = wanted
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App aus den letzten Apps gewischt: Wiedergabe beenden (wie die YouTube-App).
        // Beim Streamen läuft das Video am PC weiter – nur die Verbindung wird getrennt.
        if (PlayerController.remote.value) Cast.disconnect(forget = false) else PlayerController.close()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        castPlayer?.release()
        castPlayer = null
        super.onDestroy()
    }
}
