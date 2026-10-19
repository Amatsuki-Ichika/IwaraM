package io.github.amatsukiichika.iwaramanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IwaraSignTest {

    @Test
    fun `httpsUrl prefixes protocol-relative addresses`() {
        assertEquals("https://files.iwara.tv/a.mp4", IwaraSign.httpsUrl("//files.iwara.tv/a.mp4"))
    }

    @Test
    fun `httpsUrl keeps absolute addresses untouched`() {
        assertEquals("https://files.iwara.tv/a.mp4", IwaraSign.httpsUrl("https://files.iwara.tv/a.mp4"))
    }

    @Test
    fun `buildDownloadUrl fixes scheme missing on download src`() {
        // src.download 形如 //host/...，不归一化时 OkHttp 会报
        // "Expected URL scheme 'http' or 'https' but no scheme was found"。
        val url = IwaraSign.buildDownloadUrl("//yuko.iwara.tv/dl?expires=1", "[abc] title.mp4")
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://yuko.iwara.tv/dl?expires=1"))
        assertTrue(url.endsWith("&download=%5Babc%5D+title.mp4"))
    }

    @Test
    fun `buildDownloadUrl appends with ampersand when query exists`() {
        val url = IwaraSign.buildDownloadUrl("https://h/d?a=1", "t.mp4")
        assertEquals("https://h/d?a=1&download=t.mp4", url)
    }

    @Test
    fun `buildDownloadUrl appends with question mark when no query`() {
        val url = IwaraSign.buildDownloadUrl("//h/d", "t.mp4")
        assertEquals("https://h/d?download=t.mp4", url)
    }

    @Test
    fun `buildDownloadUrl rejects urls without http scheme`() {
        assertNull(IwaraSign.buildDownloadUrl("ftp://h/d", "t.mp4"))
        assertNull(IwaraSign.buildDownloadUrl("   ", "t.mp4"))
    }

    @Test
    fun `xVersion is deterministic 40-hex sha1`() {
        val a = IwaraSign.xVersion("https://files.iwara.tv/video.mp4?expires=123")
        val b = IwaraSign.xVersion("https://files.iwara.tv/video.mp4?expires=123")
        assertEquals(a, b)
        assertEquals(40, a.length)
        assertTrue(a.all { it.isDigit() || it in 'a'..'f' })
    }
}
