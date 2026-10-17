package com.nascon.league.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LeagueDao {
    @Query("SELECT * FROM leagues ORDER BY sort_index ASC")
    abstract fun observeAll(): Flow<List<LeagueEntity>>

    @Query("SELECT CAST(value AS INTEGER) FROM meta WHERE `key` = :key")
    abstract fun observeUpdated(key: String): Flow<Long?>

    @Query("DELETE FROM leagues")
    abstract suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<LeagueEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putMeta(meta: MetaEntity)

    @Transaction
    open suspend fun replaceAll(items: List<LeagueEntity>, updatedAt: Long) {
        deleteAll()
        insertAll(items)
        putMeta(MetaEntity(MetaKeys.LEAGUES_UPDATED, updatedAt.toString()))
    }
}

@Dao
abstract class FixtureDao {
    @Query("SELECT * FROM fixtures WHERE league_id = :leagueId")
    abstract fun observeByLeague(leagueId: String): Flow<List<FixtureEntity>>

    @Query("SELECT CAST(value AS INTEGER) FROM meta WHERE `key` = :key")
    abstract fun observeUpdated(key: String): Flow<Long?>

    @Query("DELETE FROM fixtures WHERE league_id = :leagueId")
    abstract suspend fun deleteByLeague(leagueId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<FixtureEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putMeta(meta: MetaEntity)

    @Transaction
    open suspend fun replaceForLeague(leagueId: String, items: List<FixtureEntity>, updatedAt: Long) {
        deleteByLeague(leagueId)
        insertAll(items)
        putMeta(MetaEntity(MetaKeys.fixturesUpdated(leagueId), updatedAt.toString()))
    }
}

@Dao
abstract class MatchDao {
    @Query("SELECT * FROM matches WHERE match_id = :matchId")
    abstract fun observe(matchId: String): Flow<MatchEntity?>

    @Query("SELECT * FROM matches WHERE match_id = :matchId")
    abstract suspend fun get(matchId: String): MatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: MatchEntity)

    @Query("SELECT match_id FROM matches ORDER BY updated_at DESC, match_id ASC")
    abstract suspend fun idsNewestFirst(): List<String>

    @Query("DELETE FROM matches WHERE match_id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<String>)

    @Transaction
    open suspend fun saveAndPrune(entity: MatchEntity, keep: Int) {
        upsert(entity)
        val extra = idsNewestFirst().drop(keep)
        if (extra.isNotEmpty()) deleteByIds(extra)
    }
}

@Dao
abstract class StandingsDao {
    @Query("SELECT * FROM standings WHERE league_id = :leagueId")
    abstract fun observe(leagueId: String): Flow<StandingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: StandingsEntity)
}

@Dao
abstract class ClubDao {
    @Query("SELECT * FROM clubs WHERE club_id = :clubId")
    abstract fun observe(clubId: String): Flow<ClubEntity?>

    @Query("SELECT club_id FROM clubs")
    abstract suspend fun cachedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: ClubEntity)
}

@Dao
abstract class SportDao {
    @Query("SELECT * FROM sports WHERE sport_id = :sportId")
    abstract fun observe(sportId: String): Flow<SportEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: SportEntity)
}

@Dao
abstract class ProductDao {
    @Query("SELECT * FROM products ORDER BY sort_index ASC")
    abstract fun observeAll(): Flow<List<ProductEntity>>

    @Query("SELECT CAST(value AS INTEGER) FROM meta WHERE `key` = :key")
    abstract fun observeUpdated(key: String): Flow<Long?>

    @Query("DELETE FROM products")
    abstract suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putMeta(meta: MetaEntity)

    @Transaction
    open suspend fun replaceAll(items: List<ProductEntity>, updatedAt: Long) {
        deleteAll()
        insertAll(items)
        putMeta(MetaEntity(MetaKeys.PRODUCTS_UPDATED, updatedAt.toString()))
    }
}

@Dao
abstract class PendingActionDao {
    @Query("SELECT COUNT(*) FROM pending_actions WHERE owner_user_id = :owner AND status IN ('PENDING','SYNCING')")
    abstract fun observePendingCount(owner: String): Flow<Int>

    @Query(
        "SELECT * FROM pending_actions WHERE owner_user_id = :owner AND entity_key = :entityKey " +
            "AND status IN ('PENDING','SYNCING') ORDER BY created_at DESC",
    )
    abstract fun observeActiveForEntity(owner: String, entityKey: String): Flow<List<PendingActionEntity>>

    @Query(
        "SELECT * FROM pending_actions WHERE owner_user_id = :owner AND type = :type " +
            "AND status = 'PENDING' ORDER BY created_at DESC",
    )
    abstract fun observePendingByType(owner: String, type: String): Flow<List<PendingActionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(entity: PendingActionEntity)

