package com.nascon.league.core.network

import com.nascon.league.data.api.dto.ErrorEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>
    data class Failure(val error: ApiError) : NetworkResult<Nothing>
}

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

suspend fun <T> safeCall(block: suspend () -> T): NetworkResult<T> = try {
    NetworkResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    NetworkResult.Failure(mapThrowable(e))
}

fun mapThrowable(e: Throwable): ApiError = when (e) {
    is HttpException -> mapHttp(e)
    is SocketTimeoutException -> ApiError.Timeout
    is ConnectException, is UnknownHostException -> ApiError.ServerUnreachable
    is IOException -> ApiError.NoConnection
    is SerializationException -> ApiError.Unexpected(e)
    else -> ApiError.Unexpected(e)
}

private fun mapHttp(e: HttpException): ApiError.Http {
    val status = e.code()
    val retryHeader = try {
        e.response()?.headers()?.get("Retry-After")
    } catch (_: Exception) {
        null
    }
    val raw = try {
        e.response()?.errorBody()?.string()
    } catch (_: Exception) {
        null
    }
    val parsed = parseErrorBody(raw)
    val (details, retryAfter) = mergeRetryAfter(parsed?.details, retryHeader)
    val code = parsed?.code?.takeIf { it.isNotBlank() } ?: when (status) {
        401 -> "UNAUTHENTICATED"
        403 -> "FORBIDDEN"
        429 -> "RATE_LIMITED"
        else -> "HTTP_$status"
    }
    return ApiError.Http(status, code, parsed?.message.orEmpty(), details, retryAfter)
}

private data class ParsedError(val code: String, val message: String, val details: JsonObject?)

/** Strict envelope first; a loose read keeps the code when `details` is not an object. */
private fun parseErrorBody(raw: String?): ParsedError? {
    if (raw.isNullOrBlank()) return null
    try {
        val body = AppJson.decodeFromString<ErrorEnvelope>(raw).error
        return ParsedError(body.code, body.message, body.details)
    } catch (_: Exception) {
        // fall through
    }
    return try {
        val root = AppJson.parseToJsonElement(raw) as? JsonObject ?: return null
        val err = root["error"] as? JsonObject ?: return null
        val code = (err["code"] as? JsonPrimitive)?.contentOrNull ?: return null
        val message = (err["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val details = err["details"] as? JsonObject
        ParsedError(code, message, details)
    } catch (_: Exception) {
        null
    }
}

private fun mergeRetryAfter(details: JsonObject?, header: String?): Pair<JsonObject?, Int?> {
    val fromHeader = header?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
    val fromBody = listOf("retry_after_seconds", "retry_after", "retryAfter", "retryAfterSeconds")
        .firstNotNullOfOrNull { key -> (details?.get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } }
    val seconds = fromHeader ?: fromBody ?: return details to null
    val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    details?.forEach { (k, v) -> if (v !is JsonNull) map[k] = v }
    val prim = JsonPrimitive(seconds)
    for (key in listOf("retry_after", "retryAfter", "retryAfterSeconds", "retry_after_seconds")) {
        if (map[key] == null) map[key] = prim
    }
    return JsonObject(map) to seconds
}
