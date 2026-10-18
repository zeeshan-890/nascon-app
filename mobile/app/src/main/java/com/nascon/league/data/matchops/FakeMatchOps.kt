package com.nascon.league.data.matchops

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.time.Instant
import kotlin.math.abs

/**
 * Demo backend for match operations: validates like the real server (state machine, 409 ILLEGAL_TRANSITION,
 * 409 SCHEDULE_CONFLICT, 400 VALIDATION_ERROR, 404s) over its own in-memory copy of the demo matches.
 * [withDelay] = false skips the artificial latency (JVM tests).
 */
class FakeMatchOps(
    private val hasNetwork: () -> Boolean = { true },
    private val withDelay: Boolean = true,
) : MatchOpsApi {

    data class FakeOpsMatch(
        val id: String,
        val home: String,
        val away: String,
        val status: String,
        val scheduledAt: String,
        val venueId: String?,
        val durationMin: Int = ScheduleConflicts.DEFAULT_DURATION_MIN,
    )

    private val lock = Any()
    private val matches = linkedMapOf(
        "demo-m-1" to FakeOpsMatch("demo-m-1", "team-alpha-lions", "team-beta-hawks", "completed", "2026-10-04T14:00:00Z", VENUE),
        "demo-m-2" to FakeOpsMatch("demo-m-2", "team-gamma-wolves", "team-delta-sharks", "completed", "2026-10-04T16:00:00Z", VENUE),
        "demo-m-3" to FakeOpsMatch("demo-m-3", "team-delta-sharks", "team-beta-hawks", "completed", "2026-10-11T14:00:00Z", VENUE),
        "demo-m-4" to FakeOpsMatch("demo-m-4", "team-alpha-lions", "team-delta-sharks", "in_progress", "2026-10-17T14:00:00Z", VENUE),
        "demo-m-5" to FakeOpsMatch("demo-m-5", "team-beta-hawks", "team-gamma-wolves", "upcoming", "2026-10-18T14:00:00Z", VENUE),
        "demo-m-6" to FakeOpsMatch("demo-m-6", "team-gamma-wolves", "team-alpha-lions", "upcoming", "2026-10-25T14:00:00Z", VENUE),
    )
    private val pins = HashMap<String, MutableSet<String>>()
    private val knownProducts = setOf("prod-jersey", "prod-scarf", "prod-cap")

    fun snapshot(id: String): FakeOpsMatch? = synchronized(lock) { matches[id] }
    fun pinsOf(id: String): Set<String> = synchronized(lock) { pins[id].orEmpty().toSet() }

    private suspend fun pause(key: String) {
        if (!hasNetwork()) throw java.net.UnknownHostException("offline")
        if (withDelay) delay(300L + (abs(key.hashCode()) % 400).toLong())
    }

    private fun fail(status: Int, code: String, message: String, details: JsonObject? = null): Nothing {
        val detailsJson = if (details == null) "" else ",\"details\":$details"
        val body = "{\"error\":{\"code\":\"$code\",\"message\":\"$message\"$detailsJson}}"
            .toResponseBody("application/json".toMediaType())
        throw HttpException(Response.error<Any>(status, body))
    }

    private fun need(id: String): FakeOpsMatch = matches[id] ?: fail(404, "MATCH_NOT_FOUND", "Match not found")

    override suspend fun changeStatus(matchId: String, body: StatusRequest): StatusResponse {
        pause("status:$matchId")
        synchronized(lock) {
            val m = need(matchId)
            if (body.status !in MatchStatusMachine.ALL) fail(400, "VALIDATION_ERROR", "Request validation failed")
            if (!MatchStatusMachine.canTransition(m.status, body.status)) {
                fail(409, "ILLEGAL_TRANSITION", "Cannot move match from ${m.status} to ${body.status}")
            }
            matches[matchId] = m.copy(status = body.status)
            return StatusResponse(matchId, body.status, "u-organizer")
        }
    }

    private fun conflictsFor(m: FakeOpsMatch, body: ScheduleRequest): Pair<String?, List<ConflictDto>> {
        val start = runCatching { Instant.parse(body.scheduledAt).toEpochMilli() }.getOrNull()
            ?: fail(400, "VALIDATION_ERROR", "Request validation failed")
        val venue = body.venueId ?: m.venueId
        val subject = ScheduleConflicts.Slot(m.id, m.home, m.away, start, m.durationMin, venue)
        val others = matches.values.filter { it.id != m.id && it.status !in INACTIVE }.map {
            ScheduleConflicts.Slot(it.id, it.home, it.away, Instant.parse(it.scheduledAt).toEpochMilli(), it.durationMin, it.venueId)
        }
        return venue to ScheduleConflicts.detect(subject, others)
    }

    override suspend fun checkSchedule(matchId: String, body: ScheduleRequest): ConflictsResponse {
        pause("check:$matchId")
        synchronized(lock) { return ConflictsResponse(conflictsFor(need(matchId), body).second) }
    }

    override suspend fun reschedule(matchId: String, body: ScheduleRequest): RescheduleResponse {
        pause("schedule:$matchId")
        synchronized(lock) {
            val m = need(matchId)
            val (venue, conflicts) = conflictsFor(m, body)
            if (conflicts.isNotEmpty()) {
                val arr = JsonArray(
                    conflicts.map { c ->
                        buildJsonObject {
                            put("type", c.type)
                            put("entity_ids", JsonArray(c.entityIds.map { JsonPrimitive(it) }))
                            put("match_ids", JsonArray(c.matchIds.map { JsonPrimitive(it) }))
                        }
                    },
                )
                fail(409, "SCHEDULE_CONFLICT", "The new slot conflicts with other matches", JsonObject(mapOf("conflicts" to arr)))
            }
            val iso = Instant.parse(body.scheduledAt).toString().replace(Regex("\\.\\d+Z$"), "Z")
            matches[matchId] = m.copy(scheduledAt = iso, venueId = venue)
            return RescheduleResponse(matchId, iso, venue, emptyList())
        }
    }

    override suspend fun pin(matchId: String, body: PinRequest): PinResponse {
        pause("pin:$matchId")
        synchronized(lock) {
            need(matchId)
            if (body.productId.isBlank()) fail(400, "VALIDATION_ERROR", "Request validation failed")
            if (body.productId !in knownProducts) fail(404, "PRODUCT_NOT_FOUND", "Product not found")
            pins.getOrPut(matchId) { linkedSetOf() }.add(body.productId)
            return PinResponse(matchId, body.productId, true)
        }
    }

    override suspend fun unpin(matchId: String, productId: String): PinResponse {
        pause("unpin:$matchId")
        synchronized(lock) {
            need(matchId)
            pins[matchId]?.remove(productId)
            return PinResponse(matchId, productId, false)
        }
    }

    companion object {
        const val VENUE = "venue-alpha-stadium"
        private val INACTIVE = setOf("postponed", "abandoned", "cancelled")
    }
}

object MatchOpsFakeHolder {
    private var inst: FakeMatchOps? = null

    @Synchronized
    fun get(hasNetwork: () -> Boolean): FakeMatchOps = inst ?: FakeMatchOps(hasNetwork).also { inst = it }
}
