package com.nascon.league.data.clubadmin

import com.nascon.league.data.api.dto.StaffDto
import com.nascon.league.data.api.dto.VenueDto
import com.nascon.league.data.store.formatPkr
import com.nascon.league.domain.model.Role
import com.nascon.league.domain.model.Session
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** club.write: super admin for any club, club manager for their own club only. Everyone else must not see the screens. */
fun canManageClub(session: Session?, clubId: String): Boolean = when (session?.user?.role) {
    Role.SUPER_ADMIN -> true
    Role.CLUB_MANAGER -> session?.user?.clubId == clubId
    else -> false
}

/** Validation problems. The UI maps each to a plain-language string. */
enum class FormError {
    Required, TooLong, NotANumber, LatRange, LngRange, BothCoordinates,
    BadUrl, PriceInvalid, StockInvalid, Duplicate, TooMany, NoVariants,
}

const val NAME_MAX = 80
const val ROLE_MAX = 40
const val MAX_STAFF = 50
const val MAX_VARIANTS = 30
const val MAX_SOCIALS = 10
const val LOW_STOCK_AT = 5
const val MAX_STOCK = 100_000
val ROLE_SUGGESTIONS = listOf("Coach", "Manager", "Physio")

fun validateName(text: String, max: Int = NAME_MAX): FormError? {
    val t = text.trim()
    return when {
        t.isEmpty() -> FormError.Required
        t.length > max -> FormError.TooLong
        else -> null
    }
}

/** Optional http(s) URL. Blank is fine. */
fun validateOptionalUrl(text: String): FormError? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val lower = t.lowercase()
    val ok = (lower.startsWith("http://") || lower.startsWith("https://")) &&
        t.length > 8 && !t.any { it.isWhitespace() }
    return if (ok) null else FormError.BadUrl
}

fun parseCoordinate(text: String): Double? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val d = t.toDoubleOrNull() ?: return null
    return if (d.isNaN() || d.isInfinite()) null else d
}

data class CoordinateErrors(val lat: FormError? = null, val lng: FormError? = null) {
    val any: Boolean get() = lat != null || lng != null
}

/** Latitude -90..90 and longitude -180..180. Both blank is allowed; one without the other is not. */
fun validateCoordinates(latText: String, lngText: String): CoordinateErrors {
    val latBlank = latText.isBlank()
    val lngBlank = lngText.isBlank()
    if (latBlank && lngBlank) return CoordinateErrors()
    var lat: FormError? = null
    var lng: FormError? = null
    val la = parseCoordinate(latText)
    val lo = parseCoordinate(lngText)
    if (latBlank) lat = FormError.BothCoordinates
    else if (la == null) lat = FormError.NotANumber
    else if (la < -90.0 || la > 90.0) lat = FormError.LatRange
    if (lngBlank) lng = FormError.BothCoordinates
    else if (lo == null) lng = FormError.NotANumber
    else if (lo < -180.0 || lo > 180.0) lng = FormError.LngRange
    return CoordinateErrors(lat, lng)
}

/** Builds the venue to send, or null when nothing was entered. Call only after [validateCoordinates] passed. */
fun buildVenue(name: String, latText: String, lngText: String): VenueDto? {
    val n = name.trim()
    val lat = parseCoordinate(latText)
    val lng = parseCoordinate(lngText)
    if (n.isEmpty() && lat == null && lng == null) return null
    return VenueDto(n.ifEmpty { null }, lat, lng)
}

fun formatCoordinate(value: Double?): String = value?.let {
    if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
}.orEmpty()

data class SocialRow(val key: String, val value: String)

fun socialRowsOf(socials: JsonObject): List<SocialRow> =
    socials.entries.mapNotNull { (k, v) ->
        val text = (v as? JsonPrimitive)?.contentOrNull
        if (text == null) null else SocialRow(k, text)
    }

/** Row errors by index: key/value required together, keys unique ignoring case. Fully blank rows are ignored. */
fun validateSocials(rows: List<SocialRow>): Map<Int, FormError> {
    val errors = HashMap<Int, FormError>()
    val seen = HashSet<String>()
    rows.forEachIndexed { i, r ->
        val k = r.key.trim()
        val v = r.value.trim()
        if (k.isEmpty() && v.isEmpty()) return@forEachIndexed
        if (k.isEmpty() || v.isEmpty()) errors[i] = FormError.Required
        else if (k.length > ROLE_MAX || v.length > 200) errors[i] = FormError.TooLong
        else if (!seen.add(k.lowercase())) errors[i] = FormError.Duplicate
    }
    return errors
}

