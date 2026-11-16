package io.github.amatsukiichika.iwaram.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签行单行自适应布局的规则测试。
 *
 * 语义（用户 2026-11 反馈）：省略号只在"某一枚标签本身放不下"时出现在这枚药丸内部，
 * 它仍算已显示；"+N" 只数它后面的标签；绝不出现"只有省略号"的药丸。
 *
 * 覆盖：全放得下 / 需要收 +N / 整枚 + N / 截断最后枚 / 截断 + N / 截断空间不足则收进 N /
 * 可用宽度极小 / 只量了前几枚时的边界 / 宽度恰好相等仍算放得下。
 */
class TagRowLayoutTest {

    private val gap = 4
    private val truncatedMin = 20

    private fun plusWidth(hidden: Int): Int = 12 + hidden.toString().length * 6

    /** 全部标签的宽都量到了。 */
    private fun layout(widths: List<Int>, available: Int) =
        layoutTagRow(
            chipWidthsPx = widths,
            totalTagCount = widths.size,
            gapPx = gap,
            truncatedMinPx = truncatedMin,
            plusWidthPx = ::plusWidth,
            availablePx = available
        )

    /** 只量了前几枚（后面的宽未知 ⇒ 必然收进 +N）。 */
    private fun layoutPartial(widths: List<Int>, total: Int, available: Int) =
        layoutTagRow(
            chipWidthsPx = widths,
            totalTagCount = total,
            gapPx = gap,
            truncatedMinPx = truncatedMin,
            plusWidthPx = ::plusWidth,
            availablePx = available
        )

    @Test
    fun emptyTags_showNothing() {
        val r = layout(emptyList(), 300)
        assertEquals(0, r.visibleCount)
        assertEquals(0, r.hiddenCount)
        assertFalse(r.ellipsizeLast)
    }

    @Test
    fun allFit_showAllWithoutPlus() {
        // 3 枚各 40 + 两个间距 = 128 <= 140。
        val r = layout(listOf(40, 40, 40), 140)
        assertEquals(3, r.visibleCount)
        assertEquals(0, r.hiddenCount)
        assertFalse(r.ellipsizeLast)
    }

    @Test
    fun boundary_exactFit_stillShowsAll() {
        // 3*40 + 2*4 = 128，恰好等于可用宽度 ⇒ 放得下。
        val r = layout(listOf(40, 40, 40), 128)
        assertEquals(3, r.visibleCount)
        assertEquals(0, r.hiddenCount)
        assertFalse(r.ellipsizeLast)
    }

    @Test
    fun overflow_prefersWholeChipsPlusN() {
        // 4 枚各 40：整 3 枚 + "+1" = 128 + 4 + 18 = 150 > 140；
        //   整 2 枚 + "+2" = 84 + 4 + 18 = 106 <= 140。
        //   再试着把第 3 枚截断：可用 140 - (84+4) - (4+18) = 30 >= 20 ⇒ 截断显示，+N 只数第 4 枚。
        val r = layout(listOf(40, 40, 40, 40), 140)
        assertEquals(3, r.visibleCount)
        assertEquals(1, r.hiddenCount)
        assertTrue(r.ellipsizeLast)
        assertEquals(30, r.lastChipMaxPx)
    }

    @Test
    fun overflow_whenTruncationImpossible_collapsesIntoPlusN() {
        // 140 → 120：整 2 枚 + "+2" = 106 放得下；第 3 枚截断后只剩 120 - 88 - 22 = 10px（< 20），
        //   所以宁可少显示一枚、把它收进 "+2"。
        val r = layout(listOf(40, 40, 40, 40), 120)
        assertEquals(2, r.visibleCount)
        assertEquals(2, r.hiddenCount)
        assertFalse(r.ellipsizeLast)
    }

    @Test
    fun allTagsButOneTooWide_ellipsizesLastWithoutPlus() {
        // 两枚 100：整两枚 204 > 136；截断第二枚：100 + 4 + 32（剩余）= 136 恰好放得下 ⇒ 不出现 +N。
        val r = layout(listOf(100, 100), 136)
        assertEquals(2, r.visibleCount)
        assertEquals(0, r.hiddenCount)
        assertTrue(r.ellipsizeLast)
        assertEquals(32, r.lastChipMaxPx)
    }

    @Test
    fun truncatedLast_plusCountExcludesEllipsizedTag() {
        // 两枚 200：一枚整枚都放不下 ⇒ 第一枚截断（算已显示），第二枚收进 "+1"。
        val r = layout(listOf(200, 200), 120)
        assertEquals(1, r.visibleCount)
        assertEquals(1, r.hiddenCount)
        assertTrue(r.ellipsizeLast)
        // 120 - 0（前面没有药丸） - (4 间距 + "+1" 18) = 98。
        assertEquals(98, r.lastChipMaxPx)
    }

    @Test
    fun singleLongTag_truncatedAlone() {
        val r = layout(listOf(300), 100)
        assertEquals(1, r.visibleCount)
        assertEquals(0, r.hiddenCount)
        assertTrue(r.ellipsizeLast)
        assertEquals(100, r.lastChipMaxPx)
    }

    @Test
    fun noRoomAtAll_onlyPlusN() {
        // 连第一枚（截断后）都留不下 20px：只画 "+2"，不画省略号药丸。
        val r = layout(listOf(200, 200), 8)
        assertEquals(0, r.visibleCount)
        assertEquals(2, r.hiddenCount)
        assertFalse(r.ellipsizeLast)
    }

    @Test
    fun partialMeasurement_trailingUnknownTagsCountIntoPlus() {
        // 只量出前 2 枚（各 40），其实共 5 枚：整 2 枚 + "+3" = 84 + 4 + 18 = 106 <= 140，
        //   第 3 枚没量宽度 ⇒ 不能截断，直接收进 "+3"。
        val r = layoutPartial(listOf(40, 40), total = 5, available = 140)
        assertEquals(2, r.visibleCount)
        assertEquals(3, r.hiddenCount)
        assertFalse(r.ellipsizeLast)
    }

    @Test
    fun partialMeasurement_canStillTruncateMeasuredNextTag() {
        // 量出前 2 枚：30 和一枚超宽 200；共 4 枚。整 2 枚 + "+2" 放不下；
        //   整 1 枚 + "+3" = 30 + 4 + 18 = 52 <= 120 ⇒ 第 2 枚截断：
        //   120 - (30+4) - (4+18) = 64 >= 20。
        val r = layoutPartial(listOf(30, 200), total = 4, available = 120)
        assertEquals(2, r.visibleCount)
        assertEquals(2, r.hiddenCount)
        assertTrue(r.ellipsizeLast)
        assertEquals(64, r.lastChipMaxPx)
    }

    @Test
    fun plusWidthGrowsWithDigits_keepsFewerTags() {
        // 10 枚各 40：可用宽度越窄，能显示的整枚越少、+N 里的数字越大。
        val widths = List(10) { 40 }
        val narrow = layout(widths, 80)
        val wide = layout(widths, 400)
        assertTrue(narrow.visibleCount < wide.visibleCount)
        assertTrue(narrow.hiddenCount > wide.hiddenCount)
    }

    @Test
    fun neverRendersEllipsisOnly_whenSpaceBelowThreshold() {
        // 可用宽度 8 时第一枚截断后只剩 8px < 20：不允许出现只有省略号的药丸。
        val r = layout(listOf(300), 8)
        assertEquals(0, r.visibleCount)
        assertFalse(r.ellipsizeLast)
        assertEquals(1, r.hiddenCount)
    }
}
