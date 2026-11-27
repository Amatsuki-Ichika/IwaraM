package io.github.amatsukiichika.iwaram.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「元数据是应用，不是复活」的 DAO 回归用例（ADR 0032）。
 *
 * 两条写路径都必须对"请求在途期间行已被扫描收尾删掉"免疫：
 * - [VideoDao.applyFetchedMetadata] / 批量版：旧实现是 `@Insert(REPLACE)` 或逐条事务，
 *   UPDATE 命中 0 行时必须直接放弃，不能把行插回去，更不能留下标签/关联垃圾；
 * - [linkIwaraId]：手动关联弹窗拿着的是打开那一刻的快照，行被删后 REPLACE-upsert 会
 *   复活一条指向不存在文件的幽灵条目 —— 现在只 UPDATE 身份列并重读判存。
 *
 * 用内存 Room（当前 v4 schema 由实体直接建出）跑真实 SQL，而不是断言"调用了某方法"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NonResurrectionMetadataTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: VideoDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.videoDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun video(uri: String, iwaraId: String? = null): VideoEntity = VideoEntity(
        localUri = uri,
        fileName = uri.substringAfterLast('/'),
        iwaraId = iwaraId,
        title = "原标题",
        author = null,
        authorId = null,
        createdAt = null,
        viewCount = null,
        likeCount = null,
        commentCount = null,
        rating = null,
        thumbnailUrl = null,
        localThumbnailPath = null,
        relPath = null,
        tagsCsv = null,
        metaFetched = false,
        addedAt = 1L
    )

    private fun crossRefCount(): Int =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM video_tag_cross_ref").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @Test
    fun `applyFetchedMetadata on a deleted row returns false and recreates no tags`() = runBlocking {
        val uri = "content://test/gone.mp4"
        val row = video(uri, "abc")
        dao.upsert(row)
        dao.deleteVideoWithTags(row)

        val applied = dao.applyFetchedMetadata(
            FetchedMetadataWrite(
                video = row.copy(title = "迟到的元数据", metaFetched = true, tagsCsv = "tag1"),
                tags = listOf(TagEntity(id = "tag1", name = "标签一")),
                refs = listOf(VideoTagCrossRef(uri, "tag1"))
            )
        )

        assertFalse("UPDATE 命中 0 行时必须返回 false", applied)
        assertEquals("已删除的行不得被复活", 0, dao.count())
        assertNull(dao.byUri(uri))
        assertEquals(0, dao.observeAllTags().first().size)
        assertEquals(0, crossRefCount())
    }

    @Test
    fun `applyFetchedMetadataBatch writes live rows and skips deleted ones`() = runBlocking {
        val alive = video("content://test/alive.mp4")
        val gone = video("content://test/gone-batch.mp4")
        dao.upsertAll(listOf(alive, gone))
        dao.deleteVideoWithTags(gone)

        val applied = dao.applyFetchedMetadataBatch(
            listOf(
                FetchedMetadataWrite(
                    video = alive.copy(title = "新标题", metaFetched = true, tagsCsv = "tag1"),
                    tags = listOf(TagEntity(id = "tag1", name = "标签一")),
                    refs = listOf(VideoTagCrossRef(alive.localUri, "tag1"))
                ),
                FetchedMetadataWrite(
                    video = gone.copy(title = "幽灵标题", metaFetched = true, tagsCsv = "tag2"),
                    tags = listOf(TagEntity(id = "tag2", name = "标签二")),
                    refs = listOf(VideoTagCrossRef(gone.localUri, "tag2"))
                )
            )
        )

        assertEquals("只有仍在库里的行计入写入数", 1, applied)
        assertEquals(1, dao.count())
        assertEquals("新标题", dao.byUri(alive.localUri)?.title)
        assertNull(dao.byUri(gone.localUri))
        // 已删行既不复活，也不留下它自己的标签/关联。
        assertEquals(listOf("tag1"), dao.observeAllTags().first().map { it.id })
        assertEquals(1, crossRefCount())
    }

    @Test
    fun `linkIwaraId does not resurrect a deleted row`() = runBlocking {
        val snapshot = video("content://test/linked.mp4")
        dao.upsert(snapshot)
        dao.deleteVideoWithTags(snapshot)

        // 模拟手动关联弹窗：拿着打开时的整行快照确认关联，而行已被扫描收尾删掉。
        // 修复后是一次 0 行 UPDATE + byUri 判存后放弃（也不会去拉网络元数据）。
        IwaraRepository(context, db).linkIwaraId(snapshot, "abc123")

        assertNull("已删除的行不得被 upsert 复活", dao.byUri(snapshot.localUri))
        assertEquals(0, dao.count())
    }
}
