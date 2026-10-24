package io.github.amatsukiichika.iwaram.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片三行文本归一化的回归用例。
 * 覆盖实际出现过的脏数据：全角空格、NBSP、连续空白、全角字母数字、零宽字符。
 */
class CardTextTest {

    @Test
    fun `null and blank become empty`() {
        assertEquals("", normalizeCardText(null))
        assertEquals("", normalizeCardText(""))
        assertEquals("", normalizeCardText("   "))
        // 全角空格 / NBSP 是"trim() 无效"的根源：它们必须被当成空白清掉。
        assertEquals("", normalizeCardText("\u3000"))
        assertEquals("", normalizeCardText("\u00A0"))
        assertEquals("", normalizeCardText(" \t\r\n "))
    }

    @Test
    fun `trims and collapses consecutive whitespace`() {
        assertEquals("hello", normalizeCardText("  hello  "))
        assertEquals("a b", normalizeCardText("a   b"))
        assertEquals("a b c d", normalizeCardText("a \t b \n c \rd"))
        // 全角空格、NBSP、窄不换行空格都折叠成单个半角空格
        assertEquals("a b", normalizeCardText("a\u3000b"))
        assertEquals("a b", normalizeCardText("a\u00A0b"))
        assertEquals("a b", normalizeCardText("a\u202Fb"))
        assertEquals("a b", normalizeCardText("a \u3000 \u00A0 b"))
    }

    @Test
    fun `fullwidth letters and digits become halfwidth`() {
        assertEquals("ABC123", normalizeCardText("ＡＢＣ１２３"))
        assertEquals("abc", normalizeCardText("ａｂｃ"))
        assertEquals("My Video 2", normalizeCardText("Ｍｙ　Ｖｉｄｅｏ　２"))
        // 半角片假名（U+FF71 起）不在全角 ASCII 区，必须原样保留
        assertEquals("ｱｲｳ", normalizeCardText("ｱｲｳ"))
    }

    @Test
    fun `fullwidth punctuation is preserved to keep japanese text pretty`() {
        assertEquals("标题！", normalizeCardText("标题！"))
        assertEquals("（テスト）", normalizeCardText("（テスト）"))
        assertEquals("a?b", normalizeCardText("a?b"))
    }

    @Test
    fun `zero width characters are dropped without creating spaces`() {
        assertEquals("ab", normalizeCardText("a\u200Bb"))
        assertEquals("ab", normalizeCardText("a\uFEFFb"))
        assertEquals("ab", normalizeCardText("a\u200C\u200Db"))
    }

    @Test
    fun `author-like dirty values normalize into aligned text`() {
        assertEquals("", normalizeCardText(null))
        assertEquals("Lyu ya", normalizeCardText(" Lyu\u00A0ya "))
        assertEquals("user792540", normalizeCardText("　user792540　"))
    }

    /* ---------- 卡片左下角日期文案 ---------- */

    /** 一个固定的"现在"，避免测试受真实时间影响。 */
    private val now = 1_800_000_000_000L

    /** "多久以前"的时间戳（显式组合，避免 days/hours 相加时符号搞反）。 */
    private fun ago(days: Long = 0, hours: Long = 0, mins: Long = 0): Long =
        now - days * 86_400_000L - hours * 3_600_000L - mins * 60_000L

    @Test
    fun `不足一分钟显示刚刚`() {
        assertEquals("刚刚", cardDateText(now, now))                     // 同一时刻
        assertEquals("刚刚", cardDateText(ago(mins = 1) + 1_000L, now))  // 59 秒前
    }

    @Test
    fun `满一分钟起按分钟取整`() {
        // 1~59 分钟显示「n 分钟前」。
        assertEquals("1 分钟前", cardDateText(ago(mins = 1), now))
        assertEquals("59 分钟前", cardDateText(ago(mins = 59), now))
    }

    @Test
    fun `满一小时起按小时取整`() {
        // 边界：正好 60 分钟 → 1 小时前；23h59m → 23 小时前（不到 24h 不算天）。
        assertEquals("1 小时前", cardDateText(ago(hours = 1), now))
        assertEquals("23 小时前", cardDateText(ago(hours = 23, mins = 59), now))
    }

    @Test
    fun `满24小时起按天取整`() {
        assertEquals("1 天前", cardDateText(ago(days = 1), now))
        // 29 天 23 小时仍是「29 天前」（按 24 小时向下取整）。
        assertEquals("29 天前", cardDateText(ago(days = 29, hours = 23), now))
    }

    @Test
    fun `满30天改为具体日期`() {
        val text = cardDateText(ago(days = 30), now)
        assertTrue("应是 yyyy-MM-dd，实际=$text", Regex("""\d{4}-\d{2}-\d{2}""").matches(text))
    }

    @Test
    fun `未来时间不吃负数`() {
        // 设备时钟偏慢 / 服务器时间超前时，绝不能显示"-3 小时前"。
        assertEquals("刚刚", cardDateText(now + 3 * 3_600_000L, now))
    }
}
