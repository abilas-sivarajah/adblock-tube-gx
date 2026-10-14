package de.abilas.gxtube

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import de.abilas.gxtube.data.VideoItem
import de.abilas.gxtube.data.videoIdOf
import de.abilas.gxtube.player.PlayerController
import de.abilas.gxtube.player.PlayerMode
import de.abilas.gxtube.ui.AppRoot
import de.abilas.gxtube.ui.IntentRouter
import de.abilas.gxtube.ui.ShortsLaunch
import de.abilas.gxtube.update.Updater
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val inPip = mutableStateOf(false)

    /** Wie der Bildschirm gerade festgelegt ist (siehe [updateOrientation]) */
    private enum class OrientationLock { NONE, FULLSCREEN_LANDSCAPE, FULLSCREEN_PORTRAIT, SHORTS, UNTIL_UPRIGHT }
    private var orientationLock = OrientationLock.NONE
    private var orientationWatcher: OrientationEventListener? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pipListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePip()
            updateKeepScreenOn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AppRoot(inPip = inPip.value) }

        if (savedInstanceState == null) handleIntent(intent)
        askForNotifications()

        PlayerController.player.addListener(pipListener)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(PlayerController.now, PlayerController.mode) { _, _ -> }.collect {
                    updatePip()
                    updateKeepScreenOn()
                }
            }
        }
        lifecycleScope.launch {
            PlayerController.fullscreenRequests.collect { on -> updateOrientation(leavingFullscreen = !on) }
        }
        lifecycleScope.launch {
            // Shorts / neues Video im Vollbild – und nach einem Neuaufbau der Activity den Zustand wiederherstellen
            combine(PlayerController.mode, PlayerController.now) { _, _ -> }.collect { updateOrientation() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        orientationWatcher?.enable()
        PlayerController.onAppForeground()
        Updater.checkOnOpen(this, lifecycleScope)
    }

    override fun onStop() {
        super.onStop()
        // Im Hintergrund keine Lagesensoren; erzwungenes Hochformat freigeben
        if (orientationLock == OrientationLock.UNTIL_UPRIGHT) releaseOrientation() else orientationWatcher?.disable()
        val pip = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode
        PlayerController.onAppBackground(inPip = pip)
        PlayerController.saveProgress()
    }

    override fun onDestroy() {
        PlayerController.player.removeListener(pipListener)
        stopOrientationWatcher()
        super.onDestroy()
    }

    // ------------------------------------------------------------ Links aus anderen Apps

    private fun handleIntent(intent: Intent?) {
        // z. B. adb shell am start -n de.abilas.gxtube/.MainActivity --es gxtube_route subscriptions
        intent?.getStringExtra("gxtube_route")?.let {
            IntentRouter.pending.value = "route:$it"
            return
        }
        val text = intent?.dataString ?: intent?.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("""https?://\S+""").find(text)?.value ?: text.trim()
        val id = videoIdOf(url)
        val seconds = Regex("""[?&#]t=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
        val search = Regex("""[?&]search_query=([^&]+)""").find(url)?.groupValues?.get(1)
        when {
            search != null -> IntentRouter.pending.value = "search:" + Uri.decode(search.replace('+', ' '))
            url.contains("/playlist") && url.contains("list=") -> IntentRouter.pending.value = "playlist:$url"
            id != null && url.contains("/shorts/") && de.abilas.gxtube.data.Library.settings.shortsEnabled -> {
                ShortsLaunch.start.value = VideoItem(id = id, title = "", isShort = true)
                IntentRouter.pending.value = "shorts"
            }
            id != null -> PlayerController.play(VideoItem(id = id, title = ""), startMs = seconds?.times(1000))
            url.contains("/channel/") || url.contains("/@") || url.contains("/c/") || url.contains("/user/") ->
                IntentRouter.pending.value = "channel:$url"
        }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------ Vollbild / Drehen

    /**
     * Legt die Bildschirmausrichtung passend zum Zustand fest (wie in der YouTube-App):
     * - Vollbild-Knopf: quer (Hochkant-Videos: hoch). Wird das Handy danach quer und wieder
     *   hochkant gehalten, endet das Vollbild – sonst bliebe der Bildschirm quer hängen.
     * - Vollbild verlassen, während der Bildschirm quer ist: Hochformat erzwingen, bis das Handy
     *   wieder hochkant gehalten wird; danach dreht sich die App wieder frei.
     * - Shorts: immer hochkant.
     */
    private fun updateOrientation(leavingFullscreen: Boolean = false) {
        val mode = PlayerController.mode.value
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val target = when {
            PlayerController.fullscreen.value && mode == PlayerMode.WATCH -> {
                // Während das nächste Video lädt, bleibt es beim bisherigen Format
                val wide = PlayerController.now.value?.takeIf { !it.loading && it.error == null }
                    ?.let { it.aspect >= 1f } ?: (orientationLock != OrientationLock.FULLSCREEN_PORTRAIT)
                if (wide) OrientationLock.FULLSCREEN_LANDSCAPE else OrientationLock.FULLSCREEN_PORTRAIT
            }
            mode == PlayerMode.SHORTS -> OrientationLock.SHORTS
            leavingFullscreen && landscape && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ->
                OrientationLock.UNTIL_UPRIGHT
            orientationLock == OrientationLock.UNTIL_UPRIGHT -> OrientationLock.UNTIL_UPRIGHT
            else -> OrientationLock.NONE
        }
        if (target == orientationLock) return
        orientationLock = target
        stopOrientationWatcher()
        when (target) {
            OrientationLock.FULLSCREEN_LANDSCAPE -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                var turnedSideways = false
                watchOrientation { angle ->
                    if (isSideways(angle)) {
                        turnedSideways = true
                    } else if (turnedSideways && isUpright(angle) && autoRotateEnabled()) {
                        turnedSideways = false
                        PlayerController.requestFullscreen(false)
                    }
                }
            }
            OrientationLock.FULLSCREEN_PORTRAIT, OrientationLock.SHORTS ->
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLock.UNTIL_UPRIGHT -> {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                watchOrientation { angle -> if (isUpright(angle)) releaseOrientation() }
                if (orientationWatcher == null) releaseOrientation()
            }
            OrientationLock.NONE -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun releaseOrientation() {
        stopOrientationWatcher()
        orientationLock = OrientationLock.NONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** Lage des Handys (0° = hochkant, 90°/270° = quer), unabhängig davon, wie der Bildschirm gerade steht. */
    private fun watchOrientation(onAngle: (Int) -> Unit) {
        val watcher = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) onAngle(orientation)
            }
        }
        if (!watcher.canDetectOrientation()) return
        orientationWatcher = watcher
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) watcher.enable()
    }

    private fun stopOrientationWatcher() {
        orientationWatcher?.disable()
        orientationWatcher = null
    }

    private fun isUpright(angle: Int) = angle < 30 || angle > 330
    private fun isSideways(angle: Int) = angle in 60..120 || angle in 240..300

    /** "Automatisch drehen" in den Android-Einstellungen an? Sonst bleibt das Vollbild quer, wie bei YouTube. */
    private fun autoRotateEnabled(): Boolean =
        Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    // ------------------------------------------------------------ Bild-im-Bild

    private fun pipWanted(): Boolean {
        val n = PlayerController.now.value ?: return false
        return de.abilas.gxtube.data.Library.settings.pipOnLeave &&
            PlayerController.mode.value == PlayerMode.WATCH &&
            !n.loading && n.error == null &&
            PlayerController.player.isPlaying
    }

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val aspect = (PlayerController.now.value?.aspect ?: (16f / 9f)).coerceIn(0.42f, 2.38f)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((aspect * 1000).toInt(), 1000))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(pipWanted())
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    /** Bildschirm anlassen, solange ein Video läuft (bei Pause darf das Handy wieder sperren). */
    private fun updateKeepScreenOn() {
        val playing = PlayerController.player.isPlaying && PlayerController.mode.value != PlayerMode.NONE
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updatePip() {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching { pipParams()?.let { setPictureInPictureParams(it) } }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && pipWanted()) {
            runCatching { pipParams()?.let { enterPictureInPictureMode(it) } }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            // Bild-im-Bild-Fenster wurde geschlossen
            PlayerController.player.pause()
        }
    }
}
