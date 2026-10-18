package com.nascon.league.data.useradmin

import com.nascon.league.di.AppContainer
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Contract addition (see mobile/contract_additions/useradmin.md). Super admin only. */
interface UserAdminApi {
    @GET("api/v1/admin/users")
    suspend fun users(
        @Query("query") query: String? = null,
        @Query("role") role: String? = null,
    ): AdminUserListResponse

    @GET("api/v1/admin/users/options")
    suspend fun assignmentOptions(): AssignmentOptionsResponse

    @GET("api/v1/admin/users/{id}")
    suspend fun user(@Path("id") id: String): AdminUserDto

    @POST("api/v1/admin/users")
    suspend fun createUser(@Body body: NewUserRequest): AdminUserDto

    @PATCH("api/v1/admin/users/{id}/role")
    suspend fun changeRole(@Path("id") id: String, @Body body: ChangeRoleRequest): AdminUserDto

    @POST("api/v1/admin/users/{id}/deactivate")
    suspend fun deactivate(@Path("id") id: String): AdminUserDto

    @POST("api/v1/admin/users/{id}/activate")
    suspend fun activate(@Path("id") id: String): AdminUserDto
}

fun AppContainer.userAdminApi(): UserAdminApi =
    service(UserAdminApi::class.java) { hn -> UserAdminFakeHolder.get(hn) }
