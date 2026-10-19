package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import com.kyant.backdrop.isRuntimeShaderSupported
import com.nuvio.app.core.ui.glass.LiquidGlassMaterial
import com.nuvio.app.core.ui.glass.LiquidGlassStyle
import com.nuvio.app.core.ui.glass.isLiquidGlassSupported
import com.nuvio.app.core.ui.glass.isLiquidGlassAvailable
import com.nuvio.app.core.ui.glass.isLiquidRefractionSupported
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.formatHexColor
import com.nuvio.app.core.ui.parseHexColor
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.LocalNuvioSheetBackdrop
import com.nuvio.app.core.ui.nuvioSheetSurface
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.floatingNavigationGlowSupported
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.glass.LiquidGlassEffectDefaults
import com.nuvio.app.core.ui.glass.LiquidGlassEffectSettings
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Keep high-frequency slider draft subscriptions inside the open settings-sheet subtree.
 * Updating a glass effect should not recompose the Appearance settings item behind the modal.
 */
@Composable
internal fun NavigationBarLiquidGlassEffectDrafts(
    content: @Composable (LiquidGlassEffectSettings, LiquidGlassEffectSettings) -> Unit,
) {
    val floatingBarEffects by ThemeSettingsRepository.navBarLiquidGlassBarEffects.collectAsStateWithLifecycle()
    val selectionLensEffects by ThemeSettingsRepository.navBarLiquidGlassSelectionEffects.collectAsStateWithLifecycle()
    content(floatingBarEffects, selectionLensEffects)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun NavigationBarSettingsSheet(
    isTablet: Boolean,
    tabletLayout: Boolean = false,
    selectedStyle: NavBarStyle,
    onStyleSelected: (NavBarStyle) -> Unit,
    glowEnabled: Boolean,
    onGlowChanged: (Boolean) -> Unit,
    defaultIconColorEnabled: Boolean,
    onDefaultIconColorEnabledChanged: (Boolean) -> Unit,
    defaultIconColor: Int,
    onDefaultIconColorChanged: (Int) -> Unit,
    onSaveDefaultIconColor: () -> Unit,
    liquidGlassEnabled: Boolean,
    onLiquidGlassChanged: (Boolean) -> Unit,
    floatingBarEffects: LiquidGlassEffectSettings,
    onFloatingBarEffectsChanged: (LiquidGlassEffectSettings) -> Unit,
    selectionLensEffects: LiquidGlassEffectSettings,
    onSelectionLensEffectsChanged: (LiquidGlassEffectSettings) -> Unit,
    onSaveLiquidGlassEffectSettings: () -> Unit,
    glassStyle: LiquidGlassStyle = LiquidGlassStyle.Default,
    onGlassStyleChanged: (LiquidGlassStyle) -> Unit = {},
    selectedPosition: NavBarPosition,
    onPositionSelected: (NavBarPosition) -> Unit,
    onDismiss: () -> Unit,
) {
    val liquidGlassSupported = isLiquidGlassAvailable() && isLiquidRefractionSupported() && isRuntimeShaderSupported()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        // Persist synchronously before beginning any suspend/animation work. This covers both
        // explicit Done and platform dismissal callbacks that may immediately dispose the sheet.
        onSaveLiquidGlassEffectSettings()
        onSaveDefaultIconColor()
        scope.launch {
            dismissNuvioBottomSheet(sheetState, onDismiss)
        }
    }
    var defaultIconColorExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    NuvioModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
        ) {
            stickyHeader(key = "navigation-bar-preview") {
                NavigationBarPreviewStickyHeader(
                    listState = listState,
                    selectedStyle = selectedStyle,
                    isTablet = isTablet,
                    glowEnabled = glowEnabled,
                    liquidGlassEnabled = liquidGlassEnabled,
                    selectedPosition = selectedPosition,
                    tabletLayout = tabletLayout,
                    floatingBarEffects = floatingBarEffects,
                    selectionLensEffects = selectionLensEffects,
                    defaultIconColorEnabled = defaultIconColorEnabled,
                    defaultIconColor = defaultIconColor,
                    glassStyle = glassStyle,
                )
            }
            if (!isTablet) {
                NavBarStyle.entries.forEach { style ->
                    item(key = style.key) {
                        NuvioBottomSheetActionRow(
                            title = stringResource(style.labelRes),
                            onClick = { onStyleSelected(style) },
                            selected = style == selectedStyle,
                            modifier = Modifier.semantics {
                                role = Role.RadioButton
                                selected = style == selectedStyle
                            },
                            trailingContent = {
                                if (style == selectedStyle) {
                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                    }
                }
            }
            item {
                // Only the floating pill can move; CLASSIC keeps its fixed place.
                AnimatedVisibility(selectedStyle != NavBarStyle.CLASSIC) {
                    Column {
                        NuvioBottomSheetDivider()
                        Text(
                            text = stringResource(Res.string.settings_nav_bar_position),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                        )
                        NavBarPosition.entries.forEach { position ->
                            NuvioBottomSheetActionRow(
                                title = stringResource(position.labelRes),
                                onClick = { onPositionSelected(position) },
                                modifier = Modifier.semantics {
                                    role = Role.RadioButton
                                    selected = position == selectedPosition
                                },
                                trailingContent = {
                                    if (position == selectedPosition) {
                                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            item {
                AnimatedVisibility(selectedStyle != NavBarStyle.CLASSIC) {
                    Column {
                        NuvioBottomSheetDivider()
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_default_icon_color),
                            description = stringResource(Res.string.settings_nav_bar_default_icon_color_description),
                            checked = defaultIconColorEnabled,
                            isTablet = isTablet,
                            onCheckedChange = { enabled ->
                                if (!enabled) defaultIconColorExpanded = false
                                onDefaultIconColorEnabledChanged(enabled)
                            },
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(
                    visible = selectedStyle != NavBarStyle.CLASSIC && defaultIconColorEnabled,
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    defaultIconColorExpanded = !defaultIconColorExpanded
                                }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(Res.string.settings_nav_bar_default_icon_color_change),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(
                                        if (defaultIconColorExpanded) {
                                            Res.string.settings_nav_bar_default_icon_color_collapse
                                        } else {
                                            Res.string.settings_nav_bar_default_icon_color_expand
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        AnimatedVisibility(visible = defaultIconColorExpanded) {
                            NavigationBarIconColorEditor(
                                color = defaultIconColor,
                                isTablet = isTablet,
                                onColorChanged = onDefaultIconColorChanged,
                            )
                        }
                        NuvioBottomSheetDivider()
                    }
                }
            }
            item {
                AnimatedVisibility(floatingNavigationGlowSupported && selectedStyle != NavBarStyle.CLASSIC) {
                    Column {
                        NuvioBottomSheetDivider()
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glow),
                            description = stringResource(Res.string.settings_nav_bar_glow_description),
                            checked = glowEnabled,
                            isTablet = isTablet,
                            onCheckedChange = onGlowChanged,
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(selectedStyle != NavBarStyle.CLASSIC) {
                    Column {
                        NuvioBottomSheetDivider()
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_liquid_glass),
                            description = stringResource(
                                if (liquidGlassSupported) {
                                    Res.string.settings_nav_bar_liquid_glass_description
                                } else {
                                    Res.string.settings_nav_bar_liquid_glass_unsupported_description
                                },
                            ),
                            checked = liquidGlassEnabled,
                            enabled = liquidGlassSupported,
                            isTablet = isTablet,
                            onCheckedChange = onLiquidGlassChanged,
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(
                    visible = selectedStyle != NavBarStyle.CLASSIC && liquidGlassSupported && liquidGlassEnabled,
                ) {
                    Column {
                        NuvioBottomSheetDivider()
                        LiquidGlassEffectGroupHeader(
                            title = stringResource(Res.string.settings_nav_bar_liquid_glass_floating_bar),
                            resetDescription = stringResource(Res.string.settings_nav_bar_liquid_glass_reset_bar),
                            isTablet = isTablet,
                            onReset = {
                                onFloatingBarEffectsChanged(LiquidGlassEffectDefaults.floatingBar)
                            },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_corner_radius),
                            value = floatingBarEffects.cornerRadiusPercent,
                            valueRange = 0f..50f,
                            steps = 49,
                            valueLabel = "${floatingBarEffects.cornerRadiusPercent.roundToInt()}%",
                            isTablet = isTablet,
                            onValueChange = { onFloatingBarEffectsChanged(floatingBarEffects.copy(cornerRadiusPercent = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_blur_radius),
                            value = floatingBarEffects.blurRadiusDp,
                            valueRange = 0f..LiquidGlassEffectDefaults.floatingBarLimits.maxBlurRadiusDp,
                            steps = 31,
                            valueLabel = "${floatingBarEffects.blurRadiusDp.roundToInt()} dp",
                            isTablet = isTablet,
                            onValueChange = { onFloatingBarEffectsChanged(floatingBarEffects.copy(blurRadiusDp = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_refraction_height),
                            value = floatingBarEffects.refractionHeightDp,
                            valueRange = 0f..LiquidGlassEffectDefaults.floatingBarLimits.maxRefractionHeightDp,
                            steps = 47,
                            valueLabel = "${floatingBarEffects.refractionHeightDp.roundToInt()} dp",
                            isTablet = isTablet,
                            onValueChange = { onFloatingBarEffectsChanged(floatingBarEffects.copy(refractionHeightDp = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_refraction_amount),
                            value = floatingBarEffects.refractionAmountDp,
                            valueRange = 0f..LiquidGlassEffectDefaults.floatingBarLimits.maxRefractionAmountDp,
                            steps = 47,
                            valueLabel = "${floatingBarEffects.refractionAmountDp.roundToInt()} dp",
                            isTablet = isTablet,
                            onValueChange = { onFloatingBarEffectsChanged(floatingBarEffects.copy(refractionAmountDp = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_tint_opacity),
                            value = floatingBarEffects.tintOpacity,
                            valueRange = 0f..1f,
                            steps = 19,
                            valueLabel = "${(floatingBarEffects.tintOpacity * 100f).roundToInt()}%",
                            isTablet = isTablet,
                            onValueChange = { onFloatingBarEffectsChanged(floatingBarEffects.copy(tintOpacity = it)) },
                        )
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration),
                            description = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration_description),
                            checked = floatingBarEffects.chromaticAberration,
                            isTablet = isTablet,
                            onCheckedChange = {
                                onFloatingBarEffectsChanged(floatingBarEffects.copy(chromaticAberration = it))
                            },
                        )
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glass_depth_effect),
                            description = stringResource(Res.string.settings_nav_bar_glass_depth_effect_description),
                            checked = floatingBarEffects.depthEffect,
                            isTablet = isTablet,
                            onCheckedChange = {
                                onFloatingBarEffectsChanged(floatingBarEffects.copy(depthEffect = it))
                            },
                        )

                        NuvioBottomSheetDivider()
                        LiquidGlassEffectGroupHeader(
                            title = stringResource(Res.string.settings_nav_bar_liquid_glass_selection_lens),
                            resetDescription = stringResource(Res.string.settings_nav_bar_liquid_glass_reset_lens),
                            isTablet = isTablet,
                            onReset = {
                                onSelectionLensEffectsChanged(LiquidGlassEffectDefaults.selectionLens)
                            },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_corner_radius),
                            value = selectionLensEffects.cornerRadiusPercent,
                            valueRange = 0f..50f,
                            steps = 49,
                            valueLabel = "${selectionLensEffects.cornerRadiusPercent.roundToInt()}%",
                            isTablet = isTablet,
                            onValueChange = { onSelectionLensEffectsChanged(selectionLensEffects.copy(cornerRadiusPercent = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_blur_radius),
                            value = selectionLensEffects.blurRadiusDp,
                            valueRange = 0f..LiquidGlassEffectDefaults.selectionLensLimits.maxBlurRadiusDp,
                            steps = 23,
                            valueLabel = "${selectionLensEffects.blurRadiusDp.roundToInt()} dp",
                            isTablet = isTablet,
                            onValueChange = { onSelectionLensEffectsChanged(selectionLensEffects.copy(blurRadiusDp = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_refraction_height),
                            value = selectionLensEffects.refractionHeightDp,
                            valueRange = 0f..LiquidGlassEffectDefaults.selectionLensLimits.maxRefractionHeightDp,
                            steps = 31,
                            valueLabel = "${selectionLensEffects.refractionHeightDp.roundToInt()} dp",
                            isTablet = isTablet,
                            onValueChange = { onSelectionLensEffectsChanged(selectionLensEffects.copy(refractionHeightDp = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_refraction_amount),
                            value = selectionLensEffects.refractionAmountDp,
                            valueRange = 0f..LiquidGlassEffectDefaults.selectionLensLimits.maxRefractionAmountDp,
                            steps = 31,
                            valueLabel = "${selectionLensEffects.refractionAmountDp.roundToInt()} dp",
                            isTablet = isTablet,
                            onValueChange = { onSelectionLensEffectsChanged(selectionLensEffects.copy(refractionAmountDp = it)) },
                        )
                        LiquidGlassEffectSlider(
                            title = stringResource(Res.string.settings_nav_bar_glass_tint_opacity),
                            value = selectionLensEffects.tintOpacity,
                            valueRange = 0f..1f,
                            steps = 19,
                            valueLabel = "${(selectionLensEffects.tintOpacity * 100f).roundToInt()}%",
                            isTablet = isTablet,
                            onValueChange = { onSelectionLensEffectsChanged(selectionLensEffects.copy(tintOpacity = it)) },
                        )
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration),
                            description = stringResource(Res.string.settings_nav_bar_glass_chromatic_aberration_description),
                            checked = selectionLensEffects.chromaticAberration,
                            isTablet = isTablet,
                            onCheckedChange = {
                                onSelectionLensEffectsChanged(selectionLensEffects.copy(chromaticAberration = it))
                            },
                        )
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glass_depth_effect),
                            description = stringResource(Res.string.settings_nav_bar_glass_depth_effect_description),
                            checked = selectionLensEffects.depthEffect,
                            isTablet = isTablet,
                            onCheckedChange = {
                                onSelectionLensEffectsChanged(selectionLensEffects.copy(depthEffect = it))
                            },
                        )
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glass_lens_affect_tab_icons),
                            description = stringResource(Res.string.settings_nav_bar_glass_lens_affect_tab_icons_description),
                            checked = selectionLensEffects.affectTabIcons,
                            isTablet = isTablet,
                            onCheckedChange = {
                                onSelectionLensEffectsChanged(selectionLensEffects.copy(affectTabIcons = it))
                            },
                        )
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glass_lens_affect_floating_bar),
                            description = stringResource(Res.string.settings_nav_bar_glass_lens_affect_floating_bar_description),
                            checked = selectionLensEffects.affectFloatingBar,
                            isTablet = isTablet,
                            onCheckedChange = {
                                onSelectionLensEffectsChanged(selectionLensEffects.copy(affectFloatingBar = it))
                            },
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(isLiquidGlassSupported() && selectedStyle != NavBarStyle.CLASSIC) {
                    NavigationBarGlassSettings(
                        style = glassStyle,
                        isTablet = isTablet,
                        liquidGlassSupported = liquidGlassSupported,
                        liquidGlassEnabled = liquidGlassEnabled,
                        onStyleChanged = onGlassStyleChanged,
                    )
                }
            }
            item {
                NuvioPrimaryButton(
                    text = stringResource(Res.string.action_done),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    onClick = dismiss,
                )
            }
        }
    }
}


private val LiquidGlassMaterial.labelRes: StringResource
    get() = when (this) {
        LiquidGlassMaterial.LIQUID -> Res.string.settings_nav_bar_glass_material_liquid
        LiquidGlassMaterial.FROSTED -> Res.string.settings_nav_bar_glass_material_frosted
        LiquidGlassMaterial.CLEAR -> Res.string.settings_nav_bar_glass_material_clear
        LiquidGlassMaterial.SOLID -> Res.string.settings_nav_bar_glass_material_solid
    }

internal fun shouldShowLegacyNavBarGlassControls(
    liquidGlassSupported: Boolean,
    liquidGlassEnabled: Boolean,
): Boolean = !liquidGlassSupported || !liquidGlassEnabled

@Composable
private fun NavigationBarGlassSettings(
    style: LiquidGlassStyle,
    isTablet: Boolean,
    liquidGlassSupported: Boolean,
    liquidGlassEnabled: Boolean,
    onStyleChanged: (LiquidGlassStyle) -> Unit,
) {
    val refractionSupported = isLiquidRefractionSupported()
    val showLegacyControls = shouldShowLegacyNavBarGlassControls(
        liquidGlassSupported = liquidGlassSupported,
        liquidGlassEnabled = liquidGlassEnabled,
    )
    val materials = LiquidGlassMaterial.entries.filter {
        refractionSupported || it != LiquidGlassMaterial.CLEAR
    }
    Column {
        NuvioBottomSheetDivider()
        Text(
            text = stringResource(Res.string.settings_nav_bar_glass),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        Text(
            text = stringResource(Res.string.settings_nav_bar_glass_legacy_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (showLegacyControls) {
            materials.forEach { material ->
                NuvioBottomSheetActionRow(
                    title = stringResource(material.labelRes),
                    onClick = { onStyleChanged(style.copy(material = material)) },
                    modifier = Modifier.semantics {
                        role = Role.RadioButton
                        selected = material == style.material
                    },
                    trailingContent = {
                        if (material == style.material) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                )
            }
            AnimatedVisibility(style.usesBlur) {
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_nav_bar_glass_blur),
                    value = style.blurRadius,
                    valueText = stringResource(Res.string.settings_nav_bar_glass_value_dp, style.blurRadius),
                    valueRange = LiquidGlassStyle.BlurRange,
                    step = 1,
                    isTablet = isTablet,
                    onValueChange = { onStyleChanged(style.copy(blurRadius = it)) },
                )
            }
            AnimatedVisibility(style.usesLens && refractionSupported) {
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_nav_bar_glass_refraction),
                    value = style.refraction,
                    valueText = stringResource(Res.string.settings_nav_bar_glass_value_dp, style.refraction),
                    valueRange = LiquidGlassStyle.RefractionRange,
                    step = 2,
                    isTablet = isTablet,
                    onValueChange = { onStyleChanged(style.copy(refraction = it)) },
                )
            }
            AnimatedVisibility(style.usesBackdrop) {
                Column {
                    SettingsSliderRow(
                        title = stringResource(Res.string.settings_nav_bar_glass_tint),
                        value = style.tintPercent,
                        valueText = stringResource(Res.string.settings_nav_bar_glass_value_percent, style.tintPercent),
                        valueRange = LiquidGlassStyle.TintRange,
                        step = 5,
                        isTablet = isTablet,
                        onValueChange = { onStyleChanged(style.copy(tintPercent = it)) },
                    )
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_nav_bar_glass_vibrancy),
                        description = stringResource(Res.string.settings_nav_bar_glass_vibrancy_description),
                        checked = style.vibrancy,
                        isTablet = isTablet,
                        onCheckedChange = { onStyleChanged(style.copy(vibrancy = it)) },
                    )
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_nav_bar_glass_shadow),
                        description = stringResource(Res.string.settings_nav_bar_glass_shadow_description),
                        checked = style.shadow,
                        isTablet = isTablet,
                        onCheckedChange = { onStyleChanged(style.copy(shadow = it)) },
                    )
                    if (refractionSupported) {
                        SettingsSwitchRow(
                            title = stringResource(Res.string.settings_nav_bar_glass_selection_lens),
                            description = stringResource(Res.string.settings_nav_bar_glass_selection_lens_description),
                            checked = style.selectionLens,
                            isTablet = isTablet,
                            onCheckedChange = { onStyleChanged(style.copy(selectionLens = it)) },
                        )
                    }
                }
            }
            AnimatedVisibility(refractionSupported && style.usesBackdrop && (style.usesLens || style.selectionLens)) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_nav_bar_glass_dispersion),
                    description = stringResource(Res.string.settings_nav_bar_glass_dispersion_description),
                    checked = style.chromaticAberration,
                    isTablet = isTablet,
                    onCheckedChange = { onStyleChanged(style.copy(chromaticAberration = it)) },
                )
            }
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_nav_bar_glass_highlight),
                description = stringResource(Res.string.settings_nav_bar_glass_highlight_description),
                checked = style.edgeHighlight,
                isTablet = isTablet,
                onCheckedChange = { onStyleChanged(style.copy(edgeHighlight = it)) },
            )
            AnimatedVisibility(style != LiquidGlassStyle.Default) {
                NuvioBottomSheetActionRow(
                    title = stringResource(Res.string.settings_nav_bar_glass_reset),
                    onClick = { onStyleChanged(LiquidGlassStyle.Default) },
                )
            }
        }
    }
}


@Composable
private fun LiquidGlassEffectGroupHeader(
    title: String,
    resetDescription: String,
    isTablet: Boolean,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isTablet) 20.dp else 16.dp,
                end = 8.dp,
                top = 12.dp,
                bottom = 2.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onReset) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = resetDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


@Composable
private fun NavigationBarPreviewStickyHeader(
    listState: LazyListState,
    selectedStyle: NavBarStyle,
    isTablet: Boolean,
    glowEnabled: Boolean,
    liquidGlassEnabled: Boolean,
    selectedPosition: NavBarPosition,
    tabletLayout: Boolean,
    floatingBarEffects: LiquidGlassEffectSettings,
    selectionLensEffects: LiquidGlassEffectSettings,
    defaultIconColorEnabled: Boolean,
    defaultIconColor: Int,
    glassStyle: LiquidGlassStyle,
) {
    var bounceTrigger by remember { mutableIntStateOf(0) }
    var bounceDirection by remember { mutableStateOf(0f) }

    LaunchedEffect(listState) {
        val gate = NavigationPreviewScrollBounceGate()
        snapshotFlow { listState.canScrollBackward }
            .distinctUntilChanged()
            .collectLatest { canScrollBackward ->
                val direction = gate.onScrollabilityChanged(canScrollBackward)
                    ?: return@collectLatest
                bounceDirection = direction
                bounceTrigger = if (bounceTrigger == Int.MAX_VALUE) 1 else bounceTrigger + 1
            }
    }

    val sheetBackdrop = LocalNuvioSheetBackdrop.current?.backdrop
    val sheetContainerColor = MaterialTheme.nuvio.colors.surfaceSheet

    // Keep the pinned preview opaque during scrolling while matching the shared sheet blur and tint.
    // The gate emits a one-shot request at each top-boundary crossing for the preview bar's JellyMotion springs.
    Column(
        Modifier
            .fillMaxWidth()
            .nuvioSheetSurface(
                backdrop = sheetBackdrop,
                shape = RectangleShape,
                containerColor = sheetContainerColor,
                withHighlight = false,
            ),
    ) {
        Text(
            text = stringResource(Res.string.settings_appearance_nav_bar_style),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        NavigationBarPreview(
            style = selectedStyle,
            isTablet = isTablet,
            glowEnabled = glowEnabled,
            liquidGlassEnabled = liquidGlassEnabled,
            position = selectedPosition,
            tabletLayout = tabletLayout,
            floatingBarEffects = floatingBarEffects,
            selectionLensEffects = selectionLensEffects,
            scrollBounceTrigger = bounceTrigger,
            scrollBounceDirection = bounceDirection,
            glassStyle = glassStyle,
            defaultIconColorOverride = if (defaultIconColorEnabled) {
                Color(defaultIconColor or 0xFF000000.toInt())
            } else {
                null
            },
        )
    }
}

@Composable
private fun NavigationBarIconColorEditor(
    color: Int,
    isTablet: Boolean,
    onColorChanged: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var hexCode by remember(color) { mutableStateOf(formatHexColor(color)) }
    LaunchedEffect(color) {
        hexCode = formatHexColor(color)
    }
    val hexLabel = stringResource(Res.string.custom_theme_hex_title)
    val validHex = parseHexColor(hexCode) != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Reuse the Custom Theme picker so the color channels and swatches stay consistent.
        ThemeColorPicker(
            color = color,
            colorIndex = 0,
            onColorChanged = { updated ->
                onColorChanged(updated)
                hexCode = formatHexColor(updated)
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = hexLabel,
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textPrimary,
            )
            NuvioInputField(
                value = hexCode,
                onValueChange = { value ->
                    hexCode = value
                    parseHexColor(value)?.let(onColorChanged)
                },
                placeholder = "#969CA3",
                modifier = Modifier.semantics { contentDescription = hexLabel },
            )
            if (!validHex) {
                Text(
                    text = stringResource(Res.string.custom_theme_hex_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }
        }
    }
}

@Composable
internal fun LiquidGlassEffectSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    isTablet: Boolean,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    val safeValue = value.takeIf { it.isFinite() }?.coerceIn(valueRange) ?: valueRange.start
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = 2.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = safeValue,
            onValueChange = { onValueChange(it.coerceIn(valueRange)) },
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
