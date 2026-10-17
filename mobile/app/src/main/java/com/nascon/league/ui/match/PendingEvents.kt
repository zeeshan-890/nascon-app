package com.nascon.league.ui.match

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Minimal view of a queued offline action, so the merge logic is testable without Room. */
data class PendingInput(
    val actionId: String,
    val type: String,
    val status: String,
    val ownerUserId: String,
    val entityKey: String,
    val payloadJson: String,
    val createdAt: Long,
)

data class PendingEvent(val actionId: String, val label: EventLabel, val createdAt: Long)

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseObject(s: String): JsonObject? = try {
    lenientJson.parseToJsonElement(s) as? JsonObject
} catch (_: Exception) {
    null
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Only same-owner, PENDING, score_event actions for [matchId]; newest first. Never alters the displayed score. */
fun pendingEventsFor(
    matchId: String,
    currentUserId: String?,
    actions: List<PendingInput>,
    teamName: (String?) -> String?,
): List<PendingEvent> {
    if (currentUserId == null) return emptyList()
    return actions.asSequence()
        .filter { it.type == "score_event" && it.status == "PENDING" && it.ownerUserId == currentUserId }
        .mapNotNull { a ->
            val obj = parseObject(a.payloadJson)
            val actionMatch = obj?.text("match_id") ?: a.entityKey
            if (actionMatch != matchId) return@mapNotNull null
            val inner = (obj?.get("payload") as? JsonObject) ?: obj ?: JsonObject(emptyMap())
            val type = obj?.text("type") ?: "score"
            PendingEvent(a.actionId, eventLabel(type, inner, teamName), a.createdAt)
        }
        .sortedByDescending { it.createdAt }
        .toList()
}

data class TimelineItem(
    val key: String,
    val sequence: Int?,
    val label: EventLabel,
    val compensated: Boolean,
    val pending: Boolean,
)

/** Pending (offline) events first, then server events newest first. */
fun buildTimeline(
    events: List<com.nascon.league.data.api.dto.MatchEventDto>,
    pending: List<PendingEvent>,
    teamName: (String?) -> String?,
): List<TimelineItem> {
    val pendingItems = pending.map { TimelineItem("p-" + it.actionId, null, it.label, false, true) }
    val serverItems = events.sortedByDescending { it.sequence }.map {
        TimelineItem("e-" + it.eventId, it.sequence, eventLabel(it.type, it.payload, teamName), it.compensated, false)
    }
    return pendingItems + serverItems
}
