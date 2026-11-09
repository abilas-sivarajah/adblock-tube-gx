package de.abilas.gxtube.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.security.MessageDigest

@Serializable
data class SponsorSegment(
    val segment: List<Double>,
    val category: String,
    val actionType: String = "skip",
    @SerialName("UUID") val uuid: String = "",
    /** Bei Kapiteln (actionType "chapter") der Name des Kapitels */
    val description: String = "",
) {
    val startMs: Long get() = ((segment.getOrNull(0) ?: 0.0) * 1000).toLong()
    val endMs: Long get() = ((segment.getOrNull(1) ?: 0.0) * 1000).toLong()
    /** Stumm schalten statt überspringen (z. B. eingesprochene Werbung in Musikvideos) */
    val isMute: Boolean get() = actionType == "mute"
}

@Serializable
private data class SponsorVideo(
    val videoID: String,
    val segments: List<SponsorSegment> = emptyList(),
)

/** Ein Kapitel des Videos (von YouTube oder aus SponsorBlock). */
data class Chapter(val title: String, val startMs: Long, val thumbnail: String? = null)

/** Was SponsorBlock zu einem Video weiß. */
data class SponsorInfo(
    /** Abschnitte zum Überspringen oder Stummschalten (nur die eingestellten Kategorien) */
    val segments: List<SponsorSegment> = emptyList(),
    /** Höhepunkt des Videos ("Zum Highlight springen") */
    val highlightMs: Long? = null,
    /** "Das ganze Video ist …" (z. B. gesponsert) */
    val fullLabel: String? = null,
    /** Kapitel aus der Community – nur genutzt, wenn YouTube keine hat */
    val chapters: List<Chapter> = emptyList(),
)

/**
 * SponsorBlock (sponsor.ajay.app): von der Community markierte Werbe-Abschnitte im Video
 * (Sponsor, Eigenwerbung, "Abonnieren nicht vergessen" …) – werden automatisch übersprungen,
 * als "stumm" markierte stumm geschaltet. Dazu Highlight, "ganzes Video ist …" und Kapitel.
 * Abfrage über die ersten 4 Zeichen des SHA-256-Hashs, damit der Server die Video-ID nicht erfährt.
 */
object SponsorBlock {
    val categories = linkedMapOf(
        "sponsor" to "Sponsor",
        "selfpromo" to "Eigenwerbung",
        "interaction" to "Erinnerung (Abonnieren/Liken)",
        "intro" to "Intro",
        "outro" to "Abspann",
        "preview" to "Vorschau/Rückblick",
        "music_offtopic" to "Musik: Nicht-Musik-Teil",
        "filler" to "Abschweifung",
    )

    private val fullLabels = mapOf(
        "sponsor" to "Gesponsertes Video",
        "selfpromo" to "Eigenwerbung",
        "exclusive_access" to "Exklusiver Zugang (gesponsert)",
    )

    private const val ACTION_TYPES = """["skip","mute","full","poi","chapter"]"""

    fun label(category: String) = categories[category] ?: category

    /** Antwort der API aufbereiten. [enabled]: welche Kategorien übersprungen/stumm geschaltet werden. */
    fun parse(json: String, videoId: String, enabled: Set<String>): SponsorInfo {
        val all = Library.json.decodeFromString<List<SponsorVideo>>(json)
            .firstOrNull { it.videoID == videoId }
            ?.segments
            ?.filter { it.segment.size >= 2 }
            .orEmpty()
        val segments = all
            .filter { (it.actionType == "skip" || it.actionType == "mute") && it.category in enabled && it.endMs > it.startMs && it.uuid.isNotEmpty() }
            .sortedBy { it.startMs }
        val highlight = all.firstOrNull { it.actionType == "poi" && it.category == "poi_highlight" }?.startMs
        val full = all.firstOrNull { it.actionType == "full" && it.category in fullLabels }?.let { fullLabels[it.category] }
        val chapters = all
            .filter { it.actionType == "chapter" && it.description.isNotBlank() }
            .map { Chapter(it.description.trim().take(120), it.startMs) }
            .sortedBy { it.startMs }
        return SponsorInfo(segments, highlight, full, if (chapters.size >= 2) chapters else emptyList())
    }

    suspend fun info(videoId: String, enabled: Set<String>): SponsorInfo = withContext(Dispatchers.IO) {
        runCatching {
            val prefix = sha256(videoId).take(4)
            // Highlight, Kapitel und "ganzes Video" kommen immer mit – übersprungen wird nur, was eingestellt ist
            val query = (enabled.filter { it in categories } + listOf("poi_highlight", "chapter", "sponsor", "selfpromo", "exclusive_access")).distinct()
            val cats = query.joinToString(",", "[", "]") { "\"$it\"" }
            val url = "https://sponsor.ajay.app/api/skipSegments/$prefix?categories=" +
                URLEncoder.encode(cats, "UTF-8") + "&actionTypes=" + URLEncoder.encode(ACTION_TYPES, "UTF-8")
            val request = okhttp3.Request.Builder().url(url).header("User-Agent", "GXTube").build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use SponsorInfo()
                parse(response.body.string(), videoId, enabled)
            }
        }.getOrDefault(SponsorInfo())
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
