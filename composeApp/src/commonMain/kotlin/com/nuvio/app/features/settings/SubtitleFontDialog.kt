package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.SubtitleFontImportResult
import com.nuvio.app.features.player.SubtitleFontRepository
import com.nuvio.app.features.player.rememberSubtitleFontImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Picks the subtitle font, and imports or deletes the user's own .ttf/.otf fonts. */
@Composable
internal fun SubtitleFontDialog(
    selectedFontFile: String?,
    onFontSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { SubtitleFontRepository.ensureLoaded() } }
    val fonts by SubtitleFontRepository.fonts.collectAsState()
    var errorRes by remember { mutableStateOf<StringResource?>(null) }
    val importFont = rememberSubtitleFontImporter(
        onPicked = { fileName, bytes ->
            errorRes = when (val result = SubtitleFontRepository.import(fileName, bytes)) {
                is SubtitleFontImportResult.Imported -> {
                    onFontSelected(result.font.fileName)
                    null
                }
                SubtitleFontImportResult.NotAFont -> Res.string.settings_playback_subtitle_font_invalid
                SubtitleFontImportResult.TooLarge -> Res.string.settings_playback_subtitle_font_too_large
                SubtitleFontImportResult.Failed -> Res.string.settings_playback_subtitle_font_failed
            }
        },
        onError = { errorRes = Res.string.settings_playback_subtitle_font_failed },
    )
    val selected = selectedFontFile?.takeIf { file -> fonts.any { it.fileName == file } }

    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_playback_subtitle_font),
        message = stringResource(Res.string.settings_playback_subtitle_font_note),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DialogOption(
                text = stringResource(Res.string.settings_playback_subtitle_font_default),
                selected = selected == null,
                onClick = { onFontSelected(null) },
            )
            fonts.forEach { font ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DialogOption(
                        text = font.familyName,
                        description = font.fileName,
                        selected = selected == font.fileName,
                        onClick = { onFontSelected(font.fileName) },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { SubtitleFontRepository.delete(font) }) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(
                                Res.string.settings_playback_subtitle_font_delete,
                                font.familyName,
                            ),
                            tint = MaterialTheme.nuvio.colors.textSecondary,
                        )
                    }
                }
            }
        }

        errorRes?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.danger,
            )
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.settings_playback_subtitle_font_import),
                onClick = {
                    errorRes = null
                    importFont()
                },
            )
            DialogButton(
                text = stringResource(Res.string.action_done),
                style = DialogButtonStyle.Primary,
                onClick = onDismiss,
            )
        }
    }
}
