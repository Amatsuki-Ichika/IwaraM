package io.github.amatsukiichika.iwaram.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 观看历史迁移测试。
 *
 * 在 JVM（Robolectric）上**手工复刻 v1 库**（DDL 逐字取自 Room 2.6.1 为 v1 实体生成的
 * AppDatabase_Impl），塞入旧数据后用 Room 打开 —— Room 会自己跑 [AppDatabase.MIGRATION_1_2]
 * 并对 v2 schema 做**全表结构校验**：迁移 SQL 与实体期望差一个 NOT NULL / 索引都会在这里直接炸，
 * 等价于把"覆盖安装后旧数据无损"这件事在每次单测里都验一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchHistoryMigrationTest {

    private lateinit var context: Context
    private val dbName = "watch_history_migration_test.db"

    /** v1 的三张表 DDL —— 与 version=1 时 Room 生成的建表语句逐字一致，勿手改。 */
    private val v1Ddl = listOf(
        "CREATE TABLE IF NOT EXISTS `videos` (`localUri` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
            "`iwaraId` TEXT, `title` TEXT NOT NULL, `author` TEXT, `authorId` TEXT, `createdAt` INTEGER, " +
            "`viewCount` INTEGER, `likeCount` INTEGER, `commentCount` INTEGER, `rating` TEXT, " +
            "`thumbnailUrl` TEXT, `localThumbnailPath` TEXT, `relPath` TEXT, `tagsCsv` TEXT, " +
            "`metaFetched` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`localUri`))",
        "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `video_tag_cross_ref` (`videoUri` TEXT NOT NULL, `tagId` TEXT NOT NULL, " +
            "PRIMARY KEY(`videoUri`, `tagId`))"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** 用 v1 DDL + 旧数据造一个"升级前的库"。 */
    private fun createV1DatabaseWithLegacyData() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        v1Ddl.forEach { db.execSQL(it) }
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO videos (localUri, fileName, iwaraId, title, author, authorId, createdAt, " +
                "viewCount, likeCount, commentCount, rating, thumbnailUrl, localThumbnailPath, relPath, " +
                "tagsCsv, metaFetched, addedAt) VALUES " +
                "('content://legacy/a.mp4', 'a.mp4', 'legacy001', '旧视频甲', '作者甲', 'author_x', " +
                "NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'tag1,tag2', 1, 1000)"
        )
        db.execSQL("INSERT INTO tags (id, name) VALUES ('tag1', '标签一')")
        db.execSQL("INSERT INTO video_tag_cross_ref (videoUri, tagId) VALUES ('content://legacy/a.mp4', 'tag1')")
        db.close()
    }

    @Test
    fun `迁移 1到2 后旧数据无损且新表可用`() = runBlocking {
        createV1DatabaseWithLegacyData()

        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            // Room 按**当前实体（v4）**做迁移后的全 schema 校验 —— 迁移链必须补齐到最新版，
            // 否则 v3→v4 的 fileModifiedAt 列缺失会在这里炸（校验对象是终点版本，不是 2→3 时代）。
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        try {
            // ---- 旧数据无损 ----
            val legacy = room.videoDao().byUri("content://legacy/a.mp4")
            assertNotNull("v1 视频行在迁移后必须还在", legacy)
            assertEquals("旧视频甲", legacy?.title)
            assertEquals("legacy001", legacy?.iwaraId)
            val tags = room.videoDao().observeAllTags().first()
            assertEquals(1, tags.size)
            assertEquals("标签一", tags[0].name)

            // ---- 新表：写入 / 同 key REPLACE 去重 / 读取 ----
            val dao = room.watchHistoryDao()
            dao.upsert(
                WatchHistoryEntity(
                    videoKey = "legacy001", iwaraId = "legacy001", localUri = null,
                    title = "旧视频甲", author = "作者甲", authorId = "author_x",
                    thumbnail = null, isLocal = false,
                    lastWatchAt = 1_000L, positionMs = 60_000L, durationMs = 600_000L
                )
            )
            // 同 key 重看 = REPLACE 原地覆盖（不重复、lastWatchAt 刷新）
            dao.upsert(
                WatchHistoryEntity(
                    videoKey = "legacy001", iwaraId = "legacy001", localUri = "content://legacy/a.mp4",
                    title = "旧视频甲", author = "作者甲", authorId = "author_x",
                    thumbnail = null, isLocal = true,
                    lastWatchAt = 2_000L, positionMs = 120_000L, durationMs = 600_000L
                )
            )
            val row = dao.byKey("legacy001")
            assertNotNull(row)
            assertEquals(1, dao.count())
            assertEquals(2_000L, row?.lastWatchAt)
            assertEquals(120_000L, row?.positionMs)
            // 裁剪用的"删最旧 n 条"查询也不许炸（单条 DELETE ... LIMIT）
            dao.deleteOldest(1)
            assertEquals(0, dao.count())
            // 查不存在的 key 返回 null 而不是抛错
            assertNull(dao.byKey("no_such_key"))
        } finally {
            room.close()
        }
    }
}
