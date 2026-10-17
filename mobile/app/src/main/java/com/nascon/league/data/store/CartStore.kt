package com.nascon.league.data.store

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nascon.league.core.network.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

private val Context.cartDataStore by preferencesDataStore(name = "f6_cart")

class CartStore(context: Context, private val currentUserId: () -> String?) {
    private val store = context.applicationContext.cartDataStore

    fun observe(userId: String?): Flow<Cart> {
        val key = keyFor(userId)
        return store.data.map { Cart(decodeCart(it[key])) }.distinctUntilChanged()
    }

    val cart: Flow<Cart> = flow { emitAll(observe(currentUserId())) }

    suspend fun add(line: CartLine) = mutate { addLine(it, line) }

    suspend fun setQuantity(variantId: String, qty: Int) = mutate { setLineQuantity(it, variantId, qty) }

    suspend fun remove(variantId: String): CartLine? {
        var removed: CartLine? = null
        mutate { lines ->
            removed = lines.firstOrNull { it.variantId == variantId }
            lines.filterNot { it.variantId == variantId }
        }
        return removed
    }

    suspend fun restore(line: CartLine) = mutate { addLine(it, line) }

    suspend fun replaceAll(lines: List<CartLine>) = mutate { lines }

    suspend fun clear() = mutate { emptyList() }

    private suspend fun mutate(transform: (List<CartLine>) -> List<CartLine>) {
        val key = keyFor(currentUserId())
        store.edit { prefs: androidx.datastore.preferences.core.MutablePreferences ->
            val next = transform(decodeCart(prefs[key]))
            if (next.isEmpty()) prefs.remove(key) else prefs[key] = encodeCart(next)
        }
    }

    companion object {
        const val MAX_QUANTITY = 10
        private val SERIALIZER = ListSerializer(CartLine.serializer())

        fun keyName(userId: String?): String = "cart_${userId ?: "anonymous"}"

        private fun keyFor(userId: String?): Preferences.Key<String> = stringPreferencesKey(keyName(userId))

        fun encodeCart(lines: List<CartLine>): String = AppJson.encodeToString(SERIALIZER, lines)

        fun decodeCart(json: String?): List<CartLine> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                AppJson.decodeFromString(SERIALIZER, json)
            } catch (_: Exception) {
                emptyList()
            }
        }

        /** Merges a same-variant line by summing quantities (clamped to 10); new lines are appended. */
        fun addLine(lines: List<CartLine>, line: CartLine): List<CartLine> {
            val existing = lines.indexOfFirst { it.variantId == line.variantId }
            val clamped = line.copy(quantity = line.quantity.coerceIn(1, MAX_QUANTITY))
            if (existing < 0) return lines + clamped
            return lines.toMutableList().also {
                val old = it[existing]
                it[existing] = clamped.copy(quantity = (old.quantity + clamped.quantity).coerceAtMost(MAX_QUANTITY))
            }
        }

        /** Quantity below 1 removes nothing; it is clamped to 1..10. */
        fun setLineQuantity(lines: List<CartLine>, variantId: String, qty: Int): List<CartLine> =
            lines.map { if (it.variantId == variantId) it.copy(quantity = qty.coerceIn(1, MAX_QUANTITY)) else it }
    }
}
