package com.nascon.league.ui.login

import com.nascon.league.core.network.ApiError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Which login failure presentation applies (A03 wrong credentials, A04 locked, A06 unreachable). */
enum class LoginFailureKind { CREDENTIALS, LOCKED, UNREACHABLE, OTHER }

fun loginFailureKind(error: ApiError): LoginFailureKind = when {
    error is ApiError.Http && error.code == "UNAUTHENTICATED" -> LoginFailureKind.CREDENTIALS
    error is ApiError.Http && error.code == "RATE_LIMITED" -> LoginFailureKind.LOCKED
    error is ApiError.ServerUnreachable -> LoginFailureKind.UNREACHABLE
    else -> LoginFailureKind.OTHER
}

private fun JsonObject?.firstInt(vararg keys: String): Int? {
    if (this == null) return null
    for (k in keys) (this[k] as? JsonPrimitive)?.intOrNull?.let { return it }
    return null
}

/** Seconds until the lock ends, only when the server sent it. */
fun lockedForSeconds(error: ApiError): Int? =
    (error as? ApiError.Http)?.takeIf { it.code == "RATE_LIMITED" }
        ?.details.firstInt("retryAfterSeconds", "retryAfter", "retry_after")
        ?.takeIf { it > 0 }

/** Remaining password attempts, only when the server sent it. */
fun attemptsLeft(error: ApiError): Int? =
    (error as? ApiError.Http)?.takeIf { it.code == "UNAUTHENTICATED" }
        ?.details.firstInt("attemptsRemaining", "attemptsLeft", "attempts_left")
        ?.takeIf { it >= 0 }
