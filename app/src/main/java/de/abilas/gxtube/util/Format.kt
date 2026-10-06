package de.abilas.gxtube.util

import java.util.Locale

/** Zahlen, Dauer und Datumsangaben so wie in der deutschen YouTube-App. */
object Fmt {

    fun duration(seconds: Long): String {
        if (seconds < 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    fun durationMs(ms: Long): String = duration(ms.coerceAtLeast(0) / 1000)

    /** 999 · 1234 · 12.345 · 1,2 Mio. · 3,4 Mrd. */
    fun count(n: Long): String = when {
        n < 0 -> ""
        n < 10_000 -> n.toString()
        n < 1_000_000 -> String.format(Locale.GERMANY, "%,d", n)
        n < 1_000_000_000 -> decimal(n / 1_000_000.0) + " Mio."
        else -> decimal(n / 1_000_000_000.0) + " Mrd."
    }

    private fun decimal(v: Double): String {
        val s = if (v >= 100) String.format(Locale.GERMANY, "%.0f", v)
        else String.format(Locale.GERMANY, "%.1f", v)
        return s.removeSuffix(",0")
    }

    fun views(n: Long): String = when {
        n < 0 -> ""
        n == 1L -> "1 Aufruf"
        else -> count(n) + " Aufrufe"
    }

    fun watching(n: Long): String = if (n < 0) "" else count(n) + " Zuschauer"

    fun subscribers(n: Long): String = when {
        n < 0 -> ""
        n == 1L -> "1 Abonnent"
        else -> count(n) + " Abonnenten"
    }

    fun videos(n: Long): String = when {
        n < 0 -> ""
        n == 1L -> "1 Video"
        else -> count(n) + " Videos"
    }

    /** "vor 3 Tagen" aus einem Zeitstempel, sonst der Text von YouTube. */
    fun ago(epochMillis: Long?, fallback: String? = null): String {
        if (epochMillis == null || epochMillis <= 0) return fallback.orEmpty()
        val diff = (System.currentTimeMillis() - epochMillis) / 1000
        if (diff < 0) return fallback.orEmpty()
        fun unit(v: Long, one: String, many: String) = "vor $v ${if (v == 1L) one else many}"
        return when {
            diff < 60 -> "gerade eben"
            diff < 3600 -> unit(diff / 60, "Minute", "Minuten")
            diff < 86_400 -> unit(diff / 3600, "Stunde", "Stunden")
            diff < 7 * 86_400 -> unit(diff / 86_400, "Tag", "Tagen")
            diff < 30 * 86_400 -> unit(diff / (7 * 86_400), "Woche", "Wochen")
            diff < 365 * 86_400 -> unit(diff / (30 * 86_400), "Monat", "Monaten")
            else -> unit(diff / (365 * 86_400), "Jahr", "Jahren")
        }
    }

    fun joinDot(vararg parts: String?): String =
        parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")
}
