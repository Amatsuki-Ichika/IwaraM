package io.github.amatsukiichika.iwaramanager.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ui.browse 包内的纯文本格式化函数：计数简写、相对时间、字节数。 */
class BrowseTextHelpersTest {

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

    // ---------- timeAgoLabel（IwaraCommon） ----------

    @Test
    fun `timeAgoLabel returns null for null or unparseable input`() {
        assertNull(timeAgoLabel(null))
        assertNull(timeAgoLabel("not-a-date"))
    }

    @Test
    fun `timeAgoLabel renders minutes under one hour`() {
        val iso = java.time.Instant.ofEpochMilli(System.currentTimeMillis() - 30 * 60_000L).toString()
        assertEquals("30 分钟前", timeAgoLabel(iso))
    }

    @Test
    fun `timeAgoLabel renders hours under one day`() {
        val iso = java.time.Instant.ofEpochMilli(System.currentTimeMillis() - 2 * 3_600_000L).toString()
        assertEquals("2 小时前", timeAgoLabel(iso))
    }

    @Test
    fun `timeAgoLabel renders days under 30 days`() {
        val iso = java.time.Instant.ofEpochMilli(System.currentTimeMillis() - 3L * 24 * 3_600_000).toString()
        assertEquals("3 天前", timeAgoLabel(iso))
    }

    @Test
    fun `timeAgoLabel renders absolute date beyond 30 days`() {
        val iso = java.time.Instant.ofEpochMilli(System.currentTimeMillis() - 100L * 24 * 3_600_000).toString()
        assertTrue(timeAgoLabel(iso)!!.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
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
}
