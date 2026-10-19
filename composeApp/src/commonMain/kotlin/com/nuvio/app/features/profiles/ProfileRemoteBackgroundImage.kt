package com.nuvio.app.features.profiles

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

@Composable
fun ProfileRemoteBackgroundImage(
    imageUrl: String?,
    profileIndex: Int?,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (imageUrl == null) return

    AsyncImage(
        model = imageUrl,
        contentDescription = null,
        modifier = modifier.alpha(alpha.coerceIn(0f, 1f)),
        contentScale = contentScale,
    )
}
