package io.github.amatsukiichika.iwaram.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v3→v4「文件修改日期」列迁移回归用例。
 *
 * 手工复刻 v3 库（videos 的 DDL 逐字取自 app/schemas/…AppDatabase/3.json，含 v3 的两个索引），
 * 塞三行数据后用 Room 打开 —— Room 会跑 [AppDatabase.MIGRATION_3_4] 并按 v4 实体期望做
 * **全 schema 校验**：`fileModifiedAt INTEGER NOT NULL DEFAULT 0` 与实体上的
 * `@ColumnInfo(defaultValue = "0")` 差一个字都会直接炸，等于把迁移 DDL 钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileModifiedMigrationTest {

    private lateinit var context: Context
    private val dbName = "file_modified_migration_test.db"

    /** v3 的四张表 DDL —— 与 Room 为 v3 实体生成的建表语句逐字一致，勿手改。 */
    private val v3Ddl = listOf(
        "CREATE TABLE IF NOT EXISTS `videos` (`localUri` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
            "`iwaraId` TEXT, `title` TEXT NOT NULL, `author` TEXT, `authorId` TEXT, `createdAt` INTEGER, " +
            "`viewCount` INTEGER, `likeCount` INTEGER, `commentCount` INTEGER, `rating` TEXT, " +
            "`thumbnailUrl` TEXT, `localThumbnailPath` TEXT, `relPath` TEXT, `tagsCsv` TEXT, " +
            "`metaFetched` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`localUri`))",
        "CREATE INDEX IF NOT EXISTS `index_videos_iwaraId` ON `videos` (`iwaraId`)",
        "CREATE INDEX IF NOT EXISTS `index_videos_metaFetched_iwaraId` ON `videos` (`metaFetched`, `iwaraId`)",
        "CREATE TABLE IF NOT EXISTS `tags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `video_tag_cross_ref` (`videoUri` TEXT NOT NULL, `tagId` TEXT NOT NULL, " +
            "PRIMARY KEY(`videoUri`, `tagId`))",
        "CREATE INDEX IF NOT EXISTS `index_video_tag_cross_ref_tagId` ON `video_tag_cross_ref` (`tagId`)",
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
        val raw = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        v3Ddl.forEach(raw::execSQL)
        raw.execSQL(
            "INSERT INTO videos (localUri, fileName, iwaraId, title, author, authorId, createdAt, " +
                "viewCount, likeCount, commentCount, rating, thumbnailUrl, localThumbnailPath, relPath, " +
                "tagsCsv, metaFetched, addedAt) VALUES " +
                "('content://v3/a.mp4', 'a.mp4', 'abc', '视频甲', NULL, NULL, NULL, NULL, NULL, NULL, " +
                "NULL, NULL, NULL, NULL, NULL, 1, 100)"
        )
        raw.execSQL(
            "INSERT INTO videos (localUri, fileName, iwaraId, title, author, authorId, createdAt, " +
                "viewCount, likeCount, commentCount, rating, thumbnailUrl, localThumbnailPath, relPath, " +
                "tagsCsv, metaFetched, addedAt) VALUES " +
                "('content://v3/b.mp4', 'b.mp4', NULL, '视频乙', NULL, NULL, NULL, NULL, NULL, NULL, " +
                "NULL, NULL, NULL, NULL, NULL, 0, 101)"
        )
        raw.version = 3
        raw.close()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `迁移 3到4 后新列就位且旧数据无损`() = runBlocking {
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        try {
            val dao = room.videoDao()

            // ---- 数据无损 ----
            assertEquals(2, dao.count())
            assertEquals("视频甲", dao.byIwaraId("abc")?.title)

            // ---- 新列默认 0（旧数据"修改日期未知"）----
            assertEquals(0L, dao.byIwaraId("abc")?.fileModifiedAt)
            assertEquals(0L, dao.byUri("content://v3/b.mp4")?.fileModifiedAt)

            // ---- 回填通路可用（单行 + 批量，批量一次事务只发一次失效）----
            dao.setFileModifiedAt("content://v3/b.mp4", 1_700_000_000_000L)
            assertEquals(1_700_000_000_000L, dao.byUri("content://v3/b.mp4")?.fileModifiedAt)
            dao.setFileModifiedAtBatch(
                listOf("content://v3/a.mp4" to 1_700_000_000_001L)
            )
            assertEquals(1_700_000_000_001L, dao.byIwaraId("abc")?.fileModifiedAt)
        } finally {
            room.close()
        }
    }
}
