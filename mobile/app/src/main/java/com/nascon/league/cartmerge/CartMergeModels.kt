package com.nascon.league.cartmerge

import com.nascon.league.core.network.NetworkResult
import com.nascon.league.matchlive.CartLine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

interface CartMergeRepository {
    suspend fun merge(guestToken: String, userId: String, lines: List<CartLine>): NetworkResult<List<CartLine>>
}

sealed interface MergeState {
    data object Idle : MergeState
    data object Staged : MergeState
    data object Merging : MergeState
    data class Merged(val cart: List<CartLine>) : MergeState
    /** OUT_OF_STOCK. */
    data class Conflict(val variantId: String?) : MergeState
    data class AlreadyMerged(val cart: List<CartLine>) : MergeState
    data object FailedRetryable : MergeState
    data class VariantMissing(val variantId: String?) : MergeState
    data object TooManyItems : MergeState
    data object UserMissing : MergeState
}

object CartMath {
    const val MAX_LINES = 10

    fun total(lines: List<CartLine>): Long = lines.sumOf { it.priceMinor * it.quantity }

    fun count(lines: List<CartLine>): Int = lines.sumOf { it.quantity }

    /** Stable key order: guest_token, registered_user_id, guest_cart (sorted by variant id), test_write_block. */
    fun body(guestToken: String, userId: String, lines: List<CartLine>): JsonObject {
        val cart = lines.sortedBy { it.variantId }.map {
            JsonObject(linkedMapOf("variant_id" to JsonPrimitive(it.variantId), "quantity" to JsonPrimitive(it.quantity)))
        }
        return JsonObject(
            linkedMapOf(
                "guest_token" to JsonPrimitive(guestToken),
                "registered_user_id" to JsonPrimitive(userId),
                "guest_cart" to JsonArray(cart),
                "test_write_block" to JsonPrimitive(false),
            ),
        )
    }
}
