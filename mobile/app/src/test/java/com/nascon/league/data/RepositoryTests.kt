package com.nascon.league.data

import com.nascon.league.core.db.CacheDao
import com.nascon.league.core.db.FixtureDao
import com.nascon.league.core.db.FixtureEntity
import com.nascon.league.core.db.MatchDao
import com.nascon.league.core.db.MatchEntity
import com.nascon.league.core.db.MetaEntity
import com.nascon.league.core.db.PendingActionDao
import com.nascon.league.core.db.PendingActionEntity
import com.nascon.league.core.network.ApiError
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.api.LeagueApi
import com.nascon.league.data.api.dto.CheckoutRequest
import com.nascon.league.data.api.dto.CheckoutResponse
import com.nascon.league.data.api.dto.ClubDto
import com.nascon.league.data.api.dto.FixtureDto
import com.nascon.league.data.api.dto.LeagueDto
import com.nascon.league.data.api.dto.LoginRequest
import com.nascon.league.data.api.dto.LoginResponse
import com.nascon.league.data.api.dto.LogoutRequest
import com.nascon.league.data.api.dto.MatchDetailDto
import com.nascon.league.data.api.dto.MatchEventRequest
import com.nascon.league.data.api.dto.MatchEventResponse
import com.nascon.league.data.api.dto.PingResponse
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.api.dto.RefreshRequest
import com.nascon.league.data.api.dto.RefreshResponse
import com.nascon.league.data.api.dto.RosterTransferResponse
import com.nascon.league.data.api.dto.SportConfigDto
import com.nascon.league.data.api.dto.StandingsDto
import com.nascon.league.data.api.dto.SyncRequest
import com.nascon.league.data.api.dto.SyncResponse
import com.nascon.league.data.api.dto.TeamScoreDto
import com.nascon.league.data.repo.CacheManager
import com.nascon.league.data.repo.FixturesRepository
import com.nascon.league.data.repo.MatchRepository
import com.nascon.league.data.repo.PendingActionRepository
import com.nascon.league.data.repo.RefreshDeduper
import com.nascon.league.data.repo.Resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// ---------- fakes ----------

class FakeFixtureDao : FixtureDao() {
    val rows = MutableStateFlow<List<FixtureEntity>>(emptyList())
    val meta = MutableStateFlow<Map<String, Long>>(emptyMap())
    override fun observeByLeague(leagueId: String): Flow<List<FixtureEntity>> =
        rows.map { l -> l.filter { it.leagueId == leagueId } }
    override fun observeUpdated(key: String): Flow<Long?> = meta.map { it[key] }
    override suspend fun deleteByLeague(leagueId: String) { rows.value = rows.value.filter { it.leagueId != leagueId } }
    override suspend fun insertAll(items: List<FixtureEntity>) {
        rows.value = rows.value.filter { r -> items.none { it.matchId == r.matchId } } + items
    }
    override suspend fun putMeta(meta: MetaEntity) { this.meta.value = this.meta.value + (meta.key to meta.value.toLong()) }
}

class FakeMatchDao : MatchDao() {
    val rows = MutableStateFlow<Map<String, MatchEntity>>(emptyMap())
    override fun observe(matchId: String): Flow<MatchEntity?> = rows.map { it[matchId] }
    override suspend fun upsert(entity: MatchEntity) { rows.value = rows.value + (entity.matchId to entity) }
    override suspend fun get(matchId: String): MatchEntity? = rows.value[matchId]
    override suspend fun idsNewestFirst(): List<String> =
        rows.value.values.sortedWith(compareByDescending<MatchEntity> { it.updatedAt }.thenBy { it.matchId }).map { it.matchId }
    override suspend fun deleteByIds(ids: List<String>) { rows.value = rows.value - ids.toSet() }
}

