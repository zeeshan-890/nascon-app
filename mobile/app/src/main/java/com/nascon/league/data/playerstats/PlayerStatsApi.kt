package com.nascon.league.data.playerstats

import com.nascon.league.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

/** Contract addition (not on the backend yet): see mobile/contract_additions/rosterplus.md. */
interface PlayerStatsApi {
    @GET("api/v1/players/{id}/stats")
    suspend fun stats(@Path("id") playerId: String): PlayerStatsDto
}

@Serializable
data class PlayerStatsDto(
    @SerialName("player_id") val playerId: String = "",
    @SerialName("matches_played") val matchesPlayed: Int = 0,
    @SerialName("events") val events: List<StatEventCount> = emptyList(),
    @SerialName("discipline_points") val disciplinePoints: Int = 0,
)

@Serializable
data class StatEventCount(
    @SerialName("event_type") val eventType: String = "",
    @SerialName("count") val count: Int = 0,
)

class FakePlayerStatsApi(private val hasNetwork: () -> Boolean) : PlayerStatsApi {
    override suspend fun stats(playerId: String): PlayerStatsDto {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        delay(400L)
        return PlayerStatsDto(
            playerId = playerId,
            matchesPlayed = 14,
            events = listOf(StatEventCount("goal", 9), StatEventCount("assist", 5)),
            disciplinePoints = 1,
        )
    }
}

object PlayerStatsFakeHolder {
    private var inst: FakePlayerStatsApi? = null
    fun get(hasNetwork: () -> Boolean): FakePlayerStatsApi = inst ?: FakePlayerStatsApi(hasNetwork).also { inst = it }
}

fun AppContainer.playerStatsApi(): PlayerStatsApi = service(PlayerStatsApi::class.java) { hn -> PlayerStatsFakeHolder.get(hn) }
