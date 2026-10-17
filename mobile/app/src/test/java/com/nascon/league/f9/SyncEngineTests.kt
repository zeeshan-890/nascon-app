package com.nascon.league.f9

import com.nascon.league.core.db.ActionStatus
import com.nascon.league.core.db.PendingActionEntity
import com.nascon.league.core.db.SyncResultEntity
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.api.dto.SyncRequest
import com.nascon.league.data.api.dto.SyncResponse
import com.nascon.league.data.api.dto.SyncResultDto
import com.nascon.league.data.sync.AffectedEntities
import com.nascon.league.data.sync.CacheRefresher
import com.nascon.league.data.sync.MatchCache
import com.nascon.league.data.sync.PayloadBuilders
import com.nascon.league.data.sync.SyncApiPort
import com.nascon.league.data.sync.SyncEnvironment
import com.nascon.league.data.sync.SyncEvent
import com.nascon.league.data.sync.SyncOutcome
import com.nascon.league.data.sync.SyncRepository
import com.nascon.league.data.sync.SyncScheduling
import com.nascon.league.data.sync.SyncStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore : SyncStore {
    val rows = MutableStateFlow<List<PendingActionEntity>>(emptyList())
    val results = MutableStateFlow<List<SyncResultEntity>>(emptyList())

    fun add(row: PendingActionEntity) { rows.value = rows.value + row }
    private fun ordered(l: List<PendingActionEntity>) =
        l.withIndex().sortedWith(compareBy({ it.value.queuedAt }, { it.value.createdAt }, { it.index })).map { it.value }

    override suspend fun resetSyncing() {
        rows.value = rows.value.map { if (it.status == ActionStatus.SYNCING) it.copy(status = ActionStatus.PENDING) else it }
    }

    override suspend fun claim(owner: String, limit: Int): List<PendingActionEntity> {
        val chosen = ordered(rows.value.filter { it.ownerUserId == owner && it.status == ActionStatus.PENDING }).take(limit)
        val ids = chosen.map { it.actionId }.toSet()
        rows.value = rows.value.map { if (it.actionId in ids) it.copy(status = ActionStatus.SYNCING) else it }
        return chosen
    }

    override suspend fun release(ids: List<String>, countAttempt: Boolean) {
        rows.value = rows.value.map {
            if (it.actionId in ids) it.copy(status = ActionStatus.PENDING, attempts = it.attempts + if (countAttempt) 1 else 0) else it
        }
    }

    override suspend fun settle(owner: String, deleteIds: List<String>, results: List<SyncResultEntity>, releaseIds: List<String>) {
        rows.value = rows.value.filter { it.actionId !in deleteIds }
        this.results.value = (this.results.value + results).takeLast(50)
        release(releaseIds, true)
    }

    override suspend fun insert(row: PendingActionEntity) = add(row)
    override suspend fun countPending(owner: String) =
        rows.value.count { it.ownerUserId == owner && it.status == ActionStatus.PENDING }
    override suspend fun countActiveForEntity(owner: String, entityKey: String, type: String) =
        rows.value.count { it.ownerUserId == owner && it.entityKey == entityKey && it.type == type }
    override fun observeActive(owner: String): Flow<List<PendingActionEntity>> =
        rows.map { l -> ordered(l.filter { it.ownerUserId == owner }) }
    override fun observeResults(owner: String): Flow<List<SyncResultEntity>> = results
}

private class FakeEnv : SyncEnvironment {
    var online = true
    var token = true
    var user: String? = "u1"
    var refreshCalls = 0
    private var n = 0
    private var ms = 1000L
    override fun isOnline() = online
    override fun hasToken() = token
    override fun currentUserId() = user
    override fun currentTenantId() = "t1"
    override suspend fun deviceId() = "dev-1"
    override suspend fun refreshAuth() { refreshCalls++ }
    var queuedAt = "2026-10-17T10:00:00Z"
    override fun nowUtc() = queuedAt
    override fun nowMillis() = ms++
    override fun newId() = "id-${n++}"
}

