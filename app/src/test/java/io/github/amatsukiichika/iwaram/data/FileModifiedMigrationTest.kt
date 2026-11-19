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
 * 旧库不是手抄 DDL：直接执行导出 schema v3（app/schemas/…AppDatabase/3.json）里 Room 生成的
 * createSql（含 v3 的两个索引），等于「打开上一版的导出 schema」。塞三行数据后用 Room 打开 ——
 * Room 会跑 [AppDatabase.MIGRATION_3_4] 并按 v4 实体期望做 **全 schema 校验**：
 * `fileModifiedAt INTEGER NOT NULL DEFAULT 0` 与实体上的 `@ColumnInfo(defaultValue = "0")`
 * 差一个字都会直接炸；迁移后再用 PRAGMA 读出真实形状，与 v4 导出 schema 逐表比对
 * （[SchemaJson.assertMatchesExported]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileModifiedMigrationTest {

    private lateinit var context: Context
    private val dbName = "file_modified_migration_test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        // 手工建 v3 库：建表语句取自已导出的 3.json，不是手抄副本；user_version 直接写成 3。
        val raw = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        SchemaJson.load(3).createStatements().forEach(raw::execSQL)
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

            // ---- 迁移终点形状与导出的 v4 schema 逐表一致（列/类型/NOT NULL/默认值/索引/外键）----
            SchemaJson.assertMatchesExported(room.openHelper.writableDatabase)
        } finally {
            room.close()
        }
    }
}
