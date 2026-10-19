package io.github.amatsukiichika.iwaramanager.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VideoEntity::class, TagEntity::class, VideoTagCrossRef::class, WatchHistoryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun watchHistoryDao(): WatchHistoryDao

    companion object {
        /**
         * 1→2 迁移：新增观看历史表。纯新增，不动任何旧表；
         * DDL 必须与 Room 为 [WatchHistoryEntity] 生成的建表语句逐字一致
         * （Room 打开库时按实体期望校验 schema，差一个 NOT NULL 都会崩），改字段时两边同步。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_history` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`videoKey` TEXT NOT NULL, " +
                        "`iwaraId` TEXT, " +
                        "`localUri` TEXT, " +
                        "`title` TEXT NOT NULL, " +
                        "`author` TEXT, " +
                        "`authorId` TEXT, " +
                        "`thumbnail` TEXT, " +
                        "`isLocal` INTEGER NOT NULL, " +
                        "`lastWatchAt` INTEGER NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_watch_history_videoKey` ON `watch_history` (`videoKey`)"
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "iwara_manager.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}
