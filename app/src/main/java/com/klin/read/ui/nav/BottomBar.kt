package com.klin.read.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.klin.read.ui.design.LocalColors
import com.klin.read.ui.design.LocalDarkTheme
import com.klin.read.ui.design.Motion
import com.klin.read.ui.design.Space

enum class HomeTab(val label: String) {
    SHELF("书架"),
    MUSIC("音乐"),
    SETTINGS("设置"),
    ABOUT("作者");

    val icon: ImageVector
        get() = when (this) {
            SHELF -> Icons.AutoMirrored.Filled.MenuBook
            MUSIC -> Icons.Filled.LibraryMusic
            SETTINGS -> Icons.Filled.Settings
            ABOUT -> Icons.Filled.Info
        }
}

/**
 * Floating pill navigation bar with a frosted-glass treatment.
 *
 * The skill's navigation-bar blueprint: a sticky, elevated surface carrying the
 * glass material (translucent fill + blur + 0.5px hairline) and a bottom border.
 * The bar is inset from the screen edges and fully rounded, so the app background
 * shows around it -- that is what makes it read as a floating control rather than
 * part of the chrome.
 *
 * The blur half of the material is not reproducible here: `Modifier.blur` needs
 * API 31+ and the watch runs Android 11, where it would silently do nothing.
 * A vertical gradient over a translucent fill is the portable substitute, and it
 * is what gives the surface its depth on this device.
 *
 * Spacing is on the 8pt grid, and the tokens come from [AppColors] rather than
 * local hardcoded whites, so the bar follows the theme instead of drifting from
 * it.
 */
@Composable
fun BottomBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalColors.current
    val dark = LocalDarkTheme.current

    // Derived from the theme's glass tokens, so the bar cannot drift from the
    // cards it floats above.
    val glassTop = if (dark) c.glassFill.copy(alpha = 0.82f) else c.glassFill.copy(alpha = 0.92f)
    val glassBottom = if (dark) c.glassFill.copy(alpha = 0.55f) else c.glassFill.copy(alpha = 0.78f)
    val rim = c.glassBorder

    val pill: Shape = RoundedCornerShape(999.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Space.xl, vertical = Space.sm)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(pill)
                .background(Brush.verticalGradient(listOf(glassTop, glassBottom)))
                .border(0.5.dp, rim, pill)
                .drawBehind {
                    // Sheen: a brighter band across the top inner edge. This is
                    // the skill's "material depth" read without a real blur.
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (dark) 0.10f else 0.35f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = size.height * 0.55f
                        )
                    )
                    // Faint drop shadow underneath, drawn inside the pill so no
                    // extra layer is needed.
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, c.glassShadow),
                            startY = size.height * 0.7f,
                            endY = size.height
                        )
                    )
                }
                .padding(horizontal = Space.xs, vertical = Space.sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeTab.entries.forEach { tab ->
                GlassTabItem(
                    tab = tab,
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GlassTabItem(
    tab: HomeTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalColors.current
    val tint by animateColorAsState(
        targetValue = if (selected) c.ink else c.inkFaint,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = Motion.STANDARD_MS,
            easing = Motion.standard
        ),
        label = "tabTint"
    )
    // Selected items sit at full size; unselected ones pull back. This is the
    // skill's press/hover scale idea applied to selection state.
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = Motion.spatial(),
        label = "tabScale"
    )
    val pillAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = Motion.effects(),
        label = "pillAlpha"
    )

    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 28.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.ink.copy(alpha = 0.10f * pillAlpha)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier
                    .size(20.dp)
                    .scale(scale)
            )
        }
        Text(
            text = tab.label,
            color = tint,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            letterSpacing = (-0.011).em,
            maxLines = 1,
            modifier = Modifier.padding(top = Space.xs)
        )
    }
}