class FakePendingDao : PendingActionDao() {
    val rows = MutableStateFlow<List<PendingActionEntity>>(emptyList())
    override fun observePendingCount(owner: String): Flow<Int> =
        rows.map { l -> l.count { it.ownerUserId == owner && it.status in setOf("PENDING", "SYNCING") } }
    override fun observeActiveForEntity(owner: String, entityKey: String): Flow<List<PendingActionEntity>> =
        rows.map { l ->
            l.filter { it.ownerUserId == owner && it.entityKey == entityKey && it.status in setOf("PENDING", "SYNCING") }
                .sortedByDescending { it.createdAt }
        }
    override fun observePendingByType(owner: String, type: String): Flow<List<PendingActionEntity>> =
        rows.map { l -> l.filter { it.ownerUserId == owner && it.type == type && it.status == "PENDING" } }
    override suspend fun insert(entity: PendingActionEntity) { rows.value = rows.value + entity }
    override fun observeActive(owner: String): Flow<List<PendingActionEntity>> =
        rows.map { l -> l.filter { it.ownerUserId == owner && it.status in setOf("PENDING", "SYNCING") }.sortedBy { it.queuedAt } }
    override suspend fun countPending(owner: String): Int = rows.value.count { it.ownerUserId == owner && it.status == "PENDING" }
    override suspend fun countActiveForEntity(owner: String, entityKey: String, type: String): Int =
        rows.value.count { it.ownerUserId == owner && it.entityKey == entityKey && it.type == type && it.status in setOf("PENDING", "SYNCING") }
    private fun setStatus(ids: List<String>, status: String, bump: Int = 0) {
        rows.value = rows.value.map { if (it.actionId in ids) it.copy(status = status, attempts = it.attempts + bump) else it }
    }
    override suspend fun markSyncing(ids: List<String>) = setStatus(ids, "SYNCING")
    override suspend fun releaseWithAttempt(ids: List<String>) = setStatus(ids, "PENDING", 1)
    override suspend fun releaseNoAttempt(ids: List<String>) = setStatus(ids, "PENDING")
    override suspend fun resetSyncing() { rows.value = rows.value.map { if (it.status == "SYNCING") it.copy(status = "PENDING") else it } }
    override suspend fun deleteByIds(ids: List<String>) { rows.value = rows.value.filter { it.actionId !in ids } }
    override suspend fun pendingForOwner(owner: String, limit: Int): List<PendingActionEntity> =
        rows.value.filter { it.ownerUserId == owner && it.status == "PENDING" }.sortedBy { it.queuedAt }.take(limit)
    override suspend fun get(id: String): PendingActionEntity? = rows.value.firstOrNull { it.actionId == id }
}

/** Clears only the fixture fake (a cache table); pending rows live elsewhere, as in the real DB. */
class FakeCacheDao(private val fixtures: FakeFixtureDao) : CacheDao() {
    val meta = HashMap<String, String>()
    var clears = 0
    override suspend fun deleteLeagues() {}
    override suspend fun deleteFixtures() { fixtures.rows.value = emptyList() }
    override suspend fun deleteMatches() {}
    override suspend fun deleteStandings() {}
    override suspend fun deleteClubs() {}
    override suspend fun deleteSports() {}
    override suspend fun deleteProducts() {}
    override suspend fun deleteUpdatedMeta() { fixtures.meta.value = emptyMap() }
    override suspend fun getMeta(key: String): String? = meta[key]
    override fun observeMeta(key: String): kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.flowOf(meta[key])
    override suspend fun putMeta(meta: MetaEntity) { this.meta[meta.key] = meta.value }
    override suspend fun deleteMeta(key: String) { meta.remove(key) }
    override suspend fun clearCache() { clears++; super.clearCache() }
}

class FakeApi : LeagueApi {
    var fixturesCalls = 0
    var matchCalls = 0
    var fixturesImpl: suspend (String) -> List<FixtureDto> = { emptyList() }
    var matchImpl: suspend (String) -> MatchDetailDto = { matchDto(it) }

    override suspend fun fixtures(leagueId: String): List<FixtureDto> { fixturesCalls++; return fixturesImpl(leagueId) }
    override suspend fun match(id: String): MatchDetailDto { matchCalls++; return matchImpl(id) }

    override suspend fun login(body: LoginRequest): LoginResponse = error("unused")
    override suspend fun refresh(body: RefreshRequest): RefreshResponse = error("unused")
    override suspend fun logout(body: LogoutRequest): JsonObject = error("unused")
    override suspend fun ping(): PingResponse = error("unused")
    override suspend fun leagues(): List<LeagueDto> = error("unused")
    override suspend fun postEvent(matchId: String, body: MatchEventRequest): MatchEventResponse = error("unused")
    override suspend fun sport(id: String): SportConfigDto = error("unused")
    override suspend fun standings(leagueId: String): StandingsDto = error("unused")
    override suspend fun club(id: String): ClubDto = error("unused")
    override suspend fun clubs(): List<com.nascon.league.data.api.dto.ClubSummaryDto> = error("unused")
    override suspend fun rosterTransfer(clubId: String, body: JsonObject): RosterTransferResponse = error("unused")
    override suspend fun products(): List<ProductDto> = error("unused")
    override suspend fun checkout(body: CheckoutRequest): CheckoutResponse = error("unused")
    override suspend fun receiptPdf(orderId: String): ResponseBody = error("unused")
    override suspend fun sync(body: SyncRequest): SyncResponse = error("unused")
}

