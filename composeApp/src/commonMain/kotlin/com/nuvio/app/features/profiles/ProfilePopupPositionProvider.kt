package com.nuvio.app.features.profiles

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

internal class ProfilePopupPositionProvider(
    private val margin: Int,
    private val gap: Int,
    private val preferBelow: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val spaceAbove = anchorBounds.top - gap - margin
        val spaceBelow = windowSize.height - anchorBounds.bottom - gap - margin
        val opensBelow = when {
            spaceBelow > spaceAbove -> true
            spaceAbove > spaceBelow -> false
            else -> preferBelow
        }
        val y = if (opensBelow) {
            anchorBounds.bottom + gap
        } else {
            anchorBounds.top - gap - popupContentSize.height
        }
        val x = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        }
        return IntOffset(
            x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
            y.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)),
        )
    }
}
