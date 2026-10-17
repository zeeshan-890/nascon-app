package com.nascon.league.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nascon.league.BuildConfig

@Database(
    entities = [
        LeagueEntity::class, FixtureEntity::class, MatchEntity::class, StandingsEntity::class,
        ClubEntity::class, SportEntity::class, ProductEntity::class, PendingActionEntity::class,
        MetaEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun leagueDao(): LeagueDao
    abstract fun fixtureDao(): FixtureDao
    abstract fun matchDao(): MatchDao
    abstract fun standingsDao(): StandingsDao
    abstract fun clubDao(): ClubDao
    abstract fun sportDao(): SportDao
    abstract fun productDao(): ProductDao
    abstract fun pendingActionDao(): PendingActionDao
    abstract fun cacheDao(): CacheDao

    companion object {
        fun create(context: Context): AppDatabase {
            val builder = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "nascon.db")
            // Release builds must never destroy pending offline actions.
            if (BuildConfig.DEBUG) builder.fallbackToDestructiveMigration()
            return builder.build()
        }
    }
}
