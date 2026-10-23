package de.abilas.gxtube.cast

import de.abilas.gxtube.data.VideoItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Streaming-Protokoll zu GX Tube Desktop (Version 1).
 * Beschreibung: https://github.com/abilas-sivarajah/adblock-tube-gx-desktop/blob/main/PROTOCOL.md
 */
object CastProtocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 8765
    const val DISCOVERY_PORT = 8766
    const val PATH = "/gxtube"

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    // ---------------------------------------------------------------- Handy → PC

    fun hello(name: String, deviceId: String, app: String) = msg("hello") {
        put("name", name.take(80))
        put("deviceId", deviceId)
        put("app", app)
        put("protocol", VERSION)
    }

    fun play(video: VideoItem, queue: List<VideoItem>, startMs: Long?) = msg("play") {
        put("video", videoJson(video))
        if (queue.isNotEmpty()) put("queue", buildJsonArray { queue.take(500).forEach { add(videoJson(it)) } })
        if (startMs != null && startMs > 0) put("startMs", startMs)
    }

    fun add(video: VideoItem, next: Boolean) = msg("add") {
        put("video", videoJson(video))
        put("next", next)
    }

    fun simple(type: String) = msg(type) {}
    fun seek(positionMs: Long) = msg("seek") { put("positionMs", positionMs.coerceAtLeast(0)) }
    fun seekBy(deltaMs: Long) = msg("seekBy") { put("deltaMs", deltaMs) }
    fun volume(value: Int) = msg("volume") { put("value", value.coerceIn(0, 100)) }
    fun volumeBy(delta: Int) = msg("volumeBy") { put("delta", delta) }
    fun mute(muted: Boolean) = msg("mute") { put("muted", muted) }
    fun speed(value: Float) = msg("speed") { put("value", value) }
    fun jump(index: Int) = msg("jump") { put("index", index) }
    fun remove(index: Int) = msg("remove") { put("index", index) }
    fun autoplay(enabled: Boolean) = msg("autoplay") { put("enabled", enabled) }

    /** Vollbild am PC an/aus; null = umschalten. */
    fun fullscreen(on: Boolean?) = msg("fullscreen") { if (on != null) put("on", on) }

    /** Fenster am PC auf diesen Bildschirm verschieben (IDs aus [RemoteState.displays]). */
    fun display(id: Long) = msg("display") { put("id", id) }

    private fun videoJson(v: VideoItem) = buildJsonObject {
        put("id", v.id)
        put("title", v.title)
        put("channelName", v.channelName)
        v.thumbnail?.let { put("thumbnail", it) }
        put("durationSec", v.durationSec)
        put("isLive", v.isLive)
    }

    private inline fun msg(type: String, body: JsonObjectBuilder.() -> Unit): String =
        buildJsonObject {
            put("type", type)
            body()
        }.toString()

    // ---------------------------------------------------------------- PC → Handy

    sealed interface Incoming {
        data class Welcome(val info: DeviceInfo) : Incoming
        data class State(val state: RemoteState) : Incoming
        data class Queue(val queue: RemoteQueue) : Incoming
        data class Error(val message: String) : Incoming
        data object Pong : Incoming
    }

    fun parse(text: String): Incoming? = runCatching {
        val obj = json.parseToJsonElement(text).jsonObject
        when (obj.type) {
            "welcome" -> Incoming.Welcome(json.decodeFromJsonElement<DeviceInfo>(obj))
            "state" -> Incoming.State(json.decodeFromJsonElement<RemoteState>(obj))
            "queue" -> Incoming.Queue(json.decodeFromJsonElement<RemoteQueue>(obj))
            "error" -> Incoming.Error(obj["message"]?.jsonPrimitive?.contentOrNull ?: "Fehler")
            "pong" -> Incoming.Pong
            else -> null
        }
    }.getOrNull()

    // ---------------------------------------------------------------- Suche im WLAN

    val discoverPacket: ByteArray = """{"type":"discover","protocol":$VERSION}""".toByteArray()

    /** Antwort eines PCs auf die Suche; [host] ist die Absender-Adresse des UDP-Pakets. */
    fun parseDiscovery(bytes: ByteArray, length: Int, host: String): CastDevice? = runCatching {
        val obj = json.parseToJsonElement(String(bytes, 0, length, Charsets.UTF_8)).jsonObject
        if (obj.type != "device") return null
        val info = json.decodeFromJsonElement<DeviceInfo>(obj)
        if (info.id.isBlank() || info.port !in 1..65535) return null
        CastDevice(id = info.id, name = info.name.ifBlank { host }, host = host, port = info.port, version = info.version)
    }.getOrNull()

    private val JsonObject.type: String? get() = this["type"]?.jsonPrimitive?.contentOrNull
}

/** Ein GX-Tube-PC im Netz. */
@Serializable
data class CastDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = CastProtocol.DEFAULT_PORT,
    val version: String = "",
) {
    val address: String get() = if (port == CastProtocol.DEFAULT_PORT) host else "$host:$port"
}

@Serializable
data class DeviceInfo(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val port: Int = CastProtocol.DEFAULT_PORT,
    val protocol: Int = 0,
)

@Serializable
data class RemoteVideo(
    val id: String,
    val title: String = "",
    val channelName: String = "",
    val thumbnail: String? = null,
    val durationSec: Long = -1,
    val isLive: Boolean = false,
    val autoplay: Boolean = false,
) {
    fun toVideoItem() = VideoItem(
        id = id,
        title = title,
        channelName = channelName,
        thumbnail = thumbnail,
        durationSec = durationSec,
        isLive = isLive,
    )
}

@Serializable
data class RemoteSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String = "",
    val label: String = "",
    val uuid: String = "",
)

@Serializable
data class RemoteState(
    val video: RemoteVideo? = null,
    /** idle, loading, buffering, playing, paused, ended, error */
    val status: String = "idle",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val volume: Int = 100,
    val muted: Boolean = false,
    val speed: Float = 1f,
    val isLive: Boolean = false,
    val error: String? = null,
    val segments: List<RemoteSegment> = emptyList(),
    val queueIndex: Int = -1,
    val queueLength: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val autoplay: Boolean = true,
    /** Fenster am PC im Vollbild – null bei älteren Desktop-Versionen ohne diese Steuerung. */
    val fullscreen: Boolean? = null,
    /** Bildschirm, auf dem GX Tube am PC gerade läuft. */
    val displayId: Long? = null,
    val displays: List<RemoteDisplay> = emptyList(),
) {
    val isPlaying: Boolean get() = status == "playing"
    val isLoading: Boolean get() = status == "loading" || status == "buffering"
    /** Wiedergabe gewünscht (läuft oder lädt gerade). */
    val playWhenReady: Boolean get() = status == "playing" || status == "buffering" || status == "loading"
}

/** Ein Bildschirm am PC. */
@Serializable
data class RemoteDisplay(
    val id: Long,
    val name: String = "",
    val primary: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class RemoteQueue(
    val items: List<RemoteVideo> = emptyList(),
    val index: Int = -1,
    val autoplay: Boolean = true,
)
