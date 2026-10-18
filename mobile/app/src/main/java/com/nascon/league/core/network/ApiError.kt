package com.nascon.league.core.network

import kotlinx.serialization.json.JsonObject

sealed interface ApiError {
    data class Http(
        val status: Int,
        val code: String,
        val message: String,
        val details: JsonObject? = null,
        /** Seconds from the Retry-After header or the error body, when the server sent one. */
        val retryAfterSeconds: Int? = null,
    ) : ApiError

    data object NoConnection : ApiError
    data object Timeout : ApiError
    data object ServerUnreachable : ApiError
    data class Unexpected(val cause: Throwable? = null) : ApiError
}
