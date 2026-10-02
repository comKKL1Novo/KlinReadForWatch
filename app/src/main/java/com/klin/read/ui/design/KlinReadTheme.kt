package com.klin.read.ui.design

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * KlinRead's Material 3 Expressive styling, shared with the phone build.
 *
 * Why this is hand-built rather than using `MaterialExpressiveTheme`: in
 * material3 1.4.0 (the newest version this project can use) that API — together
 * with `MotionScheme` and `ExperimentalMaterial3ExpressiveApi` — is marked
 * `internal`, so application code cannot call it. It only became public in
 * 1.5.0-alpha, which in turn demands AGP 9.1+ and compileSdk 37. Rather than
 * force a toolchain jump for a visual style, the three things that actually make
 * "Expressive" read as Expressive are applied directly:
 *
 *   1. a saturated, seeded colour scheme (falling back to the wallpaper palette
 *      on Android 12+),
 *   2. a rounder shape scale than the M3 defaults,
 *   3. springy, spatial motion instead of linear transitions.
 *
 * Behaviour stays byte-for-byte on the stable dependency set.
 */

// ---- Brand palette, seeded from the launcher icon pink ----------------------

private val Pink40 = Color(0xFF8E2A5E)
private val Pink80 = Color(0xFFFFB0D0)
private val Navy = Color(0xFF3A0021)
private val OnPink = Color(0xFFFFFFFF)

/**
 * Shape scale, on the Apple skill's radii rather than M3's defaults.
 *
 * The skill specifies 8 / 12 / 20 for small elements, buttons, and cards. On a
 * 372x430 display a literal 20dp card radius eats most of the corner, so the
 * card step is pulled to 16 and the rest of the ladder follows at one step down.
 * See [Radius] in Design.kt for the phone-scale values.
 */
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.small),
    small = RoundedCornerShape(Radius.small),
    medium = RoundedCornerShape(Radius.button),
    large = RoundedCornerShape(Radius.card),
    extraLarge = RoundedCornerShape(Radius.card + 4.dp),
)

/*
 * Motion lives in Design.kt as `Motion`, so there is one definition rather than
 * two competing sets of spring specs.
 */

private fun lightScheme(): ColorScheme = lightColorScheme(
    primary = Pink40,
    onPrimary = OnPink,
    primaryContainer = Color(0xFFFFD9E7),
    onPrimaryContainer = Navy,

    secondary = Color(0xFF8A5069),
    onSecondary = OnPink,
    secondaryContainer = Color(0xFFFFD9E4),
    onSecondaryContainer = Color(0xFF37081F),

    tertiary = Color(0xFF7A5A00),
    onTertiary = OnPink,
    tertiaryContainer = Color(0xFFFFE08A),
    onTertiaryContainer = Color(0xFF261A00),

    // Neutrals are Apple's system greys, not pink tints. The previous pair
    // (#FFF0F5 page / #FFF8F9 card) was only ~5% apart in luminance, so a card was
    // effectively invisible against the page and the layout read as one flat pink
    // field. No amount of grid or tracking work fixes that -- the palette was the
    // problem. Pink stays for primary actions, selection and the brand.
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    // Backs the unfilled slider track and chips; has to differ from both the page
    // and the cards.
    surfaceVariant = Color(0xFFE5E5EA),
    // Measured against both the card and the page; see the phone build's
    // PaletteContrastTest for the same reasoning. Apple's 60%-alpha secondaryLabel
    // lands at 3.4:1 on white, below the 4.5:1 these 11-13sp labels need.
    onSurfaceVariant = Color(0xFF6C6C6C),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFC6C6C8),
    error = Color(0xFFFF3B30),
    onError = Color(0xFFFFFFFF),
)

