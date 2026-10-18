package com.nascon.league.data.cart

import com.nascon.league.matchlive.CartLine
import com.nascon.league.matchlive.GuestCart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Room-backed guest cart keyed by the current guest token. */
class RoomGuestCart(
    private val dao: GuestCartDao,
    private val tokenProvider: () -> String?,
) : GuestCart {

    private val mutex = Mutex()

    private fun key(): String = tokenProvider()?.takeIf { it.isNotBlank() } ?: FALLBACK_KEY

    private fun GuestCartItemEntity.toLine() = CartLine(variantId, name, priceMinor, quantity)

    override val lines: Flow<List<CartLine>> = flow {
        emitAll(dao.observe(key()).map { list -> list.map { it.toLine() } })
    }

    override val count: Flow<Int> = flow {
        emitAll(dao.observe(key()).map { list -> list.sumOf { it.quantity } })
    }

    override suspend fun add(variantId: String, name: String, priceMinor: Long, quantity: Int) {
        val add = quantity.coerceAtLeast(1)
        mutex.withLock {
            val token = key()
            val existing = dao.find(token, variantId)
            if (existing != null) {
                dao.upsert(existing.copy(quantity = (existing.quantity + add).coerceAtMost(MAX_QTY)))
            } else {
                if (dao.items(token).size >= MAX_DISTINCT) return
                dao.upsert(GuestCartItemEntity(token, variantId, name, priceMinor, add.coerceAtMost(MAX_QTY)))
            }
        }
    }

    override suspend fun setQuantity(variantId: String, quantity: Int) {
        mutex.withLock {
            val token = key()
            if (quantity <= 0) {
                dao.delete(token, variantId)
                return
            }
            val existing = dao.find(token, variantId) ?: return
            dao.upsert(existing.copy(quantity = quantity.coerceAtMost(MAX_QTY)))
        }
    }

    override suspend fun remove(variantId: String) {
        mutex.withLock { dao.delete(key(), variantId) }
    }

    override suspend fun clear() {
        mutex.withLock { dao.clear(key()) }
    }

    /** Current lines, for the merge request. */
    suspend fun snapshot(): List<CartLine> = dao.items(key()).sortedBy { it.name }.map { it.toLine() }

    companion object {
        const val FALLBACK_KEY = "guest_unknown"
        const val MAX_QTY = 10
        const val MAX_DISTINCT = 10
    }
}

private suspend fun <T> kotlinx.coroutines.flow.FlowCollector<T>.emitAll(f: Flow<T>) =
    f.collect { emit(it) }
