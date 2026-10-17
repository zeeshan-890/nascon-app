package com.nascon.league.ui.roster

import com.nascon.league.data.api.dto.RosterPlayerDto
import com.nascon.league.data.api.dto.SquadDto
import com.nascon.league.domain.model.Permissions
import com.nascon.league.domain.model.Session
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.util.Locale

object RosterLogic {
    const val REASON_MAX = 200
    const val UNASSIGNED_ID = ""
    const val UNASSIGNED_NAME = "Unassigned"

    fun validate(form: RosterFormState, fromSquadId: String?): RosterFormErrors {
        val reasonLen = form.reason.trim().length
        val reasonErr = when {
            reasonLen == 0 -> RosterError.REQUIRED
            reasonLen > REASON_MAX -> RosterError.REASON_TOO_LONG
            else -> null
        }
        val dateErr = if (form.effectiveDate.year !in 2000..2100) RosterError.INVALID_DATE else null
        if (form.type == MoveType.RELEASE) {
            return RosterFormErrors(effectiveDate = dateErr, reason = reasonErr)
        }
        val to = form.toSquadId?.takeIf { it.isNotBlank() }
        val toErr = when {
            to == null -> RosterError.REQUIRED
            to == fromSquadId -> RosterError.SAME_SQUAD
            else -> null
        }
        val loanErr = if (form.type == MoveType.LOAN) {
            val end = form.loanEndDate
            when {
                end == null -> RosterError.REQUIRED
                !end.isAfter(form.effectiveDate) -> RosterError.LOAN_END_NOT_AFTER_START
                else -> null
            }
        } else null
        return RosterFormErrors(toErr, dateErr, loanErr, reasonErr)
    }

    fun isValid(form: RosterFormState, fromSquadId: String?): Boolean = !validate(form, fromSquadId).hasErrors

    fun buildFields(
        leagueId: String,
        playerId: String,
        fromSquadId: String,
        form: RosterFormState,
        requestedBy: String,
    ): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["league_id"] = JsonPrimitive(leagueId)
        m["player_id"] = JsonPrimitive(playerId)
        m["type"] = JsonPrimitive(form.type.apiValue)
        m["from_squad_id"] = JsonPrimitive(fromSquadId)
        if (form.type != MoveType.RELEASE) m["to_squad_id"] = JsonPrimitive(form.toSquadId.orEmpty())
        m["effective_date"] = JsonPrimitive(dateIso(form.effectiveDate))
        if (form.type == MoveType.LOAN) {
            form.loanEndDate?.let { m["loan_end_date"] = JsonPrimitive(dateIso(it)) }
        }
        m["reason"] = JsonPrimitive(form.reason.trim())
        m["requested_by"] = JsonPrimitive(requestedBy)
        return JsonObject(m)
    }

    fun queueLabel(type: MoveType, playerName: String, toSquadName: String?): String = when (type) {
        MoveType.TRANSFER -> "Transfer $playerName to ${toSquadName.orEmpty()}".trimEnd()
        MoveType.LOAN -> "Loan $playerName to ${toSquadName.orEmpty()}".trimEnd()
        MoveType.RELEASE -> "Release $playerName"
    }

    fun confirmation(form: RosterFormState, player: RosterPlayerDto, squads: List<SquadDto>): ConfirmationInfo {
        fun nameOf(id: String?) = id?.let { sid -> squads.firstOrNull { it.squadId == sid }?.name }
        return ConfirmationInfo(
            type = form.type,
            playerName = player.name,
            fromSquadName = nameOf(player.squadId),
            toSquadName = if (form.type == MoveType.RELEASE) null else nameOf(form.toSquadId),
            effectiveDate = form.effectiveDate,
            loanEndDate = if (form.type == MoveType.LOAN) form.loanEndDate else null,
        )
    }

    private fun positionRank(position: String): Int {
        val p = position.trim().lowercase(Locale.ROOT)
        return when {
            p.isEmpty() -> 99
            p.startsWith("g") -> 0
            p.startsWith("d") -> 1
            p.startsWith("m") -> 2
            p.startsWith("f") || p.startsWith("st") || p.startsWith("w") || p.startsWith("a") -> 3
            else -> 4
        }
    }

    private val playerOrder =
        compareBy<RosterPlayerDto>({ positionRank(it.position) }, { it.name.lowercase(Locale.ROOT) })

    fun groupBySquad(players: List<RosterPlayerDto>, squads: List<SquadDto>): List<SquadGroup> {
        val known = squads.map { it.squadId }.toSet()
        val groups = squads.map { s ->
            SquadGroup(s, players.filter { it.squadId == s.squadId }.sortedWith(playerOrder))
        }
        val orphans = players.filter { it.squadId == null || it.squadId !in known }
        return if (orphans.isEmpty()) groups
        else groups + SquadGroup(
            SquadDto(squadId = UNASSIGNED_ID, name = UNASSIGNED_NAME),
            orphans.sortedWith(playerOrder),
        )
    }

    fun destinationSquads(squads: List<SquadDto>, currentSquadId: String?, type: MoveType): List<SquadDto> =
        if (type == MoveType.RELEASE) emptyList() else squads.filter { it.squadId != currentSquadId }

    fun squadSize(players: List<RosterPlayerDto>, squadId: String): Int =
        players.distinctBy { it.playerId }.count { it.squadId == squadId }

    fun showsOnLoanWarning(player: RosterPlayerDto): Boolean = player.onLoan

    fun canManage(session: Session?, clubId: String): Boolean = Permissions(session).canManageRoster(clubId)

    fun defaultEffectiveDate(today: LocalDate = LocalDate.now()): LocalDate = today

    fun dateIso(d: LocalDate): String = d.toString()
}
