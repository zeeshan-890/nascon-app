package com.nascon.league.ui.receipt

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps a PdfRenderer. PdfRenderer is not thread-safe and allows one open page at a time,
 * so every access happens under [mutex] on a background dispatcher.
 */
class PdfPages private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val pageSizes: List<Pair<Int, Int>>,
) {
    private val mutex = Mutex()
    private var closed = false

    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    val pageCount: Int get() = pageSizes.size

    suspend fun render(index: Int, widthPx: Int): Bitmap? {
        if (widthPx <= 0) return null
        val key = "$index@$widthPx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                if (closed) return@withLock null
                cache.get(key)?.let { return@withLock it }
                val page = renderer.openPage(index)
                try {
                    val height = (widthPx.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bitmap)
                    bitmap
                } finally {
                    page.close()
                }
            }
        }
    }

    /** Closes the renderer once any in-flight render has finished. */
    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                try {
                    renderer.close()
                } catch (_: Exception) {
                }
                try {
                    descriptor.close()
                } catch (_: Exception) {
                }
                cache.evictAll()
            }
        }
    }

    companion object {
        private const val CACHE_KB = 24 * 1024

        /** Opens [file]; returns null when it is missing, empty or not a readable PDF. */
        suspend fun open(file: File): PdfPages? = withContext(Dispatchers.IO) {
            var pfd: ParcelFileDescriptor? = null
            var r: PdfRenderer? = null
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                r = PdfRenderer(pfd)
                if (r.pageCount == 0) throw IllegalStateException("empty pdf")
                val sizes = (0 until r.pageCount).map { i ->
                    val p = r.openPage(i)
                    try {
                        p.width to p.height
                    } finally {
                        p.close()
                    }
                }
                PdfPages(pfd, r, sizes)
            } catch (_: Exception) {
                try {
                    r?.close()
                } catch (_: Exception) {
                }
                try {
                    pfd?.close()
                } catch (_: Exception) {
                }
                null
            }
        }
    }
}