private class FakeApi(val respond: (SyncRequest) -> NetworkResult<SyncResponse>) : SyncApiPort {
    val requests = mutableListOf<SyncRequest>()
    var latencyMs = 0L
    override suspend fun send(request: SyncRequest): NetworkResult<SyncResponse> {
        requests += request
        if (latencyMs > 0) delay(latencyMs)
        return respond(request)
    }
}

private class FakeMatches(val version: Int? = 4) : MatchCache {
    override suspend fun matchVersion(matchId: String) = version
    override suspend fun leagueId(matchId: String) = "L1"
}

private class FakeRefresher : CacheRefresher {
    val calls = mutableListOf<AffectedEntities>()
    override suspend fun refresh(affected: AffectedEntities) { calls += affected }
}

private class FakeScheduler : SyncScheduling {
    var count = 0
    override fun schedule() { count++ }
}

private fun row(
    id: String,
    queuedAt: String = "2026-10-17T10:00:00Z",
    createdAt: Long = 1,
    owner: String = "u1",
    status: String = ActionStatus.PENDING,
    key: String = "m1",
    type: String = "score_event",
) = PendingActionEntity(
    actionId = id, type = type, queuedAt = queuedAt, baseVersion = 1, payloadJson = """{"match_id":"$key"}""",
    entityKey = key, label = "label $id", ownerUserId = owner, status = status, createdAt = createdAt,
)

private fun ok(vararg r: SyncResultDto) = NetworkResult.Success(SyncResponse(results = r.toList()))
private fun applied(id: String, order: Int) = SyncResultDto(id, "applied", "appended", null, order, 9)
private fun http(status: Int, code: String = "X") = NetworkResult.Failure(ApiError.Http(status, code, ""))

private class Rig(
    val scope: CoroutineScope,
    val store: FakeStore = FakeStore(),
    val env: FakeEnv = FakeEnv(),
    val refresher: FakeRefresher = FakeRefresher(),
    val scheduler: FakeScheduler = FakeScheduler(),
    matches: MatchCache = FakeMatches(),
    respond: (SyncRequest) -> NetworkResult<SyncResponse> = { req -> ok(*req.actions.mapIndexed { i, a -> applied(a.actionId, i) }.toTypedArray()) },
    chunk: Int = 100,
) {
    val api = FakeApi(respond)
    val repo = SyncRepository(store, matches, api, env, refresher, scheduler, scope, debounceMs = 300, chunkSize = chunk)
}

class SyncEngineTests {
    @Test fun ordersByQueuedAtThenCreationOrder() = runTest {
        val r = Rig(this)
        r.store.add(row("c", queuedAt = "2026-10-17T10:00:05Z", createdAt = 1))
        r.store.add(row("b", queuedAt = "2026-10-17T10:00:00Z", createdAt = 3))
        r.store.add(row("a", queuedAt = "2026-10-17T10:00:00Z", createdAt = 2))
        r.repo.syncNow()
        assertEquals(listOf("a", "b", "c"), r.api.requests.single().actions.map { it.actionId })
        assertEquals("dev-1", r.api.requests.single().deviceId)
    }

    @Test fun syncingRowsAreResetAtStart() = runTest {
        val r = Rig(this)
        r.store.add(row("a", status = ActionStatus.SYNCING))
        r.repo.resetInterrupted()
        assertEquals(ActionStatus.PENDING, r.store.rows.value.single().status)
        r.repo.syncNow()
        assertEquals(1, r.api.requests.size)
        assertTrue(r.store.rows.value.isEmpty())
    }

    @Test fun concurrentCallersSendOneRequest() = runTest {
        val r = Rig(this)
        r.api.latencyMs = 100
        r.store.add(row("a"))
        List(4) { async { r.repo.syncNow() } }.awaitAll()
        assertEquals(1, r.api.requests.size)
    }

