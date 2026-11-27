package io.github.amatsukiichika.iwaram.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 主题色板的 WCAG 2.x 对比度钉桩。
 *
 * 浅色 primary 同时用作两处，都必须满足 AA 正常字号（4.5:1）：
 *  - 文字色：画在页面底色（[LIGHT_BACKGROUND]）上（选中 pill / 底栏标签等 10~14sp 小字）；
 *  - 填充色：上面是 [LIGHT_ON_PRIMARY] 白字（关注按钮、Local 角标等）。
 * 曾经橙色 #C2661F 对白只有 ~4.0:1，调浅一点就会回归，因此逐项断言而不是只看橙色。
 */
class ThemeContrastTest {

    @Test
    fun `every light theme primary meets aa contrast on background and on primary`() {
        for (option in THEME_COLOR_OPTIONS) {
            val onPrimary = contrastRatio(option.light, LIGHT_ON_PRIMARY)
            assertTrue(
                "${option.value} 主题色与 onPrimary 的对比度 %.2f:1 低于 AA 的 4.5:1".format(onPrimary),
                onPrimary >= 4.5
            )
            val onBackground = contrastRatio(option.light, LIGHT_BACKGROUND)
            assertTrue(
                "${option.value} 主题色与 background 的对比度 %.2f:1 低于 AA 的 4.5:1".format(onBackground),
                onBackground >= 4.5
            )
        }
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val a = relativeLuminance(first)
        val b = relativeLuminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun linear(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
}
