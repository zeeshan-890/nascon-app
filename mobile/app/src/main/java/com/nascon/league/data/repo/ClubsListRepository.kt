package com.nascon.league.data.repo

import com.nascon.league.core.db.CacheDao
import com.nascon.league.core.db.MetaEntity
import com.nascon.league.core.network.AppJson
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.api.LeagueApi
import com.nascon.league.data.api.dto.ClubSummaryDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.builtins.ListSerializer

/**
 * Clubs list (GET /api/v1/clubs), cached as JSON in the existing meta table. The
 * "updated:" key is wiped by CacheManager.clearCache(), so a different user never sees it.
 */
class ClubsListRepository(
    private val dao: CacheDao,
    private val apiProvider: () -> LeagueApi,
    private val deduper: RefreshDeduper,
    private val isOnline: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val serializer = ListSerializer(ClubSummaryDto.serializer())

    fun observe(): Flow<Cached<List<ClubSummaryDto>>?> =
        combine(dao.observeMeta(KEY_LIST), dao.observeMeta(KEY_UPDATED)) { json, ts ->
            val stamp = ts?.toLongOrNull()
            val list = json?.let {
                try {
                    AppJson.decodeFromString(serializer, it)
                } catch (_: Exception) {
                    null
                }
            }
            if (stamp == null || list == null) null else Cached(list, stamp.toInstant())
        }

    suspend fun refresh(): NetworkResult<Unit> = deduper.run("clubs_list", isOnline) {
        when (val r = safeCall { apiProvider().clubs() }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                dao.putMeta(MetaEntity(KEY_LIST, AppJson.encodeToString(serializer, r.data)))
                dao.putMeta(MetaEntity(KEY_UPDATED, now().toString()))
                NetworkResult.Success(Unit)
            }
        }
    }

    fun observeResource(): Flow<Resource<List<ClubSummaryDto>>> = resourceFlow("clubs_list", observe(), deduper, now)

    companion object {
        const val KEY_LIST = "clubs_list"
        const val KEY_UPDATED = "updated:clubs_list"
    }
}
