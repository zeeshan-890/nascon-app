package com.nascon.league.ordersplus

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.AppJson
import com.nascon.league.core.network.mapThrowable
import com.nascon.league.data.api.dto.OrderDetailDto
import com.nascon.league.data.fake.FakeStore
import com.nascon.league.data.orders.ClubOrderDto
import com.nascon.league.data.orders.FakeClubOrdersApi
import com.nascon.league.data.orders.OrderStatusRequest
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import com.nascon.league.ui.orders.OrderStateMachine
import com.nascon.league.ui.orders.canManageOrders
import com.nascon.league.ui.orders.sortClubOrdersNewestFirst
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrdersPlusTests {
    private fun session(role: Role, clubId: String? = null) =
        Session(User(userId = "u", email = "e@x.t", role = role, clubId = clubId))

    private fun api(): FakeClubOrdersApi {
        val store = FakeStore(pdf = com.nascon.league.data.fake.ReceiptPdfGenerator { _, _ -> ByteArray(0) })
        return FakeClubOrdersApi({ true }) { store }
    }

    @Test fun stateMachineMatchesBackend() {
        assertEquals(listOf("paid", "cancelled"), OrderStateMachine.next("pending"))
        assertEquals(listOf("packed", "refunded", "cancelled"), OrderStateMachine.next("paid"))
        assertEquals(listOf("shipped", "refunded"), OrderStateMachine.next("packed"))
        assertEquals(listOf("delivered"), OrderStateMachine.next("shipped"))
        assertEquals(listOf("refunded"), OrderStateMachine.next("delivered"))
        assertTrue(OrderStateMachine.next("refunded").isEmpty())
        assertTrue(OrderStateMachine.next("cancelled").isEmpty())
        assertTrue(OrderStateMachine.next("bogus").isEmpty())
        assertFalse(OrderStateMachine.canTransition("shipped", "refunded"))
        assertEquals(listOf("packed", "cancelled"), OrderStateMachine.statusActions("paid"))
        assertTrue(OrderStateMachine.restockOn("paid", "refunded"))
        assertTrue(OrderStateMachine.restockOn("packed", "refunded"))
        assertFalse(OrderStateMachine.restockOn("delivered", "refunded"))
    }

    @Test fun roleGate() {
        assertTrue(canManageOrders(session(Role.SUPER_ADMIN)))
        assertTrue(canManageOrders(session(Role.CLUB_MANAGER, "club-alpha-1")))
        assertTrue(canManageOrders(session(Role.CLUB_MANAGER, "club-alpha-1"), "club-alpha-1"))
        assertFalse(canManageOrders(session(Role.CLUB_MANAGER, "club-alpha-1"), "club-alpha-2"))
        assertFalse(canManageOrders(session(Role.CLUB_MANAGER, null)))
        assertFalse(canManageOrders(session(Role.PLAYER, "club-alpha-1")))
        assertFalse(canManageOrders(session(Role.LEAGUE_ORGANIZER)))
        assertFalse(canManageOrders(null))
    }

    @Test fun parsesRealShapedOrder() {
        val json = """{"order_id":"o1","status":"paid","items":[{"variant_id":"v1","quantity":2,"unit_price_minor":500}],
            "subtotal_minor":1000,"discount_minor":0,"total_minor":1000,"currency":"PKR","paid_at":"2026-10-17T06:20:00Z"}"""
        val o = AppJson.decodeFromString<OrderDetailDto>(json)
        assertEquals("paid", o.status)
        assertEquals(1, o.items.size)
        assertEquals(null, o.items[0].name)
        assertEquals(1000L, o.totalMinor)
        assertTrue(o.statusHistory.isEmpty())
    }

    @Test fun sortsNewestFirst() {
        val l = listOf(ClubOrderDto("a", paidAt = "2026-10-16T00:00:00Z"), ClubOrderDto("b", paidAt = "2026-10-17T00:00:00Z"))
        assertEquals(listOf("b", "a"), sortClubOrdersNewestFirst(l).map { it.orderId })
    }

    @Test fun fakeListsSeededOrders() = runBlocking {
        val a = api()
        assertEquals(3, a.clubOrders("club-alpha-1").size)
        assertTrue(a.clubOrders("club-alpha-2").isEmpty())
    }

    @Test fun fakeTransitionsFollowStateMachine() = runBlocking {
        val a = api()
        val r = a.setStatus("ord-001", OrderStatusRequest("packed"))
        assertEquals("paid", r.from)
        assertEquals("packed", r.to)
        a.refund("ord-001")
        assertEquals("refunded", a.clubOrders("club-alpha-1").first { it.orderId == "ord-001" }.status)
        try {
            a.setStatus("ord-001", OrderStatusRequest("shipped"))
            fail("expected 409")
        } catch (e: retrofit2.HttpException) {
            val err = mapThrowable(e) as ApiError.Http
            assertEquals(409, err.status)
            assertEquals("ILLEGAL_TRANSITION", err.code)
        }
    }

    @Test fun fakeOfflineThrows() = runBlocking {
        val store = FakeStore(pdf = com.nascon.league.data.fake.ReceiptPdfGenerator { _, _ -> ByteArray(0) })
        val a = FakeClubOrdersApi({ false }) { store }
        try {
            a.clubOrders("club-alpha-1")
            fail("expected offline")
        } catch (e: java.net.UnknownHostException) {
            assertTrue(true)
        }
    }
}
