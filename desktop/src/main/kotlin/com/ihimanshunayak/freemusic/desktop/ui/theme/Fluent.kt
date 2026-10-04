// Copyright (C) 2026 Himanshu Nayak
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Free Music for Windows - Windows 11 design system.
//
// The desktop build targets Windows, so it does not imitate a phone. It is
// drawn with the same controls Explorer, Settings and the Store are drawn
// with, through Compose Fluent UI (`io.github.compose-fluent:fluent`), the
// Kotlin port of WinUI 3's Fluent Design System.
//
// Two things make that more than a colour swap:
//
//  * **The accent follows the sleeve.** Fluent derives its whole palette from a
//    single accent colour, and the Windows shell lets an app set that accent
//    (Personalisation > Colours > "Accent colour from my background"). This app
//    does the equivalent with the current track's artwork, so the window
//    changes mood with the music the way the Android app's Material You tint
//    does - but through Fluent's own shade ramp rather than a second palette.
//
//  * **Material still exists underneath.** Compose Fluent and Compose Material
//    are different component sets, not different versions of one. Screens that
//    have not been moved onto Fluent controls yet read `MaterialTheme`, so the
//    Material scheme is regenerated from the *same* Fluent colours here rather
//    than being deleted. That keeps one source of truth for colour while the
//    components migrate, instead of two palettes drifting apart.
//
// The style choices worth knowing before editing this file:
//
//  * `Shades` is built by hand, not through `generateShades`. `generateShades`
//    only knows a single hard-coded Windows-blue ramp and silently falls back
//    to it for any other colour, which would pin the app to blue and ignore the
//    artwork entirely. The ramp below follows the same lighten/darken ratios so
//    Fluent's own contrast assumptions still hold.
//  * The artwork accent is clamped in HSL rather than used raw. A quantised
//    sleeve colour is often near-grey or near-black, and either would produce a
//    ramp where the light end (used for links and selected text) or the dark
//    end (used for filled buttons) disappears.

package com.ihimanshunayak.freemusic.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ihimanshunayak.freemusic.desktop.data.ThemePreference
import com.ihimanshunayak.freemusic.desktop.data.palette.ArtworkPalette
import com.ihimanshunayak.freemusic.desktop.data.palette.animatedArtworkPalette
import com.ihimanshunayak.freemusic.desktop.data.palette.hslToArgb
import com.ihimanshunayak.freemusic.desktop.data.palette.relativeLuminance
import io.github.composefluent.Colors
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.FluentTheme
import io.github.composefluent.Shades

/**
 * The app's own Fluent accent, used when nothing is playing.
 *
 * Windows derives its default accent from the desktop wallpaper; this is the
 * equivalent "house" colour, and it is the same magenta-leaning note the
 * Android app's Material seed uses, so the two platforms read as one product.
 */
val FreeMusicAccent: Color = Color(0xFFE0466F)

/**
 * Derives a Windows accent from a sleeve's dominant colour.
 *
 * Fluent generates a nine-step shade ramp from the accent and uses the bright
 * end of it for text and the dark end for filled surfaces, so an accent that is
 * already very light or very dark produces a ramp where one of those two roles
 * disappears. Clamping lightness into a mid band keeps every step usable on
 * both the light and the dark theme.
 *
 * Saturation is clamped upwards as well: a pixel-quantised sleeve colour is
 * often a near-grey, and a grey accent would make the app look broken rather
 * than muted.
 */
fun accentFromArtwork(artworkAccent: Color, dark: Boolean): Color {
    val argb = artworkAccent.toArgb()
    val red = (argb shr 16) and 0xFF
    val green = (argb shr 8) and 0xFF
    val blue = argb and 0xFF

    val r = red / 255f
    val g = green / 255f
    val b = blue / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val delta = max - min

    // A colour with no chroma carries no hue information, so the house accent's
    // hue is used instead of the meaningless 0 an achromatic sleeve would give.
    if (delta < 0.0001f) {
        return Color(hslToArgb(HOUSE_HUE, HOUSE_SATURATION, if (dark) 0.62f else 0.46f))
    }

    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val hue = when (max) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }

    val clampedLightness = if (dark) {
        lightness.coerceIn(0.52f, 0.74f)
    } else {
        lightness.coerceIn(0.34f, 0.52f)
    }
    return Color(hslToArgb(hue, saturation.coerceIn(0.30f, 0.92f), clampedLightness))
}

/**
 * Fluent's nine-step shade ramp, built from an accent.
 *
 * The factors match Windows' own accent ramp closely enough that the derived
 * control, hover and pressed colours keep their contrast relationships: the
 * three `light` steps are what Fluent reaches for when text or a border must
 * stay legible on a dark surface, and the three `dark` steps are what it fills
 * a button with. Lightening by a fraction of the *remaining* distance to white
 * (rather than by a fixed amount) keeps a very dark accent from washing out,
 * and darkening by a fraction of the colour's own value keeps a very light one
 * from going flat at the other end.
 */
