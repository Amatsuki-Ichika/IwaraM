package io.github.amatsukiichika.iwaramanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载通知进度条取值边界的回归用例。
 *
 * 若直接 `setProgress((total / 1024).toInt(), (received / 1024).toInt(), …)`，
 * `received > total` 时 progress 会**超过 max**（部分 ROM 画出越界进度条），
 * 超大文件 `total / 1024` 还有 Int 溢出风险。这个纯函数是这条契约的唯一出口。
 */
class NotificationProgressBoundsTest {

    @Test
    fun `normal case maps bytes to kb`() {
        val (max, progress) = notificationProgressBounds(received = 512 * 1024L, total = 1024 * 1024L)
        assertEquals(1024, max)
        assertEquals(512, progress)
    }

    @Test
    fun `progress never exceeds max even when server length is wrong`() {
        val (max, progress) = notificationProgressBounds(received = 8 * 1024L * 1024L, total = 1024 * 1024L)
        assertTrue("progress($progress) 不得超过 max($max)", progress <= max)
        assertEquals(1024, progress)
    }

    @Test
    fun `unknown total falls back to the legacy 100 - 0 pair`() {
        val (max, progress) = notificationProgressBounds(received = 4096L, total = -1L)
        assertEquals(100, max)
        // total 未知时调用方传 indeterminate=true，progress 被系统忽略；这里只要求不越界。
        assertTrue(progress in 0..max)
    }

    @Test
    fun `huge total does not overflow into a negative max`() {
        // 3 TB：total / 1024 远超 Int 上限，必须夹到 Int.MAX_VALUE 而不是溢出成负数。
        val (max, progress) = notificationProgressBounds(received = 0L, total = 3L * 1024 * 1024 * 1024 * 1024)
        assertTrue("max 必须为正，实际 $max", max > 0)
        assertEquals(Int.MAX_VALUE, max)
        assertEquals(0, progress)
    }

    @Test
    fun `zero progress and zero total are safe`() {
        val (max, progress) = notificationProgressBounds(received = 0L, total = 0L)
        assertEquals(100, max)
        assertEquals(0, progress)
    }
}
