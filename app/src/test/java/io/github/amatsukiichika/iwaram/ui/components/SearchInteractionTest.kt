package io.github.amatsukiichika.iwaram.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 搜索框状态机：用户可见的每一步交互对应哪次状态转移。 */
class SearchInteractionTest {

    @Test
    fun tappingCollapsedPillExpandsIntoEditing() {
        assertEquals(
            SearchPhase.EDITING,
            reduceSearchPhase(SearchPhase.COLLAPSED, SearchFocusEvent.TapField)
        )
    }

    @Test
    fun tappingExpandedPillFromIdleReopensEditing() {
        assertEquals(
            SearchPhase.EDITING,
            reduceSearchPhase(SearchPhase.EXPANDED_IDLE, SearchFocusEvent.TapField)
        )
    }

    @Test
    fun fieldGainingFocusEntersEditing() {
        assertEquals(
            SearchPhase.EDITING,
            reduceSearchPhase(SearchPhase.COLLAPSED, SearchFocusEvent.FieldFocused)
        )
        assertEquals(
            SearchPhase.EDITING,
            reduceSearchPhase(SearchPhase.EXPANDED_IDLE, SearchFocusEvent.FieldFocused)
        )
    }

    @Test
    fun losingFocusKeepsSearchExpandedWithQueryAndResults() {
        assertEquals(
            SearchPhase.EXPANDED_IDLE,
            reduceSearchPhase(SearchPhase.EDITING, SearchFocusEvent.FieldLostFocus)
        )
        // 结果态里再点一次空白不该把已经收起的键盘又当成一次转移。
        assertEquals(
            SearchPhase.EXPANDED_IDLE,
            reduceSearchPhase(SearchPhase.EXPANDED_IDLE, SearchFocusEvent.FieldLostFocus)
        )
    }

    @Test
    fun closingImeIsTheSameDestinationAsLosingFocus() {
        assertEquals(
            SearchPhase.EXPANDED_IDLE,
            reduceSearchPhase(SearchPhase.EDITING, SearchFocusEvent.ImeHidden)
        )
    }

    @Test
    fun imeReappearingFromIdleResumesEditing() {
        assertEquals(
            SearchPhase.EDITING,
            reduceSearchPhase(SearchPhase.EXPANDED_IDLE, SearchFocusEvent.ImeShown)
        )
    }

    @Test
    fun imeShownDoesNotExpandCollapsedSearch() {
        assertEquals(
            SearchPhase.COLLAPSED,
            reduceSearchPhase(SearchPhase.COLLAPSED, SearchFocusEvent.ImeShown)
        )
    }

    @Test
    fun submittingSearchCollapsesKeyboardButKeepsResultsVisible() {
        assertEquals(
            SearchPhase.EXPANDED_IDLE,
            reduceSearchPhase(SearchPhase.EDITING, SearchFocusEvent.Submit)
        )
        assertEquals(
            SearchPhase.EXPANDED_IDLE,
            reduceSearchPhase(SearchPhase.EXPANDED_IDLE, SearchFocusEvent.Submit)
        )
    }

    @Test
    fun switchingTabDefocusesSearchWithoutCollapsingIt() {
        assertEquals(
            SearchPhase.EXPANDED_IDLE,
            reduceSearchPhase(SearchPhase.EDITING, SearchFocusEvent.TabSwitch)
        )
    }

    @Test
    fun returningToTabAfterLeavingDoesNotAskForFocusAgain() {
        // 搜一半切到「我的」：一次 TabSwitch 去焦点；切回再发一次 TabSwitch，
        // 仍是「展开无焦点」—— 这就是「切回来不自动拉起输入法」。
        val afterLeaving = reduceSearchPhase(SearchPhase.EDITING, SearchFocusEvent.TabSwitch)
        val afterReturning = reduceSearchPhase(afterLeaving, SearchFocusEvent.TabSwitch)
        assertEquals(SearchPhase.EXPANDED_IDLE, afterReturning)
    }

