package com.nascon.league.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Tenant(
    val tenantId: String,
    val name: String,
    val logoUrl: String? = null,
    val primaryColor: String? = null,
    val secondaryColor: String? = null,
)
