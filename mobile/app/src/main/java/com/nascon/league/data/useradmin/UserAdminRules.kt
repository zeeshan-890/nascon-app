package com.nascon.league.data.useradmin

import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session

/** Only the platform super admin may manage users. Hide, don't disable. */
fun canAdminister(session: Session?): Boolean = session?.user?.role == Role.SUPER_ADMIN

/** Order shown in pickers, most privileged last like the mockup. */
val assignableRoles: List<Role> =
    listOf(Role.VIEWER, Role.PLAYER, Role.CLUB_MANAGER, Role.LEAGUE_ORGANIZER, Role.SUPER_ADMIN)

fun roleRequiresClub(role: Role) = role == Role.CLUB_MANAGER
fun roleRequiresLeague(role: Role) = role == Role.LEAGUE_ORGANIZER

/** True when [raw] is exactly one of the five known role strings. */
fun isKnownRole(raw: String?): Boolean = assignableRoles.any { it.apiValue == raw }

enum class UserFormError { NAME_REQUIRED, EMAIL_REQUIRED, EMAIL_INVALID, EMAIL_TAKEN, PASSWORD_SHORT, ROLE_UNKNOWN, CLUB_REQUIRED, LEAGUE_REQUIRED }

const val MIN_PASSWORD_LENGTH = 8

private val EMAIL_RE = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

fun isValidEmail(email: String) = EMAIL_RE.matches(email.trim())

/** Assignment rules shared by "new user" and "change role". */
fun validateAssignment(role: String?, clubId: String?, leagueIds: List<String>): UserFormError? {
    if (!isKnownRole(role)) return UserFormError.ROLE_UNKNOWN
    val r = Role.fromApi(role)
    if (roleRequiresClub(r) && clubId.isNullOrBlank()) return UserFormError.CLUB_REQUIRED
    if (roleRequiresLeague(r) && leagueIds.isEmpty()) return UserFormError.LEAGUE_REQUIRED
    return null
}

/** Field errors keyed by field ("name", "email", "password", "role", "club", "league"). [existingEmails] is compared case-insensitively. */
fun validateNewUser(
    name: String,
    email: String,
    password: String,
    role: String?,
    clubId: String?,
    leagueIds: List<String>,
    existingEmails: Collection<String> = emptyList(),
): Map<String, UserFormError> {
    val errors = LinkedHashMap<String, UserFormError>()
    if (name.isBlank()) errors["name"] = UserFormError.NAME_REQUIRED
    val e = email.trim()
    when {
        e.isEmpty() -> errors["email"] = UserFormError.EMAIL_REQUIRED
        !isValidEmail(e) -> errors["email"] = UserFormError.EMAIL_INVALID
        existingEmails.any { it.equals(e, ignoreCase = true) } -> errors["email"] = UserFormError.EMAIL_TAKEN
    }
    if (password.length < MIN_PASSWORD_LENGTH) errors["password"] = UserFormError.PASSWORD_SHORT
    validateAssignment(role, clubId, leagueIds)?.let { err ->
        val key = when (err) {
            UserFormError.ROLE_UNKNOWN -> "role"
            UserFormError.CLUB_REQUIRED -> "club"
            else -> "league"
        }
        errors[key] = err
    }
    return errors
}

/** A person cannot deactivate their own account. */
fun canDeactivate(actingUserId: String?, targetUserId: String): Boolean =
    actingUserId != null && actingUserId != targetUserId
