package com.fishkingsin.manhuagui.ui.reader

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderKeyHandlerTest {
    @Test
    fun mappedNextAndPreviousKeysTurnOnKeyDownAndConsumeKeyUp() {
        val mappedKeys = listOf(
            KeyEvent.KEYCODE_PAGE_DOWN to 1,
            KeyEvent.KEYCODE_VOLUME_DOWN to 1,
            KeyEvent.KEYCODE_BUTTON_R1 to 1,
            KeyEvent.KEYCODE_DPAD_LEFT to 1,
            KeyEvent.KEYCODE_PAGE_UP to -1,
            KeyEvent.KEYCODE_VOLUME_UP to -1,
            KeyEvent.KEYCODE_BUTTON_L1 to -1,
            KeyEvent.KEYCODE_DPAD_RIGHT to -1,
        )

        mappedKeys.forEach { (keyCode, delta) ->
            assertEquals(
                ReaderKeyResult(consumed = true, pageDelta = delta),
                handleReaderKey(keyCode, KeyEvent.ACTION_DOWN, rtl = true),
            )
            assertEquals(
                ReaderKeyResult(consumed = true, pageDelta = null),
                handleReaderKey(keyCode, KeyEvent.ACTION_UP, rtl = true),
            )
        }
    }

    @Test
    fun dpadDirectionsMirrorInLtr() {
        assertEquals(
            ReaderKeyResult(consumed = true, pageDelta = 1),
            handleReaderKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, rtl = false),
        )
        assertEquals(
            ReaderKeyResult(consumed = true, pageDelta = -1),
            handleReaderKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, rtl = false),
        )
    }

    @Test
    fun mappedKeysAreConsumedForOtherActionsWithoutTurningPage() {
        assertEquals(
            ReaderKeyResult(consumed = true, pageDelta = null),
            handleReaderKey(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.ACTION_MULTIPLE),
        )
    }

    @Test
    fun unmappedKeysAreNotConsumed() {
        assertEquals(
            ReaderKeyResult(consumed = false, pageDelta = null),
            handleReaderKey(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN),
        )
    }
}
