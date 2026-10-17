package com.nascon.league.data.repo

import com.nascon.league.core.db.CacheDao
import com.nascon.league.core.db.MetaEntity
import com.nascon.league.core.db.MetaKeys
import com.nascon.league.core.db.PendingActionDao
import com.nascon.league.core.db.PendingActionEntity
import com.nascon.league.core.network.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

const val ACTION_TYPE_SCORE_EVENT = "score_event"

class PendingActionRepository(
    private val dao: PendingActionDao,
    private val currentUserId: () -> String?,
) {
    fun observePendingCount(): Flow<Int> = flow {
        val owner = currentUserId()
        if (owner == null) emit(0) else emitAll(dao.observePendingCount(owner))
    }

    fun observeForEntity(entityKey: String): Flow<List<PendingActionEntity>> = flow {
        val owner = currentUserId()
        if (owner == null) emit(emptyList()) else emitAll(dao.observeActiveForEntity(owner, entityKey))
    }

    fun observePendingScoreEvents(matchId: String): Flow<List<PendingActionEntity>> = flow {
        val owner = currentUserId()
        if (owner == null) {
            emit(emptyList())
        } else {
            emitAll(
                dao.observePendingByType(owner, ACTION_TYPE_SCORE_EVENT).map { rows ->
                    rows.filter { it.ownerUserId == owner && it.status == "PENDING" && payloadMatchId(it.payloadJson) == matchId }
                },
            )
        }
    }

    private fun payloadMatchId(json: String): String? = try {
        ((AppJson.parseToJsonElement(json) as? JsonObject)?.get("match_id") as? JsonPrimitive)?.contentOrNull
    } catch (_: Exception) {
        null
    }
}

class CacheManager(private val dao: CacheDao) {
    /** Clears every cache table; pending_actions are never touched. */
    suspend fun clearCache() = dao.clearCache()

    suspend fun onLogin(userId: String) {
        if (dao.getMeta(MetaKeys.CACHE_OWNER) != userId) {
            dao.clearCache()
            dao.putMeta(MetaEntity(MetaKeys.CACHE_OWNER, userId))
        }
    }

    suspend fun onLogout() {
        dao.clearCache()
        dao.deleteMeta(MetaKeys.CACHE_OWNER)
    }
}
