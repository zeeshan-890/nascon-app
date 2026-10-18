package com.nascon.league.data.platformadmin

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Platform admin API (super admin only). Only [sport] exists on the real backend today; everything else is a
 * contract addition documented in mobile/contract_additions/platformadmin.md.
 */
interface PlatformAdminApi {
    @POST("api/v1/admin/clubs")
    suspend fun createClub(@Body body: AdminClubCreateRequest): AdminClubDto

    @GET("api/v1/admin/sports")
    suspend fun sports(): List<SportDefinitionDto>

    /** Real endpoint: full sport definition. */
    @GET("api/v1/sports/{id}")
    suspend fun sport(@Path("id") id: String): SportDefinitionDto

    @PUT("api/v1/admin/sports/{id}")
    suspend fun saveSport(@Path("id") id: String, @Body body: SportDefinitionDto): SportDefinitionDto

    @GET("api/v1/admin/access")
    suspend fun access(): AccessMatrixDto

    @PUT("api/v1/admin/access")
    suspend fun saveAccess(@Body body: AccessMatrixDto): AccessMatrixDto

    @GET("api/v1/admin/audit")
    suspend fun audit(
        @Query("actor") actor: String? = null,
        @Query("action") action: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): AuditPageDto

    @GET("api/v1/admin/audit/{id}")
    suspend fun auditEntry(@Path("id") id: String): AuditEntryDto
}

/** Both endpoints exist on the real backend. `health` is NOT under api/v1. */
interface HealthApi {
    @GET("health")
    suspend fun health(): HealthDto

    @GET("api/v1/ping")
    suspend fun ping(): PingDto
}
