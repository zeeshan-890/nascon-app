package com.nascon.league.data.leagueadmin

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** Contract additions for league management. See mobile/contract_additions/leagueadmin.md. */
interface LeagueAdminApi {
    @GET("api/v1/sports")
    suspend fun sports(): List<SportOptionDto>

    @GET("api/v1/admin/leagues")
    suspend fun adminLeagues(): List<AdminLeagueDto>

    @GET("api/v1/admin/leagues/{id}")
    suspend fun adminLeague(@Path("id") id: String): AdminLeagueDto

    @POST("api/v1/admin/leagues")
    suspend fun createLeague(@Body body: AdminLeagueRequest): AdminLeagueDto

    @PATCH("api/v1/admin/leagues/{id}")
    suspend fun updateLeague(@Path("id") id: String, @Body body: AdminLeagueRequest): AdminLeagueDto

    /** Super admin only: pending -> active. */
    @POST("api/v1/admin/leagues/{id}/approve")
    suspend fun approveLeague(@Path("id") id: String): AdminLeagueDto

    /** Super admin only: pending -> rejected. */
    @POST("api/v1/admin/leagues/{id}/reject")
    suspend fun rejectLeague(@Path("id") id: String): AdminLeagueDto

    /** Teams of an approved league. Super admin, or the organizer who owns the league. */
    @GET("api/v1/leagues/{id}/teams")
    suspend fun teams(@Path("id") leagueId: String): List<TeamDto>

    @POST("api/v1/leagues/{id}/teams")
    suspend fun addTeam(@Path("id") leagueId: String, @Body body: TeamRequest): TeamDto

    /** 409 TEAM_HAS_FIXTURES once the team has been scheduled. */
    @DELETE("api/v1/leagues/{id}/teams/{teamId}")
    suspend fun removeTeam(@Path("id") leagueId: String, @Path("teamId") teamId: String)

    @GET("api/v1/leagues/{id}/seasons")
    suspend fun seasons(@Path("id") leagueId: String): List<SeasonDto>

    @POST("api/v1/leagues/{id}/seasons")
    suspend fun createSeason(@Path("id") leagueId: String, @Body body: SeasonRequest): SeasonDto

    @GET("api/v1/leagues/{id}/divisions")
    suspend fun divisions(@Path("id") leagueId: String): List<DivisionDto>

    @POST("api/v1/leagues/{id}/divisions")
    suspend fun createDivision(@Path("id") leagueId: String, @Body body: DivisionRequest): DivisionDto

    @GET("api/v1/leagues/{id}/promotion-rules")
    suspend fun promotionRules(@Path("id") leagueId: String): PromotionRulesDto

    @PUT("api/v1/leagues/{id}/promotion-rules")
    suspend fun savePromotionRules(@Path("id") leagueId: String, @Body body: PromotionRulesDto): PromotionRulesDto

    @GET("api/v1/leagues/{id}/standings-rules")
    suspend fun standingsRules(@Path("id") leagueId: String): StandingsRulesDto

    @PUT("api/v1/leagues/{id}/standings-rules")
    suspend fun saveStandingsRules(@Path("id") leagueId: String, @Body body: StandingsRulesDto): StandingsRulesDto
}
