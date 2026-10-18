package com.nascon.league.data.repo

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.core.security.TokenStore
import com.nascon.league.core.time.Clock
import com.nascon.league.data.api.LeagueApi
import com.nascon.league.data.api.dto.LoginRequest
import com.nascon.league.data.api.dto.LoginResponse
import com.nascon.league.data.api.dto.LogoutRequest
import com.nascon.league.data.api.dto.MeResponse
import com.nascon.league.data.api.dto.RefreshRequest
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.Tenant
import com.nascon.league.domain.model.User

class AuthRepository(
    private val apiProvider: () -> LeagueApi,
    private val tokenStore: TokenStore,
    private val sessionRepository: SessionRepository,
    private val cacheManager: CacheManager? = null,
) {
    suspend fun login(email: String, password: String): NetworkResult<Session> {
        return when (val result = safeCall { apiProvider().login(LoginRequest(email.trim(), password)) }) {
            is NetworkResult.Failure -> result
            is NetworkResult.Success -> {
                val session = result.data.toSession()
                tokenStore.saveTokens(
                    result.data.accessToken,
                    result.data.refreshToken,
                    Clock.nowEpochSeconds() + result.data.expiresIn,
                )
                // Clear another user's cached data before the new session becomes visible.
                runCatching { cacheManager?.onLogin(session.user.userId) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                sessionRepository.setSession(session)
                NetworkResult.Success(session)
            }
        }
    }

    suspend fun logout() {
        val refresh = tokenStore.refreshToken()
        if (refresh != null) safeCall { apiProvider().logout(LogoutRequest(refresh)) }
        sessionRepository.startGuest()
        runCatching { cacheManager?.onLogout() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    /** `GET /api/v1/me`. Updates the saved session when the call succeeds. */
    suspend fun me(): NetworkResult<Session> {
        return when (val result = safeCall { apiProvider().me() }) {
            is NetworkResult.Failure -> result
            is NetworkResult.Success -> {
                val session = result.data.toSession()
                sessionRepository.setSession(session)
                NetworkResult.Success(session)
            }
        }
    }

    suspend fun refreshIfNeeded() {
        if (sessionRepository.session.value == null) return
        if (tokenStore.expiresAtEpoch() - Clock.nowEpochSeconds() > 60) return
        val refresh = tokenStore.refreshToken() ?: return
        when (val result = safeCall { apiProvider().refresh(RefreshRequest(refresh)) }) {
            is NetworkResult.Success -> tokenStore.saveTokens(
                result.data.accessToken,
                result.data.refreshToken,
                Clock.nowEpochSeconds() + result.data.expiresIn,
            )
            is NetworkResult.Failure -> {
                val error = result.error
                if (error is ApiError.Http && (error.status == 401 || error.status == 403)) sessionRepository.expire()
            }
        }
    }
}

private fun MeResponse.toSession() = Session(
    user = User(
        userId = user.userId,
        email = user.email,
        role = Role.fromApi(user.role),
        tenantId = user.tenantId,
        leagueIds = user.leagueIds,
        clubId = user.clubId,
        playerId = user.playerId,
    ),
    tenant = tenant?.let {
        Tenant(it.tenantId, it.name, it.logoUrl, it.primaryColor, it.secondaryColor)
    },
)

private fun LoginResponse.toSession() = MeResponse(user, tenant).toSession()
