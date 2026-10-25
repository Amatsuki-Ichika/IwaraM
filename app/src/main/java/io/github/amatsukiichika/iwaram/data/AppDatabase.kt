package io.github.amatsukiichika.iwaram.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VideoEntity::class, TagEntity::class, VideoTagCrossRef::class, WatchHistoryEntity::class],
    version = 4,
    // schema 导出到 app/schemas/，供后续版本的迁移回归测试比对（见 app/build.gradle.kts 的 ksp 配置）
    exportSchema = true
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

        /**
         * 2→3 迁移：给 `videos` 与 `video_tag_cross_ref` 补二级索引。纯索引新增，不动任何表结构。
         * 索引名必须与 Room 按实体默认生成的名字逐字一致（`index_<表>_<列…>`）——
         * Room 打开库时按实体期望校验 schema，名字/列序对不上都会崩；
         * 回归测试见 [io.github.amatsukiichika.iwaram.data.RoomIndicesMigrationTest]。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_iwaraId` ON `videos` (`iwaraId`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_videos_metaFetched_iwaraId` ON `videos` (`metaFetched`, `iwaraId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_video_tag_cross_ref_tagId` ON `video_tag_cross_ref` (`tagId`)"
                )
            }
        }

        /**
         * 3→4 迁移：`videos` 新增 `fileModifiedAt`（文件修改日期，epoch ms，0 = 未知）。
         * 「下载时间」排序改用它 —— 此前只有入库时刻 `addedAt`，最近一次扫描导入的一批
         * （往往是未匹配元数据的本地文件）会整批置顶。纯加列，不动任何旧数据；
         * DDL 的 DEFAULT 0 必须与实体上的 `@ColumnInfo(defaultValue = "0")` 逐字一致
         * （Room 打开库时按实体期望校验 schema），回归测试见
         * [io.github.amatsukiichika.iwaram.data.FileModifiedMigrationTest]。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `videos` ADD COLUMN `fileModifiedAt` INTEGER NOT NULL DEFAULT 0")
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
    }
}
