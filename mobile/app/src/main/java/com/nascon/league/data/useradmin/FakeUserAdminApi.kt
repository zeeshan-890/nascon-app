package com.nascon.league.data.useradmin

import com.nascon.league.domain.model.Role
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** Demo implementation. Keeps state in memory for the life of the process. */
class FakeUserAdminApi(
    private val hasNetwork: () -> Boolean,
    private val delayMs: (Int) -> Long = { 300L + (it % 5) * 100L },
) : UserAdminApi {
    private var calls = 0
    private val tenant = "tenant-alpha"
    private val tenantName = "Alpha Sports"
    private val clubNames = mapOf("club-alpha-1" to "Alpha Club 1", "club-alpha-2" to "Alpha Club 2")
    private val leagueNames = mapOf("league-alpha-1" to "Alpha Premier League", "league-alpha-2" to "Alpha League 2")

    private val users = linkedMapOf<String, AdminUserDto>().also { m ->
        fun add(u: AdminUserDto) { m[u.userId] = u }
        add(AdminUserDto("u-admin", "admin@nascon.test", "Platform Admin", "super_admin", lastSignInAt = "2026-10-17T09:14:00Z"))
        add(AdminUserDto("u-organizer", "organizer@nascon.test", "Omar Organizer", "league_organizer", tenant, tenantName, leagueIds = listOf("league-alpha-1"), leagueNames = listOf("Alpha Premier League"), lastSignInAt = "2026-10-16T18:30:00Z"))
        add(AdminUserDto("u-manager", "manager@nascon.test", "Maha Manager", "club_manager", tenant, tenantName, "club-alpha-1", "Alpha Club 1", lastSignInAt = "2026-10-17T07:45:00Z"))
        add(AdminUserDto("u-player", "player@nascon.test", "Parvez Player", "player", tenant, tenantName, "club-alpha-1", "Alpha Club 1", lastSignInAt = "2026-10-15T20:10:00Z"))
        add(AdminUserDto("u-viewer", "viewer@nascon.test", "Vera Viewer", "viewer", tenant, tenantName, lastSignInAt = "2026-10-14T12:00:00Z"))
        add(AdminUserDto("u-ayesha", "ayesha@club.pk", "Ayesha Khan", "club_manager", tenant, tenantName, "club-alpha-2", "Alpha Club 2", lastSignInAt = "2026-10-17T06:20:00Z"))
        add(AdminUserDto("u-bilal", "bilal@club.pk", "Bilal Ahmed", "player", tenant, tenantName, "club-alpha-1", "Alpha Club 1"))
        add(AdminUserDto("u-sara", "sara@league.pk", "Sara Malik", "league_organizer", tenant, tenantName, leagueIds = listOf("league-alpha-2"), leagueNames = listOf("Alpha League 2")))
        add(AdminUserDto("u-usman", "usman@fans.pk", "Usman Raza", "viewer", tenant, tenantName, active = false))
    }
    private var nextId = 1

    private suspend fun pause() {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        delay(delayMs(calls++))
    }

    private fun fail(status: Int, code: String, message: String): Nothing {
        val body = "{\"error\":{\"code\":\"$code\",\"message\":\"$message\"}}"
        throw HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())))
    }

    private fun find(id: String) = users[id] ?: fail(404, "USER_NOT_FOUND", "User not found.")

    override suspend fun users(query: String?, role: String?): AdminUserListResponse {
        pause()
        val q = query?.trim().orEmpty().lowercase()
        val list = users.values.filter { u ->
            (role.isNullOrBlank() || u.role == role) &&
                (q.isEmpty() || u.name.lowercase().contains(q) || u.email.lowercase().contains(q))
        }.sortedBy { it.name.lowercase() }
        return AdminUserListResponse(list, list.size)
    }

    override suspend fun user(id: String): AdminUserDto {
        pause()
        return find(id)
    }

    override suspend fun createUser(body: NewUserRequest): AdminUserDto {
        pause()
        val errors = validateNewUser(body.name, body.email, body.temporaryPassword, body.role, body.clubId, body.leagueIds)
        if (errors.isNotEmpty()) fail(422, "VALIDATION_ERROR", "Invalid user.")
        if (users.values.any { it.email.equals(body.email.trim(), ignoreCase = true) }) fail(409, "EMAIL_TAKEN", "Email already in use.")
        val created = assign(
            AdminUserDto(userId = "u-new-${nextId++}", email = body.email.trim(), name = body.name.trim()),
            body.role, body.clubId, body.leagueIds,
        )
        users[created.userId] = created
        return created
    }

    override suspend fun changeRole(id: String, body: ChangeRoleRequest): AdminUserDto {
        pause()
        val current = find(id)
        if (validateAssignment(body.role, body.clubId, body.leagueIds) != null) fail(422, "VALIDATION_ERROR", "Invalid role assignment.")
        val updated = assign(current, body.role, body.clubId, body.leagueIds)
        users[id] = updated
        return updated
    }

    override suspend fun deactivate(id: String): AdminUserDto {
        pause()
        val updated = find(id).copy(active = false)
        users[id] = updated
        return updated
    }

    override suspend fun activate(id: String): AdminUserDto {
        pause()
        val updated = find(id).copy(active = true)
        users[id] = updated
        return updated
    }

    override suspend fun assignmentOptions(): AssignmentOptionsResponse {
        pause()
        return AssignmentOptionsResponse(
            clubs = clubNames.map { AssignableDto(it.key, it.value, tenant) },
            leagues = leagueNames.map { AssignableDto(it.key, it.value, tenant) },
        )
    }

    private fun assign(base: AdminUserDto, role: String, clubId: String?, leagueIds: List<String>): AdminUserDto {
        val r = Role.fromApi(role)
        val isAdmin = r == Role.SUPER_ADMIN
        val club = clubId?.takeIf { r == Role.CLUB_MANAGER || r == Role.PLAYER }
        val leagues = if (r == Role.LEAGUE_ORGANIZER) leagueIds else emptyList()
        return base.copy(
            role = r.apiValue,
            tenantId = if (isAdmin) null else tenant,
            tenantName = if (isAdmin) null else tenantName,
            clubId = club,
            clubName = club?.let { clubNames[it] },
            leagueIds = leagues,
            leagueNames = leagues.map { leagueNames[it] ?: it },
        )
    }
}

object UserAdminFakeHolder {
    private var inst: FakeUserAdminApi? = null
    fun get(hasNetwork: () -> Boolean): FakeUserAdminApi = inst ?: FakeUserAdminApi(hasNetwork).also { inst = it }
}