internal fun shadesFrom(accent: Color): Shades {
    fun lighten(amount: Float): Color = Color(
        red = accent.red + (1f - accent.red) * amount,
        green = accent.green + (1f - accent.green) * amount,
        blue = accent.blue + (1f - accent.blue) * amount,
    )

    fun darken(amount: Float): Color = Color(
        red = accent.red * (1f - amount),
        green = accent.green * (1f - amount),
        blue = accent.blue * (1f - amount),
    )

    return Shades(
        base = accent,
        light1 = lighten(0.09f),
        light2 = lighten(0.38f),
        light3 = lighten(0.65f),
        dark1 = darken(0.14f),
        dark2 = darken(0.36f),
        dark3 = darken(0.55f),
    )
}

/** The Fluent palette for a theme, with the accent carried through the ramp. */
internal fun fluentColorsFor(accent: Color, dark: Boolean): Colors =
    Colors(shadesFrom(accent), dark)

/**
 * Extra colours Fluent's palette has no slot for.
 *
 * The transport bar and the lyrics view need to sit visually *behind* the
 * content rather than beside it. Fluent models that as a translucent layer over
 * Mica, which is not a single colour, so these are the Web-style equivalents
 * the acrylic layers are tinted with.
 */
data class ExtendedColors(
    val playerBar: Color,
    val sidebar: Color,
    val cardHover: Color,
    val seekTrack: Color,
    val seekBuffered: Color,
    /** True when the active theme is dark, for callers that branch on artwork. */
    val dark: Boolean,
)

private val DefaultExtendedColors = ExtendedColors(
    playerBar = Color(0xCC1C1A1F),
    sidebar = Color(0x66141216),
    cardHover = Color.White.copy(alpha = 0.06f),
    seekTrack = Color.White.copy(alpha = 0.24f),
    seekBuffered = Color.White.copy(alpha = 0.40f),
    dark = true,
)

private val LocalExtendedColors = staticCompositionLocalOf { DefaultExtendedColors }

/** `FluentTheme.extended` keeps UI code from hard-coding a palette branch. */
val FluentTheme.extended: ExtendedColors
    @Composable get() = LocalExtendedColors.current

/**
 * The acrylic tint for the transport bar and the navigation pane.
 *
 * Fluent's own layers are translucent over the window backdrop so the Mica
 * wallpaper shows through; these two surfaces are the exception, because
 * content scrolls underneath them and a fully transparent bar makes the text
 * unreadable. A heavy-but-not-opaque tint keeps the depth cue while holding
 * contrast, which is how Explorer's address bar behaves.
 */
private fun barTint(dark: Boolean, base: Color): Color =
    if (dark) base.copy(alpha = 0.86f) else base.copy(alpha = 0.90f)

/**
 * The default artwork palette, used before a sleeve has been read.
 *
 * Kept as a function rather than a constant because Fluent's own surface
 * colours are theme-dependent and this has to agree with them, or the first
 * frame of a cold start would flash a colour the theme immediately replaces.
 */
private fun fallbackPalette(dark: Boolean): ArtworkPalette = if (dark) {
    ArtworkPalette(
        background = Color(0xFF141216),
        wash = Color(0xFF1C1A1F),
        elevated = Color(0xFF232126),
        accent = FreeMusicAccent,
        onBackground = Color(0xFFE6E1E6),
        onBackgroundVariant = Color(0xFFBEB7C2),
        divider = Color(0x1FFFFFFF),
    )
} else {
    ArtworkPalette(
        background = Color(0xFFFAF8FB),
        wash = Color(0xFFF4F1F6),
        elevated = Color(0xFFFFFFFF),
        accent = FreeMusicAccent,
        onBackground = Color(0xFF1C1B1F),
        onBackgroundVariant = Color(0xFF49454E),
        divider = Color(0x1F000000),
    )
}

/**
 * Material 3's scheme, regenerated from the Fluent palette.
 *
 * Screens not yet migrated to Fluent controls keep reading `MaterialTheme`, and
 * deriving their colours here means they inherit the artwork accent for free
 * instead of looking like a different app.
 */
private fun materialSchemeFrom(fluent: Colors): androidx.compose.material3.ColorScheme {
    // Fluent's own accent lives on `shades`; `text.accent` is the on-surface
    // reading colour and `fillAccent` is the filled-button pair. Material wants
    // both roles at once, so the ramp supplies the containers and the contrast
    // helper decides what sits on them.
    val primary = fluent.shades.base
    val onPrimary = onColorFor(primary)
    val primaryContainer = fluent.shades.dark2
    val onPrimaryContainer = onColorFor(primaryContainer)
    val secondary = fluent.shades.light1
    val onSecondary = onColorFor(secondary)
    val secondaryContainer = fluent.shades.light3
    val onSecondaryContainer = onColorFor(secondaryContainer)
    val tertiary = fluent.shades.light2
    val onTertiary = onColorFor(tertiary)
    val tertiaryContainer = fluent.shades.dark1
    val onTertiaryContainer = onColorFor(tertiaryContainer)
    val background = fluent.background.solid.base
    val onBackground = fluent.text.text.primary
    val surfaceVariant = fluent.subtleFill.secondary
    val onSurfaceVariant = fluent.text.text.secondary
    val outline = fluent.stroke.control.default
    val outlineVariant = fluent.stroke.divider.default
    val error = fluent.system.critical
    val errorContainer = fluent.system.criticalBackground

    return if (fluent.darkMode) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = background,
            onSurface = onBackground,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            error = error,
            onError = onColorFor(error),
            errorContainer = errorContainer,
            onErrorContainer = onColorFor(errorContainer),
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = background,
            onSurface = onBackground,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            error = error,
            onError = onColorFor(error),
            errorContainer = errorContainer,
            onErrorContainer = onColorFor(errorContainer),
        )
    }
}