    @Query("SELECT * FROM pending_actions WHERE action_id = :id")
    abstract suspend fun get(id: String): PendingActionEntity?

    /** Oldest first: queued_at, then creation time, then insertion order. */
    @Query(
        "SELECT * FROM pending_actions WHERE owner_user_id = :owner AND status = 'PENDING' " +
            "ORDER BY queued_at ASC, created_at ASC, rowid ASC LIMIT :limit",
    )
    abstract suspend fun pendingForOwner(owner: String, limit: Int): List<PendingActionEntity>

    @Query(
        "SELECT * FROM pending_actions WHERE owner_user_id = :owner AND status IN ('PENDING','SYNCING') " +
            "ORDER BY queued_at ASC, created_at ASC, rowid ASC",
    )
    abstract fun observeActive(owner: String): Flow<List<PendingActionEntity>>

    @Query("SELECT COUNT(*) FROM pending_actions WHERE owner_user_id = :owner AND status = 'PENDING'")
    abstract suspend fun countPending(owner: String): Int

    @Query(
        "SELECT COUNT(*) FROM pending_actions WHERE owner_user_id = :owner AND entity_key = :entityKey " +
            "AND type = :type AND status IN ('PENDING','SYNCING')",
    )
    abstract suspend fun countActiveForEntity(owner: String, entityKey: String, type: String): Int

    @Query("UPDATE pending_actions SET status = 'SYNCING' WHERE action_id IN (:ids)")
    abstract suspend fun markSyncing(ids: List<String>)

    @Query("UPDATE pending_actions SET status = 'PENDING', attempts = attempts + 1 WHERE action_id IN (:ids)")
    abstract suspend fun releaseWithAttempt(ids: List<String>)

    @Query("UPDATE pending_actions SET status = 'PENDING' WHERE action_id IN (:ids)")
    abstract suspend fun releaseNoAttempt(ids: List<String>)

    @Query("UPDATE pending_actions SET status = 'PENDING' WHERE status = 'SYNCING'")
    abstract suspend fun resetSyncing()

    @Query("DELETE FROM pending_actions WHERE action_id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<String>)

    @Transaction
    open suspend fun claim(owner: String, limit: Int): List<PendingActionEntity> {
        val rows = pendingForOwner(owner, limit)
        if (rows.isNotEmpty()) markSyncing(rows.map { it.actionId })
        return rows.map { it.copy(status = ActionStatus.SYNCING) }
    }
}

@Dao
abstract class SyncResultDao {
    @Query(
        "SELECT * FROM sync_results WHERE owner_user_id = :owner " +
            "ORDER BY synced_at DESC, processed_order DESC LIMIT 50",
    )
    abstract fun observe(owner: String): Flow<List<SyncResultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<SyncResultEntity>)

    @Query(
        "DELETE FROM sync_results WHERE owner_user_id = :owner AND action_id NOT IN (" +
            "SELECT action_id FROM sync_results WHERE owner_user_id = :owner " +
            "ORDER BY synced_at DESC, processed_order DESC LIMIT 50)",
    )
    abstract suspend fun trim(owner: String)
}

/** Cache housekeeping: clears every cache table, never pending_actions. */
@Dao
abstract class CacheDao {
    @Query("DELETE FROM leagues") abstract suspend fun deleteLeagues()
    @Query("DELETE FROM fixtures") abstract suspend fun deleteFixtures()
    @Query("DELETE FROM matches") abstract suspend fun deleteMatches()
    @Query("DELETE FROM standings") abstract suspend fun deleteStandings()
    @Query("DELETE FROM clubs") abstract suspend fun deleteClubs()
    @Query("DELETE FROM sports") abstract suspend fun deleteSports()
    @Query("DELETE FROM products") abstract suspend fun deleteProducts()
    @Query("DELETE FROM meta WHERE `key` LIKE 'updated:%'") abstract suspend fun deleteUpdatedMeta()

    @Query("SELECT value FROM meta WHERE `key` = :key")
    abstract fun observeMeta(key: String): Flow<String?>

    @Query("SELECT value FROM meta WHERE `key` = :key")
    abstract suspend fun getMeta(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putMeta(meta: MetaEntity)

    @Query("DELETE FROM meta WHERE `key` = :key")
    abstract suspend fun deleteMeta(key: String)

    @Transaction
    open suspend fun clearCache() {
        deleteLeagues()
        deleteFixtures()
        deleteMatches()
        deleteStandings()
        deleteClubs()
        deleteSports()
        deleteProducts()
        deleteUpdatedMeta()
    }
}
