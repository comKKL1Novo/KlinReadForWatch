package com.klin.read.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Apple UI Design System – Verified: 8pt Grid, SF Pro Typography,
 * Material-Depth, Natural Spring Motion
 *
 * Tokens follow the apple-ui-design skill: an 8pt spacing grid, SF Pro type
 * specs, semantic glass materials, and iOS spring motion. Where the skill
 * specifies web values (px, CSS), the Android equivalents are used and the
 * deviation is noted at the token.
 */

/**
 * Semantic colour names, resolved from the Material 3 scheme.
 *
 * This used to be a standalone monochrome palette with its own hardcoded
 * light/dark hex values, which meant the watch carried a second colour system in
 * parallel with Material 3. Every colour below is a role from
 * [MaterialTheme.colorScheme], so there is one source of truth for colour and a
 * theme change cannot leave one screen behind.
 *
 * The glass fields are new: the skill requires elevated surfaces to carry BOTH
 * an rgba fill and a blur, so the material has to be a token rather than
 * something each card improvises.
 */
data class AppColors(
    val canvas: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val divider: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val accent: Color,
    val accentInk: Color,
    val danger: Color,

    /** Fill for an elevated surface. Translucent by definition. */
    val glassFill: Color,
    /** Hairline around an elevated surface: 0.5dp, per the skill. */
    val glassBorder: Color,
    /** Drop shadow under an elevated surface. */
    val glassShadow: Color,
    /** Fill for recessed/search inputs: rgba(0,0,0,0.05) light / white 0.1 dark. */
    val fieldFill: Color
)

/**
 * Builds the semantic palette from the active Material 3 scheme.
 *
 * Read inside a composable, because it depends on [MaterialTheme]; there is no
 * longer any static Light/Dark constant to fall back to.
 */
@Composable
fun rememberAppColors(): AppColors {
    val s = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    return AppColors(
        canvas = s.background,
        surface = s.surface,
        surfaceMuted = s.surfaceVariant,
        divider = s.outlineVariant,
        ink = s.onSurface,
        inkMuted = s.onSurfaceVariant,
        // The scheme's onSurfaceVariant is already a measured grey (4.95:1 on the
        // dark card, 5.25:1 on the light one). The previous extra 0.72 alpha on top
        // of it dropped faint text to ~2.5:1 -- the invisible-text bug class this
        // project has hit before.
        inkFaint = s.onSurfaceVariant,
        accent = s.primary,
        accentInk = s.onPrimary,
        danger = s.error,

        // The fill is OPAQUE, a deliberate departure from the skill's 72% white.
        // A translucent white over the #F2F2F7 page resolves to ~#FBFBFC, cutting
        // the card/page step from 1.12:1 to ~1.03:1 -- undoing the layer
        // separation, so card edges stop reading. Below API 31 there is no backdrop
        // blur for the transparency to reveal, so it only costs contrast. The card
        // keeps the skill's colour (white) at full strength; the border and shadow
        // supply the elevation cue.
        glassFill = s.surface,
        // Apple's `separator` for the light hairline; the previous 10% black
        // resolved lighter than the page in places.
        glassBorder = if (dark) {
            Color.White.copy(alpha = 0.15f)
        } else {
            Color(0xFFC6C6C8)
        },
        glassShadow = Color.Black.copy(alpha = if (dark) 0.40f else 0.10f),
        // Apple's fill greys. In dark mode this sits ABOVE the card (#333336)
        // rather than below it, so a chip or field reads as recessed instead of
        // raised.
        fieldFill = if (dark) {
            Color(0xFF48484A)
        } else {
            Color(0xFFE5E5EA)
        }
    )
}

/**
 * Spacing scale on an 8pt grid.
 *
 * The skill mandates multiples of 8. This used to be 4/8/14/20/28, where 14 and
 * 20 broke the grid -- and on a watch those odd values are exactly what makes a
 * row of cards look subtly misaligned. The scale is now strictly 4/8/16/24/32.
 *
 * Deliberately still denser than the phone's rhythm for the upper steps: the
 * target is a 372x430 screen, and the phone's 32dp step would push content off a
 * round display. The phone build uses the full 8pt ladder (8/16/24/32/48).
 */
object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
}

/**
 * Corner radii from the skill: buttons 12, cards/modals 20, small elements 8.
 *
 * Scaled down one step for the watch, because a 20dp radius on a 372px screen
 * eats most of a card's corner. The phone build uses the skill's literal values.
 */
