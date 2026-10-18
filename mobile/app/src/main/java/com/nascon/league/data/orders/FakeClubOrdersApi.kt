package com.nascon.league.data.orders

import com.nascon.league.data.api.dto.OrderDetailDto
import com.nascon.league.data.api.dto.OrderItemDto
import com.nascon.league.data.api.dto.OrderStatusEntryDto
import com.nascon.league.data.fake.FakeLeagueApi
import com.nascon.league.data.fake.FakeStore
import com.nascon.league.di.AppContainer
import kotlinx.coroutines.delay

/**
 * Demo club orders over the shared [FakeStore], so orders created by the fake checkout appear here and every
 * status change is visible in the normal order detail. Enforces the same state machine as the server.
 */
class FakeClubOrdersApi(
    private val hasNetwork: () -> Boolean,
    private val storeProvider: () -> FakeStore,
) : ClubOrdersApi {
    private var seededStore: FakeStore? = null

    private fun store(): FakeStore {
        val s = storeProvider()
        if (seededStore !== s) {
            seededStore = s
            DEMO_ORDERS.forEach { s.seedOrder(it) }
        }
        return s
    }

    override suspend fun clubOrders(clubId: String): List<ClubOrderDto> {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        delay(300L + (clubId.hashCode().toLong() and 0xFF))
        val s = store()
        if (clubId != DEMO_CLUB) return emptyList()
        return s.allOrders().map { it.toClubOrder() }
    }

    override suspend fun setStatus(orderId: String, body: OrderStatusRequest): OrderTransitionDto =
        move(orderId, body.status)

    override suspend fun refund(orderId: String): OrderTransitionDto = move(orderId, "refunded")

    private suspend fun move(orderId: String, to: String): OrderTransitionDto {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        val (from, _) = store().transitionOrder(orderId, to)
        return OrderTransitionDto(orderId, from, to)
    }

    companion object {
        const val DEMO_CLUB = "club-alpha-1"

        fun OrderDetailDto.toClubOrder() = ClubOrderDto(
            orderId = orderId,
            status = status,
            totalMinor = totalMinor,
            currency = currency,
            paidAt = paidAt ?: createdAt,
            customerName = CUSTOMERS[orderId],
            itemCount = items.sumOf { it.quantity },
        )

        private val CUSTOMERS = mapOf("ord-001" to "Sara Khan", "ord-002" to "Ali Raza", "ord-003" to "Hina Malik")

        private fun demo(id: String, status: String, at: String, name: String, variant: String, qty: Int, unit: Long) =
            OrderDetailDto(
                orderId = id, status = status, currency = "PKR",
                items = listOf(OrderItemDto(variant, name, qty, unit)),
                subtotalMinor = unit * qty, discountMinor = 0, totalMinor = unit * qty,
                paidAt = at, createdAt = at,
                statusHistory = listOf(OrderStatusEntryDto(null, "pending", at), OrderStatusEntryDto("pending", "paid", at)),
            )

        val DEMO_ORDERS = listOf(
            demo("ord-001", "paid", "2026-10-17T06:20:00Z", "Lions Home Jersey", "var-jersey-m", 1, 405000),
            demo("ord-002", "shipped", "2026-10-17T07:02:00Z", "Match Ball", "var-ball", 1, 300000),
            demo("ord-003", "refunded", "2026-10-16T09:00:00Z", "Club Cap", "var-cap-black", 1, 120000),
        )
    }
}

object ClubOrdersFakeHolder {
    private var inst: FakeClubOrdersApi? = null
    @Volatile private var provider: () -> FakeStore? = { null }
    private val fallback by lazy { FakeStore(pdf = com.nascon.league.data.fake.ReceiptPdfGenerator { _, _ -> ByteArray(0) }) }

    fun get(hasNetwork: () -> Boolean, storeProvider: () -> FakeStore?): FakeClubOrdersApi {
        provider = storeProvider
        return inst ?: FakeClubOrdersApi(hasNetwork) { provider() ?: fallback }.also { inst = it }
    }
}

fun AppContainer.clubOrdersApi(): ClubOrdersApi = service(ClubOrdersApi::class.java) { hn ->
    ClubOrdersFakeHolder.get(hn) { (apiProvider() as? FakeLeagueApi)?.orderStore() }
}
