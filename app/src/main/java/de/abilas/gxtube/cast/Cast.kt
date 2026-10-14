package de.abilas.gxtube.cast

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import de.abilas.gxtube.BuildConfig
import de.abilas.gxtube.data.Http
import de.abilas.gxtube.data.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface CastConnection {
    data object Disconnected : CastConnection
    data class Connecting(val device: CastDevice) : CastConnection
    data class Connected(val device: CastDevice) : CastConnection
}

@Serializable
private data class CastPrefs(
    val deviceId: String = UUID.randomUUID().toString(),
    val lastDevice: CastDevice? = null,
    val autoReconnect: Boolean = false,
    val lastConnectedAt: Long = 0,
)

/**
 * Verbindung zu GX Tube Desktop – wie "Auf dem Fernseher abspielen" in der YouTube-App.
 * Hält genau eine WebSocket-Verbindung, spiegelt Zustand und Warteschlange des PCs
 * und schickt die Bedienung dorthin.
 */
object Cast {
    private const val TAG = "Cast"
    private const val WELCOME_TIMEOUT_MS = 6_000L
    private val RECONNECT_DELAYS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

    private lateinit var app: Application
    private lateinit var prefsFile: File
    private var prefs = CastPrefs()
    private val scope = MainScope()

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }

    private val _connection = MutableStateFlow<CastConnection>(CastConnection.Disconnected)
    val connection: StateFlow<CastConnection> = _connection.asStateFlow()

    private val _state = MutableStateFlow<RemoteState?>(null)
    /** Letzter Zustand vom PC (null = nicht verbunden). */
    val state: StateFlow<RemoteState?> = _state.asStateFlow()

    private val _queue = MutableStateFlow(RemoteQueue())
    val queue: StateFlow<RemoteQueue> = _queue.asStateFlow()

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Lautstärke am PC kurz einblenden (nach Lautstärketaste). */
    val volumeHint = MutableStateFlow<Int?>(null)

    val connected: Boolean get() = _connection.value is CastConnection.Connected
    val device: CastDevice?
        get() = when (val c = _connection.value) {
            is CastConnection.Connected -> c.device
            is CastConnection.Connecting -> c.device
            CastConnection.Disconnected -> null
        }
    val lastDevice: CastDevice? get() = prefs.lastDevice

    private var socket: WebSocket? = null
    private var generation = 0
    private var stateAt = 0L
    private var scanJob: Job? = null
    private var welcomeJob: Job? = null
    private var reconnectJob: Job? = null
    private var hintJob: Job? = null

    /**
     * Nach "Abspielen"/"Stopp" erwartetes Video (null = nichts). Bis der PC es meldet, werden
     * ältere Zustandsmeldungen ignoriert – sonst springt die Anzeige kurz aufs alte Video zurück.
     */
    private var expected: Pair<String?, Long>? = null

    private fun expect(videoId: String?) {
        expected = videoId to SystemClock.elapsedRealtime()
    }

    fun init(application: Application) {
        app = application
        prefsFile = File(app.filesDir, "cast.json")
        prefs = runCatching { CastProtocol.json.decodeFromString<CastPrefs>(prefsFile.readText()) }.getOrNull()
            ?: CastPrefs().also { savePrefs(it) }
        // Wie YouTube: kürzlich verbunden → still wieder verbinden (der PC spielt vielleicht noch)
        val last = prefs.lastDevice
        if (prefs.autoReconnect && last != null && System.currentTimeMillis() - prefs.lastConnectedAt < 12 * 3600_000L) {
            connect(last, silent = true)
        }
    }

    private fun savePrefs(p: CastPrefs) {
        prefs = p
        runCatching { prefsFile.writeText(CastProtocol.json.encodeToString(CastPrefs.serializer(), p)) }
    }

    // ---------------------------------------------------------------- Suchen

    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            _scanning.value = true
            val seen = mutableListOf<CastDevice>()
            try {
                val targets = withContext(Dispatchers.IO) { CastDiscovery.broadcastAddresses(app) }
                CastDiscovery.discover(targets) { d ->
                    scope.launch {
                        seen.removeAll { it.id == d.id }
                        seen += d
                        _devices.value = seen.toList()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Suche fehlgeschlagen", t)
            } finally {
                _devices.value = seen.toList()
                _scanning.value = false
            }
        }
    }

    // ---------------------------------------------------------------- Verbinden

    fun connect(device: CastDevice, silent: Boolean = false) {
        reconnectJob?.cancel()
        reconnectJob = null
        closeSocket()
        open(device) { ok, message ->
            if (ok) return@open
            if (silent) findMovedDevice(device) else {
                _connection.value = CastConnection.Disconnected
                _messages.tryEmit(message)
            }
        }
    }

    /** "Per Adresse verbinden": 192.168.1.5 oder 192.168.1.5:8765 */
    fun connectAddress(input: String) {
        val text = input.trim().removePrefix("http://").removePrefix("ws://").substringBefore('/')
        if (text.isBlank()) return
        val withPort = text.count { it == ':' } == 1
        val host = if (withPort) text.substringBefore(':') else text
        val port = if (withPort) text.substringAfter(':').toIntOrNull() ?: CastProtocol.DEFAULT_PORT else CastProtocol.DEFAULT_PORT
        val placeholder = CastDevice(id = "$host:$port", name = host, host = host, port = port)
        reconnectJob?.cancel()
        closeSocket()
        _connection.value = CastConnection.Connecting(placeholder)
        val gen = generation
        scope.launch {
            // Name und Kennung des PCs abfragen (GET http://<PC>:8765/)
            val info = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url("http://$host:$port/").build()
                    client.newBuilder().readTimeout(4, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
                        if (!r.isSuccessful) null else CastProtocol.json.decodeFromString<DeviceInfo>(r.body.string())
                    }
                }.getOrNull()
            }
            if (gen != generation) return@launch
            val device = if (info != null && info.id.isNotBlank()) {
                CastDevice(id = info.id, name = info.name.ifBlank { host }, host = host, port = port, version = info.version)
            } else placeholder
            connect(device)
        }
    }

    /**
     * Öffnet die Verbindung; [onResult] kommt genau einmal: true nach "welcome",
     * false (mit Grund), wenn es nicht klappt.
     */
    private fun open(device: CastDevice, onResult: (Boolean, String) -> Unit) {
        val gen = ++generation
        var done = false
        fun result(ok: Boolean, message: String) {
            if (done || gen != generation) return
            done = true
            welcomeJob?.cancel()
            onResult(ok, message)
        }
        _connection.value = CastConnection.Connecting(device)
        val host = if (device.host.contains(':')) "[${device.host}]" else device.host
        val request = runCatching { Request.Builder().url("ws://$host:${device.port}${CastProtocol.PATH}").build() }.getOrElse {
            result(false, "Ungültige Adresse")
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(CastProtocol.hello(phoneName(), prefs.deviceId, "GX Tube ${BuildConfig.VERSION_NAME}"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = CastProtocol.parse(text) ?: return
                scope.launch {
                    if (gen != generation) return@launch
                    if (msg is CastProtocol.Incoming.Welcome) {
                        onWelcome(device, msg.info)
                        result(true, "")
                    } else onIncoming(msg)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    if (gen != generation) return@launch
                    if (!done) result(false, "${device.name} hat die Verbindung abgelehnt")
                    // 4001 = am PC getrennt → nicht automatisch neu verbinden
                    else dropped(device, retry = code != 4001)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Verbindung zu ${device.address} fehlgeschlagen", t)
                scope.launch {
                    if (gen != generation) return@launch
                    if (!done) result(false, "${device.name} ist nicht erreichbar")
                    else dropped(device, retry = true)
                }
            }
        })
        welcomeJob?.cancel()
        welcomeJob = scope.launch {
            delay(WELCOME_TIMEOUT_MS)
            if (gen == generation && !done) {
                socket?.cancel()
                result(false, "${device.name} antwortet nicht")
            }
        }
    }

    private fun onWelcome(device: CastDevice, info: DeviceInfo) {
        val named = device.copy(
            id = info.id.ifBlank { device.id },
            name = info.name.ifBlank { device.name },
            version = info.version,
        )
        val announce = reconnectJob == null
        _connection.value = CastConnection.Connected(named)
        savePrefs(prefs.copy(lastDevice = named, autoReconnect = true, lastConnectedAt = System.currentTimeMillis()))
        if (announce) _messages.tryEmit("Verbunden mit ${named.name}")
    }

    private fun onIncoming(msg: CastProtocol.Incoming) {
        when (msg) {
            is CastProtocol.Incoming.State -> {
                expected?.let { (id, since) ->
                    when {
                        msg.state.video?.id == id -> expected = null
                        SystemClock.elapsedRealtime() - since < 5_000 -> return
                        else -> expected = null
                    }
                }
                stateAt = SystemClock.elapsedRealtime()
                _state.value = msg.state
            }
            is CastProtocol.Incoming.Queue -> _queue.value = msg.queue
            is CastProtocol.Incoming.Error -> Log.w(TAG, "PC meldet: ${msg.message}")
            is CastProtocol.Incoming.Welcome, CastProtocol.Incoming.Pong -> Unit
        }
    }

    /** Stilles Wiederverbinden gescheitert: Vielleicht hat der PC eine neue IP-Adresse. */
    private fun findMovedDevice(device: CastDevice) {
        val gen = generation
        scope.launch {
            val targets = withContext(Dispatchers.IO) { CastDiscovery.broadcastAddresses(app) }
            val same = runCatching { CastDiscovery.discover(targets, timeoutMs = 2000) }.getOrDefault(emptyList())
                .firstOrNull { it.id == device.id && it.address != device.address }
            if (gen != generation) return@launch
            if (same != null) {
                open(same) { ok, _ -> if (!ok) _connection.value = CastConnection.Disconnected }
            } else {
                _connection.value = CastConnection.Disconnected
            }
        }
    }

    /** Bestehende Verbindung abgebrochen (WLAN weg, PC beendet …): ein paar Mal neu versuchen. */
    private fun dropped(device: CastDevice, retry: Boolean) {
        socket = null
        if (!retry) {
            reset()
            savePrefs(prefs.copy(autoReconnect = false))
            _messages.tryEmit("${device.name} hat die Verbindung getrennt")
            return
        }
        if (reconnectJob?.isActive == true) return
        _connection.value = CastConnection.Connecting(device)
        reconnectJob = scope.launch {
            for (wait in RECONNECT_DELAYS) {
                delay(wait)
                val ok = CompletableDeferred<Boolean>()
                open(device) { success, _ -> ok.complete(success) }
                if (ok.await()) {
                    reconnectJob = null
                    return@launch
                }
                _connection.value = CastConnection.Connecting(device)
            }
            reconnectJob = null
            reset()
            _messages.tryEmit("Verbindung zu ${device.name} verloren")
        }
    }

    /** Verbindung beenden. [forget] = nicht automatisch wieder verbinden. */
    fun disconnect(forget: Boolean = true) {
        reconnectJob?.cancel()
        reconnectJob = null
        closeSocket()
        reset()
        if (forget) savePrefs(prefs.copy(autoReconnect = false))
    }

    private fun closeSocket() {
        generation++
        welcomeJob?.cancel()
        socket?.close(1000, "Getrennt")
        socket = null
    }

    private fun reset() {
        expected = null
        _connection.value = CastConnection.Disconnected
        _state.value = null
        _queue.value = RemoteQueue()
    }

    private fun phoneName(): String {
        val userName = runCatching { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return userName?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Handy"
    }

    // ---------------------------------------------------------------- Bedienung

    private fun send(text: String): Boolean {
        if (!connected) return false
        return socket?.send(text) == true
    }

    /** Zustand sofort anpassen, damit die Oberfläche nicht auf den PC warten muss. */
    private fun optimistic(change: (RemoteState) -> RemoteState) {
        _state.update { s ->
            s ?: return@update null
            val pos = positionNow()
            stateAt = SystemClock.elapsedRealtime()
            change(s.copy(positionMs = pos))
        }
    }

    fun play(video: VideoItem, queue: List<VideoItem> = emptyList(), startMs: Long? = null) {
        if (send(CastProtocol.play(video, queue, startMs))) {
            expect(video.id)
            _state.update {
                (it ?: RemoteState()).copy(
                    video = RemoteVideo(video.id, video.title, video.channelName, video.thumbnail, video.durationSec, video.isLive),
                    status = "loading",
                    positionMs = startMs ?: 0,
                    durationMs = (video.durationSec * 1000).coerceAtLeast(0),
                    error = null,
                    segments = emptyList(),
                )
            }
            stateAt = SystemClock.elapsedRealtime()
        }
    }

    fun add(video: VideoItem, next: Boolean) {
        if (send(CastProtocol.add(video, next))) {
            _messages.tryEmit(if (next) "Wird auf ${device?.name} als Nächstes abgespielt" else "Zur Warteschlange auf ${device?.name} hinzugefügt")
        }
    }

    fun togglePlay() {
        val s = _state.value ?: return
        when (s.status) {
            "ended" -> {
                send(CastProtocol.seek(0))
                resume()
            }
            "playing", "buffering", "loading" -> pause()
            else -> resume()
        }
    }

    fun pause() {
        if (send(CastProtocol.simple("pause"))) optimistic { it.copy(status = "paused") }
    }

    fun resume() {
        if (send(CastProtocol.simple("resume"))) optimistic { it.copy(status = "playing") }
    }

    fun seek(positionMs: Long) {
        if (send(CastProtocol.seek(positionMs))) optimistic { it.copy(positionMs = positionMs) }
    }

    fun seekBy(deltaMs: Long) {
        if (send(CastProtocol.seekBy(deltaMs))) optimistic { s ->
            val target = (s.positionMs + deltaMs).coerceAtLeast(0)
            s.copy(positionMs = if (s.durationMs > 0) target.coerceAtMost(s.durationMs) else target)
        }
    }

    fun next() = send(CastProtocol.simple("next"))
    fun previous() = send(CastProtocol.simple("previous"))
    fun stop() {
        if (send(CastProtocol.simple("stop"))) {
            expect(null)
            _state.update { it?.copy(video = null, status = "idle", positionMs = 0, durationMs = 0, segments = emptyList()) }
        }
    }
    fun clearQueue() = send(CastProtocol.simple("clearQueue"))
    fun jump(index: Int) = send(CastProtocol.jump(index))
    fun remove(index: Int) = send(CastProtocol.remove(index))
    fun setAutoplay(enabled: Boolean) = send(CastProtocol.autoplay(enabled))

    fun setSpeed(value: Float) {
        if (send(CastProtocol.speed(value))) optimistic { it.copy(speed = value) }
    }

    fun setVolume(value: Int) {
        if (send(CastProtocol.volume(value))) optimistic { it.copy(volume = value.coerceIn(0, 100), muted = false) }
    }

    fun setMuted(muted: Boolean) {
        if (send(CastProtocol.mute(muted))) optimistic { it.copy(muted = muted) }
    }

    /** Lautstärketasten am Handy: Lautstärke am PC ändern und kurz anzeigen. */
    fun volumeBy(delta: Int) {
        val s = _state.value ?: return
        val target = ((if (s.muted) 0 else s.volume) + delta).coerceIn(0, 100)
        if (send(CastProtocol.volume(target))) {
            optimistic { it.copy(volume = target, muted = false) }
            volumeHint.value = target
            hintJob?.cancel()
            hintJob = scope.launch {
                delay(1500)
                volumeHint.value = null
            }
        }
    }

    /** Aktuelle Position am PC – zwischen zwei Meldungen hochgerechnet. */
    fun positionNow(): Long {
        val s = _state.value ?: return 0
        if (s.status != "playing") return s.positionMs
        val p = s.positionMs + ((SystemClock.elapsedRealtime() - stateAt) * s.speed).toLong()
        return if (s.durationMs > 0) p.coerceAtMost(s.durationMs) else p
    }
}
