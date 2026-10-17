package com.nascon.league.core.network

import com.nascon.league.core.security.TokenStore
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal val unauthenticatedPaths = listOf("/auth/login", "/auth/refresh", "/ping")

internal fun Request.isAuthFree(): Boolean {
    val path = url.encodedPath
    return unauthenticatedPaths.any { path.endsWith(it) }
}

class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder().header("Accept", "application/json")
        if (!chain.request().isAuthFree()) {
            tokenStore.accessToken()?.let { builder.header("Authorization", "Bearer $it") }
        }
        return chain.proceed(builder.build())
    }
}
