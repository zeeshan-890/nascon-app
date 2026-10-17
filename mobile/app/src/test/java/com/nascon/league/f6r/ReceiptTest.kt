package com.nascon.league.f6r

import com.nascon.league.data.receipts.ReceiptEntry
import com.nascon.league.data.receipts.ReceiptFiles
import com.nascon.league.data.receipts.ReceiptStore
import com.nascon.league.ui.receipt.ReceiptFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.Base64
import java.util.Locale
import org.junit.Test

class ReceiptTest {
    private val a = ReceiptEntry("a", 100, "2026-01-01T10:00:00Z")
    private val b = ReceiptEntry("b", 200, "2026-03-01T10:00:00Z")
    private val c = ReceiptEntry("c", 300, "2026-02-01T10:00:00Z")

    @Test
    fun sortsNewestFirst() {
        assertEquals(listOf("b", "c", "a"), ReceiptStore.sortNewestFirst(listOf(a, b, c)).map { it.orderId })
    }

    @Test
    fun sortIsDeterministicOnTies() {
        val x = ReceiptEntry("x", 1, "2026-01-01T10:00:00Z")
        val y = ReceiptEntry("y", 1, "2026-01-01T10:00:00Z")
        assertEquals(listOf("x", "y"), ReceiptStore.sortNewestFirst(listOf(y, x)).map { it.orderId })
    }

    @Test
    fun indexRoundTrip() {
        val json = ReceiptStore.encodeIndex(listOf(a, b))
        assertEquals(listOf(a, b), ReceiptStore.parseIndex(json))
    }

    @Test
    fun parseIndexToleratesBadInput() {
        assertTrue(ReceiptStore.parseIndex(null).isEmpty())
        assertTrue(ReceiptStore.parseIndex("").isEmpty())
        assertTrue(ReceiptStore.parseIndex("not json").isEmpty())
    }

    @Test
    fun addEntryDedupesByOrderId() {
        val updated = a.copy(totalMinor = 999)
        val result = ReceiptStore.addEntry(listOf(a, b), updated)
        assertEquals(2, result.size)
        assertEquals(999L, result.first { it.orderId == "a" }.totalMinor)
    }

    @Test
    fun indexKeysAreIsolatedPerUser() {
        assertNotEquals(ReceiptStore.indexKey("u1"), ReceiptStore.indexKey("u2"))
        assertEquals(ReceiptStore.indexKey("u1"), ReceiptStore.indexKey("u1"))
    }

    @Test
    fun orderReferenceIsShortUppercase() {
        assertEquals("1A2B3C4D", ReceiptFormat.orderReference("1a2b3c4d-5e6f-7890-abcd-ef1234567890"))
        assertEquals("AB", ReceiptFormat.orderReference("ab"))
        assertEquals("", ReceiptFormat.orderReference(""))
    }

    @Test
    fun paidDateUsesZoneAndLocale() {
        val s = ReceiptFormat.paidDate("2026-03-05T20:30:00Z", ZoneId.of("Asia/Karachi"), Locale.ENGLISH)
        assertEquals("6 Mar 2026, 01:30", s)
        assertEquals(
            "5 Mar 2026, 20:30",
            ReceiptFormat.paidDate("2026-03-05T20:30:00Z", ZoneId.of("UTC"), Locale.ENGLISH),
        )
    }

    @Test
    fun paidDateInvalidIsNull() {
        assertNull(ReceiptFormat.paidDate("garbage", ZoneId.of("UTC"), Locale.ENGLISH))
    }

    @Test
    fun writesDecodedBase64Atomically() {
        val dir = Files.createTempDirectory("f6r").toFile()
        try {
            val bytes = "%PDF-1.4 hello".toByteArray()
            val file = ReceiptFiles.writeBase64(File(dir, "receipts"), "order-1", Base64.getEncoder().encodeToString(bytes))
            assertNotNull(file)
            assertEquals("order-1.pdf", file!!.name)
            assertTrue(bytes.contentEquals(file.readBytes()))
            assertTrue(File(dir, "receipts").listFiles()!!.none { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun blankOrInvalidBase64WritesNothing() {
        val dir = Files.createTempDirectory("f6r").toFile()
        try {
            assertNull(ReceiptFiles.writeBase64(dir, "o", "  "))
            assertNull(ReceiptFiles.writeBase64(dir, "o", "!!!not base64!!!"))
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fileNameIsSanitised() {
        assertEquals("a_b.pdf", ReceiptFiles.fileName("a/b"))
    }
}
