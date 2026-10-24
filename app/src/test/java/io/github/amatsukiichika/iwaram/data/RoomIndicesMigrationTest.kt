package io.github.amatsukiichika.iwaram.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2→v3 索引迁移回归用例。
 *
 * 手工复刻 v2 库（DDL 逐字取自 app/schemas/…AppDatabase/2.json），四张表各塞数据后用 Room
 * 打开 —— Room 会跑 [AppDatabase.MIGRATION_2_3] 并按 v3 实体期望做**全 schema 校验**
 * （索引名/列序与实体对不上直接炸）。另用 `EXPLAIN QUERY PLAN` 钉住「索引真的被查询用上」，
 * 防止索引建了但列序不对而沦为摆设。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomIndicesMigrationTest {

    private lateinit var context: Context
    private val dbName = "room_indices_migration_test.db"

    /** v2 的四张表 DDL —— 与 Room 为 v2 实体生成的建表语句逐字一致，勿手改。 */
    private val v2Ddl = listOf(
        "CREATE TABLE IF NOT EXISTS `videos` (`localUri` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
            "`iwaraId` TEXT, `title` TEXT NOT NULL, `author` TEXT, `authorId` TEXT, `createdAt` INTEGER, " +
            "`viewCount` INTEGER, `likeCount` INTEGER, `commentCount` INTEGER, `rating` TEXT, " +
            "`thumbnailUrl` TEXT, `localThumbnailPath` TEXT, `relPath` TEXT, `tagsCsv` TEXT, " +
            "`metaFetched` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`localUri`))",
        "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `video_tag_cross_ref` (`videoUri` TEXT NOT NULL, `tagId` TEXT NOT NULL, " +
            "PRIMARY KEY(`videoUri`, `tagId`))",
        "CREATE TABLE IF NOT EXISTS `watch_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`videoKey` TEXT NOT NULL, `iwaraId` TEXT, `localUri` TEXT, `title` TEXT NOT NULL, `author` TEXT, " +
            "`authorId` TEXT, `thumbnail` TEXT, `isLocal` INTEGER NOT NULL, `lastWatchAt` INTEGER NOT NULL, " +
            "`positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_watch_history_videoKey` ON `watch_history` (`videoKey`)"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        // 手工建 v2 库（version=1 的 pragma 无关紧要：打开时 Room 按文件里的 user_version 走迁移，
        // 这里直接把 user_version 写成 2，等价于"装过 v2 版本的旧库"）。
        val raw = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        v2Ddl.forEach(raw::execSQL)
        raw.execSQL(
            "INSERT INTO videos (localUri, fileName, iwaraId, title, author, authorId, createdAt, " +
                "viewCount, likeCount, commentCount, rating, thumbnailUrl, localThumbnailPath, relPath, " +
                "tagsCsv, metaFetched, addedAt) VALUES " +
                "('content://v2/a.mp4', 'a.mp4', 'abc', '视频甲', NULL, NULL, NULL, NULL, NULL, NULL, " +
                "NULL, NULL, NULL, NULL, NULL, 1, 100)"
        )
        raw.execSQL(
            "INSERT INTO videos (localUri, fileName, iwaraId, title, author, authorId, createdAt, " +
                "viewCount, likeCount, commentCount, rating, thumbnailUrl, localThumbnailPath, relPath, " +
                "tagsCsv, metaFetched, addedAt) VALUES " +
                "('content://v2/b.mp4', 'b.mp4', NULL, '视频乙', NULL, NULL, NULL, NULL, NULL, NULL, " +
                "NULL, NULL, NULL, NULL, NULL, 0, 101)"
        )
        raw.execSQL(
            "INSERT INTO videos (localUri, fileName, iwaraId, title, author, authorId, createdAt, " +
                "viewCount, likeCount, commentCount, rating, thumbnailUrl, localThumbnailPath, relPath, " +
                "tagsCsv, metaFetched, addedAt) VALUES " +
                "('content://v2/c.mp4', 'c.mp4', 'def', '视频丙', NULL, NULL, NULL, NULL, NULL, NULL, " +
                "NULL, NULL, NULL, NULL, NULL, 0, 102)"
        )
        raw.execSQL("INSERT INTO tags (id, name) VALUES ('tag1', '标签一')")
        raw.execSQL("INSERT INTO video_tag_cross_ref (videoUri, tagId) VALUES ('content://v2/a.mp4', 'tag1')")
        raw.execSQL(
            "INSERT INTO watch_history (videoKey, title, isLocal, lastWatchAt, positionMs, durationMs) " +
                "VALUES ('k1', '视频甲', 1, 1, 0, 0)"
        )
        raw.version = 2
        raw.close()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `迁移 2到3 后索引就位且四张表数据无损`() = runBlocking {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            val dao = room.videoDao()

            // ---- 数据无损 ----
            assertEquals(3, dao.count())
            assertEquals("视频甲", dao.byIwaraId("abc")?.title)
            assertEquals(1, dao.getMissingMetadata().size) // 只有 c 待补（b 的 iwaraId 为 NULL，不算）
            assertEquals(listOf("tag1"), dao.observeAllTags().first().map { it.id })
            assertEquals(1, room.watchHistoryDao().observeAll().first().size)

            // ---- 索引真实存在（Room 的 schema 校验同时保证名字逐字一致） ----
            val raw = room.openHelper.writableDatabase
            val indexes = mutableListOf<String>()
            raw.query(SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type='index'")).use { c ->
                while (c.moveToNext()) indexes.add(c.getString(0))
            }
            listOf(
                "index_videos_iwaraId",
                "index_videos_metaFetched_iwaraId",
                "index_video_tag_cross_ref_tagId"
            ).forEach { assertTrue("迁移后缺少索引 $it", it in indexes) }

            // ---- EXPLAIN QUERY PLAN：索引真的被用上 ----
            fun explain(sql: String): List<String> = buildList {
                raw.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql")).use { c ->
                    while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail")))
                }
            }

            // byIwaraId 是 `iwaraId = ?` 精确匹配：只能走单列索引（复合索引里 iwaraId 在第二列）。
            val byIwaraIdPlan = explain("SELECT * FROM videos WHERE iwaraId = 'abc' LIMIT 1")
            assertTrue(
                "byIwaraId 应走 index_videos_iwaraId，实际: $byIwaraIdPlan",
                byIwaraIdPlan.any { it.contains("index_videos_iwaraId") }
            )

            // getMissingMetadata：不得全表扫（规划器在单列/复合索引之间二选一，都算达标）。
            val missingPlan = explain("SELECT * FROM videos WHERE metaFetched = 0 AND iwaraId IS NOT NULL")
            assertTrue(
                "getMissingMetadata 应走索引，实际: $missingPlan",
                missingPlan.any { it.contains("USING INDEX") }
            )
        } finally {
            room.close()
        }
    }
}
