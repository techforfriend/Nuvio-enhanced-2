package com.nuvio.app.features.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.ProfileMeshBackground
import com.nuvio.app.core.ui.ThemeColors
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.membership.ProfileBackgroundRepository
import com.nuvio.app.features.membership.ProfileBackgroundSelection
import com.nuvio.app.features.membership.resolveProfileBackground

@Composable
fun ProfileBackgroundBackdrop(
    profile: NuvioProfile?,
    modifier: Modifier = Modifier,
) {
    val memberAccess by remember {
        MemberAccessRepository.ensureStarted()
        MemberAccessRepository.access
    }.collectAsStateWithLifecycle()
    val backgroundCatalog by ProfileBackgroundRepository.catalog.collectAsStateWithLifecycle()
    val profileColor = remember(profile?.avatarColorHex) {
        profile?.avatarColorHex?.let(::parseHexColor) ?: Color(0xFF1E88E5)
    }
    val backgroundSelection = remember(
        profile?.profileBackgroundId,
        profile?.profileBackgroundUrl,
        memberAccess.entitlements,
    ) {
        profile?.let { resolveProfileBackground(it, memberAccess.entitlements) }
    }

    BoxWithConstraints(modifier = modifier) {
        val isPortrait = maxHeight > maxWidth
        val presetPalette = when (val sel = backgroundSelection) {
            is ProfileBackgroundSelection.Catalog -> when (sel.id) {
                "preset:gold" -> ThemeColors.Gold
                "preset:jade" -> ThemeColors.Jade
                "preset:rose_gold" -> ThemeColors.RoseGold
                "preset:arctic_blue" -> ThemeColors.ArcticBlue
                "preset:graphite" -> ThemeColors.Graphite
                "preset:messenger" -> ThemeColors.Messenger
                "preset:amethyst" -> ThemeColors.Amethyst
                "preset:blossom" -> ThemeColors.Blossom
                "preset:lagoon" -> ThemeColors.Lagoon
                "preset:sunset" -> ThemeColors.Sunset
                else -> null
            }
            else -> null
        }

        LaunchedEffect(backgroundSelection, isPortrait) {
            val selectedId = (backgroundSelection as? ProfileBackgroundSelection.Catalog)?.id
            if (selectedId != null && !selectedId.startsWith("preset:")) {
                ProfileBackgroundRepository.loadSelectedAndPreload(selectedId, isPortrait)
            }
        }
        val backgroundModel: Any? = when (backgroundSelection) {
            is ProfileBackgroundSelection.Catalog -> {
                if (presetPalette != null) null
                else backgroundCatalog
                    .firstOrNull { it.id == backgroundSelection.id }
                    ?.let { background ->
                        if (isPortrait) {
                            background.portraitImageBytes ?: background.landscapeImageBytes
                        } else {
                            background.landscapeImageBytes
                        }
                    }
            }
            is ProfileBackgroundSelection.Custom -> backgroundSelection.url
            null -> null
        }

        if (presetPalette != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                presetPalette.secondary.copy(alpha = 0.5f),
                                presetPalette.secondaryVariant.copy(alpha = 0.25f),
                                presetPalette.background,
                            ),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f)),
            )
        } else if (backgroundModel == null) {
            ProfileMeshBackground(
                profileColor = profileColor,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AsyncImage(
                model = backgroundModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f)),
            )
        }
    }
}
