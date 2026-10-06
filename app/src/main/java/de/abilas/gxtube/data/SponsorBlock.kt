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
) {
    val startMs: Long get() = ((segment.getOrNull(0) ?: 0.0) * 1000).toLong()
    val endMs: Long get() = ((segment.getOrNull(1) ?: 0.0) * 1000).toLong()
}

@Serializable
private data class SponsorVideo(
    val videoID: String,
    val segments: List<SponsorSegment> = emptyList(),
)

/**
 * SponsorBlock (sponsor.ajay.app): von der Community markierte Werbe-Abschnitte im Video
 * (Sponsor, Eigenwerbung, "Abonnieren nicht vergessen" …) – werden automatisch übersprungen.
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

    fun label(category: String) = categories[category] ?: category

    suspend fun segments(videoId: String, enabled: Set<String>): List<SponsorSegment> = withContext(Dispatchers.IO) {
        if (enabled.isEmpty()) return@withContext emptyList()
        runCatching {
            val prefix = sha256(videoId).take(4)
            val cats = enabled.joinToString(",", "[", "]") { "\"$it\"" }
            val url = "https://sponsor.ajay.app/api/skipSegments/$prefix?categories=" +
                URLEncoder.encode(cats, "UTF-8")
            val request = okhttp3.Request.Builder().url(url).header("User-Agent", "GXTube").build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                Library.json.decodeFromString<List<SponsorVideo>>(response.body.string())
                    .firstOrNull { it.videoID == videoId }
                    ?.segments
                    ?.filter { it.actionType == "skip" && it.endMs > it.startMs }
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
