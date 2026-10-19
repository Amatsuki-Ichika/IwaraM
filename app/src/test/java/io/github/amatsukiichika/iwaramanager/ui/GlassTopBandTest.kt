package io.github.amatsukiichika.iwaramanager.ui

import io.github.amatsukiichika.iwaramanager.ui.components.bandEase
import io.github.amatsukiichika.iwaramanager.ui.components.bandFadeStops
import io.github.amatsukiichika.iwaramanager.ui.components.topBandStops
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顶栏块**浓度剖面**（`ui/components/GlassBackdrop.kt` 的 `topBandStops`）
 * 与**缓动形状**（`bandEase` / `bandFadeStops`）的回归护栏。
 *
 * 钉住这些形状不变量：
 * ① 位置 0..1 **严格递增**（`Brush` 的停靠点退化会让整条渐变作废）；
 * ② 顶段（= 状态栏 + App 顶栏那一格）= **1.0 实心**；
 * ③ 药丸到搜索栏整块 = **全局不透明度**（`bodyAlpha`）；
 * ④ 底端 = **0**（过渡二淡到透明）；`fadeEdge = false`（「我的」tab）时底端保持 `bodyAlpha`；
 * ⑤ 两条带**端点处的斜率≈0**（缓入缓出）—— 不能用线性（线性在带的起边一阶导突变）。
 */
class GlassTopBandTest {

    private val h = 320f       // 材质盒高（含下方为过渡二多铺的那一段）
    private val solid = 72f    // 实心段（状态栏 + App 顶栏）
    private val b1 = 20f       // 过渡一
    private val b2 = 32f       // 过渡二
    private val body = 0.78f   // 全局不透明度

    private fun stops(fadeEdge: Boolean = true) = topBandStops(h, solid, b1, b2, body, fadeEdge)

    /** 把停靠点当**剖面**读：给 y（px）返回该处浓度（段内线性插值；两端夹取）。 */
    private fun alphaAt(stops: List<Pair<Float, Float>>, y: Float): Float {
        val pos = y / h
        if (pos <= stops.first().first) return stops.first().second
        stops.zipWithNext().forEach { (a, b) ->
            if (pos <= b.first) {
                val k = (pos - a.first) / (b.first - a.first)
                return a.second + (b.second - a.second) * k
            }
        }
        return stops.last().second
    }

    @Test
    fun `位置严格递增且落在 0 到 1`() {
        listOf(true, false).forEach { fade ->
            val s = stops(fade)
            assertTrue("至少要有头尾两个停靠点", s.size >= 2)
            assertEquals("必须从 0 开始（屏幕最顶）", 0f, s.first().first, 1e-4f)
            assertEquals("必须到 1 结束（材质底边）", 1f, s.last().first, 1e-4f)
            s.zipWithNext().forEach { (a, b) ->
                assertTrue("位置必须严格递增：${a.first} -> ${b.first}", b.first > a.first)
            }
            s.forEach { assertTrue("位置须落在 0..1：${it.first}", it.first in 0f..1f) }
        }
    }

    @Test
    fun `顶段实心 体段全局 底端为 0`() {
        val s = stops(fadeEdge = true)
        assertEquals("屏幕最顶就必须是实心", 1f, alphaAt(s, 0f), 1e-4f)
        assertEquals("实心段之内整段实心", 1f, alphaAt(s, solid * 0.5f), 1e-4f)
        assertEquals("实心底边仍在 solidTop", 1f, alphaAt(s, solid), 1e-4f)
        assertEquals("过渡一落点 = solidTop + 20dp", body, alphaAt(s, solid + b1), 1e-3f)
        assertEquals("整块 = 全局不透明度", body, alphaAt(s, h - b2), 1e-4f)
        assertEquals("底端必须淡到 0", 0f, alphaAt(s, h), 1e-4f)
    }

    @Test
    fun `整条剖面单调不增`() {
        val s = stops(fadeEdge = true)
        var prev = alphaAt(s, 0f)
        var y = 1f
        while (y <= h) {
            val a = alphaAt(s, y)
            assertTrue("浓度只能越来越淡：y=$y（$prev -> $a）", a <= prev + 1e-6f)
            prev = a
            y += 1f
        }
    }

