package io.github.amatsukiichika.iwaram.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.ui.components.GlassMode
import io.github.amatsukiichika.iwaram.ui.components.GlassRole
import io.github.amatsukiichika.iwaram.ui.components.GlassSettings
import io.github.amatsukiichika.iwaram.ui.components.glassMaterialFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 半透明材质解析器（`ui/components/GlassMaterial.kt`）的回归护栏。
 *
 * 这几条钉的是以后改材质时最容易踩回去的地方：
 * - 角标胶囊永远不模糊、不画噪点（模糊由卡片级背景层在消费点做，材质里不带）；
 * - 「关」= 实心不透明（alpha = 1）；
 * - 液态玻璃在背景层不可用时整档坍缩成半透明（否则露出未模糊的底）；
 * - 不透明度滑条越大 → 底色越实；越界值收敛，不产生非法 alpha。
 */
class GlassMaterialTest {

    private val surface = Color(0xFF1B1D22)

    private fun bar(mode: GlassMode, opacity: Float = 0.55f, dark: Boolean = true, backdropActive: Boolean = true) =
        glassMaterialFor(GlassRole.BAR, GlassSettings(opacity, mode), surface, dark, backdropActive)

    private fun pill(mode: GlassMode, opacity: Float = 0.55f, backdropActive: Boolean = true) =
        glassMaterialFor(GlassRole.PILL, GlassSettings(opacity, mode), surface, true, backdropActive)

    @Test
    fun `角标胶囊的材质永不带模糊半径也不画噪点`() {
        GlassMode.entries.forEach { mode ->
            val m = pill(mode)
            assertEquals("角标材质不该带模糊半径（mode=$mode）", 0.dp, m.blurRadius)
            assertEquals("角标不该画噪点（mode=$mode）", 0f, m.noise, 0f)
        }
    }

    @Test
    fun `三档语义 关档实心 半透明档色罩 液态玻璃靠模糊区分`() {
        // 关 = 材质系统整体关闭，实心不透明 surface。
        val off = pill(GlassMode.OFF)
        assertEquals("关档胶囊应是不透明实心底", 1f, off.fill.alpha, 1e-4f)
        assertEquals("关档底色应为 surface 本色", surface, off.fill)
        // 不透明度为 0–100% 线性，罩色 alpha 直接等于滑条值。
        //   Color 内部 alpha 按 0..255 量化，绝对断言容差放宽到 1 个量化步长。
        val translucent = pill(GlassMode.TRANSLUCENT, backdropActive = false)
        assertEquals(0.55f, translucent.fill.alpha, 1f / 255f)
        // 液态玻璃与半透明同色，两档的区别只剩有没有模糊（由消费点施加）。
        val liquid = pill(GlassMode.LIQUID_GLASS)
        assertEquals("液态玻璃档与半透明档同色（区别只在模糊）", translucent.fill.alpha, liquid.fill.alpha, 1f / 255f)
        assertTrue("液态玻璃档内描边应更亮（玻璃边缘感）", liquid.edgeHighlight > translucent.edgeHighlight)
    }

    @Test
    fun `所有档位都不画噪点不做渐变不做多层色相`() {
        // 噪点会呈现为"点状物"、上缘受光会呈现为"奇怪的渐变色"且抖动，所以全部撤掉。
        //   这条钉住别再加回来。
        GlassMode.entries.forEach { mode ->
            val m = bar(mode)
            assertEquals("噪点必须为 0（mode=$mode）", 0f, m.noise, 0f)
            assertEquals("上缘受光必须为 0（mode=$mode）", 0f, m.edgeHighlight, 0f)
            assertTrue("色相层必须为空（mode=$mode）", m.tints.isEmpty())
        }
    }

    @Test
    fun `底栏三档 关=实心 液态玻璃带模糊半径 半透明不模糊`() {
        val off = bar(GlassMode.OFF)
        assertEquals("关档底栏应完全实心（alpha=1）", 1f, off.fill.alpha, 1e-4f)
        assertEquals("关档底色应为 surface 本色", surface, off.fill)
        assertEquals("关档不模糊", 0.dp, off.blurRadius)

        val translucent = bar(GlassMode.TRANSLUCENT, backdropActive = false)
        assertTrue("半透明档应是色罩（0 < alpha < 1）", translucent.fill.alpha in 0f..1f && translucent.fill.alpha < 1f)
        assertEquals("半透明档不模糊", 0.dp, translucent.blurRadius)

        val liquid = bar(GlassMode.LIQUID_GLASS)
        // 不再"更薄"——同色，区别只在模糊半径（消费点据此施加 drawBackdrop）。
        assertEquals("液态玻璃与半透明同色", translucent.fill.alpha, liquid.fill.alpha, 1f / 255f)
        // 半径 8dp@3x = 24px：实测唯一可用的消费者（回顶按钮）下发的是 14px，
        // 底栏 36px / 60px 均看不出模糊，所以压到可用区间之上一点，避免半径过大被设备丢弃。
        assertEquals("液态玻璃档的模糊半径交给消费点 drawBackdrop", 8.dp, liquid.blurRadius)
    }

