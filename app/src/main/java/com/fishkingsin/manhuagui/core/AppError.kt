package com.fishkingsin.manhuagui.core

import uniffi.manhuagui_ffi.AndroidException

sealed interface AppError {
    val detail: String
    val userMessage: String

    data class Network(override val detail: String) : AppError {
        override val userMessage = "Couldn't reach the site"
    }

    data class RateLimited(override val detail: String) : AppError {
        override val userMessage = "The site is rate-limiting. Try later."
    }

    data class ParseFailed(override val detail: String) : AppError {
        override val userMessage = "The site format changed. Update the app."
    }

    data class NotFound(override val detail: String) : AppError {
        override val userMessage = "Not found."
    }

    data class Cancelled(override val detail: String) : AppError {
        override val userMessage = "Cancelled."
    }

    data class Unknown(override val detail: String) : AppError {
        override val userMessage = "Something went wrong."
    }
}

fun AndroidException.toAppError(): AppError = when (this) {
    is AndroidException.Network -> AppError.Network(detail)
    is AndroidException.RateLimited -> AppError.RateLimited(detail)
    is AndroidException.ParseFailed -> AppError.ParseFailed(detail)
    is AndroidException.NotFound -> AppError.NotFound(detail)
    is AndroidException.Cancelled -> AppError.Cancelled(detail)
}

fun Throwable.toAppError(): AppError = when (this) {
    is AndroidException -> toAppError()
    else -> AppError.Unknown(message ?: javaClass.simpleName)
}
