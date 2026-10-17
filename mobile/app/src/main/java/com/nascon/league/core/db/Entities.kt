package com.nascon.league.core.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "leagues")
data class LeagueEntity(
    @PrimaryKey @ColumnInfo(name = "league_id") val leagueId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "sport_id") val sportId: String,
    @ColumnInfo(name = "season") val season: String?,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "fixtures", indices = [Index("league_id")])
data class FixtureEntity(
    @PrimaryKey @ColumnInfo(name = "match_id") val matchId: String,
    @ColumnInfo(name = "league_id") val leagueId: String,
    @ColumnInfo(name = "home_team_id") val homeTeamId: String,
    @ColumnInfo(name = "home_name") val homeName: String,
    @ColumnInfo(name = "away_team_id") val awayTeamId: String,
    @ColumnInfo(name = "away_name") val awayName: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: String?,
    @ColumnInfo(name = "venue_name") val venueName: String?,
    @ColumnInfo(name = "home_score") val homeScore: Int?,
    @ColumnInfo(name = "away_score") val awayScore: Int?,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "matches")
data class MatchEntity(
    @PrimaryKey @ColumnInfo(name = "match_id") val matchId: String,
    @ColumnInfo(name = "league_id") val leagueId: String,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "standings")
data class StandingsEntity(
    @PrimaryKey @ColumnInfo(name = "league_id") val leagueId: String,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "clubs")
data class ClubEntity(
    @PrimaryKey @ColumnInfo(name = "club_id") val clubId: String,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "sports")
data class SportEntity(
    @PrimaryKey @ColumnInfo(name = "sport_id") val sportId: String,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey @ColumnInfo(name = "product_id") val productId: String,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "pending_actions", indices = [Index("entity_key"), Index("owner_user_id")])
data class PendingActionEntity(
    @PrimaryKey @ColumnInfo(name = "action_id") val actionId: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "queued_at") val queuedAt: String,
    @ColumnInfo(name = "base_version") val baseVersion: Int,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "entity_key") val entityKey: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "owner_user_id") val ownerUserId: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "resolution") val resolution: String? = null,
    @ColumnInfo(name = "server_version") val serverVersion: Int? = null,
    @ColumnInfo(name = "signature") val signature: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "settled_at") val settledAt: Long? = null,
)

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)

object ActionStatus {
    const val PENDING = "PENDING"
    const val SYNCING = "SYNCING"
    const val APPLIED = "APPLIED"
    const val REJECTED = "REJECTED"
    const val DUPLICATE = "DUPLICATE"
}

object MetaKeys {
    const val CACHE_OWNER = "cache_owner_user_id"
    const val LAST_SYNC_AT = "last_sync_at"
    const val LEAGUES_UPDATED = "updated:leagues"
    const val PRODUCTS_UPDATED = "updated:products"
    private const val FIXTURES_PREFIX = "updated:fixtures:"
    fun fixturesUpdated(leagueId: String) = FIXTURES_PREFIX + leagueId
}
