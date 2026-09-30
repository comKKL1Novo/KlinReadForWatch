package com.klin.read.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Generated cover for books that carry no cover art.
 *
 * TXT, HTML and UMD have no image to embed, and on a Chinese-language shelf that
 * is most books, so this is the normal case rather than a fallback for errors.
 *
 * Watch-specific choices:
 *   - a two-colour flat fill per book rather than a per-frame gradient shader;
 *     the gradient costs a shader compile and a draw with a blend, which is not
 *     worth it on a 372px screen;
 *   - the gradient object is `remember`ed against the title so it is not rebuilt
 *     on every recomposition;
 *   - a single large glyph, which stays legible at thumbnail size.
 */
private val PLACEHOLDER_PAIRS = listOf(
    Color(0xFFFF9BD2) to Color(0xFFD93A88), // icon pink
    Color(0xFF9BB8FF) to Color(0xFF4A5FD9),
    Color(0xFF9BE8C4) to Color(0xFF2A9D6E),
    Color(0xFFFFD79B) to Color(0xFFD98A2B),
    Color(0xFFD5A6FF) to Color(0xFF7C3ACD),
    Color(0xFF9BE0F5) to Color(0xFF2A7E9D),
)

@Composable
fun GeneratedCover(
    title: String,
    modifier: Modifier = Modifier
) {
    val pair = PLACEHOLDER_PAIRS[
        (title.hashCode().let { if (it < 0) -it else it }) % PLACEHOLDER_PAIRS.size
    ]
    val initial = title.trim().firstOrNull()?.toString() ?: "书"

    // Keyed on the title so a scrolled row does not rebuild its brush, and
    // disposed with the row when it leaves composition.
    val brush = remember(title) { Brush.verticalGradient(listOf(pair.first, pair.second)) }

    Box(
        modifier = modifier.background(brush),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
