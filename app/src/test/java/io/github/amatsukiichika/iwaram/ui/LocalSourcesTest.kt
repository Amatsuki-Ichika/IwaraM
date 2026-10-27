package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多源折组 —— 「本地库显示成一个视频」这条需求的全部逻辑都在这里。
 *
 * 折的是查询结果而不是表结构：算错了最多是显示不对，不会损坏用户数据。
 * 但顺序必须稳定 —— 顺序一起变，每次打开播放器就可能换一个源。
 */
class LocalSourcesTest {

    private fun video(
        uri: String,
        iwaraId: String?,
        fileName: String,
        modifiedAt: Long = 0L
    ) = VideoEntity(
        localUri = uri,
        fileName = fileName,
        iwaraId = iwaraId,
        title = fileName,
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
        addedAt = 0L,
        fileModifiedAt = modifiedAt
    )

    @Test
    fun `three files of the same video collapse into one entry`() {
        val groups = groupLocalSources(
            listOf(
                video("content://a/360", "abc", "[abc] T (360p).mp4"),
                video("content://a/src", "abc", "[abc] T (Source).mp4"),
                video("content://a/1080", "abc", "[abc] T (1080p).mp4")
            )
        )
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].sourceCount)
        assertEquals(
            listOf("Source", "1080p", "360p"),
            groups[0].sources.map { it.sourceDisplayName() }
        )
        assertEquals("content://a/src", groups[0].primary.localUri)
    }

    @Test
    fun `files without an iwara id never get merged together`() {
        val groups = groupLocalSources(
            listOf(
                video("content://a/x", null, "x.mp4"),
                video("content://a/y", null, "y.mp4")
            )
        )
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.sourceCount == 1 })
    }

    @Test
    fun `unknown quality ranks last but stays in the group`() {
        val groups = groupLocalSources(
            listOf(
                video("content://a/weird", "abc", "[abc] T.mp4"),
                video("content://a/1080", "abc", "[abc] T (1080p).mp4")
            )
        )
        assertEquals(2, groups[0].sourceCount)
        assertEquals("content://a/1080", groups[0].primary.localUri)
    }

    @Test
    fun `same quality prefers the more recently modified file and the order is stable`() {
        val a = video("content://a/old", "abc", "[abc] T (1080p).mp4", modifiedAt = 1_000L)
        val b = video("content://a/new", "abc", "[abc] T (1080p).mp4", modifiedAt = 2_000L)
        val first = groupLocalSources(listOf(a, b))[0].sources.map { it.localUri }
        val again = groupLocalSources(listOf(b, a))[0].sources.map { it.localUri }
        assertEquals(listOf("content://a/new", "content://a/old"), first)
        assertEquals("输入顺序变化不得改变输出顺序", first, again)
    }

    @Test
    fun `an empty library yields no groups`() {
        assertTrue(groupLocalSources(emptyList()).isEmpty())
    }
}
