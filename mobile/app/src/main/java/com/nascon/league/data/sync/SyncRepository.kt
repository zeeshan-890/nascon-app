package com.nascon.league.data.sync

import com.nascon.league.core.db.ActionStatus
import com.nascon.league.core.db.PendingActionEntity
import com.nascon.league.core.db.SyncResultEntity
import com.nascon.league.core.db.SyncResultStatus
import com.nascon.league.core.error.ErrorMapper
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.AppJson
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.api.dto.SyncRequest
import com.nascon.league.data.api.dto.SyncResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger

/** Offline queue + sync engine. One code path for every score/roster action, online or offline. */
class SyncRepository(
    private val store: SyncStore,
    private val matches: MatchCache,
    private val api: SyncApiPort,
    private val env: SyncEnvironment,
    private val refresher: CacheRefresher,
    private val scheduler: SyncScheduling,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 300L,
    private val chunkSize: Int = SYNC_CHUNK_SIZE,
) {
    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state

    private val _events = MutableSharedFlow<SyncEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SyncEvent> = _events

    private val syncMutex = Mutex()
    private val enqueueMutex = Mutex()
    private val triggerGeneration = AtomicInteger(0)

    fun observePending(): Flow<List<PendingActionEntity>> = flow {
        val owner = env.currentUserId()
        if (owner == null) emit(emptyList()) else emitAll(store.observeActive(owner))
    }

    fun observePendingCount(): Flow<Int> = observePending().map { it.size }

    fun observeResults(): Flow<List<SyncResultEntity>> = flow {
        val owner = env.currentUserId()
        if (owner == null) emit(emptyList()) else emitAll(store.observeResults(owner))
    }

    suspend fun enqueueScoreEvent(matchId: String, type: String, payload: JsonObject, label: String): String =
        enqueueMutex.withLock {
            val owner = requireOwner()
            val queued = store.countActiveForEntity(owner, matchId, ACTION_SCORE_EVENT)
            val baseVersion = (matches.matchVersion(matchId) ?: 0) + queued
            val body = PayloadBuilders.scoreEvent(env.newId(), matchId, type, payload)
            insertAndTrigger(owner, ACTION_SCORE_EVENT, baseVersion, body, matchId, label)
        }

    suspend fun enqueueRosterTransfer(
        playerId: String,
        requestFields: JsonObject,
        label: String,
        baseVersion: Int,
    ): String = enqueueMutex.withLock {
        val owner = requireOwner()
        val body = PayloadBuilders.rosterTransfer(env.newId(), requestFields)
        insertAndTrigger(owner, ACTION_ROSTER_TRANSFER, baseVersion, body, playerId, label)
    }

    private suspend fun insertAndTrigger(
        owner: String,
        type: String,
        baseVersion: Int,
        body: JsonObject,
        entityKey: String,
        label: String,
    ): String {
        val actionId = env.newId()
        store.insert(
            PendingActionEntity(
                actionId = actionId,
                type = type,
                queuedAt = env.nowUtc(),
                baseVersion = baseVersion,
                payloadJson = AppJson.encodeToString(JsonObject.serializer(), body),
                entityKey = entityKey,
                label = label,
                ownerUserId = owner,
                status = ActionStatus.PENDING,
                createdAt = env.nowMillis(),
                tenantId = env.currentTenantId(),
            ),
        )
        scheduler.schedule()
        requestSync()
        return actionId
    }

    private fun requireOwner(): String =
        env.currentUserId() ?: throw IllegalStateException("No signed-in user")

    /** Debounced fire-and-forget: a burst of triggers yields one syncNow. */
    fun requestSync() {
        val generation = triggerGeneration.incrementAndGet()
        scope.launch {
            delay(debounceMs)
            if (triggerGeneration.get() == generation) {
                try {
                    syncNow()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Rows stay queued; WorkManager retries.
                }
            }
        }
    }

    /** Call at app start: anything left SYNCING by a crash goes back to PENDING. */
    suspend fun resetInterrupted() = store.resetSyncing()

    /** Connectivity became ONLINE: schedule the unique job and sync once if anything waits. */
    suspend fun onOnline() {
        val owner = env.currentUserId() ?: return
        if (store.countPending(owner) > 0) {
            scheduler.schedule()
            requestSync()
        }
    }

    suspend fun syncNow() {
        syncNowForWorker()
    }

    suspend fun syncNowForWorker(): SyncOutcome = syncMutex.withLock {
        if (!env.isOnline() || !env.hasToken()) return@withLock SyncOutcome.SKIPPED
        val owner = env.currentUserId() ?: return@withLock SyncOutcome.SKIPPED
        // Under the mutex nothing is in flight, so any SYNCING row is a leftover.
        store.resetSyncing()
        if (store.countPending(owner) == 0) return@withLock SyncOutcome.SKIPPED
        _state.value = _state.value.copy(phase = SyncPhase.SYNCING, needsLogin = false, lastError = null)
        try {
            runChunks(owner)
        } finally {
            _state.value = _state.value.copy(phase = SyncPhase.IDLE)
        }
    }

    private class Totals {
        var applied = 0
        var rejected = 0
        var duplicate = 0
        val processed = mutableListOf<PendingActionEntity>()
        val statusById = mutableMapOf<String, String>()
        fun summary() = SyncSummary(applied, rejected, duplicate)
    }

    private suspend fun runChunks(owner: String): SyncOutcome {
        val totals = Totals()
        var outcome = SyncOutcome.DONE
        var authRetried = false
        while (true) {
            val batch = store.claim(owner, chunkSize)
            if (batch.isEmpty()) break
            val ids = batch.map { it.actionId }
            var settled = false
            try {
                val request = SyncRequest(env.deviceId(), batch.map(PayloadBuilders::toDto))
                var result = api.send(request)
                if (result is NetworkResult.Failure && result.error.isHttp(401) && !authRetried) {
                    authRetried = true
                    env.refreshAuth()
                    result = api.send(request)
                }
                when (result) {
                    is NetworkResult.Success -> {
                        val clean = applyResponse(owner, batch, result.data, totals)
                        settled = true
                        if (!clean) {
                            outcome = SyncOutcome.RETRY
                            break
                        }
                    }
                    is NetworkResult.Failure -> {
                        val error = result.error
                        when {
                            error.isHttp(401) -> {
                                store.release(ids, countAttempt = false)
                                settled = true
                                _state.value = _state.value.copy(needsLogin = true)
                                _events.tryEmit(SyncEvent.NeedsLogin)
                                outcome = SyncOutcome.NEEDS_LOGIN
                            }
                            error.isEnvelopeRejection() -> {
                                rejectBatch(owner, batch, totals)
                                settled = true
                                val message = ErrorMapper.forCode("VALIDATION_ERROR")
                                _state.value = _state.value.copy(lastError = message)
                                _events.tryEmit(SyncEvent.Failed(message))
                                continue
                            }
                            else -> {
                                store.release(ids, countAttempt = true)
                                settled = true
                                val message = ErrorMapper.message(error)
                                _state.value = _state.value.copy(lastError = message)
                                if (error is ApiError.Http) _events.tryEmit(SyncEvent.Failed(message))
                                outcome = if (error.isRetryable()) SyncOutcome.RETRY else SyncOutcome.DONE
                            }
                        }
                        break
                    }
                }
            } finally {
                if (!settled) {
                    // Cancelled or crashed mid-request: never leave rows stuck in SYNCING.
                    withContext(NonCancellable) { store.release(ids, countAttempt = false) }
                }
            }
        }
        finish(owner, totals, outcome)
        return outcome
    }

    /** @return true when every claimed action got a result. */
    private suspend fun applyResponse(
        owner: String,
        batch: List<PendingActionEntity>,
        response: SyncResponse,
        totals: Totals,
    ): Boolean {
        val byId = response.results.associateBy { it.actionId }
        val now = env.nowMillis()
        val results = mutableListOf<SyncResultEntity>()
        val delete = mutableListOf<String>()
        val release = mutableListOf<String>()
        for (row in batch) {
            val r = byId[row.actionId]
            if (r == null) {
                release += row.actionId
                continue
            }
            val status = when (r.status.lowercase()) {
                SyncResultStatus.APPLIED -> SyncResultStatus.APPLIED
                SyncResultStatus.DUPLICATE -> SyncResultStatus.DUPLICATE
                else -> SyncResultStatus.REJECTED
            }
            when (status) {
                SyncResultStatus.APPLIED -> totals.applied++
                SyncResultStatus.DUPLICATE -> totals.duplicate++
                else -> totals.rejected++
            }
            results += SyncResultEntity(
                actionId = row.actionId,
                ownerUserId = owner,
                type = row.type,
                label = row.label,
                status = status,
                resolution = r.resolution,
                reasonCode = if (status == SyncResultStatus.REJECTED) r.errorCode else null,
                serverVersion = r.serverVersion,
                processedOrder = r.processedOrder,
                syncedAt = now,
            )
            delete += row.actionId
            totals.processed += row
            totals.statusById[row.actionId] = status
        }
        store.settle(owner, delete, results, release)
        return release.isEmpty()
    }

    /** HTTP 400 for the whole envelope: reject the batch with a generic reason, no endless retry. */
    private suspend fun rejectBatch(owner: String, batch: List<PendingActionEntity>, totals: Totals) {
        val now = env.nowMillis()
        val results = batch.mapIndexed { i, row ->
            SyncResultEntity(
                actionId = row.actionId,
                ownerUserId = owner,
                type = row.type,
                label = row.label,
                status = SyncResultStatus.REJECTED,
                resolution = null,
                reasonCode = "VALIDATION_ERROR",
                serverVersion = 0,
                processedOrder = i,
                syncedAt = now,
            )
        }
        store.settle(owner, batch.map { it.actionId }, results, emptyList())
        totals.rejected += batch.size
        totals.processed += batch
        batch.forEach { totals.statusById[it.actionId] = SyncResultStatus.REJECTED }
    }

    private suspend fun finish(owner: String, totals: Totals, outcome: SyncOutcome) {
        if (totals.processed.isEmpty()) return
        val summary = totals.summary()
        _state.value = _state.value.copy(lastSummary = summary)
        runCatching { refresher.refresh(affectedOf(totals.processed)) }
            .onFailure { if (it is CancellationException) throw it }
        _events.tryEmit(SyncEvent.Finished(summary))
        if (outcome == SyncOutcome.DONE && summary.rejected == 0 && store.countPending(owner) == 0) {
            _events.tryEmit(SyncEvent.AllSynced)
        }
    }

    private suspend fun affectedOf(rows: List<PendingActionEntity>): AffectedEntities {
        val matchIds = mutableSetOf<String>()
        val leagueIds = mutableSetOf<String>()
        val playerIds = mutableSetOf<String>()
        var score = false
        var roster = false
        for (row in rows) {
            when (row.type) {
                ACTION_SCORE_EVENT -> {
                    score = true
                    matchIds += row.entityKey
                    matches.leagueId(row.entityKey)?.takeIf { it.isNotBlank() }?.let { leagueIds += it }
                }
                ACTION_ROSTER_TRANSFER -> {
                    roster = true
                    playerIds += row.entityKey
                    PayloadBuilders.string(PayloadBuilders.parseObject(row.payloadJson), "league_id")
                        ?.takeIf { it.isNotBlank() }?.let { leagueIds += it }
                }
            }
        }
        return AffectedEntities(matchIds, leagueIds, playerIds, score, roster)
    }

    private fun ApiError.isHttp(status: Int) = this is ApiError.Http && this.status == status

    private fun ApiError.isEnvelopeRejection() = this is ApiError.Http && (status == 400 || status == 422)

    private fun ApiError.isRetryable(): Boolean = when (this) {
        is ApiError.Http -> status == 429 || status >= 500
        else -> true
    }
}