/**
 * Slightly tighter than the Material default.
 *
 * Windows text is read at arm's length with more horizontal room than a phone
 * has, so display sizes are pulled in while body sizes stay where Material put
 * them. Only used by screens still on Material controls.
 */
private val LegacyAppTypography = Typography().let { base ->
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
    ThemePreference.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
}

/**
 * The current artwork palette, so a screen can tint a scrim or a caption
 * without re-reading the sleeve.
 */
val LocalArtworkPalette = staticCompositionLocalOf { fallbackPalette(dark = true) }

/** Reads [LocalArtworkPalette] without threading it through a parameter. */
val artworkPalette: ArtworkPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalArtworkPalette.current

/**
 * Wraps content in the app theme.
 *
 * When [artworkUrl] names a sleeve whose colours have been read, the Fluent
 * accent is derived from it and crossfades in; otherwise the house accent is
 * used.
 */
@OptIn(ExperimentalFluentApi::class)
@Composable
fun FreeMusicTheme(
    preference: ThemePreference,
    artworkUrl: String? = null,
    reduceAnimation: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = resolveDarkTheme(preference)

    // The palette animation is what makes the title bar, the navigation pane
    // and every accent control move together when a track changes; running it
    // here rather than in a screen is what lets the whole window tint at once.
    val palette = animatedArtworkPalette(
        imageUrl = artworkUrl,
        dark = dark,
        fallback = remember(dark) { fallbackPalette(dark) },
        reduceAnimation = reduceAnimation,
    )

    val accent = accentFromArtwork(artworkAccent = palette.accent, dark = dark)
    val fluentColors = remember(accent, dark) { fluentColorsFor(accent, dark) }
    val extended = remember(fluentColors, dark) {
        ExtendedColors(
            playerBar = barTint(dark, fluentColors.background.layer.default),
            sidebar = barTint(dark, fluentColors.background.mica.base),
            cardHover = if (dark) Color.White.copy(alpha = 0.07f) else Color.Black.copy(alpha = 0.04f),
            seekTrack = if (dark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.16f),
            seekBuffered = if (dark) Color.White.copy(alpha = 0.38f) else Color.Black.copy(alpha = 0.28f),
            dark = dark,
        )
    }
    val material = remember(fluentColors) { materialSchemeFrom(fluentColors) }

    FluentTheme(colors = fluentColors) {
        CompositionLocalProvider(
            LocalExtendedColors provides extended,
            LocalArtworkPalette provides palette,
        ) {
            // Nested rather than replaced: Material's own locals (typography,
            // shapes) have no Fluent equivalent, and screens that mix the two
            // component sets would otherwise fail on a missing local.
            MaterialTheme(colorScheme = material, typography = LegacyAppTypography) {
                content()
            }
        }
    }
}

/**
 * A readable content colour for a surface that is not one of Fluent's layers.
 *
 * Fluent offers `contentColorFor`, but it only knows its own background
 * colours; artwork scrims and the transport bar are neither, and picking black
 * or white from luminance is both correct and what the Windows shell itself
 * does for tiles.
 */
fun onColorFor(background: Color): Color {
    val backgroundLuminance = background.relativeLuminance()
    val darkContrast = (backgroundLuminance + 0.05f) / (DARK_CONTRAST_LUMINANCE + 0.05f)
    val lightContrast = (LIGHT_CONTRAST_LUMINANCE + 0.05f) / (backgroundLuminance + 0.05f)
    return if (darkContrast > lightContrast) DarkContrastColor else LightContrastColor
}

/** Kept so colour maths elsewhere in the module can compare two surfaces. */
internal fun Color.isDarkSurface(): Boolean = luminance() < 0.5f

/** The hue and saturation [accentFromArtwork] falls back to for a grey sleeve. */
private const val HOUSE_HUE = 344f
private const val HOUSE_SATURATION = 0.62f

private val DarkContrastColor = Color(0xFF1A1A1A)
private val LightContrastColor = Color(0xFFFFFFFF)

/** Relative luminance of the two contrast colours, resolved once for [onColorFor]. */
private val DARK_CONTRAST_LUMINANCE = DarkContrastColor.relativeLuminance()
private val LIGHT_CONTRAST_LUMINANCE = LightContrastColor.relativeLuminance()
