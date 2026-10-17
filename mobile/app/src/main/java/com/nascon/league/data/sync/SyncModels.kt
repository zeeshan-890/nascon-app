package com.nascon.league.data.sync

import com.nascon.league.core.db.PendingActionEntity
import com.nascon.league.core.db.SyncResultEntity
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.data.api.dto.SyncRequest
import com.nascon.league.data.api.dto.SyncResponse
import kotlinx.coroutines.flow.Flow

// ---- Public contract (used by UI agents) ----

enum class SyncPhase { IDLE, SYNCING }

data class SyncSummary(val applied: Int, val rejected: Int, val duplicate: Int)

data class SyncUiState(
    val phase: SyncPhase = SyncPhase.IDLE,
    val lastSummary: SyncSummary? = null,
    val needsLogin: Boolean = false,
    val lastError: String? = null,
)

sealed interface SyncEvent {
    data object AllSynced : SyncEvent
    data class Finished(val summary: SyncSummary) : SyncEvent
    data object NeedsLogin : SyncEvent
    data class Failed(val message: String) : SyncEvent
}

/** What a sync run did, for WorkManager. */
enum class SyncOutcome { SKIPPED, DONE, RETRY, NEEDS_LOGIN }

const val ACTION_SCORE_EVENT = "score_event"
const val ACTION_ROSTER_TRANSFER = "roster_transfer"
const val SYNC_CHUNK_SIZE = 100

// ---- Ports (small interfaces so the engine runs on a plain JVM) ----

interface SyncStore {
    suspend fun resetSyncing()

    /** Atomically selects the owner's PENDING rows (oldest first, at most [limit]) and marks them SYNCING. */
    suspend fun claim(owner: String, limit: Int): List<PendingActionEntity>

    /** Puts rows back to PENDING; [countAttempt] increments attempts. */
    suspend fun release(ids: List<String>, countAttempt: Boolean)

    /** One transaction: delete acknowledged rows, store results (keeping 50 per owner), release [releaseIds] with attempts+1. */
    suspend fun settle(owner: String, deleteIds: List<String>, results: List<SyncResultEntity>, releaseIds: List<String>)

    suspend fun insert(row: PendingActionEntity)
    suspend fun countPending(owner: String): Int
    suspend fun countActiveForEntity(owner: String, entityKey: String, type: String): Int
    fun observeActive(owner: String): Flow<List<PendingActionEntity>>
    fun observeResults(owner: String): Flow<List<SyncResultEntity>>
}

interface MatchCache {
    suspend fun matchVersion(matchId: String): Int?
    suspend fun leagueId(matchId: String): String?
}

interface SyncApiPort {
    suspend fun send(request: SyncRequest): NetworkResult<SyncResponse>
}

interface SyncEnvironment {
    fun isOnline(): Boolean
    fun hasToken(): Boolean
    fun currentUserId(): String?
    fun currentTenantId(): String?
    suspend fun deviceId(): String

    /** Try to renew the access token (called once after a 401). */
    suspend fun refreshAuth()
    fun nowUtc(): String
    fun nowMillis(): Long
    fun newId(): String
}

/** Entities touched by a processed batch; the refresher updates caches to server truth. */
data class AffectedEntities(
    val matchIds: Set<String>,
    val leagueIds: Set<String>,
    val playerIds: Set<String>,
    val scoreChanged: Boolean,
    val rosterChanged: Boolean,
) {
    val isEmpty: Boolean get() = matchIds.isEmpty() && leagueIds.isEmpty() && playerIds.isEmpty()
}

interface CacheRefresher {
    suspend fun refresh(affected: AffectedEntities)
}

interface SyncScheduling {
    fun schedule()
}
