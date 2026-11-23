package com.fishkingsin.manhuagui.ui.reader

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderStateTest {
    @Test
    fun tapZonesMirrorAtRepresentativePointsInRtlAndLtr() {
        val samples = listOf(
            0f to (TapAction.NextPage to TapAction.PrevPage),
            39f to (TapAction.NextPage to TapAction.PrevPage),
            50f to (TapAction.ToggleChrome to TapAction.ToggleChrome),
            61f to (TapAction.PrevPage to TapAction.NextPage),
            100f to (TapAction.PrevPage to TapAction.NextPage),
        )

        samples.forEach { (x, expected) ->
            assertEquals("RTL at x=$x", expected.first, tapZoneAction(x, 100f, rtl = true))
            assertEquals("LTR at x=$x", expected.second, tapZoneAction(x, 100f, rtl = false))
        }
    }

    @Test
    fun exactTapZoneEdgesBelongToTheCenterZone() {
        assertEquals(TapAction.ToggleChrome, tapZoneAction(40f, 100f))
        assertEquals(TapAction.ToggleChrome, tapZoneAction(60f, 100f))
        assertEquals(TapAction.ToggleChrome, ReaderState().tapZoneAction(40f, 100f, rtl = false))
    }

    @Test
    fun changingPagerPageResetsZoomAndOffsetAndClampsIndex() {
        val changed = ReaderState(pageIndex = 1)
            .withTransform(3f, Offset(14f, -6f))
            .onPagerPage(-2)

        assertEquals(0, changed.pageIndex)
        assertEquals(1f, changed.zoom)
        assertEquals(Offset.Zero, changed.offset)
    }

    @Test
    fun transformZoomIsClampedAndOneToOneZoomClearsOffset() {
        val belowMinimum = ReaderState().withTransform(0.25f, Offset(20f, 30f))
        val aboveMaximum = ReaderState().withTransform(7f, Offset(20f, 30f))

        assertEquals(1f, belowMinimum.zoom)
        assertEquals(Offset.Zero, belowMinimum.offset)
        assertEquals(4f, aboveMaximum.zoom)
        assertEquals(Offset(20f, 30f), aboveMaximum.offset)
    }

    @Test
    fun doubleTapTogglesBetweenOneAndTwoTimesZoom() {
        val zoomed = ReaderState().withTransform(3f, Offset(8f, 9f)).toggleDoubleTapZoom()
        val reset = zoomed.toggleDoubleTapZoom()

        assertEquals(1f, zoomed.zoom)
        assertEquals(Offset.Zero, zoomed.offset)
        assertEquals(2f, reset.zoom)
        assertEquals(Offset.Zero, reset.offset)
    }
}
