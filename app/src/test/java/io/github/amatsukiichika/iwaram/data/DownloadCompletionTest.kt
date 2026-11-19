package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一次下载尝试"算不算完成"与续传区间校验的纯函数用例。
 *
 * 契约（[io.github.amatsukiichika.iwaram.DownloadService.downloadOnce] 的收尾判据依赖它们）：
 * - 只有收到的字节数正好等于应得长度才允许标记完成，读到 EOF 本身不是证据；
 * - 206 的 Content-Range 必须从本次请求的续传位置开始，缺失/错位/越界一律不拼接；
 * - 总长度未知（分块响应）时只能放行 —— 这是协议能校验到的上限。
 */
class DownloadCompletionTest {

    @Test
    fun `content range parses start end and total`() {
        assertEquals(ContentRange(100, 199, 2000), parseContentRange("bytes 100-199/2000"))
        assertEquals(ContentRange(0, 0, 1), parseContentRange("bytes 0-0/1"))
    }

    @Test
    fun `content range accepts an unknown total`() {
        assertEquals(ContentRange(100, 199, -1), parseContentRange("bytes 100-199/*"))
    }

    @Test
    fun `content range rejects malformed or unsatisfied values`() {
        assertNull(parseContentRange(null))
        assertNull(parseContentRange("   "))
        assertNull(parseContentRange("items 0-10/20"))
        assertNull(parseContentRange("bytes */2000"))
        assertNull(parseContentRange("bytes 10/2000"))
        assertNull(parseContentRange("bytes 199-100/2000"))
        assertNull(parseContentRange("bytes 0-10/abc"))
        assertNull(parseContentRange("bytes 0-10"))
    }

    @Test
    fun `resume only accepts a range that starts where the request asked`() {
        assertEquals(ContentRange(500, 999, 1000), resumeRange("bytes 500-999/1000", 500L))
        assertNull("起点错位会把两段内容拼在一起", resumeRange("bytes 0-499/1000", 500L))
        assertNull("头缺失就无法证明服务器真的按续传回包", resumeRange(null, 500L))
        assertNull(
            "区间越过总量说明 Content-Range 自相矛盾（end 是闭区间）",
            resumeRange("bytes 500-1000/1000", 500L)
        )
        assertEquals("总量未知时只校验起点", ContentRange(500, 999, -1), resumeRange("bytes 500-999/*", 500L))
    }

    @Test
    fun `completion requires the exact expected byte count`() {
        assertTrue(isDownloadComplete(1000, 1000))
        assertFalse("少一个字节也不算完成", isDownloadComplete(999, 1000))
        assertFalse("多出来的字节同样不可信", isDownloadComplete(1001, 1000))
        assertTrue("长度未知（分块响应）时只能信 EOF", isDownloadComplete(123, -1))
        assertTrue(isDownloadComplete(0, 0))
    }

    @Test
    fun `incomplete attempts are retryable transport failures`() {
        assertTrue(shouldRetryDownload(null, DownloadIncompleteException(999, 1000), 0))
        assertTrue(shouldRetryDownload(null, DownloadRangeMismatchException("bytes 0-9/100", 10), 1))
        assertFalse(
            "重试预算用尽后必须收尾，不能无限重下",
            shouldRetryDownload(null, DownloadIncompleteException(999, 1000), DOWNLOAD_MAX_AUTO_RETRIES)
        )
    }

    @Test
    fun `a library file counts as complete only when it reached the recorded total`() {
        assertTrue("收满就是完整", libraryFileIsComplete(1000L, 1000L))
        assertTrue("比记录还长（多写了字节）也按完整处理", libraryFileIsComplete(1001L, 1000L))
        assertFalse("半成品不是完整文件", libraryFileIsComplete(999L, 1000L))
        assertFalse("0 字节不算完整", libraryFileIsComplete(0L, 1000L))
        assertFalse("读不到长度时不能当完整", libraryFileIsComplete(null, 1000L))
        assertTrue("总量未知时无从判断，按完整处理避免重下用户自备文件", libraryFileIsComplete(1L, -1L))
        assertTrue(libraryFileIsComplete(12345L, 0L))
    }
}
