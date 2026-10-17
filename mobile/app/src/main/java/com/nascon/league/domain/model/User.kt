package com.nascon.league.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val userId: String,
    val email: String,
    val role: Role,
    val tenantId: String? = null,
    val leagueIds: List<String> = emptyList(),
    val clubId: String? = null,
    val playerId: String? = null,
)
