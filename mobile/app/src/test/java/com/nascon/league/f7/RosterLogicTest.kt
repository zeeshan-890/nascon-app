package com.nascon.league.f7

import com.nascon.league.data.api.dto.RosterPlayerDto
import com.nascon.league.data.api.dto.SquadDto
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import com.nascon.league.ui.roster.MoveType
import com.nascon.league.ui.roster.RosterError
import com.nascon.league.ui.roster.RosterFormErrors
import com.nascon.league.ui.roster.RosterFormState
import com.nascon.league.ui.roster.RosterLogic
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RosterLogicTest {
    private val d = LocalDate.of(2026, 3, 10)

    private fun form(
        type: MoveType = MoveType.TRANSFER,
        to: String? = "B",
        eff: LocalDate = d,
        end: LocalDate? = null,
        reason: String = "ok",
    ) = RosterFormState(type, to, eff, end, reason)

    private fun p(id: String, name: String, pos: String = "", squad: String? = "A", loan: Boolean = false) =
        RosterPlayerDto(playerId = id, name = name, position = pos, squadId = squad, onLoan = loan)

    private fun sq(id: String, name: String = "Squad $id") = SquadDto(squadId = id, name = name)

    @Test fun validTransfer() {
        assertTrue(RosterLogic.isValid(form(), "A"))
    }

    @Test fun transferNeedsSquad() {
        assertEquals(RosterError.REQUIRED, RosterLogic.validate(form(to = null), "A").toSquad)
        assertEquals(RosterError.REQUIRED, RosterLogic.validate(form(to = " "), "A").toSquad)
    }

    @Test fun sameSquad() {
        assertEquals(RosterError.SAME_SQUAD, RosterLogic.validate(form(to = "A"), "A").toSquad)
    }

    @Test fun loanNeedsEnd() {
        assertEquals(RosterError.REQUIRED, RosterLogic.validate(form(MoveType.LOAN), "A").loanEnd)
    }

    @Test fun loanEndMatrix() {
        val loan = { e: LocalDate -> form(MoveType.LOAN, end = e) }
        assertEquals(RosterError.LOAN_END_NOT_AFTER_START, RosterLogic.validate(loan(d), "A").loanEnd)
        assertEquals(RosterError.LOAN_END_NOT_AFTER_START, RosterLogic.validate(loan(d.minusDays(1)), "A").loanEnd)
        assertNull(RosterLogic.validate(loan(d.plusDays(1)), "A").loanEnd)
    }

    @Test fun transferIgnoresLoanEnd() {
        assertTrue(RosterLogic.isValid(form(end = d.minusDays(5)), "A"))
    }

    @Test fun releaseIgnoresSquadAndLoan() {
        assertTrue(RosterLogic.isValid(form(MoveType.RELEASE, to = null, end = d.minusDays(3)), "A"))
        assertTrue(RosterLogic.isValid(form(MoveType.RELEASE, to = "A"), "A"))
    }

    @Test fun reasonMatrix() {
        assertEquals(RosterError.REQUIRED, RosterLogic.validate(form(reason = ""), "A").reason)
        assertEquals(RosterError.REQUIRED, RosterLogic.validate(form(reason = "   "), "A").reason)
        assertNull(RosterLogic.validate(form(reason = "x".repeat(200)), "A").reason)
        assertNull(RosterLogic.validate(form(reason = "  " + "x".repeat(200) + "  "), "A").reason)
        assertEquals(RosterError.REASON_TOO_LONG, RosterLogic.validate(form(reason = "x".repeat(201)), "A").reason)
        assertEquals(RosterError.REQUIRED, RosterLogic.validate(form(MoveType.RELEASE, reason = " "), "A").reason)
    }

    @Test fun invalidDate() {
        val e = RosterLogic.validate(form(eff = LocalDate.of(1900, 1, 1)), "A")
        assertEquals(RosterError.INVALID_DATE, e.effectiveDate)
    }

    @Test fun hasErrorsFlag() {
        assertFalse(RosterFormErrors().hasErrors)
        assertTrue(RosterFormErrors(reason = RosterError.REQUIRED).hasErrors)
    }

    @Test fun payloadTransfer() {
        val o = RosterLogic.buildFields("L1", "P1", "A", form(reason = "  move  "), "u1")
        assertEquals(
            setOf("league_id", "player_id", "type", "from_squad_id", "to_squad_id", "effective_date", "reason", "requested_by"),
            o.keys,
        )
        assertEquals("transfer", o["type"]!!.jsonPrimitive.content)
        assertEquals("2026-03-10", o["effective_date"]!!.jsonPrimitive.content)
        assertEquals("move", o["reason"]!!.jsonPrimitive.content)
        assertEquals("B", o["to_squad_id"]!!.jsonPrimitive.content)
        assertFalse(o.containsKey("request_id"))
    }

    @Test fun payloadLoan() {
        val o = RosterLogic.buildFields("L1", "P1", "A", form(MoveType.LOAN, end = d.plusDays(30)), "u1")
        assertEquals(
            setOf(
                "league_id", "player_id", "type", "from_squad_id", "to_squad_id",
                "effective_date", "loan_end_date", "reason", "requested_by",
            ),
            o.keys,
        )
        assertEquals("2026-04-09", o["loan_end_date"]!!.jsonPrimitive.content)
    }

    @Test fun payloadRelease() {
        val o = RosterLogic.buildFields("L1", "P1", "A", form(MoveType.RELEASE, to = "B", end = d.plusDays(3)), "u1")
        assertEquals(
            setOf("league_id", "player_id", "type", "from_squad_id", "effective_date", "reason", "requested_by"),
            o.keys,
        )
        assertEquals("release", o["type"]!!.jsonPrimitive.content)
    }

    @Test fun labels() {
        assertEquals("Transfer Ali Khan to Squad B", RosterLogic.queueLabel(MoveType.TRANSFER, "Ali Khan", "Squad B"))
        assertEquals("Loan Ali Khan to Squad B", RosterLogic.queueLabel(MoveType.LOAN, "Ali Khan", "Squad B"))
        assertEquals("Release Ali Khan", RosterLogic.queueLabel(MoveType.RELEASE, "Ali Khan", "Squad B"))
    }

    @Test fun confirmationInfo() {
        val squads = listOf(sq("A"), sq("B"))
        val c = RosterLogic.confirmation(form(MoveType.LOAN, end = d.plusDays(5)), p("1", "Ali"), squads)
        assertEquals("Squad A", c.fromSquadName)
        assertEquals("Squad B", c.toSquadName)
        assertEquals(d.plusDays(5), c.loanEndDate)
        val r = RosterLogic.confirmation(form(MoveType.RELEASE, end = d), p("1", "Ali"), squads)
        assertNull(r.toSquadName)
        assertNull(r.loanEndDate)
    }

    @Test fun groupingOrderStabilityUnassigned() {
        val squads = listOf(sq("A"), sq("B"))
        val players = listOf(
            p("1", "Zed", "Forward", "A"), p("2", "Bob", "Midfielder", "A"), p("3", "Amy", "Goalkeeper", "A"),
            p("4", "Cal", "Defender", "A"), p("5", "Abe", "Midfielder", "A"),
            p("6", "Nom", "", null), p("7", "Ghost", "Defender", "ZZ"), p("8", "Dan", "Defender", "B"),
        )
        val g = RosterLogic.groupBySquad(players, squads)
        assertEquals(listOf("A", "B", ""), g.map { it.squad.squadId })
        assertEquals(listOf("3", "4", "5", "2", "1"), g[0].players.map { it.playerId })
        assertEquals(listOf("7", "6"), g[2].players.map { it.playerId })
        assertEquals("Unassigned", g[2].squad.name)
        val ties = listOf(p("x", "Same", "Defender"), p("y", "Same", "Defender"))
        val tg = RosterLogic.groupBySquad(ties, listOf(sq("A"))).first()
        assertEquals(listOf("x", "y"), tg.players.map { it.playerId })
    }

    @Test fun noUnassignedWhenNoOrphans() {
        assertEquals(1, RosterLogic.groupBySquad(listOf(p("1", "A")), listOf(sq("A"))).size)
        assertEquals(1, RosterLogic.groupBySquad(emptyList(), listOf(sq("A"))).size)
    }

    @Test fun destinations() {
        val s = listOf(sq("A"), sq("B"), sq("C"))
        assertEquals(listOf("B", "C"), RosterLogic.destinationSquads(s, "A", MoveType.TRANSFER).map { it.squadId })
        assertEquals(listOf("B", "C"), RosterLogic.destinationSquads(s, "A", MoveType.LOAN).map { it.squadId })
        assertTrue(RosterLogic.destinationSquads(s, "A", MoveType.RELEASE).isEmpty())
        assertEquals(3, RosterLogic.destinationSquads(s, null, MoveType.TRANSFER).size)
    }

    @Test fun squadSizes() {
        val ps = listOf(
            p("1", "a", squad = "A"), p("2", "b", squad = "A", loan = true),
            p("3", "c", squad = "B"), p("3", "c", squad = "B"),
        )
        assertEquals(2, RosterLogic.squadSize(ps, "A"))
        assertEquals(1, RosterLogic.squadSize(ps, "B"))
        assertEquals(0, RosterLogic.squadSize(ps, "Z"))
    }

    @Test fun onLoanWarning() {
        assertTrue(RosterLogic.showsOnLoanWarning(p("1", "a", loan = true)))
        assertFalse(RosterLogic.showsOnLoanWarning(p("1", "a")))
    }

    private fun sess(role: Role, club: String? = null) = Session(User("u", "e@x.com", role, clubId = club))

    @Test fun gating() {
        assertTrue(RosterLogic.canManage(sess(Role.CLUB_MANAGER, "c1"), "c1"))
        assertFalse(RosterLogic.canManage(sess(Role.CLUB_MANAGER, "c2"), "c1"))
        assertFalse(RosterLogic.canManage(sess(Role.CLUB_MANAGER, null), "c1"))
        assertTrue(RosterLogic.canManage(sess(Role.SUPER_ADMIN), "c1"))
        assertFalse(RosterLogic.canManage(sess(Role.LEAGUE_ORGANIZER, "c1"), "c1"))
        assertFalse(RosterLogic.canManage(sess(Role.PLAYER, "c1"), "c1"))
        assertFalse(RosterLogic.canManage(sess(Role.VIEWER), "c1"))
        assertFalse(RosterLogic.canManage(null, "c1"))
    }

    @Test fun dates() {
        assertEquals(d, RosterLogic.defaultEffectiveDate(d))
        assertEquals("2026-03-05", RosterLogic.dateIso(LocalDate.of(2026, 3, 5)))
    }
}
