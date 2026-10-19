package io.github.amatsukiichika.iwaramanager.ui

import io.github.amatsukiichika.iwaramanager.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地搜索匹配规则的单测：搜索词需命中界面上能看到 / 能唯一定位视频的所有字段。
 * 只匹配 title / author / tagsCsv 不够 —— 未匹配元数据的视频卡片显示的是文件名却搜不到；
 * 且搜索词在四个分类 tab（作者/文件夹/时间/标签）里也应生效。
 */
class LibrarySearchTest {

    private fun video(
        title: String = "",
        fileName: String = "video.mp4",
        author: String? = null,
        authorId: String? = null,
        iwaraId: String? = null,
        tagsCsv: String? = null
    ) = VideoEntity(
        localUri = "content://test/$fileName",
        fileName = fileName,
        iwaraId = iwaraId,
        title = title,
        author = author,
        authorId = authorId,
        createdAt = null,
        viewCount = null,
        likeCount = null,
        commentCount = null,
        rating = null,
        thumbnailUrl = null,
        localThumbnailPath = null,
        relPath = null,
        tagsCsv = tagsCsv,
        metaFetched = false,
        addedAt = 0L
    )

    /* ---------- matchesQuery ---------- */

    @Test
    fun `matches by title author and tag`() {
        val v = video(title = "MMD Dance", author = "Kanna", tagsCsv = "mmd,kanna_model")
        assertTrue(matchesQuery(v, "dance"))
        assertTrue(matchesQuery(v, "kanna"))
        assertTrue(matchesQuery(v, "mmd"))
    }

    @Test
    fun `matches file name even when title is blank`() {
        // 未匹配元数据的视频标题为空、卡片显示的就是文件名——搜文件名必须能命中。
        val v = video(fileName = "[abc123XYZ] summer trip.mp4")
        assertTrue(matchesQuery(v, "summer"))
        assertTrue(matchesQuery(v, "abc123XYZ"))
    }

    @Test
    fun `matches author id and iwara id`() {
        val v = video(author = "Kanna", authorId = "kanna_ch", iwaraId = "AbCdEf12345")
        assertTrue(matchesQuery(v, "kanna_ch"))
        assertTrue(matchesQuery(v, "abcdef12345")) // ID 查找忽略大小写
    }

    @Test
    fun `is case insensitive across fields`() {
        val v = video(title = "Summer Trip", tagsCsv = "MMD")
        assertTrue(matchesQuery(v, "SUMMER"))
        assertTrue(matchesQuery(v, "mmd"))
    }

    @Test
    fun `misses unrelated query`() {
        val v = video(title = "Summer Trip", author = "Kanna", tagsCsv = "mmd")
        assertFalse(matchesQuery(v, "winter"))
        assertFalse(matchesQuery(v, "rem"))
    }

    @Test
    fun `null optional fields never crash and never match`() {
        val v = video()
        assertFalse(matchesQuery(v, "kanna"))
        assertTrue(matchesQuery(v, "video.mp4"))
    }

    @Test
    fun `blank query matches everything`() {
        assertTrue(matchesQuery(video(), "  "))
    }

    /* ---------- filterBucket（分类视图响应搜索词） ---------- */

    private data class Bucket(val name: String)

    @Test
    fun `bucket filter keeps name matches ignoring case`() {
        val list = listOf(Bucket("Kanna"), Bucket("Miku"), Bucket("kanna2"))
        val out = filterBucket(list, "KANNA") { it.name }
        assertEquals(listOf("Kanna", "kanna2"), out.map { it.name })
    }

    @Test
    fun `bucket filter returns same list for blank query`() {
        val list = listOf(Bucket("Kanna"), Bucket("Miku"))
        assertSame(list, filterBucket(list, "  ") { it.name })
    }
}
