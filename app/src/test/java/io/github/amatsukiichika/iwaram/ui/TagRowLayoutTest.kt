package io.github.amatsukiichika.iwaram.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签行单行自适应布局的规则测试。
 *
 * 覆盖：全放得下 / 需要收 +N / 只截断最后枚 / 截断+收 N / 一屏只截断一枚 /
 * 可用宽度极小 / 宽度边界（恰好相等仍算放得下）。
 */
class TagRowLayoutTest {

    private val gap = 4
    private val ellipsisMin = 14

    private fun plusWidth(hidden: Int): Int = 12 + hidden.toString().length * 6

    private fun layout(widths: List<Int>, available: Int) =
        layoutTagRow(widths, gap, ellipsisMin, ::plusWidth, available)

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
    }

    @Test
    fun overflow_truncatesLastAndCountsRemainder() {
        // 4 枚各 40：整 3 枚 + "+1" = 150 > 140；于是第 3 枚截断（剩余 30px）+"+1" 放得下。
        //   截断的那枚仍算"已显示"，所以 +N 只数它后面的 1 枚。
        val r = layout(listOf(40, 40, 40, 40), 140)
        assertEquals(3, r.visibleCount)
        assertEquals(1, r.hiddenCount)
        assertTrue(r.ellipsizeLast)
        assertEquals(30, r.lastChipMaxPx)
    }

    @Test
    fun overflow_whenTruncationImpossible_collapsesIntoPlusN() {
        // 140 → 120：整 2 枚 84 + 4 + "+2"(18) = 106 放得下；第 3 枚截断后只剩 10px（< 省略号 14px），
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
        // 两枚 200：整枚都放不下 ⇒ 显示第一枚（截断），第二枚收进 "+1"。
        val r = layout(listOf(200, 200), 120)
        assertEquals(1, r.visibleCount)
        assertEquals(1, r.hiddenCount)
        assertTrue(r.ellipsizeLast)
        // 120 - 0（前面没有药丸） - (4 + 18) = 98。
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
        val r = layout(listOf(200, 200), 8)
        assertEquals(0, r.visibleCount)
        assertEquals(2, r.hiddenCount)
        assertFalse(r.ellipsizeLast)
    }

    @Test
    fun plusWidthGrowsWithDigits_keepsFewerTags() {
        // 10 枚各 40：可用宽度只够 10 枚里放一点，N 的位数会影响能放几枚。
        val widths = List(10) { 40 }
        val ten = layout(widths, 80)
        val two = layout(widths, 400)
        assertTrue(ten.visibleCount < two.visibleCount)
        assertTrue(ten.hiddenCount > two.hiddenCount)
    }
}
