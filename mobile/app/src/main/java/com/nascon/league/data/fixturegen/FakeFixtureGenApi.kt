package com.nascon.league.data.fixturegen

import com.nascon.league.core.network.AppJson
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

private fun httpError(status: Int, code: String, message: String, fields: List<String> = emptyList()): HttpException {
    val body = buildJsonObject {
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                put("details", buildJsonObject { put("fields", JsonArray(fields.map { JsonPrimitive(it) })) })
            },
        )
    }.toString()
    return HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())))
}

/** Demo-mode twin of POST /audit/v1/fixtures: same validation, same response shape. */
class FakeFixtureGenApi(private val hasNetwork: () -> Boolean) : FixtureGenApi {
    override suspend fun generate(body: JsonObject): JsonObject {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        delay(350)
        val req = GenRequest.fromJson(body)
        val dto = try {
            FixtureGenerator.generate(req)
        } catch (e: GenException) {
            when (e.code) {
                "NO_VALID_PAIRING" -> throw httpError(409, e.code, "No valid pairing exists for this round")
                else -> throw httpError(400, "VALIDATION_ERROR", "Request validation failed", e.fields)
            }
        }
        return AppJson.encodeToJsonElement(GeneratedDto.serializer(), dto) as JsonObject
    }
}

/** Demo-mode twin of the invented save endpoint: counts the real matches and remembers them in memory. */
class FakeFixtureGenSaveApi(private val hasNetwork: () -> Boolean) : FixtureGenSaveApi {
    private val saved = HashMap<String, Int>()

    override suspend fun save(leagueId: String, body: JsonObject): JsonObject {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        delay(500)
        val req = GenRequest.fromJson(body)
        val dto = try {
            FixtureGenerator.generate(req)
        } catch (e: GenException) {
            throw httpError(if (e.code == "NO_VALID_PAIRING") 409 else 400, e.code, "Request validation failed", e.fields)
        }
        val created = countMatches(dto)
        saved[leagueId] = (saved[leagueId] ?: 0) + created
        return AppJson.encodeToJsonElement(FixtureSaveResultDto.serializer(), FixtureSaveResultDto(created, leagueId)) as JsonObject
    }

    fun savedCount(leagueId: String): Int = saved[leagueId] ?: 0
}

/** Number of real (playable) matches in a generated schedule. */
fun countMatches(dto: GeneratedDto): Int {
    fun real(m: GenMatchDto) = !m.bye && !m.conditional && m.status != "voided"
    return when (dto.format) {
        "round_robin" -> dto.rounds.sumOf { r -> r.matches.count { it.status != "voided" } }
        "swiss" -> dto.pairings.size
        "single_elim" -> dto.rounds.sumOf { r -> r.matches.count(::real) }
        else -> dto.winners.sumOf { r -> r.matches.count(::real) } +
            dto.losers.sumOf { r -> r.matches.count(::real) } + dto.grandFinal.count { !it.conditional }
    }
}

object FixtureGenFakeHolder {
    private var api: FakeFixtureGenApi? = null
    private var save: FakeFixtureGenSaveApi? = null

    fun getApi(hasNetwork: () -> Boolean): FixtureGenApi = api ?: FakeFixtureGenApi(hasNetwork).also { api = it }
    fun getSave(hasNetwork: () -> Boolean): FixtureGenSaveApi = save ?: FakeFixtureGenSaveApi(hasNetwork).also { save = it }
}
