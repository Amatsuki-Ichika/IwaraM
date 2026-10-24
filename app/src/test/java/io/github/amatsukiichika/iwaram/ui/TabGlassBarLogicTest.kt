package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.ui.components.glassClamp
import io.github.amatsukiichika.iwaram.ui.components.glassDeformPeak
import io.github.amatsukiichika.iwaram.ui.components.glassStretch
import io.github.amatsukiichika.iwaram.ui.components.glassTrackingFactor
import io.github.amatsukiichika.iwaram.ui.components.hoverSegment
import io.github.amatsukiichika.iwaram.ui.components.segmentBounds
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏玻璃药丸微反馈物理：
 * 档位映射、位移 clamp、形变归一与跨段死区 —— 全部纯函数（TabGlassBar.kt 底部）。
 */
class TabGlassBarLogicTest {

    /* ---------- 档位映射（0f..1f） ---------- */

    @Test
    fun `跟手档位 - 默认0点4档约为百分之4点8系数`() {
        assertEquals(0.048f, glassTrackingFactor(0.4f), 1e-4f)
    }

    @Test
    fun `跟手档位 - 0为全关 1为满档`() {
        assertEquals(0f, glassTrackingFactor(0f), 1e-4f)
        assertEquals(0.12f, glassTrackingFactor(1f), 1e-4f)
    }

    @Test
    fun `跟手档位 - 越界值收敛到0与1`() {
        assertEquals(0f, glassTrackingFactor(-0.5f), 1e-4f)
        assertEquals(0.12f, glassTrackingFactor(2f), 1e-4f)
    }

    @Test
    fun `形变档位 - 0点4档峰值百分之2`() {
        assertEquals(0.02f, glassDeformPeak(0.4f), 1e-4f)
        assertEquals(0f, glassDeformPeak(0f), 1e-4f)
        assertEquals(0.05f, glassDeformPeak(1f), 1e-4f)
    }

    /* ---------- 位移 clamp ---------- */

    @Test
    fun `位移clamp - 正负与越界`() {
        assertEquals(2f, glassClamp(2f, 8f), 1e-4f)
        assertEquals(-8f, glassClamp(-99f, 8f), 1e-4f)
        assertEquals(8f, glassClamp(99f, 8f), 1e-4f)
    }

    /* ---------- 形变归一 ---------- */

    @Test
    fun `形变 - 峰值内线性 超过跨度封顶`() {
        // 150px / 300px = 0.5 的归一位移
        assertEquals(0.01f, glassStretch(150f / 300f, 0.02f), 1e-4f)
        // 反向位移取绝对值
        assertEquals(0.01f, glassStretch(-150f / 300f, 0.02f), 1e-4f)
        // 超过 300px 封顶到峰值
        assertEquals(0.02f, glassStretch(-900f / 300f, 0.02f), 1e-4f)
    }

    @Test
    fun `形变 - 档位为0时恒为0（关闭形变）`() {
        assertEquals(0f, glassStretch(900f, 0f), 1e-4f)
    }

    /* ---------- 跨段死区 ---------- */

    @Test
    fun `跨段 - 均分三段的命中下标`() {
        // 宽 300px 三段：0-100 / 100-200 / 200-300
        assertEquals(0, hoverSegment(50f, 300, 3, 0f, -1))
        assertEquals(1, hoverSegment(150f, 300, 3, 0f, -1))
        assertEquals(2, hoverSegment(250f, 300, 3, 0f, -1))
    }

    @Test
    fun `跨段 - 越过边界不足死区不切换`() {
        // 死区 4px：从段 0 拖到 103px（越界 3px）→ 仍算段 0
        assertEquals(0, hoverSegment(103f, 300, 3, 4f, 0))
        // 越过 5px → 切到段 1
        assertEquals(1, hoverSegment(105f, 300, 3, 4f, 0))
    }

    @Test
    fun `跨段 - 段中心附近切换与回退对称`() {
        // 从段 1 回退到段 0 的边界同样受死区保护
        assertEquals(1, hoverSegment(97f, 300, 3, 4f, 1))
        assertEquals(0, hoverSegment(95f, 300, 3, 4f, 1))
    }

    @Test
    fun `跨段 - 非法宽度保持当前段不崩溃`() {
        assertEquals(2, hoverSegment(50f, 0, 3, 4f, 2))
        assertEquals(-1, hoverSegment(50f, 300, 0, 4f, -1))
    }

    /* ---------- 解析式段位（内缩 4dp） ---------- */

    @Test
    fun `解析段位 - 三段均分且与内边距一致`() {
        // 栏宽 308dp、高 72dp、三段：内宽 = 308 − 8 = 300 ⇒ segW = 100。
        // bounds[i] = (4 + i×100, 4, 100, 64)。
        // 水平内缩 4dp = 纵向 4dp ⇒ 药丸圆帽圆心(4+pillH/2=30.06dp)
        //   与栏圆帽圆心(barH/2=30.06dp) 重合 = 同心圆（外弧与药丸弧度匹配）。
        val bounds = segmentBounds(308.dp, 72.dp, 3)
        assertEquals(3, bounds.size)
        assertEquals(4.dp, bounds[0].x)
        assertEquals(104.dp, bounds[1].x)
        assertEquals(204.dp, bounds[2].x)
        bounds.forEach {
            assertEquals(100.dp, it.w)
            assertEquals(4.dp, it.y)
            assertEquals(64.dp, it.h)
        }
    }

    @Test
    fun `解析段位 - 非法输入返回空表（首帧无宽度不崩溃）`() {
        assertTrue(segmentBounds(0.dp, 72.dp, 3).isEmpty())
        assertTrue(segmentBounds(308.dp, 72.dp, 0).isEmpty())
    }

    @Test
    fun `解析段位 - 命中网格与段位逐像素一致（修2 的回归钉）`() {
        // 以 density=1 的口径：段位是 dp、命中是 px，两者数值可直接比对。
        val barWidth = 308f
        val leftPx = 4f
        val innerWidth = (barWidth - 2 * leftPx).toInt()   // 300px，与调用处的换算一致
        val bounds = segmentBounds(barWidth.dp, 72.dp, 3)
        // 段 2 的中心 = 4 + 100×2 + 50 = 254px；扣 4dp 内边距后命中必须是段 2。
        val center2 = bounds[2].x.value + bounds[2].w.value / 2f
        assertEquals(2, hoverSegment(center2 - leftPx, innerWidth, 3, 0f, -1))
        // 最后一段的贴边处（扣 4dp 内边距后 = 299）必须仍命中段 2。
        assertEquals(2, hoverSegment(innerWidth - 1f, innerWidth, 3, 0f, -1))
    }
}
