package com.nascon.league.data.repo

import com.nascon.league.core.db.ClubDao
import com.nascon.league.core.db.ClubEntity
import com.nascon.league.core.db.FixtureDao
import com.nascon.league.core.db.FixtureEntity
import com.nascon.league.core.db.LeagueDao
import com.nascon.league.core.db.LeagueEntity
import com.nascon.league.core.db.MatchDao
import com.nascon.league.core.db.MatchEntity
import com.nascon.league.core.db.MetaKeys
import com.nascon.league.core.db.ProductDao
import com.nascon.league.core.db.ProductEntity
import com.nascon.league.core.db.SportDao
import com.nascon.league.core.db.SportEntity
import com.nascon.league.core.db.StandingsDao
import com.nascon.league.core.db.StandingsEntity
import com.nascon.league.core.network.AppJson
import com.nascon.league.core.network.NetworkResult
import com.nascon.league.core.network.safeCall
import com.nascon.league.data.api.LeagueApi
import com.nascon.league.data.api.dto.ClubDto
import com.nascon.league.data.api.dto.MatchDetailDto
import com.nascon.league.data.api.dto.ProductDto
import com.nascon.league.data.api.dto.SportConfigDto
import com.nascon.league.data.api.dto.StandingsDto
import com.nascon.league.domain.model.Fixture
import com.nascon.league.domain.model.League
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer

const val MATCH_CACHE_LIMIT = 50

private fun <T> decodeOrNull(serializer: KSerializer<T>, json: String): T? =
    try {
        AppJson.decodeFromString(serializer, json)
    } catch (_: Exception) {
        null
    }

private fun <E, T> Flow<E?>.mapDoc(read: (E) -> Pair<T?, Long>): Flow<Cached<T>?> = map { e ->
    if (e == null) {
        null
    } else {
        val (value, ts) = read(e)
        if (value == null) null else Cached(value, ts.toInstant())
    }
}

class LeaguesRepository(
    private val dao: LeagueDao,
    private val apiProvider: () -> LeagueApi,
    private val deduper: RefreshDeduper,
    private val isOnline: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observe(): Flow<Cached<List<League>>?> = combine(dao.observeAll(), dao.observeUpdated(MetaKeys.LEAGUES_UPDATED)) { rows, ts ->
        if (ts == null) null else Cached(rows.map { League(it.leagueId, it.name, it.sportId, it.season) }, ts.toInstant())
    }

    suspend fun refresh(): NetworkResult<Unit> = deduper.run("leagues", isOnline) {
        when (val r = safeCall { apiProvider().leagues() }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                val t = now()
                dao.replaceAll(
                    r.data.mapIndexed { i, d -> LeagueEntity(d.leagueId, d.name, d.sportId, d.season, i, t) },
                    t,
                )
                NetworkResult.Success(Unit)
            }
        }
    }

    fun observeResource(): Flow<Resource<List<League>>> = resourceFlow("leagues", observe(), deduper, now)
}

class FixturesRepository(
    private val dao: FixtureDao,
    private val apiProvider: () -> LeagueApi,
    private val deduper: RefreshDeduper,
    private val isOnline: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observe(leagueId: String): Flow<Cached<List<Fixture>>?> =
        combine(dao.observeByLeague(leagueId), dao.observeUpdated(MetaKeys.fixturesUpdated(leagueId))) { rows, ts ->
            if (ts == null) {
                null
            } else {
                Cached(
                    rows.map {
                        Fixture(
                            it.matchId, it.leagueId, it.homeTeamId, it.homeName, it.awayTeamId, it.awayName,
                            it.status, it.scheduledAt, it.venueName, it.homeScore, it.awayScore, it.version,
                        )
                    },
                    ts.toInstant(),
                )
            }
        }

    suspend fun refresh(leagueId: String): NetworkResult<Unit> = deduper.run("fixtures:$leagueId", isOnline) {
        when (val r = safeCall { apiProvider().fixtures(leagueId) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                val t = now()
                dao.replaceForLeague(
                    leagueId,
                    r.data.map {
                        FixtureEntity(
                            it.matchId, leagueId, it.homeTeamId, it.homeName, it.awayTeamId, it.awayName,
                            it.status, it.scheduledAt, it.venueName, it.homeScore, it.awayScore,
                            it.version.toInt(), t,
                        )
                    },
                    t,
                )
                NetworkResult.Success(Unit)
            }
        }
    }

    fun observeResource(leagueId: String): Flow<Resource<List<Fixture>>> =
        resourceFlow("fixtures:$leagueId", observe(leagueId), deduper, now)
}