    @Test
    fun recreatedScreenKeepsExpandedIdleInsteadOfEditing() {
        // 旋转 / 从二级页返回：状态由保存值恢复，不重新走 TapField，也不请求焦点。
        val restored = SearchPhase.EXPANDED_IDLE
        assertEquals(
            SearchPhase.EXPANDED_IDLE,
            reduceSearchPhase(restored, SearchFocusEvent.FieldLostFocus)
        )
        assertEquals(
            SearchPhase.EXPANDED_IDLE,
            reduceSearchPhase(restored, SearchFocusEvent.ImeHidden)
        )
        assertEquals(
            SearchPhase.EXPANDED_IDLE,
            reduceSearchPhase(restored, SearchFocusEvent.TabSwitch)
        )
    }

    @Test
    fun clearCollapsesSearchFromEveryPhase() {
        for (phase in SearchPhase.entries) {
            assertEquals(
                "phase=" + phase,
                SearchPhase.COLLAPSED,
                reduceSearchPhase(phase, SearchFocusEvent.Clear)
            )
        }
    }

    @Test
    fun pickingTagSuggestionLeavesEditingForResults() {
        assertEquals(
            SearchPhase.EXPANDED_IDLE,
            reduceSearchPhase(SearchPhase.EDITING, SearchFocusEvent.TagPick)
        )
    }

    @Test
    fun onlyClearLeavesCollapsedPhase() {
        // 除「点小球 / 拿到焦点」外，收起态不该被任何平台事件撑开。
        for (event in listOf(
            SearchFocusEvent.FieldLostFocus,
            SearchFocusEvent.ImeShown,
            SearchFocusEvent.ImeHidden,
            SearchFocusEvent.TabSwitch,
            SearchFocusEvent.Submit,
            SearchFocusEvent.TagPick
        )) {
            assertEquals(
                "event=" + event,
                SearchPhase.COLLAPSED,
                reduceSearchPhase(SearchPhase.COLLAPSED, event)
            )
        }
        assertEquals(
            SearchPhase.EDITING,
            reduceSearchPhase(SearchPhase.COLLAPSED, SearchFocusEvent.TapField)
        )
        assertEquals(
            SearchPhase.EDITING,
            reduceSearchPhase(SearchPhase.COLLAPSED, SearchFocusEvent.FieldFocused)
        )
        assertEquals(
            SearchPhase.COLLAPSED,
            reduceSearchPhase(SearchPhase.COLLAPSED, SearchFocusEvent.Clear)
        )
    }

    @Test
    fun defocusCommitsTypedTextThatWasNeverSearched() {
        assertTrue(
            shouldCommitOnDefocus(SearchPhase.EDITING, "miku", committedQuery = "")
        )
        assertTrue(
            shouldCommitOnDefocus(SearchPhase.EDITING, "  miku  ", committedQuery = "")
        )
        assertTrue(
            shouldCommitOnDefocus(SearchPhase.EDITING, "miku 2024", committedQuery = "miku")
        )
    }

    @Test
    fun defocusDoesNotCommitEmptyInput() {
        assertFalse(shouldCommitOnDefocus(SearchPhase.EDITING, "", committedQuery = ""))
        assertFalse(shouldCommitOnDefocus(SearchPhase.EDITING, "   ", committedQuery = ""))
    }

    @Test
    fun defocusDoesNotRepeatTheAlreadyCommittedQuery() {
        // 键盘「搜索」键提交过之后，收键盘不该再打一次同样的请求。
        assertFalse(
            shouldCommitOnDefocus(SearchPhase.EDITING, "miku", committedQuery = "miku")
        )
        assertFalse(
            shouldCommitOnDefocus(SearchPhase.EDITING, " miku ", committedQuery = "miku")
        )
    }

    @Test
    fun defocusOutsideEditingCommitsNothing() {
        // 结果态里点空白只是收键盘；收起态根本不该有输入。
        assertFalse(shouldCommitOnDefocus(SearchPhase.EXPANDED_IDLE, "miku", committedQuery = ""))
        assertFalse(shouldCommitOnDefocus(SearchPhase.COLLAPSED, "miku", committedQuery = ""))
    }
}
