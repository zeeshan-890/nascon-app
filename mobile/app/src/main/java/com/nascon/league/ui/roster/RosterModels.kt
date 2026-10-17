package com.nascon.league.ui.roster

import com.nascon.league.data.api.dto.RosterPlayerDto
import com.nascon.league.data.api.dto.SquadDto
import java.time.LocalDate

enum class MoveType(val apiValue: String) { TRANSFER("transfer"), LOAN("loan"), RELEASE("release") }

data class RosterFormState(
    val type: MoveType,
    val toSquadId: String? = null,
    val effectiveDate: LocalDate,
    val loanEndDate: LocalDate? = null,
    val reason: String = "",
)

enum class RosterError { REQUIRED, LOAN_END_NOT_AFTER_START, REASON_TOO_LONG, SAME_SQUAD, INVALID_DATE }

data class RosterFormErrors(
    val toSquad: RosterError? = null,
    val effectiveDate: RosterError? = null,
    val loanEnd: RosterError? = null,
    val reason: RosterError? = null,
) {
    val hasErrors: Boolean get() = toSquad != null || effectiveDate != null || loanEnd != null || reason != null
}

data class SquadGroup(val squad: SquadDto, val players: List<RosterPlayerDto>)

data class ConfirmationInfo(
    val type: MoveType,
    val playerName: String,
    val fromSquadName: String?,
    val toSquadName: String?,
    val effectiveDate: LocalDate,
    val loanEndDate: LocalDate?,
)
