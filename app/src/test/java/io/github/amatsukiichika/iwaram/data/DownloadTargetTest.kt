package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载目标解析里的两段纯逻辑：**挑哪一档**，以及**队列里的地址还能不能用**。
 *
 * 后者是本次修复的核心：签名地址带 expires，任务在队列里排久了，地址在真正开下之前就过期了，
 * 以前只能等它以 401/403 失败、再让用户手动「重新下载」。出队时先按 expires 判一次，
 * 过期就重新解析。判定必须准：把毫秒当秒会把所有地址都判成过期（每次白解析一次），
 * 反过来漏判就还是失败。
 */
class DownloadTargetTest {

    private fun quality(name: String, view: String? = null, download: String? = null) =
        IwaraQualitySource(name = name, src = IwaraQualitySrc(view = view, download = download))

    @Test
    fun `the configured quality wins when it can be downloaded`() {
        val list = listOf(
            quality("Source", download = "https://cdn/source.mp4"),
            quality("360p", download = "https://cdn/360.mp4")
        )
        assertEquals("360p", chooseDownloadQuality(list, "360p")?.name)
    }

    @Test
    fun `a quality with no usable url is never chosen`() {
        val list = listOf(
            quality("Preview"),
            quality("Source", download = "https://cdn/source.mp4")
        )
        assertEquals("Source", chooseDownloadQuality(list, "Preview")?.name)
    }

    @Test
    fun `a view-only quality is still downloadable`() {
        val list = listOf(quality("Source", view = "https://cdn/source-view.mp4"))
        assertEquals("Source", chooseDownloadQuality(list, "Source")?.name)
    }

    @Test
    fun `an unknown preference falls back to the first usable quality`() {
        val list = listOf(quality("Source", download = "https://cdn/source.mp4"))
        assertEquals("Source", chooseDownloadQuality(list, "1080p")?.name)
    }

    @Test
    fun `nothing usable yields null so the caller can report it`() {
        assertNull(chooseDownloadQuality(listOf(quality("Preview")), "Source"))
        assertNull(chooseDownloadQuality(emptyList(), "Source"))
    }

    @Test
    fun `a link without expires never counts as expired`() {
        assertNull(signedUrlTtlMs("https://cdn.example/a.mp4", 1_000_000L))
        assertFalse(isSignedUrlExpired("https://cdn.example/a.mp4", 1_000_000L))
    }

    @Test
    fun `second based expires is converted to milliseconds`() {
        val url = "https://cdn.example/a.mp4?expires=2000"
        assertEquals(1_000_000L, signedUrlTtlMs(url, nowMs = 1_000_000L)!!)
        assertFalse("余量充足", isSignedUrlExpired(url, nowMs = 1_000_000L))
        assertTrue("已过期", isSignedUrlExpired(url, nowMs = 2_500_000L))
    }

    @Test
    fun `millisecond based expires is not mistaken for seconds`() {
        // 若按秒读，2000000000000 秒是天文尺度；这里必须按毫秒解释。
        val url = "https://cdn.example/a.mp4?expires=2000000000000"
        assertEquals(1_000_000_000_000L, signedUrlTtlMs(url, nowMs = 1_000_000_000_000L)!!)
        assertFalse(isSignedUrlExpired(url, nowMs = 1_000_000_000_000L))
    }

    @Test
    fun `a link expiring inside the safety margin is already treated as expired`() {
        val url = "https://cdn.example/a.mp4?expires=100"  // 100 s = 100_000 ms
        assertTrue(isSignedUrlExpired(url, nowMs = 100_000L - DOWNLOAD_URL_EXPIRY_SLACK_MS + 1))
        assertFalse("刚好还有余量", isSignedUrlExpired(url, nowMs = 100_000L - DOWNLOAD_URL_EXPIRY_SLACK_MS - 1))
    }
}
