package io.github.amatsukiichika.iwaram.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主内容滚动上报（回归：原门只看 isScrollInProgress，宿主那条整屏背景源在滚动/拖滚动条
 * 期间每帧照旧重录 ⇒ 液态玻璃档下长列表掉帧）。
 */
class MainContentScrollActivityTest {

    @Test
    fun nothingReportsYetSoTheContentIsNotScrolling() {
        val activity = MainContentScrollActivity()

        assertFalse(activity.isScrolling)
    }

    @Test
    fun reportingAStartMakesTheContentScrolling() {
        val activity = MainContentScrollActivity()
        val page = Any()

        activity.report(page, scrolling = true)

        assertTrue(activity.isScrolling)
    }

    @Test
    fun reportingAStopClearsThatPageSoRecordingCanResume() {
        val activity = MainContentScrollActivity()
        val page = Any()
        activity.report(page, scrolling = true)

        activity.report(page, scrolling = false)

        assertFalse(activity.isScrolling)
    }

    @Test
    fun onePageStoppingDoesNotClearAnotherPagesScroll() {
        val library = Any()
        val me = Any()
        val activity = MainContentScrollActivity()
        activity.report(library, scrolling = true)
        activity.report(me, scrolling = true)

        activity.report(library, scrolling = false)

        assertTrue(activity.isScrolling)
    }

    @Test
    fun repeatedReportsOfTheSameStateAreHarmless() {
        val page = Any()
        val activity = MainContentScrollActivity()
        activity.report(page, scrolling = true)
        activity.report(page, scrolling = true)

        assertTrue(activity.isScrolling)

        activity.report(page, scrolling = false)
        activity.report(page, scrolling = false)

        assertFalse(activity.isScrolling)
    }
}
