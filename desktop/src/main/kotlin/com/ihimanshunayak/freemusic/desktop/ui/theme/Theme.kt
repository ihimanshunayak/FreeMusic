// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - theme.
//
// The palette is the desktop half of the Android app's Material You look: the
// same magenta-leaning primary, but expressed as static schemes because a
// desktop window has no dynamic colour source to sample from. Light and dark are
// both first-class - the app is meant to be readable at a desk in daylight and
// at night - and the surface colours are flattened rather than tonal so the
// window does not look washed out on a non-OLED panel.

package com.ihimanshunayak.freemusic.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ihimanshunayak.freemusic.desktop.data.ThemePreference

/**
 * The palette values, kept as named constants because several of them are also
 * used outside a `MaterialTheme` scope (scrims over artwork, the seek bar track,
 * the sidebar) where reading them from the theme is not possible.
 */
object Palette {

    val Primary = Color(0xFFE0466F)
    val PrimaryDark = Color(0xFFFF6E93)
    val Secondary = Color(0xFF7A5AF8)
    val Tertiary = Color(0xFF00A6A6)

    val SurfaceLight = Color(0xFFFAF8FB)
    val SurfaceDark = Color(0xFF141216)

    /** Backdrop behind album art. Deliberately darker than the surface in light mode. */
    val Scrim = Color(0xB3000000)

    val PlayerBarDark = Color(0xFF1C1A1F)
    val PlayerBarLight = Color(0xFFF2EFF4)
}

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Palette.Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E1),
    onPrimaryContainer = Color(0xFF3F0018),
    secondary = Palette.Secondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6DEFF),
    onSecondaryContainer = Color(0xFF1E0061),
    tertiary = Palette.Tertiary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB9F1F0),
    onTertiaryContainer = Color(0xFF00201F),
    background = Palette.SurfaceLight,
    onBackground = Color(0xFF1C1B1F),
    surface = Palette.SurfaceLight,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0E6),
    onSurfaceVariant = Color(0xFF49454E),
    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCAC4CE),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Palette.PrimaryDark,
    onPrimary = Color(0xFF5E0028),
    primaryContainer = Color(0xFF8B0F3C),
    onPrimaryContainer = Color(0xFFFFD9E1),
    secondary = Color(0xFFC8BDFF),
    onSecondary = Color(0xFF32189F),
    secondaryContainer = Color(0xFF493D8E),
    onSecondaryContainer = Color(0xFFE6DEFF),
    tertiary = Color(0xFF5FD9D8),
    onTertiary = Color(0xFF003735),
    tertiaryContainer = Color(0xFF00504E),
    onTertiaryContainer = Color(0xFFB9F1F0),
    background = Palette.SurfaceDark,
    onBackground = Color(0xFFE6E1E6),
    surface = Palette.SurfaceDark,
    onSurface = Color(0xFFE6E1E6),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCAC4CE),
    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF49454E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Extra values Material 3 has no slot for.
 *
 * The now-playing bar and the sidebar need to sit visually *behind* the content
 * rather than beside it, which is not something `surfaceVariant` expresses, so
 * they get their own pair with one value per theme.
 */
data class ExtendedColors(
    val playerBar: Color,
    val sidebar: Color,
    val cardHover: Color,
    val seekTrack: Color,
    val seekBuffered: Color,
)

private val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        playerBar = Palette.PlayerBarDark,
        sidebar = Palette.SurfaceDark,
        cardHover = Color.White.copy(alpha = 0.06f),
        seekTrack = Color.White.copy(alpha = 0.24f),
        seekBuffered = Color.White.copy(alpha = 0.40f),
    )
}

/** `MaterialTheme.extended` keeps UI code from hard-coding a palette branch. */
val MaterialTheme.extended: ExtendedColors
    @Composable get() = LocalExtendedColors.current

/**
 * Slightly tighter than the Material default.
 *
 * Desktop windows are read at arm's length and have more horizontal room than a
 * phone, so the display sizes are pulled in and the body sizes are left alone.
 */
private val AppTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontSize = 48.sp, fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontSize = 38.sp, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontSize = 23.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
}

/** Monospaced, for the diagnostics log view where column alignment matters. */
val MonospaceLogStyle: TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

/** Resolution order for [ThemePreference.SYSTEM]. */
@Composable
fun resolveDarkTheme(preference: ThemePreference): Boolean = when (preference) {
    ThemePreference.DARK -> true
    ThemePreference.LIGHT -> false
    ThemePreference.SYSTEM -> isSystemInDarkTheme()
}

/**
 * Wraps content in the app theme.
 *
 * [SideEffect] is used for the window-decoration hint because it must run after
 * the window is known to exist, which is not true at the top of composition.
 */
@Composable
fun FreeMusicTheme(
    preference: ThemePreference,
    content: @Composable () -> Unit,
) {
    val dark = resolveDarkTheme(preference)
    val scheme = if (dark) DarkScheme else LightScheme
    val extended = ExtendedColors(
        playerBar = if (dark) Palette.PlayerBarDark else Palette.PlayerBarLight,
        sidebar = if (dark) Color(0xFF1B191E) else Color(0xFFF4F1F6),
        cardHover = if (dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.04f),
        seekTrack = if (dark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.16f),
        seekBuffered = if (dark) Color.White.copy(alpha = 0.38f) else Color.Black.copy(alpha = 0.28f),
    )

    MaterialTheme(colorScheme = scheme, typography = AppTypography) {
        androidx.compose.runtime.CompositionLocalProvider(LocalExtendedColors provides extended) {
            content()
        }
    }
}