    @Test fun resultsMappedByActionIdNotPosition() = runTest {
        val r = Rig(this, respond = { req ->
            val ids = req.actions.map { it.actionId }
            ok(
                SyncResultDto(ids[2], "duplicate", "skipped_duplicate", null, 3, 5),
                SyncResultDto(ids[0], "applied", "appended", null, 1, 6),
                SyncResultDto(ids[1], "rejected", null, "SQUAD_FULL", 2, 7),
            )
        })
        r.store.add(row("a", createdAt = 1)); r.store.add(row("b", createdAt = 2)); r.store.add(row("c", createdAt = 3))
        r.repo.syncNow()
        val byId = r.store.results.value.associateBy { it.actionId }
        assertEquals("applied", byId["a"]!!.status)
        assertEquals("rejected", byId["b"]!!.status)
        assertEquals("SQUAD_FULL", byId["b"]!!.reasonCode)
        assertEquals("duplicate", byId["c"]!!.status)
        assertEquals("label a", byId["a"]!!.label)
        assertTrue(r.store.rows.value.isEmpty())
        val summary = r.repo.state.value.lastSummary!!
        assertEquals(1, summary.applied); assertEquals(1, summary.rejected); assertEquals(1, summary.duplicate)
        assertEquals(1, r.refresher.calls.size)
    }

    @Test fun networkFailureKeepsRowsAndRetries() = runTest {
        for (failure in listOf<NetworkResult<SyncResponse>>(
            NetworkResult.Failure(ApiError.NoConnection), NetworkResult.Failure(ApiError.Timeout), http(503), http(429),
        )) {
            val r = Rig(this, respond = { failure })
            r.store.add(row("a"))
            val outcome = r.repo.syncNowForWorker()
            assertEquals(SyncOutcome.RETRY, outcome)
            val kept = r.store.rows.value.single()
            assertEquals(ActionStatus.PENDING, kept.status)
            assertEquals(1, kept.attempts)
            assertTrue(r.store.results.value.isEmpty())
        }
    }

    @Test fun successDeletesAndEmitsAllSynced() = runTest {
        val r = Rig(this)
        val events = mutableListOf<SyncEvent>()
        val job = launchCollect(r, events)
        r.store.add(row("a"))
        r.repo.syncNow()
        advanceUntilIdle()
        assertTrue(r.store.rows.value.isEmpty())
        assertTrue(events.any { it is SyncEvent.AllSynced })
        job.cancel()
    }

    @Test fun rejectionStoredWithReasonAndNoAllSynced() = runTest {
        val r = Rig(this, respond = { req -> ok(SyncResultDto(req.actions[0].actionId, "rejected", null, "MATCH_NOT_IN_PROGRESS", 1, 2)) })
        val events = mutableListOf<SyncEvent>()
        val job = launchCollect(r, events)
        r.store.add(row("a"))
        r.repo.syncNow()
        advanceUntilIdle()
        assertEquals("MATCH_NOT_IN_PROGRESS", r.store.results.value.single().reasonCode)
        assertFalse(events.any { it is SyncEvent.AllSynced })
        job.cancel()
    }

    @Test fun otherUsersActionsAreNeverSent() = runTest {
        val r = Rig(this)
        r.store.add(row("mine", owner = "u1"))
        r.store.add(row("theirs", owner = "u2"))
        r.repo.syncNow()
        assertEquals(listOf("mine"), r.api.requests.single().actions.map { it.actionId })
        assertEquals(listOf("theirs"), r.store.rows.value.map { it.actionId })
        assertEquals(emptyList<String>(), r.repo.observePending().first().map { it.actionId })
    }

    @Test fun offlineOrNoTokenDoesNotTouchQueue() = runTest {
        val r = Rig(this)
        r.store.add(row("a"))
        r.env.online = false
        r.repo.syncNow()
        r.env.online = true; r.env.token = false
        r.repo.syncNow()
        assertEquals(0, r.api.requests.size)
        assertEquals(ActionStatus.PENDING, r.store.rows.value.single().status)
        assertEquals(0, r.store.rows.value.single().attempts)
    }

    @Test fun unauthorizedRefreshesOnceThenNeedsLogin() = runTest {
        val r = Rig(this, respond = { http(401, "UNAUTHENTICATED") })
        r.store.add(row("a"))
        val outcome = r.repo.syncNowForWorker()
        assertEquals(SyncOutcome.NEEDS_LOGIN, outcome)
        assertEquals(1, r.env.refreshCalls)
        assertEquals(2, r.api.requests.size)
        assertEquals(ActionStatus.PENDING, r.store.rows.value.single().status)
        assertTrue(r.repo.state.value.needsLogin)
    }

