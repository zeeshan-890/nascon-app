package com.nascon.league.data.rosteradd

import com.nascon.league.di.AppContainer
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path

/** Real endpoint: POST /api/v1/clubs/:id/roster/add (backend/src/routes/api/v1/clubs.js). Answers 201. */
interface RosterAddApi {
    @POST("api/v1/clubs/{id}/roster/add")
    suspend fun addPlayer(@Path("id") clubId: String, @Body body: AddPlayerRequest): AddPlayerResponse
}

/** Body checked by validateAdd: ids match [A-Za-z0-9_.:-]{1,64}, texts 1..200 chars, date is a real YYYY-MM-DD. */
@Serializable
data class AddPlayerRequest(
    @SerialName("request_id") val requestId: String,
    @SerialName("league_id") val leagueId: String,
    @SerialName("player_id") val playerId: String,
    @SerialName("squad_id") val squadId: String,
    @SerialName("name") val name: String,
    @SerialName("position") val position: String,
    /** One of captain, vice_captain, starter, substitute. */
    @SerialName("role") val role: String,
    @SerialName("effective_date") val effectiveDate: String,
    @SerialName("reason") val reason: String,
    @SerialName("requested_by") val requestedBy: String,
)

@Serializable
data class AddPlayerResponse(
    @SerialName("request_id") val requestId: String = "",
    @SerialName("status") val status: String = "",
    @SerialName("state") val state: String = "",
    @SerialName("registration") val registration: JsonObject? = null,
    @SerialName("audit_entry") val auditEntry: JsonObject? = null,
)

/** Demo implementation; mimics the server's checks. Long-lived so repeated adds are remembered. */
class FakeRosterAddApi(private val hasNetwork: () -> Boolean) : RosterAddApi {
    private val registered = HashSet<String>()
    private val seen = HashMap<String, AddPlayerResponse>()
    private var calls = 0

    override suspend fun addPlayer(clubId: String, body: AddPlayerRequest): AddPlayerResponse {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        delay(300L + (calls++ % 5) * 100L)
        seen[body.requestId]?.let { return it }
        val bad = AddPlayerValidation.badFields(body)
        if (bad.isNotEmpty()) fail(400, "VALIDATION_ERROR", "Request validation failed")
        if (!registered.add(body.playerId)) fail(409, "PLAYER_ALREADY_REGISTERED", "Player already has a registration in this league")
        val out = AddPlayerResponse(body.requestId, "accepted", "active")
        seen[body.requestId] = out
        return out
    }

    private fun fail(status: Int, code: String, message: String): Nothing {
        val json = """{"error":{"code":"$code","message":"$message"}}"""
        throw HttpException(Response.error<Any>(status, json.toResponseBody("application/json".toMediaType())))
    }
}

object RosterAddFakeHolder {
    private var inst: FakeRosterAddApi? = null
    fun get(hasNetwork: () -> Boolean): FakeRosterAddApi = inst ?: FakeRosterAddApi(hasNetwork).also { inst = it }
}

fun AppContainer.rosterAddApi(): RosterAddApi = service(RosterAddApi::class.java) { hn -> RosterAddFakeHolder.get(hn) }

/** Mirror of backend validateAdd for the client and the fake; returns the offending field names. */
object AddPlayerValidation {
    val ROLES = listOf("captain", "vice_captain", "starter", "substitute")
    private val ID_RE = Regex("^[A-Za-z0-9_.:-]{1,64}$")
    private val DATE_RE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    fun badFields(b: AddPlayerRequest): List<String> = buildList {
        if (!ID_RE.matches(b.requestId)) add("request_id")
        if (!ID_RE.matches(b.leagueId)) add("league_id")
        if (!ID_RE.matches(b.playerId)) add("player_id")
        if (!ID_RE.matches(b.squadId)) add("squad_id")
        if (b.name.length !in 1..200) add("name")
        if (b.position.length !in 1..200) add("position")
        if (b.role !in ROLES) add("role")
        val dateOk = DATE_RE.matches(b.effectiveDate) && runCatching { java.time.LocalDate.parse(b.effectiveDate) }.isSuccess
        if (!dateOk) add("effective_date")
        if (b.reason.length !in 1..200) add("reason")
        if (b.requestedBy.length !in 1..200) add("requested_by")
    }
}
