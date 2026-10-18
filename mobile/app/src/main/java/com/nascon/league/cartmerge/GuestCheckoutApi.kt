package com.nascon.league.cartmerge

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The direct checkout route. A guest's attempt is a REAL write: the server answers 403 GUEST_WRITE_FORBIDDEN, which opens the
 * sign-in sheet. The attempt uses the declined test card so that, if a server ever let a guest through, no order is paid.
 */
interface GuestCheckoutApi {
    @POST("audit/v1/checkout")
    suspend fun checkout(@Body body: JsonObject): JsonObject
}
