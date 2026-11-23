package com.fishkingsin.manhuagui.ui.reader

import androidx.compose.ui.geometry.Offset

enum class TapAction {
    PrevPage,
    NextPage,
    ToggleChrome,
}

data class ReaderState(
    val pageIndex: Int = 0,
    val zoom: Float = 1f,
    val offset: Offset = Offset.Zero,
) {
    fun onPagerPage(i: Int): ReaderState = copy(
        pageIndex = i.coerceAtLeast(0),
        zoom = 1f,
        offset = Offset.Zero,
    )

    fun withTransform(zoom: Float, offset: Offset): ReaderState {
        val clampedZoom = zoom.coerceIn(1f, 4f)
        return copy(
            zoom = clampedZoom,
            offset = if (clampedZoom == 1f) Offset.Zero else offset,
        )
    }

    fun toggleDoubleTapZoom(): ReaderState = if (zoom > 1f) {
        copy(zoom = 1f, offset = Offset.Zero)
    } else {
        copy(zoom = 2f, offset = Offset.Zero)
    }

    fun tapZoneAction(x: Float, width: Float, rtl: Boolean = true): TapAction =
        com.fishkingsin.manhuagui.ui.reader.tapZoneAction(x, width, rtl)
}

fun tapZoneAction(x: Float, width: Float, rtl: Boolean = true): TapAction = when {
    x < width * 0.4f -> if (rtl) TapAction.NextPage else TapAction.PrevPage
    x <= width * 0.6f -> TapAction.ToggleChrome
    else -> if (rtl) TapAction.PrevPage else TapAction.NextPage
}
