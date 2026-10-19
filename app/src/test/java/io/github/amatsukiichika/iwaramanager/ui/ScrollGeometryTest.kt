package io.github.amatsukiichika.iwaramanager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无极（连续比例）滚动条的进度换算 ——「滚动条一顿一顿」的回归用例。
 *
 * 若用 `首个可见下标 / (总数 − 可见数)`：一行有 2 个条目时下标每滚一行**跳 2**、拇指一格一格蹦；
 * 分母 `总数 − 可见数` 与"行"无关，拇指长度偏大、到底还会提前贴底。
 * 分子/分母都来自 `layoutInfo`（每帧都变），画在组合相位时整条滚动条每帧重组 ⇒ 掉帧。
 *
 * 实现把滚动量换成**单位**（网格 = 行、列表 = 条目）并计入**行内像素偏移**，于是：
 *  - 进度连续（[ScrollGeometry.scrollFraction]）；
 *  - 拖动映射与进度**严格互逆**（[ScrollGeometry.seekTarget]）—— 不互逆的话，一按拇指列表就跳；
 *  - 拇指长度 = 可见单位 / 总单位（[ScrollGeometry.thumbSpanFraction]）。
 *
 * 网格可见行数按 `视口高 ÷ 行高`（含小数）——"半行进出"不再让拇指长度
 * ±1 行地抖（"拖动时实心部分闪长闪短"的根因之一；几何瞬空回退满格在
 * FastScrollbarBasic 侧用"保持上一帧 + 死区"修）。
 */
class ScrollGeometryTest {

    /** 2 列网格：40 个条目 = 20 行，视口里 4 行（8 个条目），行高 100px。 */
    private fun grid(firstRow: Int, rowOffsetPx: Float) = ScrollGeometry(
        totalUnits = 20,
        visibleUnits = 4f,
        firstUnit = firstRow,
        unitOffsetPx = rowOffsetPx,
        unitSizePx = 100f,
        itemsPerUnit = 2
    )

    /** 列表：100 个条目，视口里 10 条，条目高 50px。 */
    private fun list(firstItem: Int, itemOffsetPx: Float) = ScrollGeometry(
        totalUnits = 100,
        visibleUnits = 10f,
        firstUnit = firstItem,
        unitOffsetPx = itemOffsetPx,
        unitSizePx = 50f,
        itemsPerUnit = 1
    )

    @Test
    fun `grid fraction is zero at top and one at bottom`() {
        assertEquals(0.0, grid(0, 0f).scrollFraction().toDouble(), 1e-6)
        // 最后能当"首行"的位置是 总行数 − 可见行数 = 16
        assertEquals(1.0, grid(16, 0f).scrollFraction().toDouble(), 1e-6)
    }

    @Test
    fun `grid fraction moves continuously inside a row and across the row seam`() {
        val a = grid(3, 0f).scrollFraction()   // 第 3 行正好对齐顶部
        val b = grid(3, 50f).scrollFraction()  // 滚出去半行
        val c = grid(3, 99f).scrollFraction()  // 快滚完这一行
        val d = grid(4, 0f).scrollFraction()   // 下一行接上
        assertTrue("行内应单调递增：$a $b $c", a < b && b < c && c < d)
        // 回归点：若无单位换算，在 c→d 这一步会跳 2/32 = 0.0625（行程的 6.25%）；这里必须基本不动。
        assertTrue("跨行不能跳格，实际增量 ${d - c}", d - c < 0.001f)
    }

    @Test
    fun `seek target inverts the fraction exactly`() {
        for (row in 0..15) {
            for (offset in listOf(0f, 37f, 50f, 99f)) {
                val g = grid(row, offset)
                val (index, offsetPx) = g.seekTarget(g.scrollFraction())
                assertEquals("行 $row / 偏移 $offset 的条目下标", row * 2, index)
                assertEquals("行 $row / 偏移 $offset 的行内偏移", offset.toDouble(), offsetPx.toDouble(), 1.0)
            }
        }
        // 到底那一行取整后正好是"最后一行 + 0 偏移"
        assertEquals(32 to 0, grid(16, 0f).seekTarget(1f))
    }

    @Test
    fun `list seek target inverts the fraction exactly`() {
        for (item in 0..89) {
            for (offset in listOf(0f, 25f, 49f)) {
                val g = list(item, offset)
                val (index, offsetPx) = g.seekTarget(g.scrollFraction())
                assertEquals(item, index)
                assertEquals(offset.toDouble(), offsetPx.toDouble(), 1.0)
            }
        }
    }

    @Test
    fun `thumb span is visible units over total units with clamping`() {
        // 4 / 20 —— 用"可见条目 / 总条目" = 8/40 会得到同样的值，但网格被拉长/末行不满时就会偏。
        assertEquals(0.2, grid(0, 0f).thumbSpanFraction().toDouble(), 1e-6)
        // 极长列表：至少留 5%，否则拇指细到抓不住。
        assertEquals(0.05, ScrollGeometry(1000, 1f, 0, 0f, 100f, 1).thumbSpanFraction().toDouble(), 1e-6)
        // 内容正好一屏：满长。
        assertEquals(1.0, ScrollGeometry(4, 4f, 0, 0f, 100f, 1).thumbSpanFraction().toDouble(), 1e-6)
    }

