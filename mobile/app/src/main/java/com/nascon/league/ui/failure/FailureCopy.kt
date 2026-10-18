package com.nascon.league.ui.failure

import com.nascon.league.core.error.ErrorContext
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.core.network.ApiError
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

const val WRONG_PASSWORD_MESSAGE = "Email or password is incorrect."
const val FORBIDDEN_MESSAGE = "You don't have permission to do that."
const val RATE_LIMIT_MESSAGE = "Too many requests — try again in a few seconds."

/**
 * Plain sentence for a failure. Never returns the server body, a stack, or a raw code.
 * Status is checked as well as [ApiError.Http.code], because an unparsed body arrives as HTTP_401 / HTTP_403 / HTTP_429.
 */
fun userMessage(error: ApiError, context: ErrorContext = ErrorContext.General): String {
    val http = error as? ApiError.Http
    if (http != null && isForbidden(http)) return FORBIDDEN_MESSAGE
    if (http != null && isRateLimited(http)) {
        if (context == ErrorContext.Login) {
            return if (http.code == "RATE_LIMITED") ErrorMapper.message(error, context)
            else "Too many attempts. Please wait a few minutes and try again."
        }
        return RATE_LIMIT_MESSAGE
    }
    if (http != null && isUnauthenticated(http)) {
        return if (context == ErrorContext.Login) WRONG_PASSWORD_MESSAGE
        else ErrorMapper.forCode("UNAUTHENTICATED")
    }
    return ErrorMapper.message(error, context)
}

/** Seconds from the error JSON, when the server sent one. The HTTP Retry-After header is not on [ApiError]. */
fun retryAfterSeconds(error: ApiError?): Int? {
    val http = error as? ApiError.Http ?: return null
    if (!isRateLimited(http)) return null
    val details = http.details ?: return null
    for (key in listOf("retryAfterSeconds", "retryAfter", "retry_after")) {
        val prim = details[key] as? JsonPrimitive ?: continue
        val n = prim.intOrNull ?: prim.contentOrNull?.trim()?.toIntOrNull()
        if (n != null && n > 0) return n
    }
    return null
}

private fun isForbidden(http: ApiError.Http): Boolean =
    http.status == 403 || http.code == "FORBIDDEN" || http.code == "HTTP_403"

private fun isRateLimited(http: ApiError.Http): Boolean =
    http.status == 429 || http.code == "RATE_LIMITED" || http.code == "HTTP_429"

private fun isUnauthenticated(http: ApiError.Http): Boolean =
    http.status == 401 || http.code == "UNAUTHENTICATED" || http.code == "HTTP_401"
