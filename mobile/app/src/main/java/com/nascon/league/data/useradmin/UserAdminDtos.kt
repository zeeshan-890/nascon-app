package com.nascon.league.data.useradmin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One platform user as the admin API returns it. [role] is the raw API string; map it with Role.fromApi for display. */
@Serializable
data class AdminUserDto(
    @SerialName("user_id") val userId: String,
    @SerialName("email") val email: String,
    @SerialName("name") val name: String = "",
    @SerialName("role") val role: String = "viewer",
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("tenant_name") val tenantName: String? = null,
    @SerialName("club_id") val clubId: String? = null,
    @SerialName("club_name") val clubName: String? = null,
    @SerialName("league_ids") val leagueIds: List<String> = emptyList(),
    @SerialName("league_names") val leagueNames: List<String> = emptyList(),
    @SerialName("active") val active: Boolean = true,
    @SerialName("last_sign_in_at") val lastSignInAt: String? = null,
)

@Serializable
data class AdminUserListResponse(
    @SerialName("items") val items: List<AdminUserDto> = emptyList(),
    @SerialName("total") val total: Int = 0,
)

@Serializable
data class NewUserRequest(
    @SerialName("name") val name: String,
    @SerialName("email") val email: String,
    @SerialName("temporary_password") val temporaryPassword: String,
    @SerialName("role") val role: String,
    @SerialName("club_id") val clubId: String? = null,
    @SerialName("league_ids") val leagueIds: List<String> = emptyList(),
)

@Serializable
data class ChangeRoleRequest(
    @SerialName("role") val role: String,
    @SerialName("club_id") val clubId: String? = null,
    @SerialName("league_ids") val leagueIds: List<String> = emptyList(),
)

@Serializable
data class AssignableDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("tenant_id") val tenantId: String? = null,
)

/** Clubs and leagues a user can be assigned to. */
@Serializable
data class AssignmentOptionsResponse(
    @SerialName("clubs") val clubs: List<AssignableDto> = emptyList(),
    @SerialName("leagues") val leagues: List<AssignableDto> = emptyList(),
)