    @Test
    fun `液态玻璃在背景层不可用时整档坍缩成半透明`() {
        // 二级页/播放页在屏上 / 拉伸冻结 / 看门狗降级 / API<31 时宿主不给 backdrop，
        // 此时不能按液态玻璃渲染（会露出未模糊的底），必须退回半透明档。
        val without = bar(GlassMode.LIQUID_GLASS, backdropActive = false)
        val translucent = bar(GlassMode.TRANSLUCENT, backdropActive = false)
        val with = bar(GlassMode.LIQUID_GLASS, backdropActive = true)
        assertEquals("无背景层时应退回半透明档的厚度", translucent.fill.alpha, without.fill.alpha, 1e-4f)
        assertEquals("无背景层时模糊半径应为 0", 0.dp, without.blurRadius)
        // 两档同色，坍缩只体现在模糊半径上（有背景层才有 8dp）。
        assertEquals("有背景层时才带模糊半径", 8.dp, with.blurRadius)
    }

    @Test
    fun `不透明度越大底色越实 越界值收敛`() {
        val low = bar(GlassMode.TRANSLUCENT, opacity = 0f).fill.alpha
        val mid = bar(GlassMode.TRANSLUCENT, opacity = 0.55f).fill.alpha
        val high = bar(GlassMode.TRANSLUCENT, opacity = 1f).fill.alpha
        assertTrue("0 档应比 0.55 档更透", low < mid)
        assertTrue("1 档应比 0.55 档更实", mid < high)
        // 越界不产生非法 alpha（coerce 后再插值）。
        assertEquals(high, bar(GlassMode.TRANSLUCENT, opacity = 2f).fill.alpha, 1e-4f)
        assertEquals(low, bar(GlassMode.TRANSLUCENT, opacity = -1f).fill.alpha, 1e-4f)
    }

    @Test
    fun `两端真到位 0是完全透明 100是完全不透明`() {
        // 线性直取，两端必须真到位：0 是完全透明、100 是完全不透明。
        assertEquals("0% 应完全透明", 0f, bar(GlassMode.TRANSLUCENT, opacity = 0f).fill.alpha, 1e-4f)
        assertEquals("100% 应完全不透明", 1f, bar(GlassMode.TRANSLUCENT, opacity = 1f).fill.alpha, 1e-4f)
        assertEquals("关档不受不透明度影响（本来就实心）", 1f, bar(GlassMode.OFF, opacity = 0f).fill.alpha, 1e-4f)
    }

    @Test
    fun `不透明度是全球唯一口径 深浅色不再改变透明度`() {
        // 线性直取：深浅色只影响底色（surface 本身），不影响 alpha。
        val darkBar = bar(GlassMode.TRANSLUCENT, dark = true)
        val lightBar = bar(GlassMode.TRANSLUCENT, dark = false)
        assertEquals("深浅主题下 alpha 应一致", darkBar.fill.alpha, lightBar.fill.alpha, 1e-4f)
    }

    @Test
    fun `默认不透明度是 78 而不是 55`() {
        // 78% 对应的实际透明度正好等于旧 55 档（旧公式浅色 lerp(0.55,0.96,0.55)≈0.7755），
        // 所以升级后不动滑条观感不变（旧值由 IwaraRepository 一次性迁移到新值域）。
        assertEquals(0.78f, GlassSettings().opacity, 1e-4f)
    }

    @Test
    fun `默认档是半透明 新能力不作默认`() {
        // GlassSettings 默认 = 半透明（历史默认观感，液态玻璃不能是默认）。
        assertEquals(GlassMode.TRANSLUCENT, GlassSettings().mode)
    }

    @Test
    fun `PILL液态玻璃档主题感知 浅色亮雾面 深色黑雾面`() {
        // 回顶按钮玻璃在浅色主题下不再压黑罩；fill.copy(alpha=1f) 还原底色可精确断言。
        val lightSurface = Color(0xFFF7F8FA)
        val light = glassMaterialFor(
            GlassRole.PILL, GlassSettings(0.55f, GlassMode.LIQUID_GLASS), lightSurface, dark = false, backdropActive = true
        )
        assertEquals(lightSurface, light.fill.copy(alpha = 1f))
        val dark = glassMaterialFor(
            GlassRole.PILL, GlassSettings(0.55f, GlassMode.LIQUID_GLASS), surface, dark = true, backdropActive = true
        )
        assertEquals(Color.Black, dark.fill.copy(alpha = 1f))
    }
}
