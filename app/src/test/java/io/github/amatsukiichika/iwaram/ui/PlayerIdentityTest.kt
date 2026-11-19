package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放页「当前这一部片」的身份推导。
 *
 * 页面用它决定换片时要不要清零进度：身份不变（同一部片的本地多源换源）必须保住进度，
 * 身份变了（推荐互切 / 换片）必须清零 —— 两条语义各钉一个测试。
 */
class PlayerIdentityTest {

    private fun video(localUri: String, iwaraId: String?) = VideoEntity(
        localUri = localUri,
        fileName = localUri.substringAfterLast('/'),
        iwaraId = iwaraId,
        title = localUri,
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
    fun `local sources of the same video share the same identity`() {
        val source = video("content://downloads/abc_source.mp4", iwaraId = "abc")
        val lower = video("content://downloads/abc_480p.mp4", iwaraId = "abc")

        assertEquals("abc", playerVideoIdentity(source))
        assertEquals(playerVideoIdentity(source), playerVideoIdentity(lower))
    }

    @Test
    fun `a local copy and the online video share the same identity`() {
        val online = video("", iwaraId = "abc")
        val downloaded = video("content://downloads/abc.mp4", iwaraId = "abc")

        assertEquals(playerVideoIdentity(online), playerVideoIdentity(downloaded))
    }

    @Test
    fun `entries without an iwara id fall back to their own uri`() {
        val first = video("content://downloads/first.mp4", iwaraId = null)
        val second = video("content://downloads/second.mp4", iwaraId = null)

        assertEquals("local:content://downloads/first.mp4", playerVideoIdentity(first))
        assertNotEquals(playerVideoIdentity(first), playerVideoIdentity(second))
    }

    @Test
    fun `different videos never share an identity`() {
        assertNotEquals(
            playerVideoIdentity(video("", iwaraId = "abc")),
            playerVideoIdentity(video("", iwaraId = "def"))
        )
    }

    @Test
    fun `entries with neither an iwara id nor a local uri have no identity`() {
        assertNull(playerVideoIdentity(video("", iwaraId = null)))
        assertNull(playerVideoIdentity(video("   ", iwaraId = "  ")))
    }
}
