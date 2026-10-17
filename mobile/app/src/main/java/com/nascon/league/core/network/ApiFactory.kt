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
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
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
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(LeagueApi::class.java)
}
