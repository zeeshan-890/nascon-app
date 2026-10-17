package com.nascon.league.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SyncActionDto(
    @SerialName("action_id") val actionId: String,
    @SerialName("type") val type: String,
    @SerialName("queued_at") val queuedAt: String,
    @SerialName("base_version") val baseVersion: Long = 0,
    @SerialName("payload") val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SyncRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("actions") val actions: List<SyncActionDto> = emptyList(),
)

@Serializable
data class SyncResultDto(
    @SerialName("action_id") val actionId: String,
    @SerialName("status") val status: String,
    @SerialName("resolution") val resolution: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("processed_order") val processedOrder: Int = 0,
    @SerialName("server_version") val serverVersion: Long = 0,
    @SerialName("signature") val signature: String? = null,
)

@Serializable
data class SyncSummaryDto(
    @SerialName("applied") val applied: Int = 0,
    @SerialName("rejected") val rejected: Int = 0,
    @SerialName("duplicate") val duplicate: Int = 0,
)

@Serializable
data class SyncResponse(
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("results") val results: List<SyncResultDto> = emptyList(),
    @SerialName("summary") val summary: SyncSummaryDto = SyncSummaryDto(),
)