    @Test
    fun `两条过渡带端点处斜率接近 0`() {
        val s = stops(fadeEdge = true)
        // 过渡一：带的前 10% 内，落差 ≤ 总落差的 5%（线性会是 10% ⇒ 会被这条挡下来）
        val drop1 = (1f - body) * 0.05f
        assertTrue(
            "过渡一起边太陡（= 生硬）",
            alphaAt(s, solid + b1 * 0.1f) >= 1f - drop1
        )
        // 过渡二：起点（带上沿）与终点（带下沿）两侧同样要平
        val drop2 = body * 0.05f
        assertTrue(
            "过渡二起边太陡（曲线不够平滑）",
            alphaAt(s, h - b2 + b2 * 0.1f) >= body - drop2
        )
        assertTrue(
            "过渡二收尾太陡（= 一条硬边）",
            alphaAt(s, h - b2 * 0.1f) <= drop2
        )
    }

    @Test
    fun `两条过渡带中点各是半值`() {
        val s = stops(fadeEdge = true)
        assertEquals("过渡一中点 = 两端的平均", (1f + body) / 2f, alphaAt(s, solid + b1 * 0.5f), 1e-3f)
        assertEquals("过渡二中点 = 一半浓度", body / 2f, alphaAt(s, h - b2 * 0.5f), 1e-3f)
    }

    @Test
    fun `不做底边淡出时底端保持全局不透明度`() {
        val s = stops(fadeEdge = false)
        assertEquals("「我的」tab：不做过渡二 ⇒ 底端仍是全局值", body, alphaAt(s, h), 1e-4f)
        assertEquals("实心 → 全局的过渡一仍在", body, alphaAt(s, solid + b1), 1e-3f)
        assertTrue("「我的」tab 不许出现【淡到 0】的段", s.none { it.second == 0f })
    }

    @Test
    fun `实心段吃掉整条时整条实心`() {
        // 「我的」tab：solidTop = 整条高度（App 顶栏自己画材质、下面没有药丸/搜索）⇒ 整条 1.0。
        val s = topBandStops(h, h, b1, b2, body, fadeEdge = false)
        assertEquals(2, s.size)
        assertEquals(1f, s[0].second, 1e-4f)
        assertEquals(1f, s[1].second, 1e-4f)
    }

    @Test
    fun `过渡带取 0 或超大都不崩且底端仍为 0`() {
        listOf(0f, 1e6f).forEach { band ->
            val s = topBandStops(h, solid, band, band, body, fadeEdge = true)
            assertTrue("至少要有头尾", s.size >= 2)
            s.zipWithNext().forEach { (a, b) ->
                assertTrue("位置必须严格递增（band=$band）：${a.first} -> ${b.first}", b.first > a.first)
            }
            assertEquals(0f, s.first().first, 1e-4f)
            assertEquals(1f, s.last().first, 1e-4f)
            assertEquals("底端永远淡到 0", 0f, alphaAt(s, h), 1e-4f)
        }
    }

    @Test
    fun `全局不透明度越界会被收敛 高度非法返回空表`() {
        assertEquals(1f, alphaAt(topBandStops(h, solid, b1, b2, 1.5f, true), solid + b1), 1e-4f)
        assertEquals(0f, alphaAt(topBandStops(h, solid, b1, b2, -3f, true), solid + b1), 1e-4f)
        assertTrue("高度非正 ⇒ 无剖面", topBandStops(0f, solid, b1, b2, body, true).isEmpty())
    }

    @Test
    fun `缓动形状两端一阶导为 0 且中点为半`() {
        assertEquals(0f, bandEase(0f), 1e-6f)
        assertEquals(1f, bandEase(1f), 1e-6f)
        assertEquals(0.5f, bandEase(0.5f), 1e-6f)
        // smoothstep 对称：ease(1−t) == 1 − ease(t)
        listOf(0.1f, 0.25f, 0.4f).forEach { t ->
            assertEquals("缓动必须对称", bandEase(t), 1f - bandEase(1f - t), 1e-6f)
        }
        // 两端各 1/8 段内的变化 ≤ 5%（缓动的判据；线性会是 12.5%）
        val keep = bandFadeStops()
        assertEquals("停靠点从 1 开始", 1f, keep.first().second, 1e-6f)
        assertEquals("停靠点收到 0", 0f, keep.last().second, 1e-6f)
        assertTrue("起边太陡", keep[1].second >= 0.95f)
        assertTrue("收尾太陡", keep[keep.size - 2].second <= 0.05f)
    }
}
