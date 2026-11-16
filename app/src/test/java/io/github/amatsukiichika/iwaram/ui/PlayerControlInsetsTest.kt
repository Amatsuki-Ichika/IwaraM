package io.github.amatsukiichika.iwaram.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 全屏播放控制条四边内缩（`fullscreenControlBarInsets`）的回归护栏。
 *
 * 用户要求"按钮内缩、避让刘海和手势条、画面不缩"：左右/上不能被挖孔挡住，
 * 下要同时照顾三键导航与手势导航；没有系统遮挡时也要保留下限，按钮不贴边。
 */
class PlayerControlInsetsTest {

    @Test
    fun `no system insets falls back to the fixed minimums`() {
        val insets = fullscreenControlBarInsets(0f, 0f, 0f, 0f, 0f)
        assertEquals(16f, insets.start, 1e-4f)
        assertEquals(12f, insets.top, 1e-4f)
        assertEquals(16f, insets.end, 1e-4f)
        assertEquals(16f, insets.bottom, 1e-4f)
    }

    @Test
    fun `cutout on a side pushes the bar content past it`() {
        val insets = fullscreenControlBarInsets(
            cutoutStartDp = 32f,
            cutoutTopDp = 40f,
            cutoutEndDp = 24f,
            navigationBarBottomDp = 0f,
            systemGestureBottomDp = 0f
        )
        assertEquals(32f, insets.start, 1e-4f)
        assertEquals(40f, insets.top, 1e-4f)
        assertEquals(24f, insets.end, 1e-4f)
    }

    @Test
    fun `bottom takes the larger of navigation bar and gesture inset`() {
        assertEquals(48f, fullscreenControlBarInsets(0f, 0f, 0f, 48f, 24f).bottom, 1e-4f)
        assertEquals(48f, fullscreenControlBarInsets(0f, 0f, 0f, 24f, 48f).bottom, 1e-4f)
        assertEquals(16f, fullscreenControlBarInsets(0f, 0f, 0f, 8f, 8f).bottom, 1e-4f)
    }

    @Test
    fun `system insets smaller than the minimum do not shrink the padding`() {
        val insets = fullscreenControlBarInsets(4f, 4f, 4f, 4f, 4f)
        assertEquals(16f, insets.start, 1e-4f)
        assertEquals(12f, insets.top, 1e-4f)
        assertEquals(16f, insets.end, 1e-4f)
        assertEquals(16f, insets.bottom, 1e-4f)
    }
}