class MatchRepository(
    private val dao: MatchDao,
    private val apiProvider: () -> LeagueApi,
    private val deduper: RefreshDeduper,
    private val isOnline: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observe(matchId: String): Flow<Cached<MatchDetailDto>?> =
        dao.observe(matchId).mapDoc { decodeOrNull(MatchDetailDto.serializer(), it.json) to it.updatedAt }

    suspend fun refresh(matchId: String): NetworkResult<Unit> = deduper.run("match:$matchId", isOnline) {
        when (val r = safeCall { apiProvider().match(matchId) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                val d = r.data
                dao.saveAndPrune(
                    MatchEntity(
                        matchId, d.leagueId.orEmpty(), AppJson.encodeToString(MatchDetailDto.serializer(), d),
                        d.version.toInt(), now(),
                    ),
                    MATCH_CACHE_LIMIT,
                )
                NetworkResult.Success(Unit)
            }
        }
    }

    fun observeResource(matchId: String): Flow<Resource<MatchDetailDto>> =
        resourceFlow("match:$matchId", observe(matchId), deduper, now)
}

class StandingsRepository(
    private val dao: StandingsDao,
    private val apiProvider: () -> LeagueApi,
    private val deduper: RefreshDeduper,
    private val isOnline: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observe(leagueId: String): Flow<Cached<StandingsDto>?> =
        dao.observe(leagueId).mapDoc { decodeOrNull(StandingsDto.serializer(), it.json) to it.updatedAt }

    suspend fun refresh(leagueId: String): NetworkResult<Unit> = deduper.run("standings:$leagueId", isOnline) {
        when (val r = safeCall { apiProvider().standings(leagueId) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                dao.upsert(StandingsEntity(leagueId, AppJson.encodeToString(StandingsDto.serializer(), r.data), now()))
                NetworkResult.Success(Unit)
            }
        }
    }

    fun observeResource(leagueId: String): Flow<Resource<StandingsDto>> =
        resourceFlow("standings:$leagueId", observe(leagueId), deduper, now)
}

class ClubRepository(
    private val dao: ClubDao,
    private val apiProvider: () -> LeagueApi,
    private val deduper: RefreshDeduper,
    private val isOnline: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observe(clubId: String): Flow<Cached<ClubDto>?> =
        dao.observe(clubId).mapDoc { decodeOrNull(ClubDto.serializer(), it.json) to it.updatedAt }

    suspend fun refresh(clubId: String): NetworkResult<Unit> = deduper.run("club:$clubId", isOnline) {
        when (val r = safeCall { apiProvider().club(clubId) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                dao.upsert(ClubEntity(clubId, AppJson.encodeToString(ClubDto.serializer(), r.data), now()))
                NetworkResult.Success(Unit)
            }
        }
    }

    fun observeResource(clubId: String): Flow<Resource<ClubDto>> =
        resourceFlow("club:$clubId", observe(clubId), deduper, now)
}

class SportRepository(
    private val dao: SportDao,
    private val apiProvider: () -> LeagueApi,
    private val deduper: RefreshDeduper,
    private val isOnline: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observe(sportId: String): Flow<Cached<SportConfigDto>?> =
        dao.observe(sportId).mapDoc { decodeOrNull(SportConfigDto.serializer(), it.json) to it.updatedAt }

    suspend fun refresh(sportId: String): NetworkResult<Unit> = deduper.run("sport:$sportId", isOnline) {
        when (val r = safeCall { apiProvider().sport(sportId) }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                dao.upsert(SportEntity(sportId, AppJson.encodeToString(SportConfigDto.serializer(), r.data), now()))
                NetworkResult.Success(Unit)
            }
        }
    }

    fun observeResource(sportId: String): Flow<Resource<SportConfigDto>> =
        resourceFlow("sport:$sportId", observe(sportId), deduper, now)
}

class ProductRepository(
    private val dao: ProductDao,
    private val apiProvider: () -> LeagueApi,
    private val deduper: RefreshDeduper,
    private val isOnline: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observe(): Flow<Cached<List<ProductDto>>?> =
        combine(dao.observeAll(), dao.observeUpdated(MetaKeys.PRODUCTS_UPDATED)) { rows, ts ->
            if (ts == null) {
                null
            } else {
                Cached(rows.mapNotNull { decodeOrNull(ProductDto.serializer(), it.json) }, ts.toInstant())
            }
        }

    suspend fun refresh(): NetworkResult<Unit> = deduper.run("products", isOnline) {
        when (val r = safeCall { apiProvider().products() }) {
            is NetworkResult.Failure -> r
            is NetworkResult.Success -> {
                val t = now()
                dao.replaceAll(
                    r.data.mapIndexed { i, p ->
                        ProductEntity(p.productId, AppJson.encodeToString(ProductDto.serializer(), p), i, t)
                    },
                    t,
                )
                NetworkResult.Success(Unit)
            }
        }
    }

    fun observeResource(): Flow<Resource<List<ProductDto>>> = resourceFlow("products", observe(), deduper, now)
}
