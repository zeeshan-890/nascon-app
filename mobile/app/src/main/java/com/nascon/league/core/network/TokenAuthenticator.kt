package com.nascon.league.core.network

import com.nascon.league.core.security.TokenStore
import com.nascon.league.core.time.Clock
import com.nascon.league.data.api.LeagueApi
import com.nascon.league.data.api.dto.RefreshRequest
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException

class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val refreshApi: LeagueApi,
    private val onSessionExpired: () -> Unit,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.isAuthFree()) return null
        if (responseCount(response) >= 2) return null

        synchronized(lock) {
            val sent = response.request.header("Authorization")?.removePrefix("Bearer ")
            val current = tokenStore.accessToken()
            if (current != null && current != sent) {
                return response.request.newBuilder().header("Authorization", "Bearer $current").build()
            }
            val refresh = tokenStore.refreshToken()
            if (refresh == null) {
                onSessionExpired()
                return null
            }
            return try {
                val tokens = runBlocking { refreshApi.refresh(RefreshRequest(refresh)) }
                tokenStore.saveTokens(
                    tokens.accessToken,
                    tokens.refreshToken,
                    Clock.nowEpochSeconds() + tokens.expiresIn,
                )
                response.request.newBuilder().header("Authorization", "Bearer ${tokens.accessToken}").build()
            } catch (e: HttpException) {
                // 401 means the refresh token is dead. 403 is a permission error and must not log the user out.
                if (e.code() == 401) onSessionExpired()
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
