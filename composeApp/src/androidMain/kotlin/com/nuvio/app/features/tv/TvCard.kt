package com.nuvio.app.features.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.nuvio.app.features.home.MetaPreview

/**
 * Phase 2 TV poster card, TV-sized: large posters (not phone-sized),
 * a stronger focus zoom and a thick white focus border, so rows read
 * as TV rows from the couch. D-pad center clicks the card.
 * The poster URL comes from [MetaPreview] as published by HomeRepository
 * (custom-poster URLs already applied there), so plain Coil loading is enough.
 */
@Composable
fun TvCard(
    item: MetaPreview,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.15f else 1f, label = "tvCardScale")
    val imageUrl = item.poster ?: item.rawPosterUrl
    Column(
        modifier = modifier
            .width(180.dp)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                .width(180.dp)
                .height(260.dp)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                .border(
                    width = 3.dp,
                    color = if (focused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = item.name,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = item.name,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = item.name,
            modifier = Modifier
                .width(180.dp)
                .padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) Color.White else Color.White.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
