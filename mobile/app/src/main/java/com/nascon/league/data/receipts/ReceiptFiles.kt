package com.nascon.league.data.receipts

import java.io.File
import java.io.InputStream
import java.util.Base64

/** Pure (Context-free) file helpers so they can be unit tested on the JVM. */
object ReceiptFiles {
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    fun fileName(orderId: String): String = UNSAFE.replace(orderId, "_") + ".pdf"

    fun fileFor(dir: File, orderId: String): File = File(dir, fileName(orderId))

    /**
     * Decodes [base64] and writes it atomically (temp file + rename) to [dir]/{orderId}.pdf.
     * Returns the file, or null when [base64] is blank or not valid base64.
     */
    fun writeBase64(dir: File, orderId: String, base64: String): File? {
        if (base64.isBlank()) return null
        val bytes = try {
            Base64.getDecoder().decode(base64.filterNot { it.isWhitespace() })
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty()) return null
        return writeStream(dir, orderId, bytes.inputStream())
    }

    /** Streams [input] into the receipt file atomically. The input is closed. */
    fun writeStream(dir: File, orderId: String, input: InputStream): File {
        dir.mkdirs()
        val target = fileFor(dir, orderId)
        val temp = File(dir, target.name + ".tmp")
        try {
            input.use { src -> temp.outputStream().use { out -> src.copyTo(out) } }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        return target
    }
}
