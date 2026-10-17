package com.nascon.league.data.sync

import com.nascon.league.core.db.PendingActionEntity
import com.nascon.league.core.network.AppJson
import com.nascon.league.data.api.dto.SyncActionDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object PayloadBuilders {
    /** score_event payload: {event_id, match_id, type, payload}. */
    fun scoreEvent(eventId: String, matchId: String, type: String, payload: JsonObject): JsonObject =
        JsonObject(
            mapOf(
                "event_id" to JsonPrimitive(eventId),
                "match_id" to JsonPrimitive(matchId),
                "type" to JsonPrimitive(type),
                "payload" to payload,
            ),
        )

    /**
     * roster_transfer payload. [fields] carries league_id, player_id, type, from_squad_id, to_squad_id?,
     * effective_date, loan_end_date?, reason, requested_by. request_id is added; to_squad_id is dropped for
     * release and loan_end_date for anything but loan.
     */
    fun rosterTransfer(requestId: String, fields: JsonObject): JsonObject {
        val type = (fields["type"] as? JsonPrimitive)?.contentOrNull
        val out = LinkedHashMap<String, JsonElement>(fields)
        out["request_id"] = JsonPrimitive(requestId)
        if (type == "release") out.remove("to_squad_id")
        if (type != "loan") out.remove("loan_end_date")
        return JsonObject(out)
    }

    fun toDto(row: PendingActionEntity): SyncActionDto = SyncActionDto(
        actionId = row.actionId,
        type = row.type,
        queuedAt = row.queuedAt,
        baseVersion = row.baseVersion.toLong(),
        payload = parseObject(row.payloadJson),
    )

    fun parseObject(json: String): JsonObject = try {
        AppJson.parseToJsonElement(json) as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }

    fun string(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
}
