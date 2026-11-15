package io.github.amatsukiichika.iwaram.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页入场过渡门判据（`entryTransitionSettled`）的回归护栏。
 *
 * 钉住三件事：
 * ① 首帧 pageAnimating 还是 false（宿主先置 showingPlayer、后置 playerSliding）时**不能**放行；
 * ② 滑动进行中（pageAnimating = true）**不能**放行；
 * ③ 见过滑动、且已静止 = 落位完成，才放行。
 */
class PlayerEntryGateTest {

    @Test
    fun `first idle frame before the slide starts keeps the light shell`() {
        assertFalse(
            "宿主先置 showingPlayer 后才启动滑动，首帧读到的 false 不是落位",
            entryTransitionSettled(pageAnimating = false, animationSeen = false)
        )
    }

    @Test
    fun `slide in progress keeps the light shell`() {
        assertFalse(entryTransitionSettled(pageAnimating = true, animationSeen = false))
        assertFalse(
            "已经动过也不能在滑动中放行",
            entryTransitionSettled(pageAnimating = true, animationSeen = true)
        )
    }

    @Test
    fun `content is released only after the slide settles`() {
        assertTrue(entryTransitionSettled(pageAnimating = false, animationSeen = true))
    }

    @Test
    fun `fallback timeout outlasts the entry slide so the normal path always wins`() {
        // 主界面入场滑动是 PAGE_SLIDE_MS = 320ms；兜底只防宿主不播动画，不能比动画还短。
        assertTrue(PLAYER_ENTRY_GATE_FALLBACK_MS >= 320L)
    }
}
