package com.nuvio.app.features.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_root_downloads_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryDownloadsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = stringResource(Res.string.compose_settings_root_downloads_title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
