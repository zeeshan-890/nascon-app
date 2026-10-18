package com.nascon.league.core

import com.nascon.league.core.error.Part2Errors
import com.nascon.league.core.error.UiErrorAction
import com.nascon.league.core.network.ApiError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Part2ErrorsTest {
    private fun http(
        status: Int,
        code: String,
        details: JsonObject? = null,
        retry: Int? = null,
        message: String = "raw {\"error\": 1}",
    ) = ApiError.Http(status, code, message, details, retry)

    private fun map(status: Int, code: String, details: JsonObject? = null, retry: Int? = null) =
        Part2Errors.map(http(status, code, details, retry))

    @Test fun guestWriteForbidden() {
        assertEquals(UiErrorAction.OpenSignInSheet, map(403, "GUEST_WRITE_FORBIDDEN"))
    }

    @Test fun guestLockCopyConstant() {
        assertEquals("Sign in to do this. Your cart is saved.", Part2Errors.GUEST_LOCK_COPY)
    }

    @Test fun unauthenticated() {
        assertEquals(UiErrorAction.GoGuestAndOfferSignIn, map(401, "UNAUTHENTICATED"))
    }

    @Test fun forbidden() {
        assertEquals(UiErrorAction.Inline("You are not allowed to do this."), map(403, "FORBIDDEN"))
    }

    @Test fun costreamFull() {
        assertEquals(UiErrorAction.Inline("This session is full."), map(409, "COSTREAM_SESSION_FULL"))
    }

    @Test fun unsupportedLayout() {
        assertEquals(UiErrorAction.Inline("That layout is not available."), map(400, "UNSUPPORTED_LAYOUT"))
    }

    @Test fun keyRevoked() {
        assertEquals(UiErrorAction.Inline("This stream key was revoked."), map(403, "KEY_REVOKED"))
    }

    @Test fun guestTokenMerged() {
        assertEquals(UiErrorAction.Inline("Your guest cart was already merged."), map(409, "GUEST_TOKEN_ALREADY_MERGED"))
    }

    @Test fun payloadTooLarge() {
        assertEquals(UiErrorAction.Inline("That is too big to send."), map(413, "PAYLOAD_TOO_LARGE"))
    }

    @Test fun notFoundCodes() {
        for (c in listOf("PLAYER_NOT_FOUND", "VARIANT_NOT_FOUND", "MATCH_NOT_FOUND", "QR_NOT_FOUND", "STREAM_NOT_FOUND", "NOT_FOUND")) {
            assertEquals(c, UiErrorAction.NotFound, map(404, c))
        }
    }

    @Test fun notFoundCodeWithOddStatus() {
        assertEquals(UiErrorAction.NotFound, map(400, "ORDER_NOT_FOUND"))
    }

    @Test fun codeIsCaseInsensitive() {
        assertEquals(UiErrorAction.NotFound, map(400, "player_not_found"))
    }

    @Test fun validationWithFields() {
        val d = buildJsonObject { put("fields", JsonArray(listOf(JsonPrimitive("email"), JsonPrimitive("name")))) }
        val r = map(400, "VALIDATION_ERROR", d) as UiErrorAction.FieldErrors
        assertEquals(listOf("email", "name"), r.fields)
        assertTrue(r.message.isNotBlank())
    }

    @Test fun validationNullDetails() {
        val r = map(400, "VALIDATION_ERROR", null) as UiErrorAction.FieldErrors
        assertTrue(r.fields.isEmpty())
    }

    @Test fun validationEmptyDetails() {
        val r = map(400, "VALIDATION_ERROR", JsonObject(emptyMap())) as UiErrorAction.FieldErrors
        assertTrue(r.fields.isEmpty())
    }

    @Test fun validationFieldsWrongType() {
        val d = buildJsonObject { put("fields", "email") }
        val r = map(400, "VALIDATION_ERROR", d) as UiErrorAction.FieldErrors
        assertTrue(r.fields.isEmpty())
    }

    @Test fun validationFieldsObjectType() {
        val d = buildJsonObject { put("fields", buildJsonObject { put("a", 1) }) }
        val r = map(400, "VALIDATION_ERROR", d) as UiErrorAction.FieldErrors
        assertTrue(r.fields.isEmpty())
    }

    @Test fun validationFieldsMixedElementsKeepOnlyStrings() {
        val d = buildJsonObject {
            put("fields", JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(3), JsonNull, buildJsonObject { })))
        }
        val r = map(400, "VALIDATION_ERROR", d) as UiErrorAction.FieldErrors
        assertEquals(listOf("a"), r.fields)
    }

    @Test fun outOfStockWithVariant() {
        val d = buildJsonObject { put("variant_id", "v-1") }
        assertEquals(UiErrorAction.OutOfStock("v-1"), map(409, "OUT_OF_STOCK", d))
    }

    @Test fun outOfStockWithoutVariant() {
        assertEquals(UiErrorAction.OutOfStock(null), map(409, "OUT_OF_STOCK", null))
    }

    @Test fun outOfStockVariantNullOrWrongType() {
        val n = buildJsonObject { put("variant_id", JsonNull) }
        val o = buildJsonObject { put("variant_id", buildJsonObject { }) }
        val num = buildJsonObject { put("variant_id", 7) }
        assertEquals(UiErrorAction.OutOfStock(null), map(409, "OUT_OF_STOCK", n))
        assertEquals(UiErrorAction.OutOfStock(null), map(409, "OUT_OF_STOCK", o))
        assertEquals(UiErrorAction.OutOfStock(null), map(409, "OUT_OF_STOCK", num))
    }

    @Test fun rateLimitedWithRetryAfter() {
        val r = map(429, "RATE_LIMITED", null, 12) as UiErrorAction.Backoff
        assertEquals(12, r.retryAfterSeconds)
    }

    @Test fun rateLimitedDefault() {
        val r = map(429, "RATE_LIMITED") as UiErrorAction.Backoff
        assertEquals(5, r.retryAfterSeconds)
    }

    @Test fun rateLimitedClampsHigh() {
        assertEquals(60, (map(429, "RATE_LIMITED", null, 9999) as UiErrorAction.Backoff).retryAfterSeconds)
    }

    @Test fun rateLimitedClampsLowAndNegative() {
        assertEquals(1, (map(429, "RATE_LIMITED", null, 0) as UiErrorAction.Backoff).retryAfterSeconds)
        assertEquals(1, (map(429, "RATE_LIMITED", null, -4) as UiErrorAction.Backoff).retryAfterSeconds)
    }

    @Test fun unknownCodeIsGeneric() {
        val r = map(400, "SOMETHING_WEIRD") as UiErrorAction.Generic
        assertEquals("Something went wrong. Please try again.", r.message)
        assertTrue(r.canRetry)
    }

    @Test fun emptyCodeAndNoStatusHint() {
        assertTrue(map(400, "") is UiErrorAction.Generic)
    }

    @Test fun httpOnlyFallbacks() {
        assertEquals(UiErrorAction.GoGuestAndOfferSignIn, map(401, ""))
        assertEquals(UiErrorAction.Inline("You are not allowed to do this."), map(403, ""))
        assertEquals(UiErrorAction.NotFound, map(404, ""))
        assertTrue(map(429, "") is UiErrorAction.Backoff)
    }

    @Test fun serverErrorsAreGenericRetryable() {
        for (s in listOf(500, 502, 503)) {
            val r = map(s, "") as UiErrorAction.Generic
            assertTrue(r.canRetry)
        }
    }

    @Test fun unknownCodeWith401StillGoesGuest() {
        assertEquals(UiErrorAction.GoGuestAndOfferSignIn, map(401, "TOKEN_EXPIRED_X"))
    }

    @Test fun transportErrorsAreGenericRetryable() {
        for (e in listOf(ApiError.NoConnection, ApiError.Timeout, ApiError.ServerUnreachable, ApiError.Unexpected(RuntimeException("x")), ApiError.Unexpected())) {
            val r = Part2Errors.map(e) as UiErrorAction.Generic
            assertTrue(r.canRetry)
            assertTrue(r.message.isNotBlank())
        }
    }

    @Test fun messagesNeverLeakRawJsonOrServerText() {
        val d = buildJsonObject { put("fields", JsonArray(listOf(JsonPrimitive("x")))) }
        val results = mutableListOf<UiErrorAction>()
        for (c in listOf("FORBIDDEN", "COSTREAM_SESSION_FULL", "UNSUPPORTED_LAYOUT", "KEY_REVOKED", "GUEST_TOKEN_ALREADY_MERGED",
            "PAYLOAD_TOO_LARGE", "VALIDATION_ERROR", "OUT_OF_STOCK", "RATE_LIMITED", "WHATEVER", "")) {
            for (s in listOf(400, 403, 429, 500)) results += map(s, c, d)
        }
        results += listOf(ApiError.NoConnection, ApiError.Timeout, ApiError.ServerUnreachable, ApiError.Unexpected()).map { Part2Errors.map(it) }
        for (r in results) {
            val msg = when (r) {
                is UiErrorAction.Inline -> r.message
                is UiErrorAction.FieldErrors -> r.message
                is UiErrorAction.Backoff -> r.message
                is UiErrorAction.Generic -> r.message
                else -> ""
            }
            assertFalse(msg, msg.contains("{") || msg.contains("}"))
            assertFalse(msg, msg.contains("error\":"))
            assertFalse(msg, msg.contains("raw"))
        }
    }
}
