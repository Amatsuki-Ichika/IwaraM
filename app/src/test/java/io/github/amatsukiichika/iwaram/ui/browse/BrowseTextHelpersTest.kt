package io.github.amatsukiichika.iwaram.ui.browse

import io.github.amatsukiichika.iwaram.i18n.AppLanguage
import io.github.amatsukiichika.iwaram.i18n.LanguageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

/** ui.browse 包内的纯文本格式化函数：计数简写、相对时间、字节数。 */
class BrowseTextHelpersTest {

    /** 固定"当前时刻"：相对时间的断言不依赖真实时钟。 */
    private val fixedNow = Instant.parse("2026-11-26T12:00:00Z").toEpochMilli()

    /** 临时替换进程默认 Locale（模拟逗号小数点/非拉丁数字的系统语言），结束后必定还原。 */
    private fun <T> withDefaultLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    /** 临时切换界面语言，结束后必定还原，避免测试间互相污染。 */
    private fun <T> withLanguage(mode: LanguageMode, block: () -> T): T {
        val previous = AppLanguage.mode
        AppLanguage.set(mode)
        try {
            return block()
        } finally {
            AppLanguage.set(previous)
        }
    }

    // ---------- formatCount（IwaraCommon） ----------

    @Test
    fun `formatCount keeps small numbers as is`() {
        assertEquals("0", formatCount(0))
        assertEquals("9999", formatCount(9_999))
    }

    @Test
    fun `formatCount abbreviates with 万 from 10000`() {
        assertEquals("1.2万", formatCount(12_345))
        assertEquals("9999.9万", formatCount(99_999_000))
    }

    @Test
    fun `formatCount abbreviates with 亿 from 100000000`() {
        assertEquals("2.3亿", formatCount(230_000_000))
    }

    @Test
    fun `formatCount follows app language under a comma-decimal process locale`() {
        // 德语系统 Locale 把 "%.1f" 格式化成 "1,2"；输出必须跟随 AppLanguage.locale 而不是进程默认值。
        withDefaultLocale(Locale.GERMANY) {
            withLanguage(LanguageMode.ZH) {
                assertEquals("1.2万", formatCount(12_345))
                assertEquals("9999.9万", formatCount(99_999_000))
            }
            withLanguage(LanguageMode.EN) {
                assertEquals("12K", formatCount(12_345))
                assertEquals("230M", formatCount(230_000_000))
            }
        }
    }

    // ---------- timeAgoLabel（IwaraCommon） ----------

    @Test
    fun `timeAgoLabel returns null for null or unparseable input`() {
        assertNull(timeAgoLabel(null, fixedNow))
        assertNull(timeAgoLabel("not-a-date", fixedNow))
    }

    @Test
    fun `timeAgoLabel renders minutes under one hour`() {
        val iso = Instant.ofEpochMilli(fixedNow - 30 * 60_000L).toString()
        assertEquals("30 分钟前", timeAgoLabel(iso, fixedNow))
    }

    @Test
    fun `timeAgoLabel renders hours under one day`() {
        val iso = Instant.ofEpochMilli(fixedNow - 2 * 3_600_000L).toString()
        assertEquals("2 小时前", timeAgoLabel(iso, fixedNow))
    }

    @Test
    fun `timeAgoLabel renders days under 30 days`() {
        val iso = Instant.ofEpochMilli(fixedNow - 3L * 24 * 3_600_000).toString()
        assertEquals("3 天前", timeAgoLabel(iso, fixedNow))
    }

    @Test
    fun `timeAgoLabel renders absolute date beyond 30 days`() {
        val iso = Instant.ofEpochMilli(fixedNow - 100L * 24 * 3_600_000).toString()
        assertTrue(timeAgoLabel(iso, fixedNow)!!.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
    }

    // ---------- formatBytes（IwaraSecondaryFeeds） ----------

    @Test
    fun `formatBytes renders plain bytes under 1 KB`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("0 B", formatBytes(0))
    }

    @Test
    fun `formatBytes scales through KB and MB`() {
        assertEquals("2 KB", formatBytes(2L shl 10))
        assertEquals("1.5 MB", formatBytes((1.5 * (1L shl 20)).toLong()))
    }

    @Test
    fun `formatBytes renders GB with two decimals`() {
        assertEquals("1.00 GB", formatBytes(1L shl 30))
    }

    @Test
    fun `formatBytes follows app language under a comma-decimal process locale`() {
        // 德语系统 Locale 会产出 "1,00 GB"；下载列表必须显示界面语言的小数点。
        withDefaultLocale(Locale.GERMANY) {
            withLanguage(LanguageMode.ZH) {
                assertEquals("1.00 GB", formatBytes(1L shl 30))
                assertEquals("1.5 MB", formatBytes((1.5 * (1L shl 20)).toLong()))
            }
            withLanguage(LanguageMode.EN) {
                assertEquals("1.00 GB", formatBytes(1L shl 30))
                assertEquals("2 KB", formatBytes(2L shl 10))
            }
        }
    }
}
