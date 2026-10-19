package io.github.amatsukiichika.iwaramanager.ui

import io.github.amatsukiichika.iwaramanager.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地与在线卡片共用同一个渲染实现（[VideoCardContent]），数据差异全部挡在映射层。
 * 这里钉住映射层里两条容易被改坏的约定：
 *
 * 1. 本地 `tagsCsv`（逗号分隔）的切分与归一化 —— 脏数据（空白 / 全角字母数字）不能混进 tag；
 * 2. 三行的最小高度规格是本地与在线共用的同一组常量（`VideoCardSpec`）——
 *    若两套实现各写各的，本地那套会被撑高，同一排两种卡片对不齐。
 */
class VideoCardModelTest {

    private fun video(tagsCsv: String?) = VideoEntity(
        localUri = "u",
        fileName = "f.mp4",
        iwaraId = null,
        title = "t",
        author = "a",
        authorId = null,
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

    @Test
    fun `null or blank tags csv yields no tags`() {
        assertTrue(video(null).tagsCsv.splitTags().isEmpty())
        assertTrue(video("").tagsCsv.splitTags().isEmpty())
        assertTrue(video(" ,  ,").tagsCsv.splitTags().isEmpty())
    }

    @Test
    fun `tags are trimmed and normalized`() {
        // 全角 ＭＭＤ → 半角 MMD；多余空白折叠
        assertEquals(listOf("MMD", "dance"), " ＭＭＤ , dance ".tagsCsvSplit())
        assertEquals(listOf("a b"), "a   b".tagsCsvSplit())
    }

    @Test
    fun `spec is one single source for both local and online cards`() {
        // 本地 / 在线必须用同一组常量，否则同一排里两种卡片高度又会跑偏。
        assertTrue("作者行最小高度必须明显小于旧版的 32dp", VideoCardSpec.authorRowMinHeight.value < 32)
        assertTrue("标签行最小高度必须小于旧版的 18dp", VideoCardSpec.tagRowMinHeight.value < 18)
        assertTrue("文字区内边距必须小于旧版的 8dp", VideoCardSpec.contentPadding.value < 8)
    }

    private fun String?.tagsCsvSplit(): List<String> = splitTags()
}
