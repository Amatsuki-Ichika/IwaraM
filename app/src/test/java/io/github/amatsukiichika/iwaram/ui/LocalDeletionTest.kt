package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.data.VideoEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从库移除」与「彻底删除」的纯逻辑 —— 先删文件还是先删行、失败后行留不留。
 *
 * 这些断言直接对应用户数据：删文件失败却把行删了，用户会以为删干净了，重新扫描又把文件收回来；
 * 反过来先删行再删文件失败，则会留下一条点开就报错的记录。生产里 SAF 拒绝写入很难复现，
 * 只能靠这里把两种失败路径钉死。
 */
class LocalDeletionTest {

    private fun video(uri: String, title: String = uri) = VideoEntity(
        localUri = uri,
        fileName = title,
        iwaraId = null,
        title = title,
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
        addedAt = 0L
    )

    @Test
    fun `permanent delete plans to remove the file first`() {
        val target = video("content://a/one")
        assertEquals(
            LocalDeletionPlan.DeleteRowAndFiles(target),
            planLocalDeletion(target, deleteFiles = true, playingLocalUri = null)
        )
    }

    @Test
    fun `library removal keeps the file on disk`() {
        val target = video("content://a/one")
        assertEquals(
            LocalDeletionPlan.DeleteRowOnly(target),
            planLocalDeletion(target, deleteFiles = false, playingLocalUri = null)
        )
    }

    @Test
    fun `permanent delete is refused while that file is playing`() {
        val target = video("content://a/one")
        val plan = planLocalDeletion(target, deleteFiles = true, playingLocalUri = target.localUri)
        assertTrue(plan is LocalDeletionPlan.Abort)
        assertEquals("该视频正在播放，请先退出播放页", (plan as LocalDeletionPlan.Abort).reason)
    }

    @Test
    fun `library removal is allowed while the file is playing`() {
        val target = video("content://a/one")
        assertEquals(
            LocalDeletionPlan.DeleteRowOnly(target),
            planLocalDeletion(target, deleteFiles = false, playingLocalUri = target.localUri)
        )
    }

    @Test
    fun `file failure keeps the row and reports the reason`() = runBlocking {
        val target = video("content://a/one")
        var rowDeleted = false
        val failure = executeLocalDeletion(
            plan = planLocalDeletion(target, deleteFiles = true, playingLocalUri = null),
            deleteFile = { "写入权限被拒绝" },
            deleteRow = { rowDeleted = true }
        )
        assertEquals("写入权限被拒绝", failure)
        assertFalse("文件删不掉时绝不能删行", rowDeleted)
    }

    @Test
    fun `successful file deletion removes the row`() = runBlocking {
        val target = video("content://a/one")
        val deletedFiles = mutableListOf<String>()
        val deletedRows = mutableListOf<String>()
        val failure = executeLocalDeletion(
            plan = planLocalDeletion(target, deleteFiles = true, playingLocalUri = null),
            deleteFile = { deletedFiles += it.localUri; null },
            deleteRow = { deletedRows += it.localUri }
        )
        assertNull(failure)
        assertEquals(listOf("content://a/one"), deletedFiles)
        assertEquals(listOf("content://a/one"), deletedRows)
    }

    @Test
    fun `library removal never touches the file`() = runBlocking {
        val target = video("content://a/one")
        var fileTouched = false
        var rowDeleted = false
        val failure = executeLocalDeletion(
            plan = planLocalDeletion(target, deleteFiles = false, playingLocalUri = null),
            deleteFile = { fileTouched = true; null },
            deleteRow = { rowDeleted = true }
        )
        assertNull(failure)
        assertFalse("从库移除只删记录", fileTouched)
        assertTrue(rowDeleted)
    }

    @Test
    fun `abort plan touches neither the file nor the row`() = runBlocking {
        val target = video("content://a/one")
        var called = false
        val failure = executeLocalDeletion(
            plan = LocalDeletionPlan.Abort("正在播放"),
            deleteFile = { called = true; null },
            deleteRow = { called = true }
        )
        assertEquals("正在播放", failure)
        assertFalse(called)
    }

    @Test
    fun `batch stops at the first file failure and keeps every remaining row`() = runBlocking {
        val first = video("content://a/first")
        val second = video("content://a/second")
        val deletedRows = mutableListOf<String>()
        val failure = executeLocalDeletionBatch(
            videos = listOf(first, second),
            deleteFiles = true,
            playingLocalUri = null,
            deleteFile = { if (it == first) "文件删除被系统拒绝" else null },
            deleteRow = { deletedRows += it.localUri }
        )
        assertEquals("文件删除被系统拒绝", failure)
        assertTrue("失败的那一行必须保留", deletedRows.isEmpty())
    }

    @Test
    fun `batch removes the earlier row then stops at a later failure`() = runBlocking {
        val first = video("content://a/first")
        val second = video("content://a/second")
        val deletedRows = mutableListOf<String>()
        val failure = executeLocalDeletionBatch(
            videos = listOf(first, second),
            deleteFiles = true,
            playingLocalUri = null,
            deleteFile = { if (it == second) "文件删除被系统拒绝" else null },
            deleteRow = { deletedRows += it.localUri }
        )
        assertEquals("文件删除被系统拒绝", failure)
        assertEquals(listOf("content://a/first"), deletedRows)
    }
}
