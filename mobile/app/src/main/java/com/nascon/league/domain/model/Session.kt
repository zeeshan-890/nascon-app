package com.nascon.league.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Session(val user: User, val tenant: Tenant? = null)

class Permissions(private val session: Session?) {
    private val user get() = session?.user

    fun canScore(leagueId: String): Boolean = when (user?.role) {
        Role.SUPER_ADMIN -> true
        Role.LEAGUE_ORGANIZER -> leagueId in (user?.leagueIds ?: emptyList())
        else -> false
    }

    fun canManageRoster(clubId: String): Boolean = when (user?.role) {
        Role.SUPER_ADMIN -> true
        Role.CLUB_MANAGER -> user?.clubId == clubId
        else -> false
    }

    val canBuy: Boolean get() = session != null
    val canSeeAdmin: Boolean get() = user?.role == Role.SUPER_ADMIN
}
