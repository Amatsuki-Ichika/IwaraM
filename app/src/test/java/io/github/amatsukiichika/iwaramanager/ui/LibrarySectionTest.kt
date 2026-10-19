package io.github.amatsukiichika.iwaramanager.ui

import io.github.amatsukiichika.iwaramanager.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本地『下载时间』排序」分节标签的回归用例。
 *
 * 若 `timeBucketSection` 只处理 `NEWEST/OLDEST`，把 `ADDED_NEW/ADDED_OLD` 落到 else 分支的
 * `nameSection("2026年1月")`（首字符不是字母 → 全部归 `"#"`），`buildMarkers` 只剩 1 个标记，
 * `FastScrollbar` 的 `markers.size < 2` 直接不渲染 → 右边没有滚动条。
 * 现在与「最新」同款返回月份标签，各月互不相同 → 标记数 ≥ 2 → 滚动条恢复。
 */
class LibrarySectionTest {

    private fun bucket(label: String, count: Int = 3) =
        TimeBucket(key = label, label = label, startEpochMs = 0L, endEpochMs = 0L, count = count)

    @Test
    fun `download time sorts use the month label just like newest`() {
        val jan = bucket("2026年1月")
        assertEquals("2026年1月", timeBucketSection(jan, SortMode.NEWEST))
        assertEquals("2026年1月", timeBucketSection(jan, SortMode.OLDEST))
        assertEquals("2026年1月", timeBucketSection(jan, SortMode.ADDED_NEW))
        assertEquals("2026年1月", timeBucketSection(jan, SortMode.ADDED_OLD))
    }

    @Test
    fun `download time sections stay distinct across months so markers can be built`() {
        val labels = listOf("2026年1月", "2025年12月", "2025年11月")
            .map { timeBucketSection(bucket(it), SortMode.ADDED_NEW) }
        assertEquals(listOf("2026年1月", "2025年12月", "2025年11月"), labels)
        // 修复前这三个都会退化成 "#"，标记只剩 1 个 → 滚动条不渲染。
        assertNotEquals(listOf("#", "#", "#"), labels)
        assertTrue(labels.toSet().size >= 2)
    }

    @Test
    fun `count sort uses the count tier and name sort uses the first letter`() {
        assertEquals("100+", timeBucketSection(bucket("任意月份", 320), SortMode.COUNT_DESC))
        assertEquals("<100", timeBucketSection(bucket("任意月份", 7), SortMode.COUNT_ASC))
        assertEquals("A", timeBucketSection(bucket("Aki", 7), SortMode.NAME_ASC))
        // 名称排序仍按首字母：非字母开头（月份标签）归 "#"，这是预期行为。
        assertEquals("#", timeBucketSection(bucket("2026年1月", 7), SortMode.NAME_DESC))
    }

    private fun videoOf(addedAt: Long) = VideoEntity(
        localUri = "u$addedAt", fileName = "f$addedAt", iwaraId = null, title = "t$addedAt",
        author = null, authorId = null, createdAt = null, viewCount = null, likeCount = null,
        commentCount = null, rating = null, thumbnailUrl = null, localThumbnailPath = null,
        relPath = null, tagsCsv = null, metaFetched = true, addedAt = addedAt
    )

    /**
     * 全部 tab 的分节按入库日期：若按入库月份，视频集中在最近一两个月时只会剩 1 个分节，
     * `markers.size < 2` 会让滚动条整条消失；按天可保证近期下载也能一屏一屏跳。
     */
    @Test
    fun `download time sections on all-tab are daily so recent downloads still get markers`() {
        val sameDay = listOf(
            videoOf(addedAt = 1_760_000_000_000L),
            videoOf(addedAt = 1_760_000_100_000L)
        )
        val nextDay = videoOf(addedAt = 1_760_900_000_000L)
        val sections = (sameDay + nextDay).map { videoSection(it, SortMode.ADDED_NEW) }
        // 同一天的分节标签相同、不同天不同 → 标记可连成 ≥2 段。
        assertEquals(sections[0], sections[1])
        assertNotEquals(sections[0], sections[2])
        assertTrue(sections.toSet().size >= 2)
        // 标签形如 "09月13日"。
        assertTrue(sections[0].matches(Regex("\\d{2}月\\d{2}日")))
    }
}