fun socialsToJson(rows: List<SocialRow>): JsonObject {
    val map = LinkedHashMap<String, JsonPrimitive>()
    rows.forEach { r ->
        val k = r.key.trim()
        val v = r.value.trim()
        if (k.isNotEmpty() && v.isNotEmpty()) map[k] = JsonPrimitive(v)
    }
    return JsonObject(map)
}

/** Staff row errors by index. Blank rows are errors (the user can remove them). */
fun validateStaff(staff: List<StaffDto>): Map<Int, FormError> {
    val errors = HashMap<Int, FormError>()
    staff.forEachIndexed { i, s ->
        val e = validateName(s.name)
        if (e != null) errors[i] = e
        else if (s.role.trim().length > ROLE_MAX) errors[i] = FormError.TooLong
    }
    return errors
}

/**
 * Parses what a person types as a price in rupees ("4,500", "4500.5", "4,500.00") into minor units.
 * Null when empty, not a number, negative, zero, more than two decimals or absurdly large.
 */
fun parsePkrToMinor(text: String): Long? {
    val t = text.trim().replace(",", "").removePrefix("PKR").trim()
    if (t.isEmpty()) return null
    val parts = t.split('.')
    if (parts.size > 2) return null
    val whole = parts[0].ifEmpty { "0" }
    val frac = parts.getOrNull(1) ?: ""
    if (!whole.all { it in '0'..'9' } || !frac.all { it in '0'..'9' }) return null
    if (frac.length > 2 || whole.length > 9) return null
    val minor = whole.toLong() * 100 + frac.padEnd(2, '0').toLong()
    return if (minor > 0) minor else null
}

/** Inverse of [parsePkrToMinor] for pre-filling a field, e.g. 450000 -> "4,500.00". Uses the shared PKR formatter. */
fun minorToInput(minor: Long): String = formatPkr(minor).removePrefix("PKR ")

fun parseStock(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty() || !t.all { it in '0'..'9' } || t.length > 6) return null
    val n = t.toInt()
    return if (n in 0..MAX_STOCK) n else null
}

/** An editable variant row, with text fields as typed. */
data class VariantForm(
    val key: Int,
    val variantId: String? = null,
    val size: String = "",
    val color: String = "",
    val price: String = "",
    val stock: String = "0",
)

data class VariantErrors(val label: FormError? = null, val price: FormError? = null, val stock: FormError? = null) {
    val any: Boolean get() = label != null || price != null || stock != null
}

fun variantLabel(size: String?, color: String?): String =
    listOfNotNull(size?.trim()?.ifEmpty { null }, color?.trim()?.ifEmpty { null }).joinToString(" / ")

/** Per-row errors: needs a size or colour, unique size+colour, valid price and stock. */
fun validateVariants(rows: List<VariantForm>): Map<Int, VariantErrors> {
    val out = HashMap<Int, VariantErrors>()
    val seen = HashSet<String>()
    rows.forEachIndexed { i, r ->
        var label: FormError? = null
        val s = r.size.trim()
        val c = r.color.trim()
        if (s.isEmpty() && c.isEmpty()) label = FormError.Required
        else if (s.length > ROLE_MAX || c.length > ROLE_MAX) label = FormError.TooLong
        else if (!seen.add(s.lowercase() + "|" + c.lowercase())) label = FormError.Duplicate
        val price = if (parsePkrToMinor(r.price) == null) FormError.PriceInvalid else null
        val stock = if (parseStock(r.stock) == null) FormError.StockInvalid else null
        val e = VariantErrors(label, price, stock)
        if (e.any) out[i] = e
    }
    return out
}

fun toVariantDtos(rows: List<VariantForm>): List<AdminVariantDto> = rows.map {
    AdminVariantDto(
        variantId = it.variantId,
        size = it.size.trim().ifEmpty { null },
        color = it.color.trim().ifEmpty { null },
        priceMinor = parsePkrToMinor(it.price) ?: 0,
        stock = parseStock(it.stock) ?: 0,
    )
}

enum class StockKind { InStock, Low, Out }

/** What the list chip shows: Out when nothing is left, Low (count = smallest variant) when any size is nearly gone, else total. */
data class StockSummary(val kind: StockKind, val count: Int)

fun stockSummary(variants: List<AdminVariantDto>): StockSummary {
    val total = variants.sumOf { it.stock }
    if (variants.isEmpty() || total <= 0) return StockSummary(StockKind.Out, 0)
    val lowest = variants.minOf { it.stock }
    return if (lowest <= LOW_STOCK_AT) StockSummary(StockKind.Low, lowest) else StockSummary(StockKind.InStock, total)
}

/** Lowest and highest price across variants, null when there are none. */
fun priceRange(variants: List<AdminVariantDto>): Pair<Long, Long>? =
    if (variants.isEmpty()) null else variants.minOf { it.priceMinor } to variants.maxOf { it.priceMinor }
