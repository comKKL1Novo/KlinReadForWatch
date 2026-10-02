package com.klin.read.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Apple UI Design System – Verified: 8pt Grid, SF Pro Typography,
 * Material-Depth, Natural Spring Motion
 */

/**
 * A raised surface panel.
 *
 * The skill requires every elevated surface to carry BOTH an rgba fill and a
 * blur ("Does the background have BOTH rgba transparency and backdrop-filter?").
 * Compose has no `backdrop-filter`: a true blur of what is behind a composable
 * needs `Modifier.blur`, which on this dependency set requires API 31+ and, on
 * the watch's Android 11, silently does nothing. Blurring the panel's own content
 * instead would just make the text unreadable.
 *
 * So the material is expressed with the parts that ARE portable:
 *
 *   - a translucent fill ([AppColors.glassFill]),
 *   - a 0.5dp hairline border ([AppColors.glassBorder]),
 *   - a soft drop shadow for depth ([AppColors.glassShadow]),
 *
 * which is the same visual language minus the blur, and degrades predictably on
 * API 24 instead of no-opping on the devices that matter. The tint is drawn from
 * the theme's surface so the app's pink identity survives the change.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = Radius.card,
    filled: Boolean = true,
    contentPadding: Dp = Space.md,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .then(
                if (filled) {
                    Modifier
                        .shadow(
                            elevation = 2.dp,
                            shape = shape,
                            ambientColor = c.glassShadow,
                            spotColor = c.glassShadow
                        )
                        .clip(shape)
                        .background(c.glassFill)
                        // 0.5dp is the skill's hairline; it is exempt from the 8pt
                        // rule by the skill's own checklist.
                        .border(0.5.dp, c.glassBorder, shape)
                } else {
                    Modifier.clip(shape)
                }
            )
            .padding(contentPadding),
        content = content
    )
}

/**
 * Section label: small, spaced, muted.
 *
 * Uses 0.06em tracking (the skill's uppercase-label treatment) rather than a
 * hardcoded sp value, so it scales with the font instead of drifting.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Text(
        text = text,
        color = c.inkFaint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.06.em,
        modifier = modifier.padding(
            start = Space.sm,
            bottom = Space.sm,
            top = Space.md
        )
    )
}

/**
 * Screen title.
 *
 * Carries the skill's -0.022em negative tracking for headlines, which is what
 * makes large type read as deliberate rather than merely big.
 */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Text(
        text = text,
        color = c.ink,
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.022).em,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** Hairline divider, on the skill's 0.5dp rather than a full pixel. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Box(
        modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(c.glassBorder)
    )
}

/**
 * Wraps a clickable so it presses down to 0.96, per the skill's active state.
 *
 * Compose has no hover on a watch, so `scale(1.02)` is not reachable here; the
 * press state is the one that matters on a touch-only device and is applied
 * everywhere a surface is tappable.
 */
@Composable
private fun pressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESSED_SCALE else 1f,
        animationSpec = Motion.pop(),
        label = "press"
    )
    return scale
}

/**
 * Text input.
 *
 * Height and radius follow the skill's search-input spec (a compact field on a
 * translucent recessed fill). The 4.5:1 contrast requirement is why the
 * placeholder uses `inkFaint` rather than a fixed grey -- `inkFaint` tracks the
 * theme, and a hardcoded grey is what made an earlier error message invisible on
 * the watch's dark background.
 */
@Composable
fun FlatTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null
) {
    val c = LocalColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(Radius.button))
            .background(c.fieldFill)
            .border(0.5.dp, c.glassBorder, RoundedCornerShape(Radius.button))
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Box(Modifier.padding(end = Space.sm))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                textStyle = LocalTextStyle.current.copy(
                    color = c.ink,
                    fontSize = 15.sp,
                    letterSpacing = (-0.011).em
                ),
                cursorBrush = SolidColor(c.accent),
                visualTransformation = if (isPassword) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    color = c.inkFaint,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Primary button: solid accent, 12dp radius, 44dp tall, presses to 0.96.
 *
 * Matches the skill's blueprint (height 44, radius 12) and adds the drop shadow
 * the skill specifies for primary actions.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    val c = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    val bg = when {
        !enabled -> c.surfaceMuted
        destructive -> c.danger
        else -> c.accent
    }
    val fg = when {
        !enabled -> c.inkFaint
        destructive -> Color.White
        else -> c.accentInk
    }
    val shape = RoundedCornerShape(Radius.button)
    Box(
        modifier = modifier
            .scale(scale)
            .then(
                if (enabled) {
                    Modifier.shadow(
                        elevation = 4.dp,
                        shape = shape,
                        ambientColor = c.glassShadow,
                        spotColor = c.glassShadow
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(bg)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .defaultMinSize(minHeight = MinTouchTarget)
            .padding(horizontal = Space.lg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.011).em,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Secondary button: translucent fill, same geometry as the primary. */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    val shape = RoundedCornerShape(Radius.button)
    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(c.fieldFill)
            .border(0.5.dp, c.glassBorder, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .defaultMinSize(minHeight = MinTouchTarget)
            .padding(horizontal = Space.md),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) c.ink else c.inkFaint,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.011).em,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Standard list row.
 *
 * Minimum height is the skill's 44px touch target, and the padding is on the 8pt
 * grid (8 vertical + 44 min) instead of the previous 15dp, which was neither a
 * grid multiple nor tall enough to be a comfortable target.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val c = LocalColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (leading != null) {
            leading()
            Box(Modifier.padding(end = Space.md))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = c.ink,
                fontSize = 15.sp,
                letterSpacing = (-0.011).em,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = c.inkMuted,
                    fontSize = 12.5.sp,
                    letterSpacing = (-0.011).em,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Space.xs)
                )
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * Filter chip.
 *
 * Radius follows the skill's "small elements: 8px" rule rather than a full pill,
 * so chips read as tags rather than as buttons, and the minimum height keeps them
 * tappable on a watch.
 */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val scale = pressScale(interaction)
    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(Radius.small))
            .background(if (selected) c.accent else c.fieldFill)
            .then(
                if (selected) {
                    Modifier
                } else {
                    Modifier.border(0.5.dp, c.glassBorder, RoundedCornerShape(Radius.small))
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .defaultMinSize(minHeight = 32.dp)
            .padding(horizontal = Space.md, vertical = Space.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) c.accentInk else c.inkMuted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            letterSpacing = (-0.011).em,
            maxLines = 1
        )
    }
}

/** Empty-state block. */
@Composable
fun EmptyHint(title: String, detail: String, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Column(
        modifier = modifier.fillMaxWidth().padding(Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            color = c.inkMuted,
            fontSize = 15.sp,
            letterSpacing = (-0.011).em
        )
        Text(
            detail,
            color = c.inkFaint,
            fontSize = 12.5.sp,
            letterSpacing = (-0.011).em,
            modifier = Modifier.padding(top = Space.sm)
        )
    }
}
