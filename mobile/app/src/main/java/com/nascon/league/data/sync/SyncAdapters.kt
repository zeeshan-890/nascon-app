package com.nascon.league.data.sync

import androidx.room.withTransaction
import com.nascon.league.core.db.AppDatabase
import com.nascon.league.core.db.ClubDao
import com.nascon.league.core.db.MatchDao
import com.nascon.league.core.db.PendingActionDao
import com.nascon.league.core.db.PendingActionEntity
import com.nascon.league.core.db.SyncResultDao
import com.nascon.league.core.db.SyncResultEntity
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.core.security.TokenStore
import com.nascon.league.core.config.ServerConfigStore
import com.nascon.league.core.time.Clock
import com.nascon.league.data.api.LeagueApi
import com.nascon.league.data.api.dto.SyncRequest
import com.nascon.league.data.api.dto.SyncResponse
import com.nascon.league.data.repo.AuthRepository
import com.nascon.league.data.repo.ClubRepository
import com.nascon.league.data.repo.FixturesRepository
import com.nascon.league.data.repo.LeaguesRepository
import com.nascon.league.data.repo.MatchRepository
import com.nascon.league.data.repo.SessionRepository
import com.nascon.league.data.repo.StandingsRepository
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Room-backed queue. */
class RoomSyncStore(
    private val db: AppDatabase,
    private val pending: PendingActionDao = db.pendingActionDao(),
    private val results: SyncResultDao = db.syncResultDao(),
) : SyncStore {
    override suspend fun resetSyncing() = pending.resetSyncing()

    override suspend fun claim(owner: String, limit: Int): List<PendingActionEntity> = pending.claim(owner, limit)

    override suspend fun release(ids: List<String>, countAttempt: Boolean) {
        if (ids.isEmpty()) return
        if (countAttempt) pending.releaseWithAttempt(ids) else pending.releaseNoAttempt(ids)
    }

    override suspend fun settle(
        owner: String,
        deleteIds: List<String>,
        results: List<SyncResultEntity>,
        releaseIds: List<String>,
    ) {
        db.withTransaction {
            if (deleteIds.isNotEmpty()) pending.deleteByIds(deleteIds)
            if (results.isNotEmpty()) {
                this.results.insertAll(results)
                this.results.trim(owner)
            }
            if (releaseIds.isNotEmpty()) pending.releaseWithAttempt(releaseIds)
        }
    }

    override suspend fun insert(row: PendingActionEntity) = pending.insert(row)
    override suspend fun countPending(owner: String): Int = pending.countPending(owner)
    override suspend fun countActiveForEntity(owner: String, entityKey: String, type: String): Int =
        pending.countActiveForEntity(owner, entityKey, type)

    override fun observeActive(owner: String): Flow<List<PendingActionEntity>> = pending.observeActive(owner)
    override fun observeResults(owner: String): Flow<List<SyncResultEntity>> = results.observe(owner)
}

class RoomMatchCache(private val dao: MatchDao) : MatchCache {
    override suspend fun matchVersion(matchId: String): Int? = dao.get(matchId)?.version
    override suspend fun leagueId(matchId: String): String? = dao.get(matchId)?.leagueId
}

class RetrofitSyncApi(private val apiProvider: () -> LeagueApi) : SyncApiPort {
    override suspend fun send(request: SyncRequest): NetworkResult<SyncResponse> =
        safeCall { apiProvider().sync(request) }
}

class AppSyncEnvironment(
    private val sessionRepository: SessionRepository,
    private val tokenStore: TokenStore,
    private val configStore: ServerConfigStore,
    private val authRepository: AuthRepository,
    private val online: () -> Boolean,
) : SyncEnvironment {
    override fun isOnline() = online()
    override fun hasToken() = tokenStore.accessToken() != null
    override fun currentUserId(): String? = sessionRepository.session.value?.user?.userId
    override fun currentTenantId(): String? = sessionRepository.session.value?.user?.tenantId
    override suspend fun deviceId(): String = configStore.deviceId()
    override suspend fun refreshAuth() = authRepository.refreshIfNeeded()
    override fun nowUtc(): String = Clock.nowUtc()
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun newId(): String = UUID.randomUUID().toString()
}

/** After a sync, pull server truth for everything the batch touched. Failures are ignored (cache stays as is). */
class RepositoryCacheRefresher(
    private val leagues: LeaguesRepository,
    private val fixtures: FixturesRepository,
    private val matches: MatchRepository,
    private val standings: StandingsRepository,
    private val clubs: ClubRepository,
    private val clubDao: ClubDao,
) : CacheRefresher {
    override suspend fun refresh(affected: AffectedEntities) {
        affected.matchIds.forEach { matches.refresh(it) }
        affected.leagueIds.forEach { league ->
            fixtures.refresh(league)
            if (affected.scoreChanged) standings.refresh(league)
        }
        if (affected.rosterChanged) clubDao.cachedIds().forEach { clubs.refresh(it) }
        leagues.refresh()
    }
}
