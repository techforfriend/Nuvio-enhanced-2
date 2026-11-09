package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.support_buymeacoffee_button
import nuvio.composeapp.generated.resources.support_buymeacoffee_message
import nuvio.composeapp.generated.resources.support_buymeacoffee_title
import org.jetbrains.compose.resources.stringResource

private const val BUY_ME_A_COFFEE_URL = "https://buymeacoffee.com/raja_ji"

@Composable
internal fun BuyMeACoffeeSupportCard(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    val accentColors = listOf(tokens.colors.accent, tokens.colors.accent.copy(alpha = 0.6f))
    val backgroundBrush = remember(accentColors) {
        Brush.linearGradient(
            listOf(accentColors[0].copy(alpha = 0.20f), accentColors[1].copy(alpha = 0.10f))
        )
    }
    val borderBrush = remember(accentColors) {
        Brush.linearGradient(
            listOf(accentColors[0].copy(alpha = 0.55f), accentColors[1].copy(alpha = 0.55f))
        )
    }
    val shape = RoundedCornerShape(NuvioTokens.Radius.xl)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = tokens.colors.surfaceCard,
        shape = shape,
        border = BorderStroke(tokens.borders.hairline, borderBrush),
    ) {
        Box(
            modifier = Modifier
                .background(backgroundBrush)
                .padding(20.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = tokens.colors.accent.copy(alpha = 0.13f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(72.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.support_buymeacoffee_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(Res.string.support_buymeacoffee_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
                Button(
                    onClick = { uriHandler.openUri(BUY_ME_A_COFFEE_URL) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.colors.accent,
                        contentColor = tokens.colors.onAccent,
                    ),
                ) {
                    Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.support_buymeacoffee_button))
                }
            }
        }
    }
}
