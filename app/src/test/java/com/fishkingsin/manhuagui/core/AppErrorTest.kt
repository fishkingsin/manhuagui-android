package com.fishkingsin.manhuagui.core

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.manhuagui_ffi.AndroidException

class AppErrorTest {
    @Test
    fun networkErrorKeepsDiagnosticDetailAndShowsSafeMessage() {
        assertMapping(
            AndroidException.Network(detail = "socket reset"),
            "socket reset",
            "Couldn't reach the site",
        )
    }

    @Test
    fun rateLimitedErrorKeepsDiagnosticDetailAndShowsSafeMessage() {
        assertMapping(
            AndroidException.RateLimited(detail = "429 from host"),
            "429 from host",
            "The site is rate-limiting. Try later.",
        )
    }

    @Test
    fun parseFailedErrorKeepsDiagnosticDetailAndShowsSafeMessage() {
        assertMapping(
            AndroidException.ParseFailed(detail = "unexpected markup"),
            "unexpected markup",
            "The site format changed. Update the app.",
        )
    }

    @Test
    fun notFoundErrorKeepsDiagnosticDetailAndShowsSafeMessage() {
        assertMapping(
            AndroidException.NotFound(detail = "chapter missing"),
            "chapter missing",
            "Not found.",
        )
    }

    @Test
    fun cancelledErrorKeepsDiagnosticDetailAndShowsSafeMessage() {
        assertMapping(
            AndroidException.Cancelled(detail = "request cancelled"),
            "request cancelled",
            "Cancelled.",
        )
    }

    @Test
    fun unknownErrorKeepsDiagnosticDetailOutOfUserMessage() {
        val mapped = IllegalStateException("private diagnostic").toAppError()

        assertEquals("private diagnostic", mapped.detail)
        assertEquals("Something went wrong.", mapped.userMessage)
    }

    private fun assertMapping(
        error: AndroidException,
        expectedDetail: String,
        expectedMessage: String,
    ) {
        val mapped = error.toAppError()

        assertEquals(expectedMessage, mapped.userMessage)
        assertEquals(expectedDetail, mapped.detail)
    }
}
