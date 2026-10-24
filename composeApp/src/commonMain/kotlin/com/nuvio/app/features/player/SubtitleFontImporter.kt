package com.nuvio.app.features.player

import androidx.compose.runtime.Composable

@Composable
internal expect fun rememberSubtitleFontImporter(
    onPicked: (fileName: String?, bytes: ByteArray) -> Unit,
    onError: () -> Unit,
): () -> Unit
