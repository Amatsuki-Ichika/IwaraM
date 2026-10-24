package io.github.amatsukiichika.iwaram.ui

import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.ui.components.BAR_HEIGHT_BASE
import io.github.amatsukiichika.iwaram.ui.components.BAR_SIDE_BASE
import io.github.amatsukiichika.iwaram.ui.components.glassScale
import io.github.amatsukiichika.iwaram.ui.components.glassSideInset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏**尺寸不变量**的回归护栏。
 *
 * 底栏尺寸是**可调**的（滑条 0~100 → k = 0.70~1.00），所以最容易踩的坑不是"某个数字写错"，
 * 而是**"拉到最小档时内容放不下"**与**"宽高不同比例（不是等比）"**。这几条都钉在单测里。
 */
class GlassBarGeometryTest {

    /** 玻璃容器内 Row 的上下内缩合计（TabGlassBar 里写死 vertical = 4dp）——段高 = 玻璃高 − 这一块。 */
    private val rowVerticalPadding = 8.dp

    /** 段内容高度：24dp 图标 + 2dp 间距 + labelSmall(11sp / 行高 16sp) 一行 ≈ 42dp。 */
    private val segmentContentHeight = 42.dp

    @Test
    fun `底栏拉到最小档 内容仍放得下`() {
        // k 最小 = 0.70（滑条拉到 0）—— 这是"可调"之后最危险的一档。
        val segmentHeight = BAR_HEIGHT_BASE * glassScale(0f) - rowVerticalPadding
        assertTrue(
            "最小档段高 $segmentHeight 必须 ≥ 内容高 $segmentContentHeight" +
                "（图标钉死 24dp、文字钉死 labelSmall，只能靠玻璃高度留出余量）",
            segmentHeight >= segmentContentHeight
        )
    }

    @Test
    fun `底栏拉满档 回到原始尺寸`() {
        assertEquals(1f, glassScale(1f), 1e-4f)
        assertEquals(BAR_HEIGHT_BASE, BAR_HEIGHT_BASE * glassScale(1f))
        assertEquals(BAR_SIDE_BASE, glassSideInset(1f, 411))
    }

    @Test
    fun `底栏大小档位映射与边界收敛`() {
        assertEquals(0.70f, glassScale(0f), 1e-4f)
        assertEquals(0.85f, glassScale(0.5f), 1e-4f)
        assertEquals(1f, glassScale(2f), 1e-4f)     // 越界收敛
        assertEquals(0.70f, glassScale(-1f), 1e-4f)
    }

    @Test
    fun `宽度与高度按同一比例收 才是等比`() {
        val screenW = 411
        val baseVisible = screenW - BAR_SIDE_BASE.value * 2
        for (v in listOf(0f, 0.25f, 0.55f, 1f)) {
            val k = glassScale(v)
            val visible = screenW - glassSideInset(v, screenW).value * 2
            assertEquals(
                "sizeValue=$v 时可见宽度应 = 基准宽 × k（否则就不是等比了）",
                baseVisible * k,
                visible,
                0.5f
            )
        }
    }

    /** 作者行最小高度：40dp。关注按钮**槽位常驻**（有没有都不改行高，避免行高跳动），
     *  且按钮已用 `LocalMinimumInteractiveComponentSize = Unspecified` 退出 M3 的 48dp 最小触控
     *  （有意的取舍：少 8dp 留白，代价是那枚按钮的触控面积变小）。 */
    @Test
    fun `作者行高度钉死40dp`() {
        assertEquals(40.dp, AUTHOR_ROW_MIN_HEIGHT)
    }
}
