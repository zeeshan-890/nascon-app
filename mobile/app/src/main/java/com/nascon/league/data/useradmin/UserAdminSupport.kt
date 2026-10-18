package com.nascon.league.data.useradmin

import com.nascon.league.core.time.Clock
import com.nascon.league.domain.model.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Bumped after any change so open lists reload. */
object UserAdminEvents {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    fun changed() { _version.value = _version.value + 1 }
}

sealed interface SignInLabel {
    data object Never : SignInLabel
    data class Today(val time: String) : SignInLabel
    data class On(val date: String) : SignInLabel
}

/** Last sign-in as "never", "today at h:mm a" or a calendar date, in [zone]. */
fun signInLabel(iso: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): SignInLabel {
    val at = Clock.parseUtc(iso) ?: return SignInLabel.Never
    val local = at.atZone(zone)
    return if (local.toLocalDate() == now.atZone(zone).toLocalDate()) {
        SignInLabel.Today(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).format(local))
    } else {
        SignInLabel.On(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(local))
    }
}

/** Display name; falls back to the email so a row is never blank. */
fun AdminUserDto.displayName(): String = name.ifBlank { email }

fun AdminUserDto.roleEnum(): Role = Role.fromApi(role)

/** Short role words used by the filter pills. */
fun roleFilterValue(role: Role): String = role.apiValue
