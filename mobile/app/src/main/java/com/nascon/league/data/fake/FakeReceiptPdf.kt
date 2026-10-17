package com.nascon.league.data.fake

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.nascon.league.data.store.formatPkr
import java.io.ByteArrayOutputStream

/** Builds a real one-page A5-ish PDF with the order reference and total. */
class AndroidReceiptPdfGenerator : ReceiptPdfGenerator {
    override fun generate(orderId: String, totalMinor: Long): ByteArray {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(420, 595, 1).create())
            val canvas = page.canvas
            val title = Paint().apply { textSize = 22f; isFakeBoldText = true; isAntiAlias = true }
            val body = Paint().apply { textSize = 14f; isAntiAlias = true }
            canvas.drawText("Receipt", 36f, 64f, title)
            canvas.drawText("Order: $orderId", 36f, 110f, body)
            canvas.drawText("Total: ${formatPkr(totalMinor)}", 36f, 140f, body)
            canvas.drawText("Demo payment - no real money was charged.", 36f, 180f, body)
            doc.finishPage(page)
            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }
}
