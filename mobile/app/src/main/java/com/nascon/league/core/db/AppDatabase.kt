package com.nascon.league.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nascon.league.BuildConfig
import com.nascon.league.data.cart.GuestCartDao
import com.nascon.league.data.cart.GuestCartItemEntity

@Database(
    entities = [
        LeagueEntity::class, FixtureEntity::class, MatchEntity::class, StandingsEntity::class,
        ClubEntity::class, SportEntity::class, ProductEntity::class, PendingActionEntity::class,
        MetaEntity::class, SyncResultEntity::class, GuestCartItemEntity::class,
    ],
    version = 3,
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
    abstract fun syncResultDao(): SyncResultDao
    abstract fun guestCartDao(): GuestCartDao

    companion object {
        /** v1 -> v2: sync_results table + attempts/tenant_id on pending_actions. Pending rows are preserved. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pending_actions` ADD COLUMN `attempts` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pending_actions` ADD COLUMN `tenant_id` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_results` (`action_id` TEXT NOT NULL, " +
                        "`owner_user_id` TEXT NOT NULL, `type` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `resolution` TEXT, `reason_code` TEXT, " +
                        "`server_version` INTEGER NOT NULL, `processed_order` INTEGER NOT NULL, " +
                        "`synced_at` INTEGER NOT NULL, PRIMARY KEY(`action_id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_results_owner_user_id` ON `sync_results` (`owner_user_id`)",
                )
            }
        }

        /** SQL of the guest cart table. Kept as a constant so a unit test can compare it with the exported v3 schema. */
        const val GUEST_CART_CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS `guest_cart_item` (`token` TEXT NOT NULL, `variantId` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `priceMinor` INTEGER NOT NULL, `quantity` INTEGER NOT NULL, " +
                "PRIMARY KEY(`token`, `variantId`))"

        /** v2 -> v3: adds the guest cart. Nothing else changes, so pending offline actions and caches are preserved. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(GUEST_CART_CREATE_SQL)
            }
        }

        fun create(context: Context): AppDatabase {
            val builder = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "nascon.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            // Release builds must never destroy pending offline actions.
            if (BuildConfig.DEBUG) builder.fallbackToDestructiveMigration()
            return builder.build()
        }
    }
}
