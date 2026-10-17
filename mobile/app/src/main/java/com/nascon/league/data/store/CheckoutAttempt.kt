package com.nascon.league.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nascon.league.core.network.AppJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.UUID

private val Context.attemptDataStore by preferencesDataStore(name = "f6_checkout_attempt")

@Serializable
data class CheckoutAttempt(
    @SerialName("order_id") val orderId: String,
    @SerialName("request_hash") val requestHash: String,
)

enum class CheckoutOutcome { Success, Declined, OutOfStock, BadRequest, DuplicateOrderId, Unknown }

/** Unknown / network outcomes keep the attempt so a retry reuses the same order id. */
fun shouldClearAttempt(outcome: CheckoutOutcome): Boolean = outcome != CheckoutOutcome.Unknown

object CheckoutAttempts {
    fun hash(items: List<Pair<String, Int>>, discountCode: String?, token: String): String {
        val text = buildString {
            items.forEach { (id, qty) -> append(id.length).append(':').append(id).append('x').append(qty).append(';') }
            append("|d=").append(discountCode?.trim().orEmpty())
            append("|t=").append(token)
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun resolve(
        stored: CheckoutAttempt?,
        newHash: String,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): CheckoutAttempt =
        if (stored != null && stored.requestHash == newHash) stored else CheckoutAttempt(newId(), newHash)
}

class CheckoutAttemptStore(context: Context, private val currentUserId: () -> String?) {
    private val store = context.applicationContext.attemptDataStore

    private fun key() = stringPreferencesKey(keyName(currentUserId()))

    suspend fun get(): CheckoutAttempt? {
        val raw = store.data.first()[key()] ?: return null
        return try {
            AppJson.decodeFromString(CheckoutAttempt.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun set(a: CheckoutAttempt) {
        val k = key()
        store.edit { it[k] = AppJson.encodeToString(CheckoutAttempt.serializer(), a) }
    }

    suspend fun clear() {
        val k = key()
        store.edit { it.remove(k) }
    }

    companion object {
        fun keyName(userId: String?): String = "attempt_${userId ?: "anonymous"}"
    }
}
