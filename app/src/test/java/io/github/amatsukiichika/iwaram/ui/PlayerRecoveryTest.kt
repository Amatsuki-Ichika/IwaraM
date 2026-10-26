package io.github.amatsukiichika.iwaram.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放器错误自动恢复的两条契约：**恢复后要接着播**，**重试上限要真的生效**。
 *
 * 两个都是"看起来有、实际没有"的缺陷：
 *  - 恢复意图取自 `player.isPlaying`，而错误回调触发时播放器已回到 IDLE，值是 false，
 *    于是 7001/1000 恢复出来的新实例 `playWhenReady = false`，错误面板又已被清空 ——
 *    用户看到的是没有任何提示的静默暂停。
 *  - 1000 的重试计数写在装片 effect 里，而 1000 的恢复路径正是"换代 → 新实例 → 装片"，
 *    每一轮都把自己的额度清零，`< 2` 的上限形同不存在，可以无限重建播放器实例。
 */
class PlayerRecoveryTest {

    @Test
    fun `recovery stops at the attempt cap so a broken pipeline cannot rebuild forever`() {
        assertTrue(mayRetryRecovery(0))
        assertTrue(mayRetryRecovery(1))
        assertFalse("第 3 次必须交给错误面板", mayRetryRecovery(2))
        assertFalse(mayRetryRecovery(3))
        assertEquals(2, PLAYER_RECOVERY_MAX_ATTEMPTS)
    }

    @Test
    fun `the retry budget is not refunded by a rebuild of the same source`() {
        assertFalse(
            "同片源换代（1000 恢复/切模型/换档）必须继续累计",
            shouldResetRecoveryBudget("https://cdn.example/a.mp4", "https://cdn.example/a.mp4")
        )
    }

    @Test
    fun `the retry budget resets when the source really changes`() {
        assertTrue(shouldResetRecoveryBudget("https://cdn.example/a.mp4", "https://cdn.example/b.mp4"))
        assertTrue("首次装片", shouldResetRecoveryBudget(null, "https://cdn.example/a.mp4"))
    }

    @Test
    fun `an error must not turn a playing session into a paused one after recovery`() {
        assertTrue(
            "错误回调时 isPlaying 已经为 false，但用户意图是继续播放",
            rebuildPlayIntent(playWhenReady = true, isPlaying = false)
        )
    }

    @Test
    fun `a session the user paused stays paused after recovery`() {
        assertFalse(rebuildPlayIntent(playWhenReady = false, isPlaying = false))
        assertFalse(rebuildPlayIntent(playWhenReady = false, isPlaying = true))
    }
}
