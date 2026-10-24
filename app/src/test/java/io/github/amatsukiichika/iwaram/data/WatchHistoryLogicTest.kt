package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 观看历史的纯逻辑单测：
 * key 推导 / 续播起点判定 / 封面 URL 反解 —— 三条规则都会走真实播放路径，改坏任一条
 * 都会表现为"历史重复/不置顶、续播乱跳、历史封面丢失"，所以钉死在这里。
 */
class WatchHistoryLogicTest {

    /* ---------- watchHistoryKey ---------- */

    @Test
    fun `key 有 iwaraId 时优先 iwaraId`() {
        assertEquals("abc123", watchHistoryKey(iwaraId = "abc123", localUri = "content://x"))
    }

    @Test
    fun `key 无 iwaraId 时用 local 前缀兜底`() {
        assertEquals("local:content://x", watchHistoryKey(iwaraId = null, localUri = "content://x"))
    }

    @Test
    fun `key 空白 iwaraId 视同缺失`() {
        assertEquals("local:content://x", watchHistoryKey(iwaraId = "  ", localUri = "content://x"))
        assertEquals("local:content://x", watchHistoryKey(iwaraId = "", localUri = "content://x"))
    }

    @Test
    fun `key 两者皆空返回 null（调用方跳过记录）`() {
        assertNull(watchHistoryKey(iwaraId = null, localUri = null))
        assertNull(watchHistoryKey(iwaraId = "", localUri = ""))
    }

    @Test
    fun `本地副本与在线同 id 时 key 合并（同一行，重看置顶不重复）`() {
        // 下载后本地播放（有 iwaraId + localUri）与在线播放（只有 iwaraId）必须落到同一行
        assertEquals(
            watchHistoryKey(iwaraId = "abc123", localUri = "content://x"),
            watchHistoryKey(iwaraId = "abc123", localUri = null)
        )
    }

    /* ---------- resumeStartMs ---------- */

    @Test
    fun `续播 进度不足 5 秒从头播`() {
        assertEquals(0L, resumeStartMs(positionMs = 4_999, durationMs = 600_000))
        assertEquals(0L, resumeStartMs(positionMs = 0, durationMs = 600_000))
    }

    @Test
    fun `续播 进度在中段则原样续`() {
        assertEquals(300_000L, resumeStartMs(positionMs = 300_000, durationMs = 600_000))
    }

    @Test
    fun `续播 进度达到 95% 视为看完 从头播`() {
        assertEquals(0L, resumeStartMs(positionMs = 570_000, durationMs = 600_000))
        assertEquals(0L, resumeStartMs(positionMs = 600_000, durationMs = 600_000))
    }

    @Test
    fun `续播 时长未知时不续（避免 5s 判定在 0 时长上误放行）`() {
        assertEquals(0L, resumeStartMs(positionMs = 300_000, durationMs = 0))
        assertEquals(0L, resumeStartMs(positionMs = 300_000, durationMs = -1))
    }

    /* ---------- thumbnailFileIdFromUrl ---------- */

    @Test
    fun `封面反解 标准 thumbnail 模板`() {
        assertEquals(
            "file001",
            thumbnailFileIdFromUrl("https://files.iwara.tv/image/thumbnail/file001/thumbnail-00.jpg")
        )
    }

    @Test
    fun `封面反解 非 thumbnail 模板或空值返回 null`() {
        assertNull(thumbnailFileIdFromUrl("https://files.iwara.tv/image/original/file001.jpg"))
        assertNull(thumbnailFileIdFromUrl(null))
        assertNull(thumbnailFileIdFromUrl(""))
        assertNull(thumbnailFileIdFromUrl("not a url"))
    }

    /* ---------- historyGroupLabel（B 站样式分组头）---------- */

    private fun millisOf(date: java.time.LocalDate, hour: Int): Long =
        date.atStartOfDay(java.time.ZoneId.systemDefault()).plusHours(hour.toLong()).toInstant().toEpochMilli()

    @Test
    fun `分组头 今天`() {
        val now = millisOf(java.time.LocalDate.now(), 12)
        assertEquals("今天", historyGroupLabel(millisOf(java.time.LocalDate.now(), 1), now))
    }

    @Test
    fun `分组头 昨天（跨日）`() {
        val today = java.time.LocalDate.now()
        val now = millisOf(today, 12)
        // 昨天 23:59 与 今天 00:01 是相邻两天：对 now=今天中午而言，昨天任意时刻都是"昨天"
        assertEquals("昨天", historyGroupLabel(millisOf(today.minusDays(1), 23), now))
    }

    @Test
    fun `分组头 今年更早只带月日`() {
        val today = java.time.LocalDate.now()
        val now = millisOf(today, 12)
        val label = historyGroupLabel(millisOf(today.minusDays(10), 8), now)
        org.junit.Assert.assertTrue("应形如 M月d日：$label", label.matches(Regex("\\d{1,2}月\\d{1,2}日")))
    }

    @Test
    fun `分组头 跨年带年份`() {
        val now = millisOf(java.time.LocalDate.now(), 12)
        val label = historyGroupLabel(millisOf(java.time.LocalDate.of(2020, 5, 4), 8), now)
        assertEquals("2020年5月4日", label)
    }

    /* ---------- historyBadge（时长角标）---------- */

    @Test
    fun `角标 中段进度 当前斜杠总时长`() {
        assertEquals("00:44 / 03:40", historyBadge(44_000, 220_000))
    }

    @Test
    fun `角标 看完 95% 显示已看完`() {
        assertEquals("已看完", historyBadge(600_000, 600_000))
        assertEquals("已看完", historyBadge(570_001, 600_000))
    }

    @Test
    fun `角标 超一小时用时 分秒 格式`() {
        assertEquals("1:00:05 / 1:30:00", historyBadge(3_605_000, 5_400_000))
    }

    @Test
    fun `角标 总时长未知只显示当前`() {
        assertEquals("00:12", historyBadge(12_000, 0))
    }
}
