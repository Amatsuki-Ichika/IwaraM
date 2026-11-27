package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.i18n.AppLanguage
import io.github.amatsukiichika.iwaram.i18n.LanguageMode
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 进度时间戳必须按 [AppLanguage.locale] 格式化，而不是进程默认 Locale（ui/AGENTS.md）。
 *
 * 系统语言是阿拉伯语等使用非拉丁数字的 Locale 时，中文/英文界面仍要显示 0-9；
 * 改回默认 Locale 的会输出「٢:٠٥」这类读不懂的进度。默认 Locale 在 @After 里还原，
 * 不给整个测试 JVM 留状态。
 */
class PlayerControlsFormatTest {

    private lateinit var originalLocale: Locale

    @Before
    fun rememberLocale() {
        originalLocale = Locale.getDefault()
        AppLanguage.set(LanguageMode.EN)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
        AppLanguage.set(LanguageMode.ZH)
    }

    @Test
    fun `formatTime keeps latin digits when the process locale uses arabic digits`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        assertEquals("2:05", formatTime(125_000L))
        assertEquals("1:00:00", formatTime(3_600_000L))
    }

    @Test
    fun `formatTime pads minutes and seconds and switches to hours only past one hour`() {
        assertEquals("0:00", formatTime(0L))
        assertEquals("0:09", formatTime(9_000L))
        assertEquals("59:59", formatTime(3_599_000L))
        assertEquals("1:02:03", formatTime(3_723_000L))
    }
}
