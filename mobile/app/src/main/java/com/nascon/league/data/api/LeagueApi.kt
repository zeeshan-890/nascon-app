package com.nascon.league.data.api

import com.nascon.league.data.api.dto.CheckoutRequest
import com.nascon.league.data.api.dto.CheckoutResponse
import com.nascon.league.data.api.dto.ClubDto
import com.nascon.league.data.api.dto.ClubSummaryDto
import com.nascon.league.data.api.dto.FixtureDto
import com.nascon.league.data.api.dto.LeagueDto
import com.nascon.league.data.api.dto.LoginRequest
import com.nascon.league.data.api.dto.LoginResponse
import com.nascon.league.data.api.dto.LogoutRequest
import com.nascon.league.data.api.dto.MatchDetailDto
import com.nascon.league.data.api.dto.MatchEventRequest
import com.nascon.league.data.api.dto.MatchEventResponse
import com.nascon.league.data.api.dto.PingResponse
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.api.dto.RefreshRequest
import com.nascon.league.data.api.dto.RefreshResponse
import com.nascon.league.data.api.dto.RosterTransferResponse
import com.nascon.league.data.api.dto.SportConfigDto
import com.nascon.league.data.api.dto.StandingsDto
import com.nascon.league.data.api.dto.SyncRequest
import com.nascon.league.data.api.dto.SyncResponse
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming

interface LeagueApi {
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): RefreshResponse

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: LogoutRequest): JsonObject

    @GET("api/v1/ping")
    suspend fun ping(): PingResponse

    @GET("api/v1/leagues")
    suspend fun leagues(): List<LeagueDto>

    @GET("api/v1/leagues/{id}/fixtures")
    suspend fun fixtures(@Path("id") leagueId: String): List<FixtureDto>

    @GET("api/v1/matches/{id}")
    suspend fun match(@Path("id") id: String): MatchDetailDto

    @POST("api/v1/matches/{id}/events")
    suspend fun postEvent(@Path("id") matchId: String, @Body body: MatchEventRequest): MatchEventResponse

    @GET("api/v1/sports/{id}")
    suspend fun sport(@Path("id") id: String): SportConfigDto

    @GET("api/v1/leagues/{id}/standings")
    suspend fun standings(@Path("id") leagueId: String): StandingsDto

    @GET("api/v1/clubs")
    suspend fun clubs(): List<ClubSummaryDto>

    @GET("api/v1/clubs/{id}")
    suspend fun club(@Path("id") id: String): ClubDto

    @POST("api/v1/clubs/{id}/roster/transfer")
    suspend fun rosterTransfer(@Path("id") clubId: String, @Body body: JsonObject): RosterTransferResponse

    @GET("api/v1/products")
    suspend fun products(): List<ProductDto>

    @POST("api/v1/checkout")
    suspend fun checkout(@Body body: CheckoutRequest): CheckoutResponse

    @Streaming
    @GET("api/v1/orders/{id}/receipt.pdf")
    suspend fun receiptPdf(@Path("id") orderId: String): ResponseBody

    @POST("api/v1/sync")
    suspend fun sync(@Body body: SyncRequest): SyncResponse
}
