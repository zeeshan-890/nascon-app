package com.nascon.league.rosterplus

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.playerstats.FakePlayerStatsApi
import com.nascon.league.data.playerstats.PlayerStatsDto
import com.nascon.league.data.playerstats.StatEventCount
import com.nascon.league.data.rosteradd.AddPlayerValidation
import com.nascon.league.data.rosteradd.FakeRosterAddApi
import com.nascon.league.ui.player.statTiles
import com.nascon.league.ui.roster.AddPlayerError
import com.nascon.league.ui.roster.AddPlayerFailure
import com.nascon.league.ui.roster.AddPlayerForm
import com.nascon.league.ui.roster.AddPlayerLogic
import com.nascon.league.ui.roster.addPlayerFailureFor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RosterPlusTest {
    private fun req(playerId: String = "pl-1", requestId: String = "r-1") = AddPlayerLogic.build(
        AddPlayerForm("  Hamza Ali ", "MF", "sq-a", "substitute"),
        "league-alpha-1", "user-1", "Registered in the app", LocalDate.parse("2026-10-17"), requestId, playerId,
    )

    @Test fun formErrors() {
        assertEquals(
            setOf(AddPlayerError.NAME, AddPlayerError.POSITION, AddPlayerError.SQUAD),
            AddPlayerLogic.errors(AddPlayerForm()),
        )
        assertTrue(AddPlayerLogic.isValid(AddPlayerForm("A", "GK", "sq", "starter")))
        assertFalse(AddPlayerLogic.isValid(AddPlayerForm("   ", "GK", "sq")))
    }

    @Test fun buildTrimsAndFormats() {
        val r = req()
        assertEquals("Hamza Ali", r.name)
        assertEquals("2026-10-17", r.effectiveDate)
        assertTrue(AddPlayerValidation.badFields(r).isEmpty())
    }

    @Test fun generatedIdsAreAcceptedByServerPattern() {
        assertTrue(AddPlayerValidation.badFields(req(playerId = AddPlayerLogic.newPlayerId())).isEmpty())
        assertEquals(listOf("role"), AddPlayerValidation.badFields(req().copy(role = "goalie")))
    }

    @Test fun suggestionsDeduplicate() {
        assertEquals(listOf("GK", "DF"), AddPlayerLogic.positionSuggestions(listOf("GK", " ", "DF", "GK")))
    }

    @Test fun fakeOfflineAndDuplicate() = runBlocking {
        var online = false
        val api = FakeRosterAddApi { online }
        val offline = safeCall { api.addPlayer("club-alpha-1", req()) }
        assertTrue((offline as NetworkResult.Failure).error is ApiError.ServerUnreachable)
        online = true
        assertTrue(safeCall { api.addPlayer("club-alpha-1", req()) } is NetworkResult.Success)
        // same request id replays
        assertTrue(safeCall { api.addPlayer("club-alpha-1", req()) } is NetworkResult.Success)
        // same player, new request: already registered
        val dup = safeCall { api.addPlayer("club-alpha-1", req(requestId = "r-2")) } as NetworkResult.Failure
        assertEquals(AddPlayerFailure.AlreadyRegistered, addPlayerFailureFor(dup.error))
    }

    @Test fun failureMapping() {
        assertEquals(AddPlayerFailure.Offline, addPlayerFailureFor(ApiError.NoConnection))
        val rule = addPlayerFailureFor(ApiError.Http(409, "SQUAD_FULL", "x"))
        assertTrue(rule is AddPlayerFailure.Rule && rule.rejection.code == "SQUAD_FULL")
        val other = addPlayerFailureFor(ApiError.Http(500, "BOOM", "x"))
        assertTrue(other is AddPlayerFailure.Other)
    }

    @Test fun statTilesUseDataNames() {
        val tiles = statTiles(PlayerStatsDto("p", 14, listOf(StatEventCount("free_kick", 3), StatEventCount("", 1)), 2))
        assertEquals(listOf(null, "Free kick", null), tiles.map { it.label })
        assertEquals(listOf(14, 3, 2), tiles.map { it.value })
    }

    @Test fun fakeStats() = runBlocking {
        val api = FakePlayerStatsApi { true }
        assertEquals(14, api.stats("p1").matchesPlayed)
        assertNull(null)
    }
}
