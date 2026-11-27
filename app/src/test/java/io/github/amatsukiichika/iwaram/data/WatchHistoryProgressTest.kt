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
 * 观看进度写入必须是单条原子 UPDATE（Low-1）。
 *
 * 旧实现是 `byKey` 读整行 + REPLACE-upsert 写回：`historyScope` 是多线程 IO 池，
 * 旧快照后落库会把进度回退，行在中途被删还会被 REPLACE 复活。这里用内存 Room 跑真实 SQL，
 * 钉住三件事：更新命中行、`durationMs <= 0` 时保留已存总时长、行不存在时绝不造行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchHistoryProgressTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: WatchHistoryDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.watchHistoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(
        key: String = "video-1",
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        lastWatchAt: Long = 100L
    ) = WatchHistoryEntity(
        videoKey = key, iwaraId = key, localUri = null, title = "标题",
        author = null, authorId = null, thumbnail = null, isLocal = false,
        lastWatchAt = lastWatchAt, positionMs = positionMs, durationMs = durationMs
    )

    @Test
    fun `progress update is one statement that keeps the known duration`() = runBlocking {
        dao.upsert(row(positionMs = 1_000L, durationMs = 60_000L, lastWatchAt = 100L))

        // 首帧/心跳常常还拿不到总时长（0）：只更新进度与时间，不把已知总时长抹掉。
        val changed = dao.updateProgress(key = "video-1", positionMs = 5_000L, durationMs = 0L, now = 200L)

        assertEquals(1, changed)
        val updated = dao.byKey("video-1")
        assertEquals(5_000L, updated?.positionMs)
        assertEquals(60_000L, updated?.durationMs)
        assertEquals(200L, updated?.lastWatchAt)
    }

    @Test
    fun `a positive duration replaces the stored one`() = runBlocking {
        dao.upsert(row(positionMs = 1_000L, durationMs = 60_000L))
        dao.updateProgress(key = "video-1", positionMs = 2_000L, durationMs = 90_000L, now = 200L)
        assertEquals(90_000L, dao.byKey("video-1")?.durationMs)
    }

    @Test
    fun `progress update never resurrects a deleted row`() = runBlocking {
        dao.upsert(row())
        val id = dao.byKey("video-1")!!.id
        dao.deleteById(id)

        // 删除与进度写在多线程池里可能乱序：UPDATE 命中 0 行时必须什么都不留下，
        // 旧实现会在这里 REPLACE 出一条指向已删视频的幽灵历史行。
        val changed = dao.updateProgress(key = "video-1", positionMs = 5_000L, durationMs = 0L, now = 300L)

        assertEquals(0, changed)
        assertEquals(0, dao.count())
    }
}
