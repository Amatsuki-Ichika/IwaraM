package io.github.amatsukiichika.iwaram.ui.browse

import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「下一集」预取候选的选择：ADR 0038 承诺挑第一个未缓存的条目。
 * 之前只看 id 不同：第一条若已缓存，prefetchSignedUrls 直接 no-op，
 * 后面真正未缓存的一集永远轮不到预取。
 */
class RelatedPrefetchCandidateTest {

    @Test
    fun `skips cached entries, missing ids and the video already playing`() {
        val related = listOf(
            IwaraVideoItem(id = "current"),
            IwaraVideoItem(id = "cached"),
            IwaraVideoItem(id = null),
            IwaraVideoItem(id = "uncached")
        )
        val picked = firstUncachedRelatedVideo(related, currentId = "current") { it == "cached" }
        assertEquals("uncached", picked?.id)
    }

    @Test
    fun `picks the first uncached entry in list order`() {
        val related = listOf(IwaraVideoItem(id = "first"), IwaraVideoItem(id = "second"))
        val picked = firstUncachedRelatedVideo(related, currentId = "current") { false }
        assertEquals("first", picked?.id)
    }

    @Test
    fun `returns null when every entry is cached or is the current video`() {
        val related = listOf(IwaraVideoItem(id = "current"), IwaraVideoItem(id = "cached"))
        val picked = firstUncachedRelatedVideo(related, currentId = "current") { true }
        assertNull(picked)
    }
}
