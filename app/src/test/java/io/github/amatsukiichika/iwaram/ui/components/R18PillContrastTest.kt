package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * R18 药丸的红底/文字色是固定硬编码值，不随主题变化，所以用 WCAG 公式直接钉住可读性：
 * 10sp 小字（非大号文本）需达到 AA 的 4.5:1，防止以后调色时把对比度改回 3.3:1。
 */
class R18PillContrastTest {

    @Test
    fun `r18 pill text meets aa contrast on its red fill`() {
        val ratio = contrastRatio(R18_RED, R18_TEXT)
        assertTrue(
            "R18 药丸对比度 %.2f:1 低于 AA 小字要求的 4.5:1".format(ratio),
            ratio >= 4.5
        )
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
