package com.nascon.league.domain.model

data class Fixture(
    val matchId: String,
    val leagueId: String,
    val homeTeamId: String,
    val homeName: String,
    val awayTeamId: String,
    val awayName: String,
    val status: String,
    val scheduledAt: String?,
    val venueName: String?,
    val homeScore: Int?,
    val awayScore: Int?,
    val version: Int,
)
