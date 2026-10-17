package com.nascon.league.core.error

import com.nascon.league.core.network.ApiError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class ErrorContext { General, Login }

object ErrorMapper {
    const val GENERIC = "Something went wrong on the server. Please try again."
    const val SYNC_GENERIC = "This change was not applied."

    val knownCodes = listOf(
        "UNAUTHENTICATED", "FORBIDDEN", "RATE_LIMITED", "VALIDATION_ERROR", "OUT_OF_STOCK",
        "PAYMENT_DECLINED", "PAYMENT_CAPTURE_FAILED", "INVALID_DISCOUNT_CODE",
        "TRANSFER_WINDOW_CLOSED", "PLAYER_ON_LOAN", "SQUAD_FULL", "MIN_POSITION_VIOLATION",
        "NOT_IN_SQUAD", "CROSS_TENANT_TRANSFER", "MATCH_NOT_IN_PROGRESS", "ILLEGAL_TRANSITION",
        "TIE_NOT_ALLOWED", "INVALID_COMPENSATION", "INNINGS_COMPLETE", "SERIES_ALREADY_DECIDED",
        "UNKNOWN_EVENT_TYPE", "DUPLICATE_EVENT_ID", "DUPLICATE_ORDER_ID", "DUPLICATE_REQUEST_ID",
        "INVALID_BASE_VERSION", "MATCH_NOT_FOUND", "CLUB_NOT_FOUND", "LEAGUE_NOT_FOUND",
        "PRODUCT_NOT_FOUND", "ORDER_NOT_FOUND", "PLAYER_NOT_FOUND", "SQUAD_NOT_FOUND",
        "VARIANT_NOT_FOUND", "SPORT_NOT_FOUND", "EMPTY_CART",
    )

    fun message(error: ApiError, context: ErrorContext = ErrorContext.General): String = when (error) {
        ApiError.NoConnection ->
            if (context == ErrorContext.Login) "You're offline. Connect to the internet to sign in."
            else "You're offline. We'll use saved data where we can."
        ApiError.ServerUnreachable -> "Can't reach the server. Check the server address in Settings."
        ApiError.Timeout -> "The server is taking too long. Please try again."
        is ApiError.Unexpected -> GENERIC
        is ApiError.Http -> forCode(error.code, context, error.details)
    }

    /** Plain reason for a rejected sync result. */
    fun syncRejection(code: String?): String =
        if (code.isNullOrBlank()) SYNC_GENERIC else forCode(code, ErrorContext.General, null)

    fun forCode(code: String, context: ErrorContext = ErrorContext.General): String =
        forCode(code, context, null)

    private fun positionOf(details: JsonObject?): String? {
        val raw = (details?.get("position") as? JsonPrimitive)?.contentOrNull?.trim()
        if (raw.isNullOrEmpty()) return null
        return raw.replace('_', ' ').lowercase()
    }

    fun forCode(code: String, context: ErrorContext, details: JsonObject?): String = when {
        code == "UNAUTHENTICATED" ->
            if (context == ErrorContext.Login) "Email or password is incorrect." else "Please sign in again."
        code == "FORBIDDEN" -> "You don't have permission to do that."
        code == "RATE_LIMITED" ->
            if (context == ErrorContext.Login) "Too many attempts. Please wait a few minutes and try again."
            else "Too many requests — try again in a few seconds."
        code == "VALIDATION_ERROR" -> "This change was not valid and was not saved."
        code == "OUT_OF_STOCK" -> "Sorry, this item just sold out."
        code == "PAYMENT_DECLINED" -> "Your payment was declined. No money was taken."
        code == "PAYMENT_CAPTURE_FAILED" -> "The payment couldn't be completed. No money was taken."
        code == "INVALID_DISCOUNT_CODE" -> "That discount code isn't valid."
        code == "EMPTY_CART" -> "Your cart is empty."
        code == "TRANSFER_WINDOW_CLOSED" -> "The transfer window is closed for that date."
        code == "PLAYER_ON_LOAN" -> "This player is on loan and can't be moved."
        code == "SQUAD_FULL" -> "The destination squad is already full, so this transfer was not made."
        code == "MIN_POSITION_VIOLATION" -> positionOf(details)
            ?.let { "This would leave the squad without enough $it players." }
            ?: "This would leave the squad without enough players in that position."
        code == "NOT_IN_SQUAD" -> "This player isn't in that squad anymore."
        code == "CROSS_TENANT_TRANSFER" -> "That squad belongs to a different organisation."
        code == "MATCH_NOT_IN_PROGRESS" -> "This match isn't live, so scores can't be added."
        code == "ILLEGAL_TRANSITION" -> "That action isn't allowed for the match right now."
        code == "TIE_NOT_ALLOWED" -> "This match can't end level. Record the tie-break winner first."
        code == "INVALID_COMPENSATION" -> "That event was already corrected."
        code == "INNINGS_COMPLETE" -> "This team's innings is already complete."
        code == "SERIES_ALREADY_DECIDED" -> "This series has already been decided."
        code == "UNKNOWN_EVENT_TYPE" -> "That scoring action isn't valid for this sport."
        code == "DUPLICATE_ORDER_ID" -> "This was already submitted."
        code == "DUPLICATE_EVENT_ID" || code == "DUPLICATE_REQUEST_ID" ->
            "This change conflicts with an earlier one and was not saved."
        code == "INVALID_BASE_VERSION" -> "This item changed on the server, so your change was not applied."
        code.endsWith("_NOT_FOUND") -> "This item no longer exists."
        else -> GENERIC
    }
}
