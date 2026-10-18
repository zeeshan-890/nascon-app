package com.nascon.league.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ErrorEnvelope(@SerialName("error") val error: ErrorBody)

@Serializable
data class ErrorBody(
    @SerialName("code") val code: String,
    @SerialName("message") val message: String = "",
    @SerialName("details") val details: JsonObject? = null,
)

@Serializable
data class LoginRequest(
    @SerialName("email") val email: String,
    @SerialName("password") val password: String,
)

@Serializable
data class UserDto(
    @SerialName("user_id") val userId: String,
    @SerialName("email") val email: String,
    @SerialName("role") val role: String,
    @SerialName("tenant_id") val tenantId: String? = null,
    @SerialName("league_ids") val leagueIds: List<String> = emptyList(),
    @SerialName("club_id") val clubId: String? = null,
    @SerialName("player_id") val playerId: String? = null,
)

@Serializable
data class TenantDto(
    @SerialName("tenant_id") val tenantId: String,
    @SerialName("name") val name: String,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("primary_color") val primaryColor: String,
    @SerialName("secondary_color") val secondaryColor: String,
)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("user") val user: UserDto,
    @SerialName("tenant") val tenant: TenantDto? = null,
)

@Serializable
data class RefreshRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
data class RefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class LogoutRequest(@SerialName("refresh_token") val refreshToken: String)

/** `GET /api/v1/me` — same user and tenant objects as login, without tokens. */
@Serializable
data class MeResponse(
    @SerialName("user") val user: UserDto,
    @SerialName("tenant") val tenant: TenantDto? = null,
)

@Serializable
data class PingResponse(@SerialName("pong") val pong: Boolean = false)