    @Test fun envelope400RejectsBatchWithoutRetry() = runTest {
        val r = Rig(this, respond = { http(400, "VALIDATION_ERROR") })
        r.store.add(row("a")); r.store.add(row("b", createdAt = 2))
        val outcome = r.repo.syncNowForWorker()
        assertEquals(SyncOutcome.DONE, outcome)
        assertTrue(r.store.rows.value.isEmpty())
        assertTrue(r.store.results.value.all { it.status == "rejected" })
        assertEquals(2, r.store.results.value.size)
    }

    @Test fun chunksOfConfiguredSize() = runTest {
        val r = Rig(this, chunk = 2)
        (1..5).forEach { r.store.add(row("a$it", createdAt = it.toLong())) }
        r.repo.syncNow()
        assertEquals(listOf(2, 2, 1), r.api.requests.map { it.actions.size })
    }

    @Test fun missingResultIsKeptNotDeleted() = runTest {
        val r = Rig(this, respond = { req -> ok(applied(req.actions[0].actionId, 1)) })
        r.store.add(row("a", createdAt = 1)); r.store.add(row("b", createdAt = 2))
        assertEquals(SyncOutcome.RETRY, r.repo.syncNowForWorker())
        assertEquals(listOf("b"), r.store.rows.value.map { it.actionId })
    }

    @Test fun debouncedTriggersCallSyncOnce() = runTest {
        val r = Rig(this)
        r.store.add(row("a"))
        r.repo.requestSync(); advanceTimeBy(100)
        r.repo.requestSync(); advanceTimeBy(100)
        r.repo.requestSync()
        advanceUntilIdle()
        assertEquals(1, r.api.requests.size)
    }

    @Test fun enqueueScoreEventBuildsPayloadAndBaseVersion() = runTest {
        val r = Rig(this, matches = FakeMatches(version = 4))
        r.env.online = false
        val payload = buildJsonObject { put("team_id", "t9"); put("event_type", "alpha") }
        val id1 = r.repo.enqueueScoreEvent("m1", "score", payload, "Alpha")
        val id2 = r.repo.enqueueScoreEvent("m1", "score", payload, "Alpha")
        val rows = r.store.rows.value.associateBy { it.actionId }
        assertEquals(4, rows[id1]!!.baseVersion)
        assertEquals(5, rows[id2]!!.baseVersion)
        val body = PayloadBuilders.parseObject(rows[id1]!!.payloadJson)
        assertEquals(setOf("event_id", "match_id", "type", "payload"), body.keys)
        assertEquals("m1", (body["match_id"] as JsonPrimitive).content)
        assertEquals(payload, body["payload"])
        assertEquals("u1", rows[id1]!!.ownerUserId)
        assertEquals("m1", rows[id1]!!.entityKey)
        assertTrue(r.scheduler.count >= 2)
    }

    @Test fun enqueueRosterTransferPayload() = runTest {
        val r = Rig(this)
        r.env.online = false
        val fields = buildJsonObject {
            put("league_id", "L1"); put("player_id", "p1"); put("type", "release"); put("from_squad_id", "s1")
            put("to_squad_id", "s2"); put("effective_date", "2026-10-20"); put("reason", "r"); put("requested_by", "u1")
        }
        val id = r.repo.enqueueRosterTransfer("p1", fields, "Release Ali", 7)
        val row = r.store.rows.value.single { it.actionId == id }
        val body = PayloadBuilders.parseObject(row.payloadJson)
        assertEquals(7, row.baseVersion)
        assertFalse(body.containsKey("to_squad_id"))
        assertFalse(body.containsKey("loan_end_date"))
        assertTrue(body.containsKey("request_id"))
        assertEquals("p1", row.entityKey)
        assertEquals("roster_transfer", row.type)
    }

    private fun kotlinx.coroutines.test.TestScope.launchCollect(r: Rig, into: MutableList<SyncEvent>) =
        backgroundScope.let { s ->
            s.async(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { r.repo.events.collect { into += it } }
        }
}
