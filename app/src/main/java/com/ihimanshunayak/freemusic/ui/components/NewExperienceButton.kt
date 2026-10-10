package com.ihimanshunayak.freemusic.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ihimanshunayak.freemusic.data.settings.AppSettings

/**
 * The one control the New Experience draws: a circular glass disc with the
 * glyph centred in it.
 *
 * [onClick] null draws the control without owning the gesture. The two bars
 * need that: a tab is a weighted cell of the pill — well past the 48dp a touch
 * target wants — and a 34dp disc inside it that swallowed the tap would shrink
 * every tab's target down to the disc. There the disc is the tab's *face* and
 * the cell is still the tab; here it stays a button.
 *
 * [FloatingBottomBar] and [GlassNavBar] are pills — a container with a row of
 * contents in it — and every control in the app so far has been a variant of
 * that. This is the other primitive: a single control sized to the icon it
 * carries rather than to a row. A page header's buttons and a shelf's leading
 * tile are both that shape, which is why it is a component rather than a
 * modifier applied in two places.
 *
 * The material resolves in the order the bars resolve theirs: glass when the
 * device supports it and the listener has glass on, the theme's `surfaceVariant`
 * when either is not true. `reduceDynamicBlur` lands on the flat disc too, which
 * is what that setting promises. A flat disc is not a degraded glass disc, but
 * it is a button — and a control that renders as nothing on an older phone is
 * not a fallback, it is a bug.
 *
 * Selection is carried by the disc's presence rather than by its colour, which
 * is the rule [GlassNavBar] already follows: the selected control wears the
 * surface and full-strength ink, the rest are the same glyph on the page. Two
 * bars a few pixels apart disagreeing about how selection reads would be worse
 * than either rule on its own.
 */
@Composable
fun NewExperienceButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** False drops the disc entirely, leaving the glyph on the page. */
    selected: Boolean = true,
    discSize: Dp = NEW_EXPERIENCE_DISC,
    glyphSize: Dp = NEW_EXPERIENCE_GLYPH,
    /**
     * Ink overrides, named as [FloatingBottomBar]'s own tab tints are. Both
     * bars read their content colour off the backdrop when the backdrop is
     * glass, and a disc drawn in the theme's `onSurfaceVariant` over someone's
     * album art is exactly the contrast loss that override exists to fix.
     */
    selectedTint: Color? = null,
    unselectedTint: Color? = null,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else NEW_EXPERIENCE_UNSELECTED_SCALE,
        animationSpec = GlassSpring,
        label = "newExperienceScale",
    )
    val tint = if (selected) {
        selectedTint ?: MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        unselectedTint ?: MaterialTheme.colorScheme.onSurfaceVariant
            .copy(alpha = NEW_EXPERIENCE_UNSELECTED)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(discSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (selected) Modifier.newExperienceDisc() else Modifier)
            .clip(CircleShape)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                },
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(glyphSize),
        )
    }
}

/** The disc on its own, for a caller that has its own content to put in it. */
@Composable
fun NewExperienceDisc(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.newExperienceDisc(),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * The disc's surface, without the sizing or the click.
 *
 * Not glass on the reduce-blur branch, deliberately: sampling the backdrop is
 * the exact work `reduceDynamicBlur` promises to skip, so the setting takes the
 * material down to a flat fill rather than leaving a disc that still costs a
 * blur per frame.
 */
@Composable
private fun Modifier.newExperienceDisc(): Modifier {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    return if (useGlass && !reduceDynamicBlur) {
        liquidGlass(shape = CircleShape)
    } else {
        background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, CircleShape)
    }
}

/** The disc's diameter — a 19dp glyph with the reference sheet's margin around it. */
internal val NEW_EXPERIENCE_DISC = 34.dp

/** The glyph inside the disc, at the ratio the reference sheet draws. */
internal val NEW_EXPERIENCE_GLYPH = 19.dp

/** How far an unselected control's ink falls back. */
internal const val NEW_EXPERIENCE_UNSELECTED = 0.65f

/**
 * How much an unselected control shrinks.
 *
 * Not zero: a disc that scales to nothing is a disc that is animating, and a
 * bar that also has an indicator travelling along it does not need two things
 * moving. Shrinking a little *and* fading in equal measure is what makes the
 * change read as one movement rather than as two that nearly agree.
 */
internal const val NEW_EXPERIENCE_UNSELECTED_SCALE = 0.88f
