package com.bydmapcam.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AlertPoint::class, Trip::class, ParkingBlock::class, TrackCell::class],
    version = 8,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun alertPointDao(): AlertPointDao
    abstract fun tripDao(): TripDao
    abstract fun parkingBlockDao(): ParkingBlockDao
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alert_points ADD COLUMN alertEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alert_points ADD COLUMN infoMode INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alert_points ADD COLUMN imported INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trips (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "startTime INTEGER NOT NULL, " +
                        "endTime INTEGER NOT NULL, " +
                        "distanceKm REAL NOT NULL, " +
                        "startSoc INTEGER NOT NULL, " +
                        "endSoc INTEGER NOT NULL, " +
                        "pricePerKwh REAL)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Nullable on purpose: "we don't know which way this road runs" is a real state,
                // and every point saved before this existed is in it.
                db.execSQL("ALTER TABLE alert_points ADD COLUMN headingDeg REAL")
                db.execSQL("ALTER TABLE alert_points ADD COLUMN oneWay INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Parking blocks are a second, independent kind of thing on the map — a stretch of
                // kerb with a rule, not a point with a radius — so they get their own table rather
                // than more nullable columns on alert_points.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS parking_blocks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "path TEXT NOT NULL, " +
                        "leftRule TEXT NOT NULL, " +
                        "rightRule TEXT NOT NULL, " +
                        "banFromMin INTEGER, " +
                        "banToMin INTEGER, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // The grid of squares this car has driven through. The pair of coordinates is the
                // key, which is what makes re-driving a road free in both storage and work.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS track_cells (" +
                        "cellY INTEGER NOT NULL, " +
                        "cellX INTEGER NOT NULL, " +
                        "firstSeenAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(cellY, cellX))"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "byd-map-cam.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8
                )
                    .build().also { INSTANCE = it }
            }
    }
}
