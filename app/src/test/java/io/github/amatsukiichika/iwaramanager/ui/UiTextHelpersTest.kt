package io.github.amatsukiichika.iwaramanager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ui 包内的纯文本格式化函数：播放器时间轴、异常摘要、评论 tab 标签。 */
class UiTextHelpersTest {

    // ---------- formatTime（PlayerControls） ----------

    @Test
    fun `formatTime renders seconds only under one minute`() {
        assertEquals("0:05", formatTime(5_000))
        assertEquals("0:59", formatTime(59_999))
    }

    @Test
    fun `formatTime pads minutes and seconds`() {
        assertEquals("12:34", formatTime((12 * 60 + 34) * 1000L))
    }

    @Test
    fun `formatTime switches to hours at one hour`() {
        assertEquals("1:00:00", formatTime(3_600_000L))
        assertEquals("1:01:05", formatTime(3_665_000L))
    }

    // ---------- describeThrowable（SuperResolution） ----------

    @Test
    fun `describeThrowable combines message and top frames`() {
        val text = describeThrowable(IllegalStateException("boom"))
        assertTrue("应以 message 开头: $text", text.startsWith("boom @ "))
        // 至少带上当前测试帧的类名与方法名
        assertTrue(text.contains("describeThrowable"))
    }

    @Test
    fun `describeThrowable falls back to frames when message is blank`() {
        val text = describeThrowable(IllegalStateException())
        // 无 message：只剩栈帧，不应有多余的 "@ " 前缀
        assertTrue(!text.startsWith(" @ "))
        assertTrue(text.contains("describeThrowable"))
    }

    @Test
    fun `describeThrowable truncates beyond 240 chars`() {
        val long = "x".repeat(500)
        val text = describeThrowable(IllegalStateException(long))
        assertEquals(240 + 1, text.length) // 截断到 240 再补一个 "…"
        assertTrue(text.endsWith("…"))
    }

    // ---------- commentTabLabel（PlayerDetails） ----------

    @Test
    fun `commentTabLabel hides count when null`() {
        assertEquals("评论", commentTabLabel(null))
    }

    @Test
    fun `commentTabLabel formats large counts via formatCount`() {
        assertEquals("评论 999", commentTabLabel(999))
        assertEquals("评论 1.2万", commentTabLabel(12_345))
    }
}
