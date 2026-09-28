package com.nuvio.app.features.ratings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.user_rating_action_rate
import nuvio.composeapp.generated.resources.user_rating_action_rated
import org.jetbrains.compose.resources.stringResource

private const val STAR_COUNT = 5

/**
 * The viewer's own 1–10 rating drawn as five stars in half-star steps (10 → 5, 9 → 4.5).
 * Unrated shows five outlined stars. The whole row is one tap target that opens the rating popup.
 */
@Composable
fun UserRatingStars(
    rating: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    starSize: Dp = 30.dp,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val description = rating?.let { stringResource(Res.string.user_rating_action_rated, it) }
        ?: stringResource(Res.string.user_rating_action_rate)
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val halfSteps = rating?.coerceIn(0, STAR_COUNT * 2) ?: 0
        for (index in 0 until STAR_COUNT) {
            val fill = when {
                halfSteps >= (index + 1) * 2 -> 1f
                halfSteps == index * 2 + 1 -> 0.5f
                else -> 0f
            }
            Box(modifier = Modifier.size(starSize), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.StarBorder,
                    contentDescription = null,
                    tint = if (fill > 0f) accent else outline,
                    modifier = Modifier.size(starSize),
                )
                if (fill > 0f) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier
                            .size(starSize)
                            .drawWithContent {
                                val visibleWidth = size.width * fill
                                val left = if (isRtl) size.width - visibleWidth else 0f
                                clipRect(left = left, right = left + visibleWidth) {
                                    this@drawWithContent.drawContent()
                                }
                            },
                    )
                }
            }
        }
    }
}
