package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作者二级页「视频」栏本地部分的分组与排序。
 *
 * 本地部分固定按发布时间由新到旧排序，不跟随本地库的排序设置；
 * 分组键与本地库「作者」分类保持一致（无 authorId 的本地视频归入「未指定」），
 * 否则从卡片进入作者页会看到与本地库列表不同的分组结果。
 */
class LocalAuthorVideosTest {

    private fun video(
        uri: String,
        authorId: String?,
        createdAt: Long? = null,
        addedAt: Long = 0L
    ) = VideoEntity(
        localUri = uri,
        fileName = "$uri.mp4",
        iwaraId = null,
        title = uri,
        author = "作者",
        authorId = authorId,
        createdAt = createdAt,
        viewCount = null,
        likeCount = null,
        commentCount = null,
        rating = null,
        thumbnailUrl = null,
        localThumbnailPath = null,
        relPath = null,
        tagsCsv = null,
        metaFetched = false,
        addedAt = addedAt
    )

    @Test
    fun `missing or blank author id falls into the unknown bucket`() {
        assertEquals(VideoViewModel.UNKNOWN_AUTHOR_KEY, LocalAuthorVideos.keyOf(video("a", null)))
        assertEquals(VideoViewModel.UNKNOWN_AUTHOR_KEY, LocalAuthorVideos.keyOf(video("b", "")))
        assertEquals(VideoViewModel.UNKNOWN_AUTHOR_KEY, LocalAuthorVideos.keyOf(video("c", "   ")))
        assertEquals("uuid-1", LocalAuthorVideos.keyOf(video("d", "uuid-1")))
        assertEquals("未指定", VideoViewModel.UNKNOWN_AUTHOR_NAME)
    }

    @Test
    fun `unknown bucket collects every unmatched local video`() {
        val source = listOf(
            video("a", null),
            video("b", "uuid-1"),
            video("c", ""),
            video("d", "uuid-2")
        )
        val unknown = LocalAuthorVideos.of(source, VideoViewModel.UNKNOWN_AUTHOR_KEY)
        assertEquals(listOf("a", "c"), unknown.map { it.localUri })
    }

    @Test
    fun `only the requested author's videos come back`() {
        val source = listOf(
            video("a", "uuid-1"),
            video("b", "uuid-2"),
            video("c", "uuid-1"),
            video("d", null)
        )
        assertEquals(
            listOf("a", "c"),
            LocalAuthorVideos.of(source, "uuid-1").map { it.localUri }
        )
        assertEquals(0, LocalAuthorVideos.of(source, "uuid-9").size)
    }

    @Test
    fun `local segment is always newest first regardless of input order`() {
        val source = listOf(
            video("old", "uuid-1", createdAt = 1_000L),
            video("new", "uuid-1", createdAt = 9_000L),
            video("mid", "uuid-1", createdAt = 5_000L)
        )
        assertEquals(
            listOf("new", "mid", "old"),
            LocalAuthorVideos.of(source, "uuid-1").map { it.localUri }
        )
    }

    @Test
    fun `missing publish time falls back to the time it was added`() {
        val source = listOf(
            // 有发布日期的最前，即使入库更晚
            video("published", "uuid-1", createdAt = 100L, addedAt = 1L),
            video("added-late", "uuid-1", createdAt = null, addedAt = 50L),
            video("added-early", "uuid-1", createdAt = null, addedAt = 10L)
        )
        assertEquals(
            listOf("published", "added-late", "added-early"),
            LocalAuthorVideos.of(source, "uuid-1").map { it.localUri }
        )
    }

    @Test
    fun `grouping agrees with the local library author buckets`() {
        val source = listOf(
            video("a", "uuid-1", createdAt = 3L),
            video("b", "uuid-1", createdAt = 2L),
            video("c", null, createdAt = 1L)
        )
        // 两个分组键互不重叠，且合起来正好是全部本地视频（不漏不重）。
        val keys = source.map { LocalAuthorVideos.keyOf(it) }.toSet()
        assertEquals(setOf("uuid-1", VideoViewModel.UNKNOWN_AUTHOR_KEY), keys)
        val total = keys.sumOf { LocalAuthorVideos.of(source, it).size }
        assertEquals(source.size, total)
    }

    @Test
    fun `filtering does not mutate or reorder the source list`() {
        val source = listOf(
            video("old", "uuid-1", createdAt = 1L),
            video("new", "uuid-1", createdAt = 2L)
        )
        val snapshot = source.toList()
        LocalAuthorVideos.of(source, "uuid-1")
        assertEquals(snapshot, source)
        assertTrue(source[0].createdAt!! < source[1].createdAt!!)
    }
}
