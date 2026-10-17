package com.nascon.league.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.nascon.league.data.api.LeagueApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ConnectivityMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val apiProvider: () -> LeagueApi,
) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkPresent = MutableStateFlow(hasNetwork())
    private val reachable = MutableStateFlow(true)
    private val syncing = MutableStateFlow(false)
    private val probeTrigger = Channel<Unit>(Channel.CONFLATED)
    private var probeJob: Job? = null

    val state: StateFlow<ConnState> = combine(networkPresent, reachable, syncing) { net, ok, sync ->
        when {
            !net -> ConnState.OFFLINE
            sync -> ConnState.SYNCING
            !ok -> ConnState.SERVER_UNREACHABLE
            else -> ConnState.ONLINE
        }
    }.stateIn(scope, SharingStarted.Eagerly, if (networkPresent.value) ConnState.ONLINE else ConnState.OFFLINE)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkPresent.value = true
            probeTrigger.trySend(Unit)
        }

        override fun onLost(network: Network) {
            networkPresent.value = hasNetwork()
            probeTrigger.trySend(Unit)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            networkPresent.value = true
        }
    }

    init {
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    fun setSyncing(value: Boolean) {
        syncing.value = value
    }

    fun refreshNow() {
        probeTrigger.trySend(Unit)
    }

    fun onForeground() {
        if (probeJob?.isActive == true) return
        probeJob = scope.launch {
            while (isActive) {
                probe()
                withTimeoutOrNull(PROBE_INTERVAL_MS) { probeTrigger.receive() }
            }
        }
    }

    fun onBackground() {
        probeJob?.cancel()
        probeJob = null
    }

    private suspend fun probe() {
        networkPresent.value = hasNetwork()
        if (!networkPresent.value) return
        reachable.value = safeCall { apiProvider().ping() } is NetworkResult.Success
    }

    private fun hasNetwork(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val PROBE_INTERVAL_MS = 15_000L
    }
}
