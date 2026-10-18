package com.nascon.league.ui.orders

import com.nascon.league.data.orders.ClubOrderDto
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session

/** Mirror of backend/src/domain/store/orderStateMachine.js. */
object OrderStateMachine {
    val TRANSITIONS: Map<String, List<String>> = mapOf(
        "pending" to listOf("paid", "cancelled"),
        "paid" to listOf("packed", "refunded", "cancelled"),
        "packed" to listOf("shipped", "refunded"),
        "shipped" to listOf("delivered"),
        "delivered" to listOf("refunded"),
        "cancelled" to emptyList(),
        "refunded" to emptyList(),
    )

    fun next(from: String?): List<String> = TRANSITIONS[from?.trim()?.lowercase()].orEmpty()

    fun canTransition(from: String?, to: String): Boolean = to in next(from)

    /** Stock goes back on the shelf only when a paid (not yet delivered) order is unwound. */
    fun restockOn(from: String, to: String): Boolean =
        (from == "paid" && (to == "cancelled" || to == "refunded")) || (from == "packed" && to == "refunded")

    fun canRefund(from: String?): Boolean = canTransition(from, "refunded")

    /** Forward / cancel actions shown as buttons; refund has its own destructive button. */
    fun statusActions(from: String?): List<String> = next(from).filter { it != "refunded" }
}

/**
 * order.manage: super_admin anywhere; club_manager for their own club. The order's club is not in the order JSON,
 * so a club manager passes when they have a club at all (or when [clubId] matches); the server enforces the real scope.
 */
fun canManageOrders(session: Session?, clubId: String? = null): Boolean {
    val user = session?.user ?: return false
    return when (user.role) {
        Role.SUPER_ADMIN -> true
        Role.CLUB_MANAGER -> user.clubId != null && (clubId == null || user.clubId == clubId)
        else -> false
    }
}

/** Newest first; ISO timestamps sort lexically, missing dates go last. */
fun sortClubOrdersNewestFirst(list: List<ClubOrderDto>): List<ClubOrderDto> =
    list.sortedWith(compareByDescending<ClubOrderDto> { it.paidAt ?: "" }.thenByDescending { it.orderId })
