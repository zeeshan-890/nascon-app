package com.nascon.league.data.fake

import com.nascon.league.data.api.dto.MatchEventRequest
import com.nascon.league.data.api.dto.SyncRequest
import com.nascon.league.data.api.dto.SyncResponse
import com.nascon.league.data.api.dto.SyncResultDto
import com.nascon.league.data.api.dto.SyncSummaryDto
import kotlinx.serialization.json.JsonObject

/** Demo implementation of POST /sync, mirroring DESIGN section 14. Pure Kotlin. */
class FakeSync(private val matches: FakeMatches, private val roster: FakeRoster) {

    private val lock = Any()
    private val processed = HashMap<Pair<String, String>, SyncResultDto>()

    fun sync(req: SyncRequest): SyncResponse = synchronized(lock) {
        val order = req.actions.indices.sortedBy { req.actions[it].queuedAt } // stable: ties keep request order
        val byIndex = HashMap<Int, SyncResultDto>()
        order.forEachIndexed { n, idx ->
            val a = req.actions[idx]
            val key = req.deviceId to a.actionId
            val prior = processed[key]
            val result = if (prior != null) {
                prior.copy(
                    status = "duplicate",
                    resolution = "skipped_duplicate",
                    errorCode = null,
                    processedOrder = n + 1,
                    signature = null,
                )
            } else {
                val r = process(a.actionId, a.type, a.baseVersion, a.payload).copy(processedOrder = n + 1)
                processed[key] = r
                r
            }
            byIndex[idx] = result
        }
        val results = req.actions.indices.map { byIndex.getValue(it) }
        SyncResponse(
            deviceId = req.deviceId,
            results = results,
            summary = SyncSummaryDto(
                applied = results.count { it.status == "applied" },
                rejected = results.count { it.status == "rejected" },
                duplicate = results.count { it.status == "duplicate" },
            ),
        )
    }

    private fun rejected(id: String, code: String, version: Long) = SyncResultDto(
        actionId = id, status = "rejected", resolution = "server_wins", errorCode = code, serverVersion = version,
    )

    private fun process(id: String, type: String, base: Long, payload: JsonObject): SyncResultDto = when (type) {
        "score_event" -> {
            val eventId = payload.str("event_id")
            val matchId = payload.str("match_id")
            val evType = payload.str("type")
            val inner = payload["payload"] as? JsonObject ?: JsonObject(emptyMap())
            if (eventId == null || matchId == null || evType == null) {
                rejected(id, "VALIDATION_ERROR", 0)
            } else {
                when (val o = matches.applyEvent(MatchEventRequest(eventId, matchId, evType, inner), base)) {
                    is MatchOutcome.Applied -> SyncResultDto(
                        actionId = id, status = "applied", resolution = o.resolution,
                        serverVersion = o.response.matchVersion, signature = o.response.signature,
                    )
                    is MatchOutcome.Duplicate -> SyncResultDto(
                        actionId = id, status = "duplicate", resolution = "skipped_duplicate",
                        serverVersion = o.response.matchVersion,
                    )
                    is MatchOutcome.Rejected -> rejected(id, o.code, o.serverVersion)
                }
            }
        }
        "roster_transfer" -> when (val o = roster.transfer(payload, base)) {
            is RosterOutcome.Applied -> SyncResultDto(
                actionId = id, status = "applied", resolution = o.resolution, serverVersion = o.serverVersion,
            )
            is RosterOutcome.Duplicate -> SyncResultDto(
                actionId = id, status = "duplicate", resolution = "skipped_duplicate", serverVersion = o.serverVersion,
            )
            is RosterOutcome.Rejected -> rejected(id, o.code, o.serverVersion)
        }
        else -> rejected(id, "VALIDATION_ERROR", 0)
    }
}
