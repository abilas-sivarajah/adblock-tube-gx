package de.abilas.gxtube.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object YtColors {
    val Red = Color(0xFFFF0033)
    val LiveRed = Color(0xFFCC0000)
    val Sponsor = Color(0xFF00D400)
    val Cyan = Color(0xFF3DF2FF)
}

@Immutable
data class YtPalette(
    val background: Color,
    val text: Color,
    val textSecondary: Color,
    val chip: Color,
    val chipSelected: Color,
    val chipSelectedText: Color,
    val divider: Color,
    val card: Color,
    val link: Color,
    val isDark: Boolean,
)

private val DarkPalette = YtPalette(
    background = Color(0xFF0F0F0F),
    text = Color(0xFFF1F1F1),
    textSecondary = Color(0xFFAAAAAA),
    chip = Color(0xFF272727),
    chipSelected = Color(0xFFF1F1F1),
    chipSelectedText = Color(0xFF0F0F0F),
    divider = Color(0xFF3F3F3F),
    card = Color(0xFF212121),
    link = Color(0xFF3EA6FF),
    isDark = true,
)

private val LightPalette = YtPalette(
    background = Color(0xFFFFFFFF),
    text = Color(0xFF0F0F0F),
    textSecondary = Color(0xFF606060),
    chip = Color(0xFFF2F2F2),
    chipSelected = Color(0xFF0F0F0F),
    chipSelectedText = Color(0xFFFFFFFF),
    divider = Color(0xFFE5E5E5),
    card = Color(0xFFF2F2F2),
    link = Color(0xFF065FD4),
    isDark = false,
)

val LocalYt = staticCompositionLocalOf { DarkPalette }

object Yt {
    val colors: YtPalette
        @Composable get() = LocalYt.current
}

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    )
}

val VideoTitleStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
val MetaStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

@Composable
fun isDarkTheme(mode: String): Boolean = when (mode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun GxTubeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.text,
            onPrimary = p.background,
            secondary = p.link,
            tertiary = YtColors.Red,
            background = p.background,
            onBackground = p.text,
            surface = p.background,
            onSurface = p.text,
            surfaceVariant = p.chip,
            onSurfaceVariant = p.textSecondary,
            surfaceContainer = p.card,
            surfaceContainerHigh = p.card,
            surfaceContainerHighest = p.chip,
            surfaceContainerLow = p.background,
            outline = p.divider,
            outlineVariant = p.divider,
        )
    } else {
        lightColorScheme(
            primary = p.text,
            onPrimary = p.background,
            secondary = p.link,
            tertiary = YtColors.Red,
            background = p.background,
            onBackground = p.text,
            surface = p.background,
            onSurface = p.text,
            surfaceVariant = p.chip,
            onSurfaceVariant = p.textSecondary,
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color.White,
            surfaceContainerHighest = p.chip,
            surfaceContainerLow = Color.White,
            outline = p.divider,
            outlineVariant = p.divider,
        )
    }
    CompositionLocalProvider(LocalYt provides p) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