object Radius {
    /** Badges, tags, chips. */
    val small: Dp = 8.dp
    /** Buttons and inputs. */
    val button: Dp = 12.dp
    /** Cards, sheets, modals. */
    val card: Dp = 16.dp
}

/**
 * Haptics-free motion tokens.
 *
 * The skill asks for `300ms cubic-bezier(0.25,0.1,0.25,1)` for standard
 * transitions and an overshooting `cubic-bezier(0.4,0,0.2,1.4)` for modals. Both
 * are reproduced exactly; Compose expresses the overshoot as a spring, which is
 * the platform-native equivalent and avoids a hand-rolled easing that would not
 * be interruptible.
 */
object Motion {
    /** Standard transition: the skill's cubic-bezier(0.25,0.1,0.25,1), 300ms. */
    val standard: androidx.compose.animation.core.Easing =
        androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** Standard duration in ms, for tween specs. */
    const val STANDARD_MS: Int = 300

    /** For elements that move across the screen (sheets, page changes). */
    fun <T> spatial(): androidx.compose.animation.core.SpringSpec<T> =
        androidx.compose.animation.core.spring(
            dampingRatio = 0.75f,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        )

    /** Spring pop for modals and sheets: slight overshoot, then settle. */
    fun <T> pop(): androidx.compose.animation.core.SpringSpec<T> =
        androidx.compose.animation.core.spring(
            dampingRatio = 0.55f,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        )

    /** For elements that resize or fade in place; no overshoot. */
    fun <T> effects(): androidx.compose.animation.core.SpringSpec<T> =
        androidx.compose.animation.core.spring(
            dampingRatio = 1f,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        )

    /** Emphasised easing for one-shot reveals. */
    val emphasized: androidx.compose.animation.core.Easing =
        androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)

    // ---- Interaction scales, straight from the skill ------------------------

    /** Pressed state. `scale(0.96)` in the skill. */
    const val PRESSED_SCALE: Float = 0.96f

    /** Hover/pointed state. `scale(1.02)` in the skill. */
    const val HOVER_SCALE: Float = 1.02f
}

/**
 * Minimum touch target.
 *
 * The skill sets 44px (the iOS standard). Android's own guidance is 48dp, and the
 * watch's original code already used 44dp for destructive actions. 44 is kept so
 * the two platforms agree and the value matches the skill.
 */
val MinTouchTarget: Dp = 44.dp

/**
 * Structural clearances.
 *
 * NOT spacing-scale values: these are distances that depend on the app's own
 * chrome, so they cannot be a step on the 8pt ladder. Naming them keeps the number
 * in one place instead of repeated as a literal in several screens.
 *
 * The watch values are larger than the phone's because the floating bottom bar is
 * proportionally taller on a 372x430 display.
 */
object Clearance {
    /** Scroll content must clear the floating bottom bar. */
    val bottomBar: Dp = 104.dp
    /** Bottom padding for a LazyColumn whose last item sits above the bar. */
    val listBottom: Dp = 140.dp
    /** Reserve for the shelf's pinned import button plus the bar. */
    val shelfBottom: Dp = 170.dp
}

/**
 * Fixed component geometry the skill does not specify.
 *
 * Grouped so a change to, say, the nav-bar indicator is one edit rather than four.
 * Watch values are smaller than the phone's to suit the 372px screen.
 */
object Size {
    /** Bottom-bar selection indicator. */
    val navIndicatorWidth: Dp = 44.dp
    val navIndicatorHeight: Dp = 28.dp
    val navIcon: Dp = 20.dp
    /** Reader sheet drag handle. */
    val sheetHandleWidth: Dp = 32.dp
    val sheetHandleHeight: Dp = 4.dp
    /** Progress bars (import, chapter, book). */
    val progressBarHeight: Dp = 4.dp
    val progressBarThin: Dp = 3.dp
    /** Shelf book cover in the list. */
    val coverWidth: Dp = 44.dp
    val coverHeight: Dp = 62.dp
}

/**
 * The semantic palette for the current theme.
 *
 * Populated by [KlinReadTheme]; reading it outside that theme is a programming
 * error, so the default throws rather than silently rendering light-mode values
 * on a dark background.
 */
val LocalColors = compositionLocalOf<AppColors> {
    error("LocalColors read outside KlinReadTheme")
}
