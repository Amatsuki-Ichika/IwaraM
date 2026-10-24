package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.ui.components.AppSliderGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑动条几何（`ui/components/AppSlider.kt` 的 `AppSliderGeometry`）的回归护栏。
 *
 * 三条不变量，改动这条滑条时不许破：
 * ① 白点圆心 ≡ 蓝条圆头圆心（与渲染半径无关：按下放大 12→16dp 时圆心不许位移）；
 * ② 白点整圆 ⊂ 蓝条圆头整圆 ⊂ 轨道；
 * ③ 行程端点 = 半个轨道厚（圆头半径）⇒ 白点永不接触轨道两端（按下 3dp / 常态 5dp）。
 */
class AppSliderGeometryTest {

    /** 轨道宽（px；数值本身无关紧要，取个便于读断言的值）。 */
    private val trackW = 700f

    /** 半个轨道厚 = 蓝条圆头半径（现实里 22dp 轨道 ⇒ 11dp）。 */
    private val capR = 11f

    private val rest = 6f      // 常态半径（圆点 12dp / 2）
    private val pressed = 8f   // 按下半径（圆点 16dp / 2）

    private fun center(f: Float, r: Float = rest): Float =
        AppSliderGeometry.drawCenterX(trackW, capR, r, f)

    @Test
    fun `行程端点在轨道两端的圆头上`() {
        assertEquals("0% 圆心应落在左圆头圆心", capR, AppSliderGeometry.nominalCenterX(trackW, capR, 0f), 1e-4f)
        assertEquals("100% 圆心应落在右圆头圆心", trackW - capR, AppSliderGeometry.nominalCenterX(trackW, capR, 1f), 1e-4f)
        assertEquals("中点不动（f=0.5 仍是中点）", trackW / 2f, AppSliderGeometry.nominalCenterX(trackW, capR, 0.5f), 1e-4f)
    }

    @Test
    fun `按下放大 12 到 16dp 的全过程圆心不位移`() {
        listOf(0f, 0.1f, 0.5f, 0.9f, 1f).forEach { f ->
            assertEquals(
                "f=$f：常态径与按下径必须落在同一个圆心（否则圆心会偏右 2dp）",
                center(f, rest),
                center(f, pressed),
                1e-4f
            )
            assertEquals(
                "f=$f：白点圆心 ≡ 蓝条圆头圆心（名义圆心）",
                AppSliderGeometry.nominalCenterX(trackW, capR, f),
                center(f, pressed),
                1e-4f
            )
        }
    }

    @Test
    fun `白点整圆被蓝条圆头包住且永不接触轨道两端`() {
        listOf(rest, pressed).forEach { r ->
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { f ->
                val c = center(f, r)
                assertTrue("f=$f r=$r：白点左缘出界/贴边（gap=${c - r}）", c - r > 0f)
                assertTrue("f=$f r=$r：白点右缘出界/贴边（gap=${trackW - (c + r)}）", trackW - (c + r) > 0f)
                // 内层包含：白点半径 < 圆头半径，且同心 ⇒ 圆头整个罩住白点
                assertTrue("f=$f r=$r：白点半径必须小于圆头半径", r < capR)
                // 外层包含：圆头整圆不越出轨道（= 端点取"半个轨道厚"的直接推论）
                assertTrue("f=$f：蓝条圆头左缘出界", c - capR >= -1e-4f)
                assertTrue("f=$f：蓝条圆头右缘出界", c + capR <= trackW + 1e-4f)
            }
        }
    }

    @Test
    fun `两端按下离外缘 3dp 常态 5dp`() {
        assertEquals("常态：11 − 6 = 5dp", 5f, capR - rest, 1e-4f)
        assertEquals("按下：11 − 8 = 3dp", 3f, capR - pressed, 1e-4f)
        assertEquals("0% 常态离左外缘", capR - rest, center(0f, rest) - rest, 1e-4f)
        assertEquals("100% 按下离右外缘（两端对称）", capR - pressed, trackW - (center(1f, pressed) + pressed), 1e-4f)
    }

    @Test
    fun `零进度时蓝条是一个完整的圆 满进度正好铺满`() {
        assertEquals(
            "0%：蓝条右端 = 圆心 + 圆头半径 ⇒ 整段恰好是直径 2×圆头半径 的圆",
            2f * capR,
            AppSliderGeometry.nominalCenterX(trackW, capR, 0f) + capR,
            1e-4f
        )
        assertEquals(
            "100%：蓝条右端 = 轨道宽（正好铺满，末端是完整圆头）",
            trackW,
            AppSliderGeometry.nominalCenterX(trackW, capR, 1f) + capR,
            1e-4f
        )
    }

    @Test
    fun `反解与名义圆心严格互逆（按下不跳）`() {
        listOf(0f, 0.2f, 0.5f, 0.8f, 1f).forEach { f ->
            val x = AppSliderGeometry.nominalCenterX(trackW, capR, f)
            assertEquals("x=$x 反解应回到 f=$f", f, AppSliderGeometry.fractionAt(trackW, capR, x), 1e-4f)
        }
        assertEquals("超出左端 → 0", 0f, AppSliderGeometry.fractionAt(trackW, capR, -50f), 1e-4f)
        assertEquals("超出右端 → 1", 1f, AppSliderGeometry.fractionAt(trackW, capR, trackW + 50f), 1e-4f)
    }

    @Test
    fun `退化宽度（轨道比两个圆头还窄）不崩且仍落在轨道内`() {
        val narrow = 10f
        val f = 0.7f
        val nom = AppSliderGeometry.nominalCenterX(narrow, capR, f)
        assertTrue("退化宽度下行程不得为负（圆心=${nom}）", nom >= 0f)
        val c = AppSliderGeometry.drawCenterX(narrow, capR, pressed, f)
        assertTrue("退化宽度下圆心仍须落在轨道内（圆心=${c}）", c in 0f..narrow)
        assertEquals("退化宽度下反解退化为 0", 0f, AppSliderGeometry.fractionAt(narrow, capR, 5f), 1e-4f)
    }
}
