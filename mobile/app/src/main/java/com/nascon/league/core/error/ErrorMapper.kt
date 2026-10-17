package com.nascon.league.core.error

import com.nascon.league.core.network.ApiError

enum class ErrorContext { General, Login }

object ErrorMapper {
    const val GENERIC = "Something went wrong on the server. Please try again."

    val knownCodes = listOf(
        "UNAUTHENTICATED", "FORBIDDEN", "RATE_LIMITED", "VALIDATION_ERROR", "OUT_OF_STOCK",
        "PAYMENT_DECLINED", "PAYMENT_CAPTURE_FAILED", "INVALID_DISCOUNT_CODE",
        "TRANSFER_WINDOW_CLOSED", "PLAYER_ON_LOAN", "SQUAD_FULL", "MIN_POSITION_VIOLATION",
        "NOT_IN_SQUAD", "CROSS_TENANT_TRANSFER", "MATCH_NOT_IN_PROGRESS", "ILLEGAL_TRANSITION",
        "TIE_NOT_ALLOWED", "INVALID_COMPENSATION", "INNINGS_COMPLETE", "SERIES_ALREADY_DECIDED",
        "UNKNOWN_EVENT_TYPE", "DUPLICATE_EVENT_ID", "DUPLICATE_ORDER_ID", "INVALID_BASE_VERSION",
        "MATCH_NOT_FOUND", "CLUB_NOT_FOUND", "LEAGUE_NOT_FOUND", "PRODUCT_NOT_FOUND",
        "ORDER_NOT_FOUND", "PLAYER_NOT_FOUND",
    )

    fun message(error: ApiError, context: ErrorContext = ErrorContext.General): String = when (error) {
        ApiError.NoConnection ->
            if (context == ErrorContext.Login) "You're offline. Connect to the internet to sign in."
            else "You're offline. We'll use saved data where we can."
        ApiError.ServerUnreachable -> "Can't reach the server. Check the server address in Settings."
        ApiError.Timeout -> "The server is taking too long. Please try again."
        is ApiError.Unexpected -> GENERIC
        is ApiError.Http -> forCode(error.code, context)
    }

    fun forCode(code: String, context: ErrorContext = ErrorContext.General): String = when {
        code == "UNAUTHENTICATED" ->
            if (context == ErrorContext.Login) "Email or password is incorrect." else "Please sign in again."
        code == "FORBIDDEN" -> "You don't have permission to do that."
        code == "RATE_LIMITED" ->
            if (context == ErrorContext.Login) "Too many attempts. Please wait a few minutes and try again."
            else "Too many requests. Please wait a few seconds."
        code == "VALIDATION_ERROR" -> "Some details look wrong. Please check and try again."
        code == "OUT_OF_STOCK" -> "Sorry, this item just sold out."
        code == "PAYMENT_DECLINED" -> "Your payment was declined. No money was taken."
        code == "PAYMENT_CAPTURE_FAILED" -> "The payment couldn't be completed. No money was taken."
        code == "INVALID_DISCOUNT_CODE" -> "That discount code isn't valid."
        code == "TRANSFER_WINDOW_CLOSED" -> "The transfer window is closed for that date."
        code == "PLAYER_ON_LOAN" -> "This player is on loan and can't be moved right now."
        code == "SQUAD_FULL" -> "The destination squad is already full."
        code == "MIN_POSITION_VIOLATION" -> "This would leave the squad without enough players in that position."
        code == "NOT_IN_SQUAD" -> "This player isn't in that squad any more."
        code == "CROSS_TENANT_TRANSFER" -> "Players can only move between clubs in the same organisation."
        code == "MATCH_NOT_IN_PROGRESS" -> "This match isn't live, so scores can't be added."
        code == "ILLEGAL_TRANSITION" -> "That action isn't allowed for the match right now."
        code == "TIE_NOT_ALLOWED" -> "This match can't end level. Record the tie-break winner first."
        code == "INVALID_COMPENSATION" -> "That event was already corrected."
        code == "INNINGS_COMPLETE" -> "This team's innings is already complete."
        code == "SERIES_ALREADY_DECIDED" -> "This series has already been decided."
        code == "UNKNOWN_EVENT_TYPE" -> "That scoring action isn't valid for this sport."
        code == "DUPLICATE_EVENT_ID" || code == "DUPLICATE_ORDER_ID" -> "This was already submitted."
        code == "INVALID_BASE_VERSION" -> "Your data was out of date. Please refresh and try again."
        code.endsWith("_NOT_FOUND") -> "We couldn't find that item. It may have been removed."
        else -> GENERIC
    }
}
