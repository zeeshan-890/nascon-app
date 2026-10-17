package com.nascon.league.domain.model

data class League(
    val leagueId: String,
    val name: String,
    val sportId: String,
    val season: String?,
)
