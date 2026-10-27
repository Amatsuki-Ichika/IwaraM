package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页 chip 菜单落位几何（[AnchorMenuGeometry]）的回归护栏。
 *
 * 每个用例盯一条不变量或一次翻转（不变量本身只写在 [AnchorMenuGeometry] 的 KDoc 里）：
 * 底栏向上、常规下拉、放不下翻转、两侧都紧时贴边距、右缘越界收边、首帧尺寸未知、
 * 高度上限、零锚点，以及在窗口内穷举锚点。
 */
class AnchorMenuGeometryTest {

    /** 典型手机窗口（px，1080×2400 @3x）。 */
    private val window = IntSize(1080, 2400)

    /** 间距 6dp、边距 8dp @3x。 */
    private val gap = 18
    private val margin = 24

    /** 底栏清晰度 chip：贴屏幕右下角。 */
    private val bottomChip = IntRect(left = 860, top = 2260, right = 1060, bottom = 2296)

    /** 菜单实体尺寸：宽 260px、高 300px（≈ 5 行 32dp 加内外边距）。 */
    private val menu = IntSize(260, 300)

    private fun offsetOf(anchor: IntRect, popup: IntSize = menu) =
        AnchorMenuGeometry.offset(anchor, window, popup, gap, margin)

    @Test
    fun `底栏 chip 的菜单贴着 chip 上沿向上展开`() {
        assertTrue(
            "chip 贴屏底，上方空间远多于下方：应向上展开",
            AnchorMenuGeometry.opensUpward(bottomChip, window, menu.height, gap, margin)
        )
        val offset = offsetOf(bottomChip)
        assertEquals(
            "菜单右缘对齐 chip 右缘（chip 自己就贴着右边距时，以窗口边距为准）",
            minOf(bottomChip.right, window.width - margin),
            offset.x + menu.width
        )
        assertEquals("菜单底边 = chip 顶边 − 间距", bottomChip.top - gap, offset.y + menu.height)
        assertTrue("菜单不许压住 chip", offset.y + menu.height <= bottomChip.top)
        assertTrue("菜单整块在窗口内", offset.y >= margin)
    }

    @Test
    fun `锚点在窗口中部时按常规下拉向下展开`() {
        val anchor = IntRect(left = 500, top = 1100, right = 700, bottom = 1136)
        assertFalse(
            "下方放得下，不该翻到上方",
            AnchorMenuGeometry.opensUpward(anchor, window, menu.height, gap, margin)
        )
        val offset = offsetOf(anchor)
        assertEquals("菜单顶边 = 锚点底边 + 间距", anchor.bottom + gap, offset.y)
        assertEquals("右缘对齐锚点右缘", anchor.right, offset.x + menu.width)
    }

    @Test
    fun `下方放不下时翻到上方`() {
        val anchor = IntRect(left = 860, top = 2000, right = 1060, bottom = 2036)
        val tallMenu = IntSize(260, 600)
        assertTrue(
            "下方只剩 322px，600px 的菜单放不下：应翻到上方",
            AnchorMenuGeometry.opensUpward(anchor, window, tallMenu.height, gap, margin)
        )
        val offset = offsetOf(anchor, tallMenu)
        assertEquals("翻上去后贴锚点顶边", anchor.top - gap, offset.y + tallMenu.height)
        assertTrue("翻上去也不许出窗口", offset.y >= margin)
        assertTrue("翻上去也不许出窗口", offset.y + tallMenu.height <= window.height - margin)
    }

    @Test
    fun `两侧都放不下时贴边距并交给内部滚动`() {
        val shortWindow = IntSize(1080, 300)
        val anchor = IntRect(left = 860, top = 150, right = 1060, bottom = 186)
        val tallMenu = IntSize(260, 400)
        val offset = AnchorMenuGeometry.offset(anchor, shortWindow, tallMenu, gap, margin)
        assertEquals("上方(108px)比下方(72px)宽裕，但都放不下 400px：贴住上边距", margin, offset.y)
        assertTrue("宁可压住锚点也不许出屏", offset.y >= margin)
    }

    @Test
    fun `右缘越界时收进屏幕边距`() {
        val leftSideChip = IntRect(left = 40, top = 2000, right = 240, bottom = 2036)
        assertEquals(
            "chip 靠左时菜单会被推到屏幕外：应收进左边距",
            margin,
            offsetOf(leftSideChip).x
        )

        val narrowWindow = IntSize(400, 2400)
        val wideMenu = IntSize(280, 300)
        val anchor = IntRect(left = 300, top = 2000, right = 380, bottom = 2036)
        val offset = AnchorMenuGeometry.offset(anchor, narrowWindow, wideMenu, gap, margin)
        assertEquals(
            "菜单比剩余宽度还宽时收进右边距",
            narrowWindow.width - margin,
            offset.x + wideMenu.width
        )
    }

    @Test
    fun `首帧量不出尺寸时先按空间大小定方向、不闪到下方`() {
        val offset = offsetOf(bottomChip, IntSize.Zero)
        assertEquals("首帧就按向上摆，量出尺寸后再收敛", bottomChip.top - gap, offset.y)
        assertTrue("首帧不许出现在 chip 下方", offset.y < bottomChip.top)
    }

    @Test
    fun `高度上限取两侧里更宽裕的那侧`() {
        assertEquals(
            "底栏 chip：上方 2218px 远多于下方 62px",
            2218,
            AnchorMenuGeometry.maxHeight(bottomChip, window, gap, margin)
        )
        val middle = IntRect(left = 500, top = 1100, right = 700, bottom = 1136)
        assertEquals(
            "中部锚点：下方 1222px 多于上方 1058px",
            1222,
            AnchorMenuGeometry.maxHeight(middle, window, gap, margin)
        )
    }

    @Test
    fun `锚点还没量出来时退化尺寸不崩`() {
        assertEquals(
            "零锚点零尺寸：退回边距，绝不返回负坐标",
            IntOffset(margin, margin),
            offsetOf(IntRect.Zero, IntSize.Zero)
        )
    }

    @Test
    fun `任何位置的 chip 菜单都整块留在窗口内`() {
        listOf(120, 300, 600, 900).forEach { height ->
            val popup = IntSize(260, height)
            (0..2280 step 120).forEach { top ->
                val anchor = IntRect(left = 860, top = top, right = 1060, bottom = top + 36)
                val offset = AnchorMenuGeometry.offset(anchor, window, popup, gap, margin)
                val tag = "top=$top height=$height"
                assertTrue("$tag 越过上边距", offset.y >= margin)
                assertTrue("$tag 越过下边距", offset.y + popup.height <= window.height - margin)
                assertTrue("$tag 越过左边距", offset.x >= margin)
                assertTrue("$tag 越过右边距", offset.x + popup.width <= window.width - margin)
                val upward = AnchorMenuGeometry.opensUpward(anchor, window, popup.height, gap, margin)
                if (upward) {
                    assertTrue("$tag 向上展开压住了 chip", offset.y + popup.height <= anchor.top)
                }
            }
        }
    }
}
