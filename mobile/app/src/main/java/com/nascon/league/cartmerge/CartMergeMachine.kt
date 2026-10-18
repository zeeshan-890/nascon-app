package com.nascon.league.cartmerge

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.matchlive.CartLine
import com.nascon.league.matchlive.GuestCart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class CartMergeMachine(
    private val repo: CartMergeRepository,
    private val cart: GuestCart,
    private val guestTokenProvider: () -> String?,
    private val snapshotLines: suspend () -> List<CartLine>,
) {
    private data class Frozen(val token: String, val userId: String, val lines: List<CartLine>)

    private val _state = MutableStateFlow<MergeState>(MergeState.Idle)
    val state: StateFlow<MergeState> = _state.asStateFlow()

    private val lock = Any()
    private var staged: List<CartLine> = emptyList()
    private var frozen: Frozen? = null

    suspend fun stage(): Boolean {
        val lines = snapshotLines().sortedBy { it.variantId }
        synchronized(lock) {
            if (_state.value == MergeState.Merging) return staged.isNotEmpty()
            staged = lines
            frozen = null
            _state.value = if (lines.isEmpty()) MergeState.Idle else MergeState.Staged
        }
        return lines.isNotEmpty()
    }

    suspend fun restage() {
        synchronized(lock) { frozen = null }
        stage()
    }

    /** Keeps the first 10 lines by variant id; the rest stay in the guest cart. */
    suspend fun trim() {
        synchronized(lock) {
            if (staged.size > CartMath.MAX_LINES) staged = staged.take(CartMath.MAX_LINES)
            frozen = null
            if (staged.isNotEmpty() && _state.value != MergeState.Merging) _state.value = MergeState.Staged
        }
    }

    suspend fun merge(userId: String): MergeState {
        if (staged.isEmpty() && frozen == null) stage()
        val f: Frozen
        synchronized(lock) {
            if (_state.value == MergeState.Merging) return _state.value
            if (frozen?.userId != userId) frozen = null
            if (frozen == null) {
                if (staged.isEmpty()) return _state.value
                val token = guestTokenProvider()
                if (token.isNullOrBlank()) {
                    _state.value = MergeState.FailedRetryable
                    return _state.value
                }
                frozen = Frozen(token, userId, staged.sortedBy { it.variantId })
            }
            f = frozen!!
            _state.value = MergeState.Merging
        }
        val result = try {
            repo.merge(f.token, f.userId, f.lines)
        } catch (e: kotlinx.coroutines.CancellationException) {
            synchronized(lock) { _state.value = MergeState.Staged }
            throw e
        }
        val next: MergeState = when (result) {
            is NetworkResult.Success -> {
                cart.clear()
                MergeState.Merged(result.data.ifEmpty { f.lines })
            }
            is NetworkResult.Failure -> when (val e = result.error) {
                is ApiError.Http -> mapHttp(e, f)
                else -> MergeState.FailedRetryable
            }
        }
        if (next is MergeState.AlreadyMerged) cart.clear()
        _state.value = next
        return next
    }

    private fun mapHttp(e: ApiError.Http, f: Frozen): MergeState {
        val variant = (e.details?.get("variant_id") as? JsonPrimitive)?.contentOrNull
        return when {
            e.status == 409 && e.code == "OUT_OF_STOCK" -> MergeState.Conflict(variant)
            e.status == 409 && e.code == "GUEST_TOKEN_ALREADY_MERGED" -> MergeState.AlreadyMerged(emptyList())
            e.status == 400 -> if (f.lines.size > CartMath.MAX_LINES) MergeState.TooManyItems else MergeState.FailedRetryable
            e.status == 404 && e.code == "VARIANT_NOT_FOUND" -> MergeState.VariantMissing(variant)
            e.status == 404 && e.code == "PLAYER_NOT_FOUND" -> MergeState.UserMissing
            else -> MergeState.FailedRetryable
        }
    }
}