private fun darkScheme(): ColorScheme = darkColorScheme(
    primary = Pink80,
    onPrimary = Color(0xFF3A0021),
    primaryContainer = Color(0xFF5C1039),
    onPrimaryContainer = Color(0xFFFFD9E7),

    secondary = Color(0xFFFFB0C9),
    onSecondary = Color(0xFF521D33),
    secondaryContainer = Color(0xFF6C3349),
    onSecondaryContainer = Color(0xFFFFD9E4),

    tertiary = Color(0xFFE9C349),
    onTertiary = Color(0xFF3E2E00),
    tertiaryContainer = Color(0xFF584400),
    onTertiaryContainer = Color(0xFFFFE08A),

    // The page is #1C1C1E, NOT true black: on a shelf full of white covers a pure
    // black field halates around every edge, and it left the nav bar with nothing
    // to separate it from. The card is #333336 rather than Apple's #2C2C2E because
    // against #1C1C1E that measures only 1.22:1 -- weaker separation than a card
    // needs (1.35:1 here). Both are measured in the phone build's
    // PaletteContrastTest.
    background = Color(0xFF1C1C1E),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF333336),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF48484A),
    // Measured against the #333336 card: #A0A0A0 gives 4.82:1, clearing the
    // 4.5:1 these small labels need. #9A9A9A measured 4.475:1 and failed.
    onSurfaceVariant = Color(0xFFA0A0A0),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFF48484A),
    error = Color(0xFFFF453A),
    onError = Color(0xFF000000),
)

/**
 * Typography following the skill's SF Pro specs.
 *
 * Two things the skill mandates and the M3 defaults do not do:
 *
 *   1. **Negative tracking.** Headlines carry letter-spacing -0.022em and body
 *      text -0.011em. Compose expresses tracking in em, so those values are used
 *      directly. This is what makes Apple's type read as tight and deliberate;
 *      without it, large text looks loose and generic.
 *   2. **Font weight 600 on headlines, 400 on body.**
 *
 * The skill forbids Inter/Roboto/Helvetica and asks for SF Pro, falling back to
 * "the system's native sans-serif equivalent to maintain OS-level consistency".
 * On Android that sanctioned fallback is the system default face, which is what
 * `FontFamily.Default` resolves to -- so the family is left at the default rather
 * than shipping a bundled font that would both bloat the APK and, on a watch,
 * render Chinese with worse hinting than the system face.
 *
 * Headline sizes are pulled in for the 372px screen; the ratios and tracking are
 * the skill's.
 */
private val AppTypography = Typography().let { base ->
    val headlineTracking = (-0.022).em
    val bodyTracking = (-0.011).em

    base.copy(
        displayLarge = base.displayLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = headlineTracking
        ),
        displayMedium = base.displayMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = headlineTracking
        ),
        displaySmall = base.displaySmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = headlineTracking
        ),
        headlineLarge = base.headlineLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = headlineTracking
        ),
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = headlineTracking
        ),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = headlineTracking
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = headlineTracking
        ),
        titleMedium = base.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = bodyTracking
        ),
        titleSmall = base.titleSmall.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = bodyTracking
        ),
        bodyLarge = base.bodyLarge.copy(
            fontWeight = FontWeight.Normal,
            letterSpacing = bodyTracking
        ),
        bodyMedium = base.bodyMedium.copy(
            fontWeight = FontWeight.Normal,
            letterSpacing = bodyTracking
        ),
        bodySmall = base.bodySmall.copy(
            fontWeight = FontWeight.Normal,
            letterSpacing = bodyTracking
        ),
        labelLarge = base.labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = bodyTracking
        ),
        labelMedium = base.labelMedium.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = bodyTracking
        ),
        labelSmall = base.labelSmall.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = bodyTracking
        ),
    )
}

/** Whether the app is currently rendering its dark palette. */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun KlinReadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Follow the Android 12+ wallpaper palette instead of the seeded pink. */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> darkScheme()
        else -> lightScheme()
    }

    MaterialTheme(
        colorScheme = scheme,
        shapes = ExpressiveShapes,
        typography = AppTypography
    ) {
        // The semantic palette is derived from the scheme above, so screens and
        // Material components can never drift apart.
        CompositionLocalProvider(
            LocalColors provides rememberAppColors(),
            LocalDarkTheme provides darkTheme,
            content = content
        )
    }
}
