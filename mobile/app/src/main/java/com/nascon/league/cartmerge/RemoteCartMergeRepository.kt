package com.nascon.league.cartmerge

import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.matchlive.CartLine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import retrofit2.http.Body
import retrofit2.http.POST

interface CartMergeApi {
    @POST("audit/v1/guest/cart-merge")
    suspend fun merge(@Body body: JsonObject): JsonObject
}

class RemoteCartMergeRepository(private val api: () -> CartMergeApi) : CartMergeRepository {
    override suspend fun merge(guestToken: String, userId: String, lines: List<CartLine>): NetworkResult<List<CartLine>> =
        safeCall {
            val response = api().merge(CartMath.body(guestToken, userId, lines))
            parse(response, lines)
        }

    internal fun parse(root: JsonObject, frozen: List<CartLine>): List<CartLine> {
        val arr = (root["active_cart"] as? JsonArray)
            ?: ((root["data"] as? JsonObject)?.get("active_cart") as? JsonArray)
            ?: return emptyList()
        val byId = frozen.associateBy { it.variantId }
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["variant_id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val qty = (o["quantity"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val known = byId[id]
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: known?.name ?: ""
            val price = listOf("unit_price_minor", "price_minor")
                .firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.longOrNull } ?: known?.priceMinor ?: 0L
            CartLine(id, name, price, qty)
        }
    }
}
