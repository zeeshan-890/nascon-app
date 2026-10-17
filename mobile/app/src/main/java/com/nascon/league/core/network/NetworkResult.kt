package com.nascon.league.core.network

import com.nascon.league.data.api.dto.ErrorEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
    val raw = try {
        e.response()?.errorBody()?.string()
    } catch (_: Exception) {
        null
    }
    val parsed = raw?.let {
        try {
            AppJson.decodeFromString<ErrorEnvelope>(it).error
        } catch (_: Exception) {
            null
        }
    }
    return if (parsed != null) {
        ApiError.Http(status, parsed.code, parsed.message, parsed.details)
    } else {
        ApiError.Http(status, "HTTP_$status", "")
    }
}
