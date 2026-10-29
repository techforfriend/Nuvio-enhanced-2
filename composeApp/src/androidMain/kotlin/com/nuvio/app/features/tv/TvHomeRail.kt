package com.nuvio.app.features.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.stableKey

/**
 * Phase 2: one Home rail = a title plus a horizontally scrolling row of
 * [TvCard]s. LazyRow handles D-pad scrolling to the focused card.
 * [firstCardFocusRequester] is only passed for the first rail's first card,
 * so the Home screen opens with focus in a known place.
 */
@Composable
fun TvHomeRail(
    title: String,
    subtitle: String,
    items: List<MetaPreview>,
    onItemClick: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
    firstCardFocusRequester: FocusRequester? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 48.dp),
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 48.dp),
            )
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 20.dp),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { it.stableKey() }) { item ->
                TvCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    focusRequester = if (item == items.firstOrNull()) firstCardFocusRequester else null,
                )
            }
        }
    }
}
