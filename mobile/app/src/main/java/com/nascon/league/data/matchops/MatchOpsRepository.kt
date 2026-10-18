package com.nascon.league.data.matchops

import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.AppJson
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.util.WeakHashMap

/**
 * What this device already knows to have changed on the server. Used until the cached match catches up
 * (always in demo mode, where the demo match cache is separate from the demo match-operations backend).
 */
data class MatchOverride(
    val status: String? = null,
    val scheduledAt: String? = null,
    /** product id -> true when pinned, false when unpinned. */
    val pinned: Map<String, Boolean> = emptyMap(),
)

sealed interface ScheduleResult {
    data class Saved(val scheduledAt: String) : ScheduleResult
    data class Conflicts(val conflicts: List<ConflictDto>) : ScheduleResult
    data class Failed(val error: ApiError) : ScheduleResult
}

/** Online-only match operations. Nothing here is queued: offline calls fail with [ApiError.NoConnection]. */
class MatchOpsRepository(
    private val api: () -> MatchOpsApi,
    private val refreshMatch: suspend (String) -> NetworkResult<Unit>,
    private val refreshFixtures: suspend (String) -> NetworkResult<Unit>,
    private val isOnline: () -> Boolean,
    /** True in demo mode: the cache cannot confirm changes, so overrides stay. */
    private val keepOverrides: () -> Boolean = { false },
) {
    private val _overrides = MutableStateFlow<Map<String, MatchOverride>>(emptyMap())
    val overrides: StateFlow<Map<String, MatchOverride>> = _overrides.asStateFlow()

    private fun edit(matchId: String, f: (MatchOverride) -> MatchOverride) {
        _overrides.value = _overrides.value.toMutableMap().also { it[matchId] = f(it[matchId] ?: MatchOverride()) }
    }

    private suspend fun afterChange(matchId: String, leagueId: String?) {
        val refreshed = refreshMatch(matchId)
        if (leagueId != null) refreshFixtures(leagueId)
        if (refreshed is NetworkResult.Success && !keepOverrides()) {
            _overrides.value = _overrides.value - matchId
        }
    }

    private fun offline(): NetworkResult.Failure? =
        if (isOnline()) null else NetworkResult.Failure(ApiError.NoConnection)

    suspend fun changeStatus(matchId: String, leagueId: String?, to: String): NetworkResult<Unit> {
        offline()?.let { return it }
        return when (val r = safeCall { api().changeStatus(matchId, StatusRequest(to)) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                edit(matchId) { it.copy(status = r.data.status.ifBlank { to }) }
                afterChange(matchId, leagueId)
                NetworkResult.Success(Unit)
            }
        }
    }

    suspend fun check(matchId: String, scheduledAtIso: String): NetworkResult<List<ConflictDto>> {
        offline()?.let { return it }
        return when (val r = safeCall { api().checkSchedule(matchId, ScheduleRequest(scheduledAtIso)) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> NetworkResult.Success(r.data.conflicts)
        }
    }

    /** Checks first, then saves. The venue is never sent, so the server keeps the match's current venue. */
    suspend fun reschedule(matchId: String, leagueId: String?, scheduledAtIso: String): ScheduleResult {
        offline()?.let { return ScheduleResult.Failed(ApiError.NoConnection) }
        when (val c = check(matchId, scheduledAtIso)) {
            is NetworkResult.Failure -> return ScheduleResult.Failed(c.error)
            is NetworkResult.Success -> if (c.data.isNotEmpty()) return ScheduleResult.Conflicts(c.data)
        }
        return when (val r = safeCall { api().reschedule(matchId, ScheduleRequest(scheduledAtIso)) }) {
            is NetworkResult.Failure -> conflictsFrom(r.error)?.let { ScheduleResult.Conflicts(it) } ?: ScheduleResult.Failed(r.error)
            is NetworkResult.Success -> {
                val saved = r.data.scheduledAt.ifBlank { scheduledAtIso }
                edit(matchId) { it.copy(scheduledAt = saved) }
                afterChange(matchId, leagueId)
                ScheduleResult.Saved(saved)
            }
        }
    }

    suspend fun pin(matchId: String, leagueId: String?, productId: String): NetworkResult<Unit> {
        offline()?.let { return it }
        return when (val r = safeCall { api().pin(matchId, PinRequest(productId)) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                edit(matchId) { it.copy(pinned = it.pinned + (productId to true)) }
                afterChange(matchId, leagueId)
                NetworkResult.Success(Unit)
            }
        }
    }

    suspend fun unpin(matchId: String, leagueId: String?, productId: String): NetworkResult<Unit> {
        offline()?.let { return it }
        return when (val r = safeCall { api().unpin(matchId, productId) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                edit(matchId) { it.copy(pinned = it.pinned + (productId to false)) }
                afterChange(matchId, leagueId)
                NetworkResult.Success(Unit)
            }
        }
    }

    companion object {
        /** The server answers a save into a clashing slot with 409 SCHEDULE_CONFLICT and the list in details. */
        fun conflictsFrom(error: ApiError): List<ConflictDto>? {
            val http = error as? ApiError.Http ?: return null
            if (http.code != "SCHEDULE_CONFLICT") return null
            val el = http.details?.get("conflicts") ?: return emptyList()
            return runCatching { AppJson.decodeFromJsonElement(ListSerializer(ConflictDto.serializer()), el) }
                .getOrDefault(emptyList())
        }

        /** Pins as shown: cached pins plus what this device just pinned, minus what it just unpinned. */
        fun effectivePinned(cached: Set<String>, override: MatchOverride?): Set<String> {
            val o = override?.pinned.orEmpty()
            return (cached + o.filterValues { it }.keys) - o.filterValues { !it }.keys
        }
    }
}

private val holders = WeakHashMap<AppContainer, MatchOpsRepository>()

/** Real Retrofit service on a live server, the in-memory demo backend in demo mode. */
fun AppContainer.matchOpsApi(): MatchOpsApi =
    service(MatchOpsApi::class.java) { hn -> MatchOpsFakeHolder.get(hn) }

/** One repository per container so every match-admin screen shares the same override state. */
fun AppContainer.matchOpsRepository(): MatchOpsRepository = synchronized(holders) {
    holders.getOrPut(this) {
        val c = this
        MatchOpsRepository(
            api = { c.matchOpsApi() },
            refreshMatch = { c.matchRepository.refresh(it) },
            refreshFixtures = { c.fixturesRepository.refresh(it) },
            isOnline = { c.hasNetwork() },
            keepOverrides = { c.isDemoMode },
        )
    }
}
