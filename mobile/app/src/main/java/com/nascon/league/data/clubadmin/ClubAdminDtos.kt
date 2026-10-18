package com.nascon.league.data.clubadmin

import com.nascon.league.data.api.dto.StaffDto
import com.nascon.league.data.api.dto.VenueDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Contract addition: body of PATCH /api/v1/clubs/{id}. */
@Serializable
data class ClubUpdateRequest(
    @SerialName("name") val name: String,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("venue") val venue: VenueDto? = null,
    @SerialName("socials") val socials: JsonObject = JsonObject(emptyMap()),
)

/** Contract addition: body and response of PUT /api/v1/clubs/{id}/staff. */
@Serializable
data class StaffListDto(
    @SerialName("staff") val staff: List<StaffDto> = emptyList(),
)

@Serializable
data class AdminVariantDto(
    @SerialName("variant_id") val variantId: String? = null,
    @SerialName("size") val size: String? = null,
    @SerialName("color") val color: String? = null,
    @SerialName("price_minor") val priceMinor: Long = 0,
    @SerialName("stock") val stock: Int = 0,
)

@Serializable
data class AdminProductDto(
    @SerialName("product_id") val productId: String,
    @SerialName("name") val name: String,
    @SerialName("category") val category: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("active") val active: Boolean = true,
    @SerialName("variants") val variants: List<AdminVariantDto> = emptyList(),
)

/** Contract addition: body of POST/PATCH /api/v1/clubs/{id}/products[/{product_id}]. */
@Serializable
data class ProductWriteRequest(
    @SerialName("name") val name: String,
    @SerialName("category") val category: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("active") val active: Boolean = true,
    @SerialName("variants") val variants: List<AdminVariantDto> = emptyList(),
)

/** Contract addition: body of PATCH .../variants/{variant_id}/stock. */
@Serializable
data class StockUpdateRequest(
    @SerialName("stock") val stock: Int,
)
