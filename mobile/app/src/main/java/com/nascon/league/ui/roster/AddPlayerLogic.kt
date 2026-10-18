package com.nascon.league.ui.roster

import com.nascon.league.data.rosteradd.AddPlayerRequest
import java.time.LocalDate
import java.util.UUID

enum class AddPlayerError { NAME, POSITION, SQUAD }

data class AddPlayerForm(
    val name: String = "",
    val position: String = "",
    val squadId: String? = null,
    val role: String = "substitute",
)

/** Pure rules for the add-player form (mirrors backend validateAdd limits). */
object AddPlayerLogic {
    const val TEXT_MAX = 200

    fun errors(f: AddPlayerForm): Set<AddPlayerError> = buildSet {
        if (f.name.trim().isEmpty()) add(AddPlayerError.NAME)
        if (f.position.trim().isEmpty()) add(AddPlayerError.POSITION)
        if (f.squadId.isNullOrBlank()) add(AddPlayerError.SQUAD)
    }

    fun isValid(f: AddPlayerForm) = errors(f).isEmpty()

    /** Positions already used in the club, in first-seen order, as quick picks. */
    fun positionSuggestions(positions: List<String>): List<String> =
        positions.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)

    /** An id the server accepts: [A-Za-z0-9_.:-]{1,64}. */
    fun newPlayerId(): String = "pl-" + UUID.randomUUID().toString()

    fun roleLabelKey(role: String): String = role

    fun build(
        form: AddPlayerForm,
        leagueId: String,
        requestedBy: String,
        reason: String,
        today: LocalDate,
        requestId: String,
        playerId: String,
    ) = AddPlayerRequest(
        requestId = requestId,
        leagueId = leagueId,
        playerId = playerId,
        squadId = form.squadId.orEmpty(),
        name = form.name.trim().take(TEXT_MAX),
        position = form.position.trim().take(TEXT_MAX),
        role = form.role,
        effectiveDate = today.toString(),
        reason = reason.take(TEXT_MAX),
        requestedBy = requestedBy,
    )

    /** "ali_khan" style event or role keys shown to people: underscores to spaces, first letter upper case. */
    fun humanize(key: String): String =
        key.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
}
