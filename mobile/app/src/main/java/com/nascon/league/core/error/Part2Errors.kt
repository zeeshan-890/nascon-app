package com.nascon.league.core.error

import com.nascon.league.core.network.ApiError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The UI reaction to a failed call. Copy is short, plain, and never contains raw server text. */
sealed interface UiErrorAction {
    data object OpenSignInSheet : UiErrorAction
    data object GoGuestAndOfferSignIn : UiErrorAction
    data class Inline(val message: String) : UiErrorAction
    data object NotFound : UiErrorAction
    data class FieldErrors(val fields: List<String>, val message: String) : UiErrorAction
    data class OutOfStock(val variantId: String?) : UiErrorAction
    data class Backoff(val retryAfterSeconds: Int, val message: String) : UiErrorAction
    data class Generic(val message: String, val canRetry: Boolean) : UiErrorAction
}

object Part2Errors {
    const val GUEST_LOCK_COPY = "Sign in to do this. Your cart is saved."
    const val GENERIC_COPY = "Something went wrong. Please try again."
    const val OFFLINE_COPY = "You are offline. Check your connection and try again."
    const val TIMEOUT_COPY = "That took too long. Please try again."
    const val UNREACHABLE_COPY = "Cannot reach the server. Please try again."
    const val VALIDATION_COPY = "Please check the highlighted fields."
    const val DEFAULT_BACKOFF_SECONDS = 5

    fun map(error: ApiError): UiErrorAction = when (error) {
        is ApiError.Http -> mapHttp(error)
        ApiError.NoConnection -> UiErrorAction.Generic(OFFLINE_COPY, true)
        ApiError.Timeout -> UiErrorAction.Generic(TIMEOUT_COPY, true)
        ApiError.ServerUnreachable -> UiErrorAction.Generic(UNREACHABLE_COPY, true)
        is ApiError.Unexpected -> UiErrorAction.Generic(GENERIC_COPY, true)
    }

    private fun mapHttp(e: ApiError.Http): UiErrorAction {
        val code = e.code.trim().uppercase()
        when (code) {
            "GUEST_WRITE_FORBIDDEN" -> return UiErrorAction.OpenSignInSheet
            "UNAUTHENTICATED" -> return UiErrorAction.GoGuestAndOfferSignIn
            "FORBIDDEN" -> return forbidden()
            "COSTREAM_SESSION_FULL" -> return UiErrorAction.Inline("This session is full.")
            "UNSUPPORTED_LAYOUT" -> return UiErrorAction.Inline("That layout is not available.")
            "KEY_REVOKED" -> return UiErrorAction.Inline("This stream key was revoked.")
            "GUEST_TOKEN_ALREADY_MERGED" -> return UiErrorAction.Inline("Your guest cart was already merged.")
            "PAYLOAD_TOO_LARGE" -> return UiErrorAction.Inline("That is too big to send.")
            "NOT_FOUND" -> return UiErrorAction.NotFound
            "VALIDATION_ERROR" -> return UiErrorAction.FieldErrors(fieldList(e.details), VALIDATION_COPY)
            "OUT_OF_STOCK" -> return UiErrorAction.OutOfStock(stringDetail(e.details, "variant_id"))
            "RATE_LIMITED" -> return backoff(e)
        }
        if (code.endsWith("_NOT_FOUND")) return UiErrorAction.NotFound
        return when {
            e.status == 401 -> UiErrorAction.GoGuestAndOfferSignIn
            e.status == 403 -> forbidden()
            e.status == 404 -> UiErrorAction.NotFound
            e.status == 429 -> backoff(e)
            else -> UiErrorAction.Generic(GENERIC_COPY, true)
        }
    }

    private fun forbidden() = UiErrorAction.Inline("You are not allowed to do this.")

    private fun backoff(e: ApiError.Http): UiErrorAction {
        val s = (e.retryAfterSeconds ?: DEFAULT_BACKOFF_SECONDS).coerceIn(1, 60)
        val unit = if (s == 1) "second" else "seconds"
        return UiErrorAction.Backoff(s, "Too many requests. Try again in $s $unit.")
    }

    private fun fieldList(details: JsonObject?): List<String> {
        val arr = details?.get("fields") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            (el as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }

    private fun stringDetail(details: JsonObject?, key: String): String? =
        (details?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
