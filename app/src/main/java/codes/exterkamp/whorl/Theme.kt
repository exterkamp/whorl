package codes.exterkamp.whorl

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.termux.terminal.TerminalColors
import java.util.Properties

// The fallback before Android 12, where there are no wallpaper colors: Material's baseline built from an ocean teal.
internal val OceanLight = lightColorScheme(
    primary = Color(0xFF006A6A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F0), onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E7), onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF8C4F3A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBD0), onTertiaryContainer = Color(0xFF3A0B01),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAFDFC), onBackground = Color(0xFF191C1C),
    surface = Color(0xFFFAFDFC), onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE5E4), onSurfaceVariant = Color(0xFF3F4948),
    outline = Color(0xFF6F7979), outlineVariant = Color(0xFFBEC9C8),
    inverseSurface = Color(0xFF2D3131), inverseOnSurface = Color(0xFFEFF1F0), inversePrimary = Color(0xFF80D5D4),
    surfaceBright = Color(0xFFFAFDFC), surfaceDim = Color(0xFFD5DBDA),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF4F7F6),
    surfaceContainer = Color(0xFFEEF1F0), surfaceContainerHigh = Color(0xFFE8ECEB),
    surfaceContainerHighest = Color(0xFFE2E6E5),
)

internal val OceanDark = darkColorScheme(
    primary = Color(0xFF80D5D4), onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F4F), onPrimaryContainer = Color(0xFF9CF1F0),
    secondary = Color(0xFFB0CCCB), onSecondary = Color(0xFF1B3534),
    secondaryContainer = Color(0xFF324B4B), onSecondaryContainer = Color(0xFFCCE8E7),
    tertiary = Color(0xFFFFB59E), onTertiary = Color(0xFF55200F),
    tertiaryContainer = Color(0xFF713624), onTertiaryContainer = Color(0xFFFFDBD0),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1514), onBackground = Color(0xFFDDE4E3),
    surface = Color(0xFF0E1514), onSurface = Color(0xFFDDE4E3),
    surfaceVariant = Color(0xFF3F4948), onSurfaceVariant = Color(0xFFBEC9C8),
    outline = Color(0xFF889392), outlineVariant = Color(0xFF3F4948),
    inverseSurface = Color(0xFFDDE4E3), inverseOnSurface = Color(0xFF2B3231), inversePrimary = Color(0xFF006A6A),
    surfaceBright = Color(0xFF343A3A), surfaceDim = Color(0xFF0E1514),
    surfaceContainerLowest = Color(0xFF090F0F), surfaceContainerLow = Color(0xFF161D1D),
    surfaceContainer = Color(0xFF1A2121), surfaceContainerHigh = Color(0xFF252B2B),
    surfaceContainerHighest = Color(0xFF2F3636),
)

/** Wallpaper colors on Android 12 and later (Material You), otherwise the ocean palette. */
fun appColors(context: Context, dark: Boolean): ColorScheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> OceanDark
    else -> OceanLight
}

fun isDark(context: Context) =
    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

@Composable
fun WhorlTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = appColors(LocalContext.current, dark), content = content)
}

/**
 * The terminal follows the app: its background and text are the theme's surface colors, and the 16 ANSI colors
 * are a set that stays readable on that background (GitHub's, light or dark). Programs can still set their own.
 */
object TerminalTheme {
    private var applied: Pair<Boolean, Int>? = null

    /** Set the default terminal colors for the current theme. True if they changed. */
    fun apply(context: Context): Boolean {
        val dark = isDark(context)
        val colors = appColors(context, dark)
        val key = dark to colors.surface.toArgb()
        if (applied == key) return false
        applied = key
        fun hex(c: Color) = String.format("#%06x", c.toArgb() and 0xFFFFFF)
        val ansi = if (dark) DARK_ANSI else LIGHT_ANSI
        TerminalColors.COLOR_SCHEME.updateWith(Properties().apply {
            setProperty("background", hex(colors.surface))
            setProperty("foreground", hex(colors.onSurface))
            setProperty("cursor", hex(colors.primary))
            ansi.forEachIndexed { i, c -> setProperty("color$i", c) }
        })
        return true
    }

    private val DARK_ANSI = listOf(
        "#484f58", "#ff7b72", "#3fb950", "#d29922", "#58a6ff", "#bc8cff", "#39c5cf", "#b1bac4",
        "#6e7681", "#ffa198", "#56d364", "#e3b341", "#79c0ff", "#d2a8ff", "#56d4dd", "#ffffff",
    )
    private val LIGHT_ANSI = listOf(
        "#24292f", "#cf222e", "#116329", "#4d2d00", "#0969da", "#8250df", "#1b7c83", "#6e7781",
        "#57606a", "#a40e26", "#1a7f37", "#633c01", "#218bff", "#a475f9", "#3192aa", "#8c959f",
    )
}
