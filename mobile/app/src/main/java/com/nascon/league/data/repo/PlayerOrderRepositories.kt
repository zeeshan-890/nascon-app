package com.nascon.league.data.repo

import com.nascon.league.core.db.CacheDao
import com.nascon.league.core.db.MetaEntity
import com.nascon.league.core.network.AppJson
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.api.LeagueApi
import com.nascon.league.data.api.dto.OrderDetailDto
import com.nascon.league.data.api.dto.PlayerHistoryDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.KSerializer

/** Shared cache-as-JSON-in-meta behaviour for one keyed document. */
internal class MetaDocCache<T>(
    private val dao: CacheDao,
    private val serializer: KSerializer<T>,
) {
    fun observe(key: String, updatedKey: String): Flow<Cached<T>?> =
        combine(dao.observeMeta(key), dao.observeMeta(updatedKey)) { json, ts ->
            val stamp = ts?.toLongOrNull()
            val doc = json?.let {
                try {
                    AppJson.decodeFromString(serializer, it)
                } catch (_: Exception) {
                    null
                }
            }
            if (stamp == null || doc == null) null else Cached(doc, stamp.toInstant())
        }

    suspend fun store(key: String, updatedKey: String, value: T, now: Long) {
        dao.putMeta(MetaEntity(key, AppJson.encodeToString(serializer, value)))
        dao.putMeta(MetaEntity(updatedKey, now.toString()))
    }
}

class PlayerHistoryRepository(
    dao: CacheDao,
    private val apiProvider: () -> LeagueApi,
    private val deduper: RefreshDeduper,
    private val isOnline: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = MetaDocCache(dao, PlayerHistoryDto.serializer())

    fun observe(playerId: String): Flow<Cached<PlayerHistoryDto>?> =
        cache.observe(key(playerId), updatedKey(playerId))

    suspend fun refresh(playerId: String): NetworkResult<Unit> = deduper.run(key(playerId), isOnline) {
        when (val r = safeCall { apiProvider().playerHistory(playerId) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                cache.store(key(playerId), updatedKey(playerId), r.data, now())
                NetworkResult.Success(Unit)
            }
        }
    }

    fun observeResource(playerId: String): Flow<Resource<PlayerHistoryDto>> =
        resourceFlow(key(playerId), observe(playerId), deduper, now)

    companion object {
        fun key(playerId: String) = "player_history:$playerId"
        fun updatedKey(playerId: String) = "updated:player_history:$playerId"
    }
}

class OrderRepository(
    dao: CacheDao,
    private val apiProvider: () -> LeagueApi,
    private val deduper: RefreshDeduper,
    private val isOnline: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cache = MetaDocCache(dao, OrderDetailDto.serializer())

    fun observe(orderId: String): Flow<Cached<OrderDetailDto>?> =
        cache.observe(key(orderId), updatedKey(orderId))

    suspend fun refresh(orderId: String): NetworkResult<Unit> = deduper.run(key(orderId), isOnline) {
        when (val r = safeCall { apiProvider().order(orderId) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                cache.store(key(orderId), updatedKey(orderId), r.data, now())
                NetworkResult.Success(Unit)
            }
        }
    }

    fun observeResource(orderId: String): Flow<Resource<OrderDetailDto>> =
        resourceFlow(key(orderId), observe(orderId), deduper, now)

    companion object {
        fun key(orderId: String) = "order:$orderId"
        fun updatedKey(orderId: String) = "updated:order:$orderId"
    }
}
