package com.nascon.league.ui.club

import com.nascon.league.data.api.dto.ClubStatDto

/** Sections of one club profile (mockups B19 squad, B18 staff, B20 season stats). */
enum class ClubTab { SQUAD, STAFF, STATS }

/** Restores a saved tab name; anything unknown falls back to the squad. */
fun clubTabOf(saved: String?): ClubTab = ClubTab.values().firstOrNull { it.name == saved } ?: ClubTab.SQUAD

/** The newest season row for the stat cards: highest season label, ties keep the later entry. */
fun latestSeasonStat(stats: List<ClubStatDto>): ClubStatDto? =
    stats.withIndex().maxWithOrNull(compareBy({ it.value.season }, { it.index }))?.value

/** Which management buttons show: only for people who may manage the club and only wired ones. */
data class ManageActions(val editClub: Boolean, val editStaff: Boolean, val products: Boolean, val orders: Boolean) {
    val any: Boolean get() = editClub || editStaff || products || orders
}

fun manageActions(
    canManage: Boolean,
    hasEditClub: Boolean,
    hasEditStaff: Boolean,
    hasProducts: Boolean,
    hasOrders: Boolean,
): ManageActions = if (!canManage) {
    ManageActions(false, false, false, false)
} else {
    ManageActions(hasEditClub, hasEditStaff, hasProducts, hasOrders)
}
