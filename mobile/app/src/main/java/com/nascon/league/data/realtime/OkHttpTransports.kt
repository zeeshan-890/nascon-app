package com.nascon.league.data.realtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private fun memberToken(token: String?): String? =
    token?.takeIf { it.isNotEmpty() && !it.startsWith("guest_") }

private fun baseBuilder(base: String): HttpUrl.Builder =
    base.trim().trimEnd('/').toHttpUrl().newBuilder()

class WebSocketTransport(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String?,
    private val client: OkHttpClient,
) : MatchTransport {
    override val kind: TransportKind = TransportKind.WEBSOCKET

    override fun connect(matchId: String): Flow<TransportEvent> = callbackFlow {
        val builder = baseBuilder(baseUrlProvider())
            .addPathSegment("ws").addPathSegment("matches").addPathSegment(matchId)
        memberToken(tokenProvider())?.let { builder.addQueryParameter("token", it) }
        val request = Request.Builder().url(builder.build()).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(TransportEvent.Opened)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                LiveFrameParser.parse(text)?.let { trySend(TransportEvent.Message(it)) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                close(IOException("websocket closing code=$code"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(IOException("websocket closed code=$code"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(IOException("websocket failure status=${response?.code}", t))
            }
        })
        awaitClose { ws.cancel() }
    }
}

class SseTransport(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String?,
    private val client: OkHttpClient,
) : MatchTransport {
    override val kind: TransportKind = TransportKind.SSE

    override fun connect(matchId: String): Flow<TransportEvent> = channelFlow {
        val url = baseBuilder(baseUrlProvider())
            .addPathSegments("api/v1/matches").addPathSegment(matchId).addPathSegment("stream")
            .build()
        val rb = Request.Builder().url(url).header("Accept", "text/event-stream")
        tokenProvider()?.takeIf { it.isNotEmpty() }?.let { rb.header("Authorization", "Bearer $it") }
        val call = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build().newCall(rb.build())
        coroutineContext.job.invokeOnCompletion { call.cancel() }
        withContext(Dispatchers.IO) {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("sse http status=${response.code}")
                send(TransportEvent.Opened)
                val source = (response.body ?: throw IOException("sse empty body")).source()
                val dataLines = ArrayList<String>()
                var size = 0
                var overflow = false
                var lastId: String? = null
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> {
                            if (!overflow) {
                                LiveFrameParser.parseSse(dataLines)?.let { m ->
                                    val out = if (m.id == null && lastId != null) m.copy(id = lastId) else m
                                    send(TransportEvent.Message(out))
                                }
                            }
                            dataLines.clear(); size = 0; overflow = false
                        }
                        line.startsWith(":") -> Unit
                        line.startsWith("data:") -> {
                            val v = line.substring(5).removePrefix(" ")
                            size += v.length + 1
                            if (size > LiveFrameParser.MAX_MESSAGE_CHARS) {
                                overflow = true; dataLines.clear()
                            } else if (!overflow) dataLines.add(v)
                        }
                        line.startsWith("id:") -> lastId = line.substring(3).removePrefix(" ").ifEmpty { null }
                        else -> Unit // event:, retry:, unknown fields
                    }
                }
            }
        }
        throw IOException("sse stream ended")
    }
}

class PollingTransport(
    private val fetch: suspend (matchId: String) -> LiveMessage?,
    private val intervalMs: Long = 1000,
    private val onDelay: suspend (Long) -> Unit = { delay(it) },
) : MatchTransport {
    override val kind: TransportKind = TransportKind.POLLING

    override fun connect(matchId: String): Flow<TransportEvent> = flow {
        emit(TransportEvent.Opened)
        while (true) {
            val m = fetch(matchId)
            if (m != null) emit(TransportEvent.Message(m))
            onDelay(intervalMs)
        }
    }
}

object ScorePollMapper {
    fun fromScoreJson(json: JsonObject): LiveMessage? {
        val matchId = (json["match_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val vp = json["version"] as? JsonPrimitive ?: return null
        val version = vp.content.toLongOrNull() ?: return null
        return LiveMessage(
            id = "poll-$matchId-$version",
            sequence = version,
            timestampMs = null,
            type = "match.snapshot",
            matchId = matchId,
            data = json,
        )
    }
}
