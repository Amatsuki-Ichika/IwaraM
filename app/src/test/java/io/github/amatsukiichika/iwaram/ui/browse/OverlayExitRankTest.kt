package io.github.amatsukiichika.iwaram.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖层退场页的层序契约：弹出最后一页后，退场页在 320ms 动画期间仍算"在屏"。
 *
 * 只把活栈顶序号喂给 [drawOrderSeqs] 时，弹栈当帧 `overlayTopSeq` 就是 null ⇒ zIndex 塌到 0，
 * 退场页被不透明的播放页盖住（滑出看不见），backOwner 也会落回主页面
 *（第二下返回清筛选 / 收起搜索，甚至退出 App）。[overlayOnScreenTopSeq] 把退场页并进"在屏"集合。
 */
class OverlayExitRankTest {

    @Test
    fun `an exiting top page keeps its rank above the player after the live stack empties`() {
        val leavingSeq = 13L
        val playerSeq = 12L
        // 模拟 MainActivity：活栈已空，只剩宿主回报的退场页序号。
        val onScreenSeq = overlayOnScreenTopSeq(liveTopSeq = null, leavingTopSeq = leavingSeq)
        assertEquals(leavingSeq, onScreenSeq)

        val seqs = drawOrderSeqs(
            overlayTopSeq = onScreenSeq,
            settingsSeq = 0L,
            settingsOnScreen = false,
            playerSeq = playerSeq,
            playerOnScreen = true
        )
        // 退场页序号更大 ⇒ 它画在播放页之上，滑出动画全程可见。
        assertTrue(zIndexOf(leavingSeq, seqs) > zIndexOf(playerSeq, seqs))
    }

    @Test
    fun `a page pushed while the previous one is still exiting ranks above it`() {
        // 上一页还在退场时压入新页：取新页（序号更大），退场页继续参与排序。
        assertEquals(14L, overlayOnScreenTopSeq(liveTopSeq = 14L, leavingTopSeq = 13L))
    }

    @Test
    fun `a live stack without an exiting page keeps its own top`() {
        assertEquals(9L, overlayOnScreenTopSeq(liveTopSeq = 9L, leavingTopSeq = null))
    }

    @Test
    fun `no page on screen reports null so the main page owns back again`() {
        assertNull(overlayOnScreenTopSeq(liveTopSeq = null, leavingTopSeq = null))
    }

    @Test
    fun `an exiting overlay page still owns back during the exit window`() {
        // 弹出最后一页后、退场动画结束前：返回键必须仍归覆盖层（宿主吞掉这一下），
        // 否则会落到主页面去清筛选 / 收起搜索，或退出 App。
        val leavingSeq = 13L
        val playerSeq = 12L
        val onScreenSeq = overlayOnScreenTopSeq(liveTopSeq = null, leavingTopSeq = leavingSeq)
        assertEquals(
            BackOwner.OVERLAY,
            backOwner(
                onboardingVisible = false,
                sheetOpen = false,
                overlaySeq = onScreenSeq,
                overlayOnScreen = onScreenSeq != null,
                settingsSeq = null,
                settingsOnScreen = false,
                playerSeq = playerSeq,
                playerOnScreen = true
            )
        )
        // 退场结束后（序号清空）才交还播放页。
        assertEquals(
            BackOwner.PLAYER,
            backOwner(
                onboardingVisible = false,
                sheetOpen = false,
                overlaySeq = null,
                overlayOnScreen = false,
                settingsSeq = null,
                settingsOnScreen = false,
                playerSeq = playerSeq,
                playerOnScreen = true
            )
        )
    }
}
