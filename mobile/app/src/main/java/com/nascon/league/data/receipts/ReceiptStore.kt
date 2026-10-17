package com.nascon.league.data.receipts

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.AppJson
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.core.time.Clock
import com.nascon.league.data.api.LeagueApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

private val Context.receiptDataStore by preferencesDataStore(name = "receipt_index")

sealed interface ReceiptLoadResult {
    data class Ready(val file: File) : ReceiptLoadResult
    data object NotSavedOffline : ReceiptLoadResult
    data class Failed(val error: ApiError) : ReceiptLoadResult
}

class ReceiptStore(
    context: Context,
    private val currentUserId: () -> String?,
    private val apiProvider: () -> LeagueApi,
) {
    private val appContext = context.applicationContext
    private val store = appContext.receiptDataStore
    private val dir: File get() = File(appContext.filesDir, "receipts")

    fun observeIndex(): Flow<List<ReceiptEntry>> = store.data.map { prefs ->
        val uid = currentUserId() ?: return@map emptyList()
        sortNewestFirst(parseIndex(prefs[indexKey(uid)]))
    }

    fun receiptFile(orderId: String): File = ReceiptFiles.fileFor(dir, orderId)

    fun hasLocalFile(orderId: String): Boolean = receiptFile(orderId).let { it.isFile && it.length() > 0 }

    suspend fun saveReceipt(orderId: String, pdfBase64: String, totalMinor: Long, paidAtIso: String) {
        withContext(Dispatchers.IO) {
            if (pdfBase64.isNotBlank()) {
                try {
                    ReceiptFiles.writeBase64(dir, orderId, pdfBase64)
                } catch (_: Exception) {
                    // Storage problem: still index so the receipt can be downloaded later.
                }
            }
        }
        addToIndex(ReceiptEntry(orderId, totalMinor, paidAtIso))
    }

    suspend fun ensureDownloaded(orderId: String): ReceiptLoadResult {
        if (hasLocalFile(orderId)) return ReceiptLoadResult.Ready(receiptFile(orderId))
        val result = safeCall {
            val body = apiProvider().receiptPdf(orderId)
            withContext(Dispatchers.IO) {
                body.use { ReceiptFiles.writeStream(dir, orderId, it.byteStream()) }
            }
        }
        return when (result) {
            is NetworkResult.Success -> {
                if (!hasLocalFile(orderId)) {
                    result.data.delete()
                    ReceiptLoadResult.Failed(ApiError.Unexpected())
                } else {
                    addToIndexIfMissing(orderId)
                    ReceiptLoadResult.Ready(result.data)
                }
            }
            is NetworkResult.Failure -> when (result.error) {
                ApiError.NoConnection, ApiError.ServerUnreachable, ApiError.Timeout ->
                    ReceiptLoadResult.NotSavedOffline
                else -> ReceiptLoadResult.Failed(result.error)
            }
        }
    }

    /** Removes the local file (e.g. corrupt) so it can be downloaded again. */
    suspend fun delete(orderId: String) {
        withContext(Dispatchers.IO) { receiptFile(orderId).delete() }
    }

    private suspend fun addToIndexIfMissing(orderId: String) {
        val uid = currentUserId() ?: return
        val existing = parseIndex(store.data.first()[indexKey(uid)])
        if (existing.none { it.orderId == orderId }) {
            addToIndex(ReceiptEntry(orderId, -1L, Clock.nowUtc()))
        }
    }

    private suspend fun addToIndex(entry: ReceiptEntry) {
        val uid = currentUserId() ?: return
        val key = indexKey(uid)
        store.edit { prefs ->
            val current = parseIndex(prefs[key])
            prefs[key] = encodeIndex(addEntry(current, entry))
        }
    }

    companion object {
        fun indexKey(userId: String) = stringPreferencesKey("receipt_index_$userId")

        fun sortNewestFirst(entries: List<ReceiptEntry>): List<ReceiptEntry> =
            entries.sortedWith(compareByDescending<ReceiptEntry> { it.paidAt }.thenBy { it.orderId })

        /** Appends [entry], replacing any existing entry with the same order id. */
        fun addEntry(entries: List<ReceiptEntry>, entry: ReceiptEntry): List<ReceiptEntry> =
            entries.filterNot { it.orderId == entry.orderId } + entry

        fun parseIndex(json: String?): List<ReceiptEntry> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                AppJson.decodeFromString(ListSerializer(ReceiptEntry.serializer()), json)
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun encodeIndex(entries: List<ReceiptEntry>): String =
            AppJson.encodeToString(ListSerializer(ReceiptEntry.serializer()), entries)
    }
}