    @Test
    fun `short content reports no scrollable span`() {
        val short = ScrollGeometry(totalUnits = 2, visibleUnits = 2f, firstUnit = 0, unitOffsetPx = 0f, unitSizePx = 100f, itemsPerUnit = 2)
        assertEquals(0.0, short.scrollFraction().toDouble(), 1e-6)
        assertEquals(0 to 0, short.seekTarget(0.5f))
    }

    @Test
    fun `pixel by pixel scroll moves the thumb by less than a pixel`() {
        // 600px 高的轨道：逐像素扫 5 行，任意相邻两帧的进度增量都必须小于"拇指移动 1px"。
        val trackPx = 600f
        var previous = -1f
        for (row in 0..4) {
            for (px in 0..99) {
                val fraction = grid(row, px.toFloat()).scrollFraction()
                assertTrue("进度必须单调，row=$row px=$px", fraction >= previous)
                if (previous >= 0f) {
                    assertTrue(
                        "row=$row px=$px 的增量 ${(fraction - previous) * trackPx}px 超过 1px",
                        (fraction - previous) * trackPx < 1f
                    )
                }
                previous = fraction
            }
        }
    }

    @Test
    fun `offset beyond one unit caps at exactly one unit of travel`() {
        // 懒布局理论上不会给出"超过一行"的偏移（真到了那一步，首行早就换人了）；万一给了，
        // 也必须按"正好又滚完一行"封顶 —— 这样取值恰好等于"下一行成为首行、偏移归零"，
        // 相邻两帧严格相接、不会回跳。若改成"封顶到 0"就会在这里产生反向跳变。
        assertEquals(
            grid(1, 0f).scrollFraction().toDouble(),
            grid(0, 250f).scrollFraction().toDouble(),
            1e-6
        )
        // 再夸张也还是同一个值（单调、有界）。
        assertEquals(
            grid(1, 0f).scrollFraction().toDouble(),
            grid(0, 99_999f).scrollFraction().toDouble(),
            1e-6
        )
    }

    /* ---------- 网格可见行数的"视口公式"（gridScrollGeometry 纯函数直测；
       LazyGridItemInfo 等自 1.7 起是 sealed 接口，单测无法造假实现，故映射逻辑抽成普通值入参） ---------- */

    /** 2 列、行高 100px、视口 400px、总条目 40（= 20 行）的网格几何。 */
    private fun gridGeo(
        firstRow: Int,
        lastRow: Int,
        firstRowTopPx: Int = 0,
        beforePad: Int = 0,
        afterPad: Int = 0,
        viewportH: Int = 400
    ) = gridScrollGeometry(
        totalItems = 40,
        firstRow = firstRow,
        lastRow = lastRow,
        columns = 2,
        firstRowTopPx = firstRowTopPx,
        rowHeightPx = 100,
        beforePad = beforePad,
        afterPad = afterPad,
        viewportMainAxisPx = viewportH
    )!!

    @Test
    fun `grid visible rows come from viewport so partial rows never change the thumb span`() {
        // 视口 400 / 行高 100 = 4 行，无论"顶行是不是半行"，拇指长度都必须恒为 4/20。
        // 若按"数可见行数"计：frameA 数到 5 行（0.25）、frameB 数到 4 行（0.2）—— 正是闪烁源。
        val frameA = gridGeo(firstRow = 2, lastRow = 6, firstRowTopPx = -90)
        val frameB = gridGeo(firstRow = 3, lastRow = 6)
        assertEquals(4f, frameA.visibleUnits, 1e-4f)
        assertEquals(4f, frameB.visibleUnits, 1e-4f)
        assertEquals(
            frameA.thumbSpanFraction().toDouble(),
            frameB.thumbSpanFraction().toDouble(),
            1e-9
        )
    }

    @Test
    fun `grid geometry still tracks first row and offset for the fraction`() {
        val g = gridGeo(firstRow = 3, lastRow = 6, firstRowTopPx = -90)
        assertEquals(3, g.firstUnit)
        assertEquals(2, g.itemsPerUnit)
        assertEquals(20, g.totalUnits)
        // 首行顶在 -90px：滚出 90px = 行内 0.9
        assertEquals(0.9f, (g.unitOffsetPx / g.unitSizePx), 1e-4f)
    }

    @Test
    fun `grid before and after content padding shrink the effective viewport`() {
        val withPadding = gridGeo(firstRow = 0, lastRow = 3, beforePad = 12, afterPad = 8)
        // 有效视口 = 400 − 12 − 8 = 380 → 可见行 3.8
        assertEquals(3.8f, withPadding.visibleUnits, 1e-4f)
    }

    @Test
    fun `list layout info maps to unit geometry`() {
        val info = listScrollGeometry(
            totalItems = 100,
            firstItemIndex = 10,
            firstItemTopPx = -20,
            firstItemHeightPx = 50,
            visibleCount = 10
        )!!
        assertEquals(100, info.totalUnits)
        assertEquals(10f, info.visibleUnits, 1e-4f)
        assertEquals(10, info.firstUnit)
        assertEquals(1, info.itemsPerUnit)
        assertEquals(20f, info.unitOffsetPx, 1e-4f)
    }
}
