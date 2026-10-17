package com.nascon.league.data.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import kotlin.random.Random

/** Pure routing rule: does [msg] belong to a subscriber of [topic]? */
fun routesTo(topic: String, msg: RealtimeMessage): Boolean =
    if (msg.matchId != null) topic == "match:${msg.matchId}" else true

/** http -> ws, https -> wss, plus /ws?token=... (the result contains the token; never log it). */
internal fun buildSocketUrl(baseUrl: String, token: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    val ws = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
        trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
        else -> trimmed
    }
    return "$ws/ws?token=" + URLEncoder.encode(token, "UTF-8")
}

class OkHttpRealtimeClient(
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String?,
    private val scope: CoroutineScope,
    private val tryRefreshToken: suspend () -> Boolean,
    private val client: OkHttpClient = OkHttpClient(),
) : RealtimeClient {

    private val _state = MutableStateFlow(RealtimeState.DISCONNECTED)
    override val state: StateFlow<RealtimeState> = _state.asStateFlow()

    private val registry = SubscriptionRegistry()
    private val incoming = MutableSharedFlow<RealtimeMessage>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val lock = Any()
    private var loopJob: Job? = null

    @Volatile
    private var socket: WebSocket? = null

    override fun subscribe(topic: String): Flow<RealtimeMessage> = flow {
        register(topic)
        try {
            incoming.filter { routesTo(topic, it) }.collect { emit(it) }
        } finally {
            unregister(topic)
        }
    }

    private fun register(topic: String) {
        val first = registry.add(topic)
        if (first) socket?.send(subscribeFrame(topic))
        synchronized(lock) {
            if (loopJob?.isActive != true) loopJob = scope.launch { runLoop() }
        }
    }

    private fun unregister(topic: String) {
        registry.remove(topic)
        if (registry.isEmpty()) {
            synchronized(lock) {
                loopJob?.cancel()
                loopJob = null
            }
            socket?.cancel()
            socket = null
            _state.value = RealtimeState.DISCONNECTED
        }
    }

    private fun subscribeFrame(topic: String) = "{\"subscribe\":\"$topic\"}"

    private suspend fun runLoop() {
        var attempt = 0
        var refreshed = false
        try {
            while (scope.isActive && !registry.isEmpty()) {
                val token = tokenProvider()
                if (token.isNullOrEmpty()) break
                _state.value = RealtimeState.CONNECTING
                val closed = CompletableDeferred<Int>()
                var opened = false
                val request = try {
                    Request.Builder().url(buildSocketUrl(baseUrlProvider(), token)).build()
                } catch (e: IllegalArgumentException) {
                    break
                }
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened = true
                        _state.value = RealtimeState.CONNECTED
                        registry.activeTopics().forEach { webSocket.send(subscribeFrame(it)) }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        parseFrame(text)?.let { incoming.tryEmit(it) }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        closed.complete(code)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closed.complete(code)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closed.complete(-1)
                    }
                })
                socket = ws
                val code = try {
                    closed.await()
                } finally {
                    ws.cancel()
                    if (socket === ws) socket = null
                }
                _state.value = RealtimeState.DISCONNECTED
                if (opened) {
                    attempt = 0
                    refreshed = false
                }
                if (code == 4401) {
                    if (!refreshed && tryRefreshToken()) {
                        refreshed = true
                        continue
                    }
                    break
                }
                delay(Backoff.delayMs(attempt++, Random.nextDouble()))
            }
        } finally {
            _state.value = RealtimeState.DISCONNECTED
        }
    }
}
