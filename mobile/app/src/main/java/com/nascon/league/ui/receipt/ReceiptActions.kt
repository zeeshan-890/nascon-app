package com.nascon.league.ui.receipt

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class DownloadResult { Saved, StorageFull, Failed }

object ReceiptActions {
    fun authority(context: Context) = "${context.packageName}.fileprovider"

    /** Opens the system share sheet. Returns false when nothing can handle it. */
    fun share(context: Context, file: File, chooserTitle: String): Boolean = try {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
        true
    } catch (_: Exception) {
        false
    }

    /** Opens the receipt PDF in a viewer app. Returns false when nothing can open it. */
    fun openPdf(context: Context, file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(view)
        true
    } catch (_: Exception) {
        false
    }

    /** Copies the receipt into the public Downloads folder through MediaStore (API 29+). */
    suspend fun saveToDownloads(context: Context, file: File, orderId: String): DownloadResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var uri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "receipt-${ReceiptFormat.orderReference(orderId)}.pdf")
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext DownloadResult.Failed
                uri = created
                val out = resolver.openOutputStream(created) ?: throw IOException("no stream")
                out.use { o -> file.inputStream().use { it.copyTo(o) } }
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(created, done, null, null)
                DownloadResult.Saved
            } catch (e: Exception) {
                uri?.let { u -> runCatching { resolver.delete(u, null, null) } }
                if (isStorageFull(e)) DownloadResult.StorageFull else DownloadResult.Failed
            }
        }

    private fun isStorageFull(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message.orEmpty()
            if (m.contains("ENOSPC", true) || m.contains("No space left", true)) return true
            t = t.cause
        }
        return false
    }
}
