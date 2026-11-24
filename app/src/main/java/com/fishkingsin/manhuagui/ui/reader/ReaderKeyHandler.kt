package com.fishkingsin.manhuagui.ui.reader

import android.view.KeyEvent

data class ReaderKeyResult(val consumed: Boolean, val pageDelta: Int?)

fun handleReaderKey(keyCode: Int, action: Int, rtl: Boolean = true): ReaderKeyResult {
    val delta = when (keyCode) {
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_BUTTON_R1 -> 1
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_BUTTON_L1 -> -1
        KeyEvent.KEYCODE_DPAD_LEFT -> if (rtl) 1 else -1
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (rtl) -1 else 1
        else -> return ReaderKeyResult(consumed = false, pageDelta = null)
    }

    return ReaderKeyResult(
        consumed = true,
        pageDelta = if (action == KeyEvent.ACTION_DOWN) delta else null,
    )
}
