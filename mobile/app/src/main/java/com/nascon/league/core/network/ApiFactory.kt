package com.nascon.league.core.network

import com.nascon.league.BuildConfig
import com.nascon.league.core.security.TokenStore
import com.nascon.league.data.api.LeagueApi
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class ApiFactory(
    private val tokenStore: TokenStore,
    private val onSessionExpired: () -> Unit,
) {
    fun create(baseUrl: String): LeagueApi {
        val plainApi = retrofit(baseUrl, client(authenticated = false, authenticator = null))
        val authenticator = TokenAuthenticator(tokenStore, plainApi, onSessionExpired)
        return retrofit(baseUrl, client(authenticated = true, authenticator = authenticator))
    }

    private fun client(authenticated: Boolean, authenticator: TokenAuthenticator?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // Cleartext HTTP is for development hosts only (10.0.2.2, localhost, private LAN); anything else must be https.
            .addInterceptor(CleartextGuardInterceptor())
        if (authenticated) builder.addInterceptor(AuthInterceptor(tokenStore))
        else builder.addInterceptor(Interceptor { it.proceed(it.request().newBuilder().header("Accept", "application/json").build()) })
        if (authenticator != null) builder.authenticator(authenticator)
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                },
            )
        }
        return builder.build()
    }

    private fun retrofit(baseUrl: String, client: OkHttpClient): LeagueApi =
        buildRetrofit(baseUrl, client).create(LeagueApi::class.java)

    private fun buildRetrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
            .build()

    private val serviceCache = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Authenticated Retrofit service for any extra API interface (cached per interface and base URL). */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> createService(baseUrl: String, service: Class<T>): T =
        serviceCache.getOrPut(service.name + "@" + baseUrl) {
            val plainApi = retrofit(baseUrl, client(authenticated = false, authenticator = null))
            val authenticator = TokenAuthenticator(tokenStore, plainApi, onSessionExpired)
            buildRetrofit(baseUrl, client(authenticated = true, authenticator = authenticator)).create(service)
        } as T
}
