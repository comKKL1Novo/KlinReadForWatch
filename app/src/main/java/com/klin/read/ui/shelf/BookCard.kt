package com.klin.read.ui.shelf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.klin.read.data.BookEntity
import com.klin.read.ui.design.LocalColors
import com.klin.read.ui.design.Motion
import com.klin.read.ui.design.Radius
import com.klin.read.ui.design.Size
import com.klin.read.ui.design.Space
import java.io.File

/**
 * A book in the shelf grid.
 *
 * The shelf used to be a vertical list of wide rows, which on a 372x430 watch
 * showed barely two books at a time and left a 44x62 cover too small to recognise
 * a book by. A two-column grid shows four at once with covers roughly 50% larger.
 *
 * Two columns rather than the phone's three: at 372px a third column leaves each
 * cover about 100px wide, which is smaller than the row thumbnail this replaced.
 *
 * The metadata line is dropped entirely here. `EPUB · 415 字` is three short lines
 * of text per card on a watch, which crowds out the cover; the format is already
 * implied by the book opening successfully, and the count is visible on the
 * reading screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    book: BookEntity,
    progress: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The skill's active state: the whole card pulls back slightly.
    val scale by animateFloatAsState(
        targetValue = if (pressed) Motion.PRESSED_SCALE else 1f,
        animationSpec = Motion.pop(),
        label = "cardPress"
    )

    Column(
        modifier = modifier
            .scale(scale)
            // combinedClickable rather than clickable: long-press opens the
            // actions sheet, and two pointerInput modifiers on one node is the
            // pattern that already broke the slider's gesture handling.
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 2:3 is the standard book cover ratio, so changing the column
                // count never distorts artwork.
                .aspectRatio(2f / 3f)
                .shadow(
                    elevation = 3.dp,
                    shape = RoundedCornerShape(Radius.small),
                    ambientColor = c.glassShadow,
                    spotColor = c.glassShadow
                )
                .clip(RoundedCornerShape(Radius.small))
                .background(c.fieldFill)
                .border(
                    width = 0.5.dp,
                    color = c.glassBorder,
                    shape = RoundedCornerShape(Radius.small)
                )
        ) {
            val coverPath = book.coverPath
            if (coverPath != null) {
                AsyncImage(
                    model = File(coverPath),
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                GeneratedCover(title = book.title, modifier = Modifier.fillMaxSize())
            }

            // Progress over the cover's bottom edge, so progress costs no extra
            // vertical space on a screen this short.
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(Size.progressBarThin)
                        .background(c.glassShadow)
                ) {
                    /*
                     * `fillMaxHeight`, NOT `fillMaxSize`, on the inner bar.
                     *
                     * The inner box had both `.fillMaxWidth(progress)` and
                     * `.fillMaxSize()`, and `fillMaxSize` sets width as well as
                     * height -- so it overrode the fraction and the bar rendered
                     * full width for any progress above zero. The shelf therefore
                     * showed either nothing or a completely full bar; the ratio was
                     * computed correctly and then discarded at draw time.
                     */
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(c.accent)
                    )
                }
            }

            // "读完" marker, top-right of the cover.
            if (book.isFinished) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(Radius.small))
                        .background(c.accent)
                        .padding(horizontal = Space.xs)
                ) {
                    Text(
                        text = "完",
                        color = c.accentInk,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.011).em
                    )
                }
            }
        }

        Text(
            text = book.title,
            color = c.ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.011).em,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Space.xs)
        )
    }
}

