package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.data.VideoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSorterTest {

    private fun video(
        uri: String,
        title: String = uri,
        createdAt: Long? = null,
        addedAt: Long = 0L,
        viewCount: Int? = null,
        fileModifiedAt: Long = 0L
    ) = VideoEntity(
        localUri = uri,
        fileName = "$uri.mp4",
        iwaraId = null,
        title = title,
        author = null,
        authorId = null,
        createdAt = createdAt,
        viewCount = viewCount,
        likeCount = null,
        commentCount = null,
        rating = null,
        thumbnailUrl = null,
        localThumbnailPath = null,
        relPath = null,
        tagsCsv = null,
        metaFetched = true,
        addedAt = addedAt,
        fileModifiedAt = fileModifiedAt
    )

    private val a = video("a", title = "beta", createdAt = 100L, addedAt = 300L, viewCount = 50)
    private val b = video("b", title = "Alpha", createdAt = 300L, addedAt = 100L, viewCount = 500)
    private val c = video("c", title = "gamma", createdAt = null, addedAt = 200L, viewCount = 5000)

    @Test
    fun `newest sorts by createdAt descending with nulls last`() {
        assertEquals(listOf(b, a, c), VideoSorter.sortVideos(listOf(a, b, c), SortMode.NEWEST))
    }

    @Test
    fun `oldest sorts by createdAt ascending with nulls last`() {
        assertEquals(listOf(a, b, c), VideoSorter.sortVideos(listOf(c, b, a), SortMode.OLDEST))
    }

    @Test
    fun `added new sorts by download time descending`() {
        assertEquals(listOf(a, c, b), VideoSorter.sortVideos(listOf(b, c, a), SortMode.ADDED_NEW))
    }

    @Test
    fun `added old sorts by download time ascending`() {
        assertEquals(listOf(b, c, a), VideoSorter.sortVideos(listOf(a, c, b), SortMode.ADDED_OLD))
    }

    // 「下载时间 = 文件修改日期」的两段式比较器回归：取得到修改日期的行按其排，
    // 取不到（0，未匹配/SAF provider 不上报）的**恒垫底**、组内退回 addedAt 同方向排。
    // 未匹配视频（mtime=0）若被排到最顶部（单键升序置顶）即为回归。
    private val m1 = video("m1", fileModifiedAt = 500L, addedAt = 1L)
    private val m2 = video("m2", fileModifiedAt = 300L, addedAt = 999L)
    private val u1 = video("u1", fileModifiedAt = 0L, addedAt = 10_000L)
    private val u2 = video("u2", fileModifiedAt = 0L, addedAt = 20L)

    @Test
    fun `added new sorts by file mtime and keeps unknown mtime last`() {
        // mtime 优先于 addedAt（m1 的 addedAt 最小却最新）；未知组内按 addedAt 降序。
        assertEquals(
            listOf(m1, m2, u1, u2),
            VideoSorter.sortVideos(listOf(u1, m2, u2, m1), SortMode.ADDED_NEW)
        )
    }

    @Test
    fun `added old sorts by file mtime ascending and keeps unknown mtime last`() {
        // 已知组按 mtime 升序（m2 < m1）；未知组垫底、按 addedAt 升序（u2 < u1）。
        assertEquals(
            listOf(m2, m1, u2, u1),
            VideoSorter.sortVideos(listOf(u1, m2, u2, m1), SortMode.ADDED_OLD)
        )
    }

    @Test
    fun `added sorts fall back to addedAt when no mtime known`() {
        // 三条都没有修改日期 → 完全退化为旧行为（按 addedAt）。
        assertEquals(listOf(a, c, b), VideoSorter.sortVideos(listOf(b, c, a), SortMode.ADDED_NEW))
        assertEquals(listOf(b, c, a), VideoSorter.sortVideos(listOf(a, c, b), SortMode.ADDED_OLD))
    }

    @Test
    fun `name sort is case insensitive`() {
        assertEquals(listOf(b, a, c), VideoSorter.sortVideos(listOf(a, b, c), SortMode.NAME_ASC))
        assertEquals(listOf(c, a, b), VideoSorter.sortVideos(listOf(a, b, c), SortMode.NAME_DESC))
    }

    /**
     * 「A-Z/Z-A 排序出现两个 #」的回归用例。
     * 若直接按 `title.lowercase()` 排：ASCII 数字/符号（"2020…"、"#…"）排在所有字母
     * **前面**，中文/全角/符号排在**后面** —— 两堆都被 nameSection 归进 "#"，产生两个不相连
     * 的 # 标记。现在先按 sectionRank（# < A < … < Z）再按标题，同一分节必然连续。
     */
    @Test
    fun `section rank keeps hash section contiguous across ascii and cjk titles`() {
        val titles = listOf("2020 回顾", "#tag", "banana", "Apple", "中文标题", "★特别篇", "zeta")
        val asc = titles
            .mapIndexed { i, t -> video("v$i", title = t) }
            .sortedWith(VideoSorter.nameOrder(VideoEntity::title, desc = false))
        // 所有 "#" 档（非 a-z 开头）必须连续出现且只在开头一段；随后 A→Z 单调不减。
        val ranks = asc.map { VideoSorter.sectionRank(it.title) }
        assertEquals(0, ranks.count { it == 0 } - ranks.takeWhile { it == 0 }.size)
        assertTrue(ranks.zipWithNext().all { (x, y) -> x <= y })

        val desc = titles
            .mapIndexed { i, t -> video("v$i", title = t) }
            .sortedWith(VideoSorter.nameOrder(VideoEntity::title, desc = true))
        val descRanks = desc.map { VideoSorter.sectionRank(it.title) }
        // 降序：Z→A 单调不增，# 段恒在最后且连续。
        assertTrue(descRanks.zipWithNext().all { (x, y) -> x >= y })
        assertEquals(0, descRanks.last())
        assertTrue(descRanks.dropLastWhile { it == 0 }.last() > 0)
    }

    @Test
    fun `section rank maps letters to 1-26 and everything else to 0`() {
        assertEquals(1, VideoSorter.sectionRank("apple"))
        assertEquals(26, VideoSorter.sectionRank(" Zebra "))
        assertEquals(0, VideoSorter.sectionRank("2020"))
        assertEquals(0, VideoSorter.sectionRank("#tag"))
        assertEquals(0, VideoSorter.sectionRank("中文"))
        assertEquals(0, VideoSorter.sectionRank(""))
    }

    @Test
    fun `count sort treats missing views as smallest`() {
        assertEquals(listOf(c, b, a), VideoSorter.sortVideos(listOf(a, b, c), SortMode.COUNT_DESC))
        assertEquals(listOf(a, b, c), VideoSorter.sortVideos(listOf(c, b, a), SortMode.COUNT_ASC))
    }

    @Test
    fun `folder buckets fall back to name for time sorts`() {
        val buckets = listOf(FolderBucket("k2", "B", 1), FolderBucket("k1", "A", 2))
        val sorted = VideoSorter.sortFolderBuckets(buckets, SortMode.ADDED_NEW)
        assertEquals(listOf("A", "B"), sorted.map { it.name })
    }

    @Test
    fun `folder buckets sort by count then name`() {
        val buckets = listOf(
            FolderBucket("k1", "B", 2),
            FolderBucket("k2", "A", 2),
            FolderBucket("k3", "C", 1)
        )
        val sorted = VideoSorter.sortFolderBuckets(buckets, SortMode.COUNT_DESC)
        assertEquals(listOf("A", "B", "C"), sorted.map { it.name })
    }

    @Test
    fun `time buckets keep unknown month at end for date sorts`() {
        val dated = listOf(
            TimeBucket("2026-01", "2026年1月", 1, 2, 1),
            TimeBucket("2026-03", "2026年3月", 3, 4, 1)
        )
        val unknown = listOf(TimeBucket("__unknown__", "未识别时间", 0, 0, 1))
        val sorted = VideoSorter.sortTimeBuckets(dated, unknown, SortMode.NEWEST)
        assertEquals(listOf("2026年3月", "2026年1月", "未识别时间"), sorted.map { it.label })
        val asc = VideoSorter.sortTimeBuckets(dated, unknown, SortMode.OLDEST)
        assertEquals(listOf("2026年1月", "2026年3月", "未识别时间"), asc.map { it.label })
        assertTrue(asc.last().key == "__unknown__")
    }
}