fun matchDto(id: String) = MatchDetailDto(
    matchId = id, leagueId = "L1", home = TeamScoreDto("h"), away = TeamScoreDto("a"),
)

fun fx(id: String, league: String = "L1", t: Long = 1L) = FixtureEntity(
    id, league, "h", "Home", "a", "Away", "scheduled", null, null, null, null, 1, t,
)

fun fxDto(id: String) = FixtureDto(matchId = id, homeTeamId = "h", awayTeamId = "a")

fun pending(
    id: String, owner: String = "u1", status: String = "PENDING", type: String = "score_event",
    payload: String = """{"match_id":"m1"}""", entity: String = "m1", created: Long = 1L,
) = PendingActionEntity(id, type, "2026-01-01T00:00:00Z", 1, payload, entity, "label", owner, status, createdAt = created)

// ---------- tests ----------

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryTests {


    @Test
    fun cacheFirstThenLoadingThenFresh() = runTest {
        val dao = FakeFixtureDao()
        dao.replaceForLeague("L1", listOf(fx("old")), 1_000L)
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi().apply { fixturesImpl = { gate.await(); listOf(fxDto("new")) } }
        val repo = FixturesRepository(dao, { api }, RefreshDeduper(backgroundScope), { true }, { 1_000L })
        val seen = mutableListOf<Resource<List<com.nascon.league.domain.model.Fixture>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.observeResource("L1").collect { seen.add(it) } }
        runCurrent()
        assertTrue(seen.first() is Resource.Success)
        assertEquals(listOf("old"), (seen.first() as Resource.Success).data.map { it.matchId })
        val job = launch { repo.refresh("L1") }
        runCurrent()
        assertTrue(seen.last() is Resource.Loading)
        assertEquals(listOf("old"), (seen.last() as Resource.Loading).cached!!.map { it.matchId })
        gate.complete(Unit)
        job.join()
        runCurrent()
        val last = seen.last() as Resource.Success
        assertEquals(listOf("new"), last.data.map { it.matchId })
    }

    @Test
    fun failedRefreshKeepsCacheAndSurfacesError() = runTest {
        val dao = FakeFixtureDao()
        dao.replaceForLeague("L1", listOf(fx("old")), 1_000L)
        val api = FakeApi().apply { fixturesImpl = { throw IOException("boom") } }
        val repo = FixturesRepository(dao, { api }, RefreshDeduper(backgroundScope), { true }, { 1_000L })
        val seen = mutableListOf<Resource<List<com.nascon.league.domain.model.Fixture>>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.observeResource("L1").collect { seen.add(it) } }
        runCurrent()
        val result = repo.refresh("L1")
        runCurrent()
        assertTrue(result is NetworkResult.Failure)
        assertEquals(listOf("old"), dao.rows.value.map { it.matchId })
        val err = seen.last() as Resource.Error
        assertEquals(listOf("old"), err.cached!!.map { it.matchId })
        assertEquals(ApiError.NoConnection, err.error)
    }

    @Test
    fun neverLoadedLeagueIsNullAndEmptyLoadedLeagueIsCachedEmpty() = runTest {
        val dao = FakeFixtureDao()
        val api = FakeApi()
        val repo = FixturesRepository(dao, { api }, RefreshDeduper(backgroundScope), { true }, { 5L })
        assertNull(repo.observe("L1").first())
        repo.refresh("L1")
        val c = repo.observe("L1").first()
        assertEquals(emptyList<Any>(), c!!.data)
    }

    @Test
    fun fixtureReplacementIsPerLeagueAndRemovesDisappeared() = runTest {
        val dao = FakeFixtureDao()
        dao.replaceForLeague("L1", listOf(fx("a"), fx("b")), 1L)
        dao.replaceForLeague("L2", listOf(fx("z", "L2")), 1L)
        val api = FakeApi().apply { fixturesImpl = { listOf(fxDto("b"), fxDto("c")) } }
        FixturesRepository(dao, { api }, RefreshDeduper(backgroundScope), { true }, { 2L }).refresh("L1")
        assertEquals(setOf("b", "c", "z"), dao.rows.value.map { it.matchId }.toSet())
    }

    @Test
    fun logoutClearsCacheButKeepsPending() = runTest {
        val fixtures = FakeFixtureDao().apply { replaceForLeague("L1", listOf(fx("a")), 1L) }
        val pend = FakePendingDao().apply { insert(pending("p1")) }
        val manager = CacheManager(FakeCacheDao(fixtures))
        manager.onLogout()
        assertTrue(fixtures.rows.value.isEmpty())
        assertEquals(1, pend.rows.value.size)
    }

    @Test
    fun loginDifferentUserClearsSameUserKeeps() = runTest {
        val fixtures = FakeFixtureDao().apply { replaceForLeague("L1", listOf(fx("a")), 1L) }
        val cacheDao = FakeCacheDao(fixtures)
        val manager = CacheManager(cacheDao)
        manager.onLogin("u1") // no owner stored yet -> clears, stores owner
        assertTrue(fixtures.rows.value.isEmpty())
        fixtures.replaceForLeague("L1", listOf(fx("a")), 1L)
        manager.onLogin("u1")
        assertEquals(1, fixtures.rows.value.size)
        manager.onLogin("u2")
        assertTrue(fixtures.rows.value.isEmpty())
        assertEquals("u2", cacheDao.meta["cache_owner_user_id"])
    }

    @Test
    fun pendingCountCountsPendingAndSyncingForCurrentOwnerOnly() = runTest {
        val dao = FakePendingDao()
        listOf(
            pending("1", status = "PENDING"), pending("2", status = "SYNCING"), pending("3", status = "APPLIED"),
            pending("4", status = "REJECTED"), pending("5", status = "DUPLICATE"), pending("6", owner = "other"),
        ).forEach { dao.insert(it) }
        var user: String? = "u1"
        val repo = PendingActionRepository(dao) { user }
        assertEquals(2, repo.observePendingCount().first())
        user = null
        assertEquals(0, repo.observePendingCount().first())
    }

    @Test
    fun pendingScoreEventsFilteredByMatchOwnerStatusAndMalformedIgnored() = runTest {
        val dao = FakePendingDao()
        listOf(
            pending("ok", payload = """{"match_id":"m1","type":"x"}"""),
            pending("otherMatch", payload = """{"match_id":"m2"}"""),
            pending("otherOwner", owner = "u2"),
            pending("syncing", status = "SYNCING"),
            pending("wrongType", type = "roster_transfer"),
            pending("bad", payload = "not json {"),
            pending("noField", payload = """{"x":1}"""),
        ).forEach { dao.insert(it) }
        val repo = PendingActionRepository(dao) { "u1" }
        assertEquals(listOf("ok"), repo.observePendingScoreEvents("m1").first().map { it.actionId })
        assertEquals(listOf("ok"), repo.observeForEntity("m1").first().filter { it.actionId == "ok" }.map { it.actionId })
    }

    @Test
    fun pruneKeepsFiftyNewest() = runTest {
        val dao = FakeMatchDao()
        var clock = 0L
        val api = FakeApi()
        val repo = MatchRepository(dao, { api }, RefreshDeduper(backgroundScope), { true }, { ++clock })
        for (i in 1..55) repo.refresh("m$i")
        assertEquals(50, dao.rows.value.size)
        assertTrue(dao.rows.value.keys.containsAll((6..55).map { "m$it" }))
        assertTrue("m1" !in dao.rows.value.keys)
    }

    @Test
    fun simultaneousRefreshesShareOneApiCall() = runTest {
        val dao = FakeFixtureDao()
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi().apply { fixturesImpl = { gate.await(); listOf(fxDto("a")) } }
        val repo = FixturesRepository(dao, { api }, RefreshDeduper(backgroundScope), { true }, { 1L })
        val r1 = async { repo.refresh("L1") }
        val r2 = async { repo.refresh("L1") }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, api.fixturesCalls)
        assertTrue(r1.await() is NetworkResult.Success)
        assertTrue(r2.await() is NetworkResult.Success)
    }

    @Test
    fun offlineRefreshDoesNotCallApi() = runTest {
        val api = FakeApi()
        val repo = FixturesRepository(FakeFixtureDao(), { api }, RefreshDeduper(backgroundScope), { false }, { 1L })
        val result = repo.refresh("L1")
        assertEquals(NetworkResult.Failure(ApiError.NoConnection), result)
        assertEquals(0, api.fixturesCalls)
    }
}
