package com.nascon.league.clubadmin

import com.nascon.league.data.api.dto.StaffDto
import com.nascon.league.data.clubadmin.AdminVariantDto
import com.nascon.league.data.clubadmin.FormError
import com.nascon.league.data.clubadmin.SocialRow
import com.nascon.league.data.clubadmin.StockKind
import com.nascon.league.data.clubadmin.VariantForm
import com.nascon.league.data.clubadmin.buildVenue
import com.nascon.league.data.clubadmin.canManageClub
import com.nascon.league.data.clubadmin.minorToInput
import com.nascon.league.data.clubadmin.parsePkrToMinor
import com.nascon.league.data.clubadmin.parseStock
import com.nascon.league.data.clubadmin.priceRange
import com.nascon.league.data.clubadmin.socialsToJson
import com.nascon.league.data.clubadmin.stockSummary
import com.nascon.league.data.clubadmin.validateCoordinates
import com.nascon.league.data.clubadmin.validateName
import com.nascon.league.data.clubadmin.validateOptionalUrl
import com.nascon.league.data.clubadmin.validateSocials
import com.nascon.league.data.clubadmin.validateStaff
import com.nascon.league.data.clubadmin.validateVariants
import com.nascon.league.data.store.formatPkr
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import com.nascon.league.domain.model.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClubAdminRulesTest {
    private fun session(role: Role, clubId: String? = null) =
        Session(User(userId = "u", email = "e@x.test", role = role, clubId = clubId))

    @Test fun roleGate() {
        assertTrue(canManageClub(session(Role.SUPER_ADMIN), "club-a"))
        assertTrue(canManageClub(session(Role.CLUB_MANAGER, "club-a"), "club-a"))
        assertFalse(canManageClub(session(Role.CLUB_MANAGER, "club-b"), "club-a"))
        assertFalse(canManageClub(session(Role.CLUB_MANAGER, null), "club-a"))
        assertFalse(canManageClub(session(Role.LEAGUE_ORGANIZER, "club-a"), "club-a"))
        assertFalse(canManageClub(session(Role.PLAYER, "club-a"), "club-a"))
        assertFalse(canManageClub(session(Role.VIEWER), "club-a"))
        assertFalse(canManageClub(null, "club-a"))
    }

    @Test fun nameAndUrl() {
        assertEquals(FormError.Required, validateName("   "))
        assertEquals(FormError.TooLong, validateName("x".repeat(81)))
        assertNull(validateName(" Alpha "))
        assertNull(validateOptionalUrl(""))
        assertNull(validateOptionalUrl("https://x.test/logo.png"))
        assertEquals(FormError.BadUrl, validateOptionalUrl("logo.png"))
        assertEquals(FormError.BadUrl, validateOptionalUrl("https://a b"))
    }

    @Test fun coordinates() {
        assertFalse(validateCoordinates("", "").any)
        assertFalse(validateCoordinates("31.5", "74.3").any)
        assertFalse(validateCoordinates("-90", "180").any)
        assertEquals(FormError.LatRange, validateCoordinates("90.1", "0").lat)
        assertEquals(FormError.LngRange, validateCoordinates("0", "-180.5").lng)
        assertEquals(FormError.NotANumber, validateCoordinates("abc", "0").lat)
        assertEquals(FormError.BothCoordinates, validateCoordinates("10", "").lng)
        assertEquals(FormError.BothCoordinates, validateCoordinates("", "10").lat)
        assertEquals(FormError.NotANumber, validateCoordinates("NaN", "0").lat)
    }

    @Test fun venueBuilding() {
        assertNull(buildVenue("  ", "", ""))
        val v = buildVenue(" Alpha Stadium ", "31.5", "74.25")!!
        assertEquals("Alpha Stadium", v.name)
        assertEquals(31.5, v.lat!!, 0.0)
        assertEquals(74.25, v.lng!!, 0.0)
    }

    @Test fun socials() {
        val rows = listOf(SocialRow("Instagram", "@a"), SocialRow("", ""), SocialRow("x", ""), SocialRow("instagram", "@b"))
        val errors = validateSocials(rows)
        assertEquals(setOf(2, 3), errors.keys)
        assertEquals(FormError.Required, errors[2])
        assertEquals(FormError.Duplicate, errors[3])
        val json = socialsToJson(rows)
        assertEquals(setOf("Instagram", "instagram"), json.keys)
    }

    @Test fun staff() {
        val errors = validateStaff(listOf(StaffDto("Ann", "Coach"), StaffDto(" ", "Physio"), StaffDto("Bob", "r".repeat(41))))
        assertEquals(setOf(1, 2), errors.keys)
        assertEquals(FormError.Required, errors[1])
        assertEquals(FormError.TooLong, errors[2])
    }

    @Test fun moneyParsing() {
        assertEquals(450000L, parsePkrToMinor("4,500"))
        assertEquals(450000L, parsePkrToMinor("4,500.00"))
        assertEquals(450050L, parsePkrToMinor("4500.5"))
        assertEquals(5L, parsePkrToMinor("0.05"))
        assertEquals(50L, parsePkrToMinor(".5"))
        assertEquals(450000L, parsePkrToMinor("PKR 4,500"))
        assertNull(parsePkrToMinor(""))
        assertNull(parsePkrToMinor("0"))
        assertNull(parsePkrToMinor("-5"))
        assertNull(parsePkrToMinor("1.234"))
        assertNull(parsePkrToMinor("1.2.3"))
        assertNull(parsePkrToMinor("12a"))
        assertNull(parsePkrToMinor("1".repeat(12)))
    }

    @Test fun moneyRoundTripUsesSharedFormatter() {
        assertEquals("4,500.00", minorToInput(450000))
        assertEquals("PKR 4,500.00", formatPkr(parsePkrToMinor(minorToInput(450000))!!))
        assertEquals("49.99", minorToInput(4999))
        assertEquals(4999L, parsePkrToMinor(minorToInput(4999)))
    }

    @Test fun stockParsing() {
        assertEquals(0, parseStock("0"))
        assertEquals(12, parseStock(" 12 "))
        assertNull(parseStock(""))
        assertNull(parseStock("-1"))
        assertNull(parseStock("1.5"))
        assertNull(parseStock("1000000"))
    }

    @Test fun variantValidation() {
        val rows = listOf(
            VariantForm(0, size = "S", price = "4,500", stock = "5"),
            VariantForm(1, size = "s", price = "4,500", stock = "5"),
            VariantForm(2, price = "10", stock = "1"),
            VariantForm(3, color = "Red", price = "x", stock = "-1"),
        )
        val e = validateVariants(rows)
        assertFalse(e.containsKey(0))
        assertEquals(FormError.Duplicate, e[1]?.label)
        assertEquals(FormError.Required, e[2]?.label)
        assertEquals(FormError.PriceInvalid, e[3]?.price)
        assertEquals(FormError.StockInvalid, e[3]?.stock)
    }

    @Test fun stockSummaries() {
        fun v(vararg s: Int) = s.map { AdminVariantDto(stock = it, priceMinor = 100) }
        assertEquals(StockKind.Out, stockSummary(emptyList()).kind)
        assertEquals(StockKind.Out, stockSummary(v(0, 0)).kind)
        val low = stockSummary(v(12, 1, 30))
        assertEquals(StockKind.Low, low.kind)
        assertEquals(1, low.count)
        assertEquals(StockKind.Low, stockSummary(v(0, 40)).kind)
        val ok = stockSummary(v(50, 50))
        assertEquals(StockKind.InStock, ok.kind)
        assertEquals(100, ok.count)
    }

    @Test fun priceRanges() {
        assertNull(priceRange(emptyList()))
        assertEquals(100L to 300L, priceRange(listOf(AdminVariantDto(priceMinor = 300), AdminVariantDto(priceMinor = 100))))
    }
}
