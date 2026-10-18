package com.nascon.league.data.clubadmin

import com.nascon.league.data.api.dto.ClubDto
import com.nascon.league.di.AppContainer
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** Club administration endpoints. None of these exist on the current backend (see contract_additions/clubadmin.md). */
interface ClubAdminApi {
    @PATCH("api/v1/clubs/{id}")
    suspend fun updateClub(@Path("id") clubId: String, @Body body: ClubUpdateRequest): ClubDto

    @PUT("api/v1/clubs/{id}/staff")
    suspend fun updateStaff(@Path("id") clubId: String, @Body body: StaffListDto): StaffListDto

    @GET("api/v1/clubs/{id}/products")
    suspend fun products(@Path("id") clubId: String): List<AdminProductDto>

    @POST("api/v1/clubs/{id}/products")
    suspend fun createProduct(@Path("id") clubId: String, @Body body: ProductWriteRequest): AdminProductDto

    @PATCH("api/v1/clubs/{id}/products/{product_id}")
    suspend fun updateProduct(
        @Path("id") clubId: String,
        @Path("product_id") productId: String,
        @Body body: ProductWriteRequest,
    ): AdminProductDto

    @PATCH("api/v1/clubs/{id}/products/{product_id}/variants/{variant_id}/stock")
    suspend fun updateStock(
        @Path("id") clubId: String,
        @Path("product_id") productId: String,
        @Path("variant_id") variantId: String,
        @Body body: StockUpdateRequest,
    ): AdminVariantDto
}

fun AppContainer.clubAdminApi(): ClubAdminApi = service(ClubAdminApi::class.java) { hn -> ClubAdminFakeHolder.get(hn) }
