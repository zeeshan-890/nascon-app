package com.nascon.league.data.clubadmin

import com.nascon.league.data.api.dto.ClubDto
import com.nascon.league.data.api.dto.StaffDto
import com.nascon.league.data.api.dto.VenueDto
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import kotlin.math.abs

object ClubAdminFakeHolder {
    private var inst: FakeClubAdminApi? = null
    fun get(hasNetwork: () -> Boolean): FakeClubAdminApi = inst ?: FakeClubAdminApi(hasNetwork).also { inst = it }
}

/**
 * In-memory club administration backend for demo mode, seeded for the demo club "club-alpha-1".
 * [delayMs] is 0 in unit tests.
 */
class FakeClubAdminApi(
    private val hasNetwork: () -> Boolean,
    private val delayMs: (key: String) -> Long = { 300L + (abs(it.hashCode()) % 400).toLong() },
) : ClubAdminApi {

    private val lock = Any()
    private var nextId = 1

    private class ClubState(
        var name: String,
        var logoUrl: String?,
        var venue: VenueDto?,
        var socials: JsonObject,
        var staff: List<StaffDto>,
        val products: LinkedHashMap<String, AdminProductDto>,
    )

    private val clubs = LinkedHashMap<String, ClubState>().apply {
        put(
            "club-alpha-1",
            ClubState(
                name = "Alpha Lions FC",
                logoUrl = null,
                venue = VenueDto("Alpha Stadium", 31.5204, 74.3587),
                socials = buildJsonObject {
                    put("website", "https://alphalions.example.com")
                    put("twitter", "@alphalions")
                    put("instagram", "@alphalionsfc")
                },
                staff = listOf(
                    StaffDto("Sofia Marin", "Head Coach"),
                    StaffDto("Daniel Park", "Assistant Coach"),
                    StaffDto("Amina Rao", "Physio"),
                ),
                products = LinkedHashMap(seedProducts().associateBy { it.productId }),
            ),
        )
        put("club-alpha-2", ClubState("Beta Hawks United", null, null, JsonObject(emptyMap()), emptyList(), LinkedHashMap()))
    }

    private suspend fun gate(key: String) {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        val d = delayMs(key)
        if (d > 0) delay(d)
    }

    private fun fail(status: Int, code: String, message: String): Nothing {
        val body = "{\"error\":{\"code\":\"$code\",\"message\":\"$message\"}}".toResponseBody("application/json".toMediaType())
        throw HttpException(Response.error<Any>(status, body))
    }

    private fun club(id: String) = clubs[id] ?: fail(404, "CLUB_NOT_FOUND", "Club not found.")

    private fun invalid(): Nothing = fail(400, "VALIDATION_ERROR", "Request validation failed.")

    override suspend fun updateClub(clubId: String, body: ClubUpdateRequest): ClubDto {
        gate("club$clubId")
        synchronized(lock) {
            val c = club(clubId)
            if (body.name.isBlank()) invalid()
            val v = body.venue
            if (v != null) {
                val lat = v.lat
                val lng = v.lng
                if ((lat != null && lat !in -90.0..90.0) || (lng != null && lng !in -180.0..180.0)) invalid()
            }
            c.name = body.name.trim()
            c.logoUrl = body.logoUrl?.trim()?.ifEmpty { null }
            c.venue = body.venue
            c.socials = body.socials
            return ClubDto(
                clubId = clubId, name = c.name, tenantId = "tenant-alpha", logoUrl = c.logoUrl,
                venue = c.venue, socials = c.socials, staff = c.staff,
            )
        }
    }

    override suspend fun updateStaff(clubId: String, body: StaffListDto): StaffListDto {
        gate("staff$clubId")
        synchronized(lock) {
            val c = club(clubId)
            if (body.staff.any { it.name.isBlank() }) invalid()
            c.staff = body.staff.map { StaffDto(it.name.trim(), it.role.trim()) }
            return StaffListDto(c.staff)
        }
    }

    override suspend fun products(clubId: String): List<AdminProductDto> {
        gate("products$clubId")
        synchronized(lock) { return club(clubId).products.values.toList() }
    }

    override suspend fun createProduct(clubId: String, body: ProductWriteRequest): AdminProductDto {
        gate("create$clubId")
        synchronized(lock) {
            val c = club(clubId)
            validate(body)
            val pid = "prod-new-${nextId++}"
            val p = AdminProductDto(
                pid, body.name.trim(), body.category.trim(), body.imageUrl?.trim()?.ifEmpty { null }, body.active,
                body.variants.mapIndexed { i, v -> v.copy(variantId = "$pid-v${i + 1}") },
            )
            c.products[pid] = p
            return p
        }
    }

    override suspend fun updateProduct(clubId: String, productId: String, body: ProductWriteRequest): AdminProductDto {
        gate("update$productId")
        synchronized(lock) {
            val c = club(clubId)
            val old = c.products[productId] ?: fail(404, "PRODUCT_NOT_FOUND", "Product not found.")
            validate(body)
            val p = AdminProductDto(
                productId, body.name.trim(), body.category.trim(), body.imageUrl?.trim()?.ifEmpty { null }, body.active,
                body.variants.map { v ->
                    val existing = v.variantId?.let { id -> old.variants.firstOrNull { it.variantId == id } }
                    if (existing != null) v.copy(variantId = existing.variantId, stock = existing.stock)
                    else v.copy(variantId = "$productId-v${nextId++}")
                },
            )
            c.products[productId] = p
            return p
        }
    }

    override suspend fun updateStock(clubId: String, productId: String, variantId: String, body: StockUpdateRequest): AdminVariantDto {
        gate("stock$variantId")
        synchronized(lock) {
            val c = club(clubId)
            val p = c.products[productId] ?: fail(404, "PRODUCT_NOT_FOUND", "Product not found.")
            val v = p.variants.firstOrNull { it.variantId == variantId } ?: fail(404, "VARIANT_NOT_FOUND", "Variant not found.")
            if (body.stock < 0) invalid()
            val updated = v.copy(stock = body.stock)
            c.products[productId] = p.copy(variants = p.variants.map { if (it.variantId == variantId) updated else it })
            return updated
        }
    }

    private fun validate(body: ProductWriteRequest) {
        if (body.name.isBlank() || body.variants.isEmpty()) invalid()
        if (body.variants.any { it.priceMinor <= 0 || it.stock < 0 }) invalid()
    }

    companion object {
        fun seedProducts(): List<AdminProductDto> = listOf(
            AdminProductDto(
                "prod-jersey", "Alpha Lions Home Jersey", "apparel", null, true,
                listOf(
                    AdminVariantDto("var-jersey-s", "S", "Blue", 4999, 20),
                    AdminVariantDto("var-jersey-m", "M", "Blue", 4999, 15),
                    AdminVariantDto("var-jersey-l", "L", "Blue", 5499, 8),
                ),
            ),
            AdminProductDto(
                "prod-scarf", "Alpha Lions Scarf", "accessories", null, true,
                listOf(
                    AdminVariantDto("var-scarf-red", null, "Red", 1999, 30),
                    AdminVariantDto("var-scarf-blue", null, "Blue", 1999, 1),
                ),
            ),
            AdminProductDto(
                "prod-cap", "Alpha Lions Cap", "accessories", null, false,
                listOf(
                    AdminVariantDto("var-cap-black", null, "Black", 1499, 0),
                    AdminVariantDto("var-cap-white", null, "White", 1499, 0),
                ),
            ),
        )
    }
}
