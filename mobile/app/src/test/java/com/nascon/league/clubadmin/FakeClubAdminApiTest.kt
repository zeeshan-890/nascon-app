package com.nascon.league.clubadmin

import com.nascon.league.data.api.dto.StaffDto
import com.nascon.league.data.api.dto.VenueDto
import com.nascon.league.data.clubadmin.AdminVariantDto
import com.nascon.league.data.clubadmin.ClubUpdateRequest
import com.nascon.league.data.clubadmin.FakeClubAdminApi
import com.nascon.league.data.clubadmin.ProductWriteRequest
import com.nascon.league.data.clubadmin.StaffListDto
import com.nascon.league.data.clubadmin.StockUpdateRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException

class FakeClubAdminApiTest {
    private fun api(online: Boolean = true) = FakeClubAdminApi({ online }, { 0L })

    private fun expectHttp(status: Int, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected HTTP $status")
        } catch (e: HttpException) {
            assertEquals(status, e.code())
        }
    }

    @Test fun offlineThrowsUnknownHost() {
        try {
            runBlocking { api(false).products("club-alpha-1") }
            fail("expected offline")
        } catch (e: java.net.UnknownHostException) {
            // expected
        }
    }

    @Test fun seededProductsAndUnknownClub() = runBlocking {
        val a = api()
        assertEquals(3, a.products("club-alpha-1").size)
        assertTrue(a.products("club-alpha-2").isEmpty())
        expectHttp(404) { a.products("nope") }
    }

    @Test fun updateClubPersistsAndValidates() = runBlocking {
        val a = api()
        val r = a.updateClub("club-alpha-1", ClubUpdateRequest("  New Name ", venue = VenueDto("V", 10.0, 20.0)))
        assertEquals("New Name", r.name)
        assertEquals(10.0, r.venue!!.lat!!, 0.0)
        expectHttp(400) { a.updateClub("club-alpha-1", ClubUpdateRequest(" ")) }
        expectHttp(400) { a.updateClub("club-alpha-1", ClubUpdateRequest("x", venue = VenueDto("V", 91.0, 0.0))) }
        expectHttp(404) { a.updateClub("nope", ClubUpdateRequest("x")) }
    }

    @Test fun staffReplaced() = runBlocking {
        val a = api()
        val r = a.updateStaff("club-alpha-1", StaffListDto(listOf(StaffDto(" Ann ", " Coach "))))
        assertEquals(listOf(StaffDto("Ann", "Coach")), r.staff)
        expectHttp(400) { a.updateStaff("club-alpha-1", StaffListDto(listOf(StaffDto("", "x")))) }
    }

    @Test fun createUpdateAndStock() = runBlocking {
        val a = api()
        val created = a.createProduct(
            "club-alpha-2",
            ProductWriteRequest("Ball", "equipment", null, true, listOf(AdminVariantDto(size = "5", priceMinor = 300000, stock = 7))),
        )
        val vid = created.variants.single().variantId!!
        assertEquals(1, a.products("club-alpha-2").size)

        val s = a.updateStock("club-alpha-2", created.productId, vid, StockUpdateRequest(0))
        assertEquals(0, s.stock)
        expectHttp(400) { a.updateStock("club-alpha-2", created.productId, vid, StockUpdateRequest(-1)) }
        expectHttp(404) { a.updateStock("club-alpha-2", created.productId, "nope", StockUpdateRequest(1)) }

        // Updating keeps live stock for existing variants and gives new variants an id.
        val updated = a.updateProduct(
            "club-alpha-2", created.productId,
            ProductWriteRequest(
                "Ball 2", "equipment", null, false,
                listOf(AdminVariantDto(vid, "5", null, 350000, 99), AdminVariantDto(null, "4", null, 250000, 3)),
            ),
        )
        assertEquals(false, updated.active)
        assertEquals(0, updated.variants[0].stock)
        assertEquals(3, updated.variants[1].stock)
        assertTrue(updated.variants[1].variantId != null)
    }

    @Test fun productValidation() {
        val a = api()
        expectHttp(400) { a.createProduct("club-alpha-1", ProductWriteRequest("X", variants = emptyList())) }
        expectHttp(400) { a.createProduct("club-alpha-1", ProductWriteRequest("", variants = listOf(AdminVariantDto(priceMinor = 1)))) }
        expectHttp(400) { a.createProduct("club-alpha-1", ProductWriteRequest("X", variants = listOf(AdminVariantDto(priceMinor = 0)))) }
        expectHttp(404) { a.updateProduct("club-alpha-1", "nope", ProductWriteRequest("X", variants = listOf(AdminVariantDto(priceMinor = 1)))) }
    }
}
