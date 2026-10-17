package com.nascon.league.neworders

import com.nascon.league.R
import com.nascon.league.data.receipts.ReceiptEntry
import com.nascon.league.ui.orders.orderStatusRes
import com.nascon.league.ui.orders.sortOrdersNewestFirst
import org.junit.Assert.assertEquals
import org.junit.Test

class OrdersLogicTest {
    @Test fun newestFirst() {
        val a = ReceiptEntry("a", 1, "2026-01-01T10:00:00Z")
        val b = ReceiptEntry("b", 2, "2026-02-01T10:00:00Z")
        assertEquals(listOf("b", "a"), sortOrdersNewestFirst(listOf(a, b)).map { it.orderId })
    }

    @Test fun statusMapping() {
        assertEquals(R.string.ord_status_paid, orderStatusRes("PAID"))
        assertEquals(R.string.ord_status_cancelled, orderStatusRes("canceled"))
        assertEquals(R.string.ord_status_unknown, orderStatusRes("weird_code"))
        assertEquals(R.string.ord_status_unknown, orderStatusRes(null))
    }
}
