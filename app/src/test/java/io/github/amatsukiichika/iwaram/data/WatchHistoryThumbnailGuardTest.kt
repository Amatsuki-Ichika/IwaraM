package io.github.amatsukiichika.iwaram.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 删除库行封面文件时必须避开观看历史仍在引用的封面（Low-2）。
 *
 * 观看历史把 `VideoEntity.localThumbnailPath` 直接存成自己的封面快照；删库行 / 扫描收尾时
 * 若无条件删掉 filesDir/thumbs 里的文件，历史行就会留下死路径且没有在线兜底。
 * 这里用真实文件 + 内存 Room 验证：被历史行引用 → 保留；引用解除 → 照常清理；
 * 目录外的路径始终不碰（原守卫不变）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchHistoryThumbnailGuardTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: IwaraRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = IwaraRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun coverFile(name: String): File =
        File(localThumbsDir(context), name).apply { writeText("cover") }

    private fun historyRow(thumbnail: String?) = WatchHistoryEntity(
        videoKey = "key-$thumbnail", iwaraId = "key-$thumbnail", localUri = null, title = "标题",
        author = null, authorId = null, thumbnail = thumbnail, isLocal = true,
        lastWatchAt = 1L, positionMs = 0L, durationMs = 0L
    )

    @Test
    fun `a cover still referenced by watch history is kept`() = runBlocking {
        val referenced = coverFile("thumb_referenced.jpg")
        db.watchHistoryDao().upsert(historyRow(referenced.absolutePath))

        repo.deleteLocalThumbnailFile(referenced.absolutePath)

        assertTrue("历史行还在引用封面，删库行不能把历史页的封面删掉", referenced.isFile)
    }

    @Test
    fun `an unreferenced cover is deleted as before`() = runBlocking {
        val orphan = coverFile("thumb_orphan.jpg")

        repo.deleteLocalThumbnailFile(orphan.absolutePath)

        assertFalse("没有任何历史行引用的封面照常清理", orphan.isFile)
    }

    @Test
    fun `once the history row is gone the cover can be deleted`() = runBlocking {
        val referenced = coverFile("thumb_released.jpg")
        db.watchHistoryDao().upsert(historyRow(referenced.absolutePath))
        repo.deleteLocalThumbnailFile(referenced.absolutePath)
        assertTrue(referenced.isFile)

        db.watchHistoryDao().clearAll()
        repo.deleteLocalThumbnailFile(referenced.absolutePath)

        assertFalse("历史行删除后封面不再被引用，可以清理", referenced.isFile)
    }

    @Test
    fun `a file outside the thumbs directory is never touched`() = runBlocking {
        val outside = File(context.filesDir, "not-a-cover.jpg").apply { writeText("x") }

        repo.deleteLocalThumbnailFile(outside.absolutePath)

        assertTrue("目录外文件不在清理范围（原守卫不变）", outside.isFile)
    }
}
