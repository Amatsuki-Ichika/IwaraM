package io.github.amatsukiichika.iwaram.data

import io.github.amatsukiichika.iwaram.DownloadState
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 下载自动重试策略的纯函数用例。
 *
 * 契约（Service 的退避重试接线依赖它）：
 * - 网络类失败在次数预算内可重试；401/403/404、权限、磁盘满、用户取消永不自动重试；
 * - 退避 3s / 10s / 30s；
 * - 重启恢复只续 queued/running，paused/failed 永不自动恢复；
 * - 失败卡片只用"重试次数"表达，不硬编码中英文案。
 */
class DownloadPoliciesTest {

    private fun pending(
        videoId: String,
        state: String,
        attempts: Int = 0
    ) = PendingDownload(
        videoId = videoId, title = videoId, fileName = "$videoId.mp4", quality = "Source",
        received = 0, total = -1, docUri = null, updatedAt = 0L, state = state, attempts = attempts
    )

    @Test
    fun `transient io failures retry within the attempt budget`() {
        assertTrue(shouldRetryDownload(null, IOException("connection reset"), 0))
        assertTrue(shouldRetryDownload(null, SocketTimeoutException("timeout"), 1))
        assertTrue(shouldRetryDownload(null, UnknownHostException("no dns"), 2))
        assertFalse("已用完 3 次自动重试后不再重试", shouldRetryDownload(null, IOException("reset"), 3))
        assertFalse(shouldRetryDownload(null, IOException("reset"), 4))
    }

    @Test
    fun `server and throttling http codes are retryable while client errors are not`() {
        for (code in listOf(408, 429, 500, 502, 503, 599)) {
            assertTrue("HTTP $code 应可重试", shouldRetryDownload(code, null, 0))
        }
        for (code in listOf(400, 401, 403, 404, 409, 416, 499)) {
            assertFalse("HTTP $code 不应自动重试", shouldRetryDownload(code, null, 0))
        }
    }

    @Test
    fun `deterministic failures are never retried even with a retryable http code`() {
        assertFalse(
            "用户取消不能被当成失败重试",
            shouldRetryDownload(null, CancellationException("cancelled"), 0)
        )
        assertFalse(shouldRetryDownload(500, CancellationException("cancelled"), 0))
        assertFalse(
            "权限/SAF 拒绝重试也没用",
            shouldRetryDownload(503, SecurityException("write denied"), 0)
        )
        assertFalse(
            "磁盘满：重试只会再失败一次",
            shouldRetryDownload(null, IOException("write failed: ENOSPC (No space left on device)"), 0)
        )
        assertFalse(
            "ENOSPC 包在 cause 链里同样不可重试",
            shouldRetryDownload(null, IOException("write failed", IOException("No space left on device")), 0)
        )
        assertFalse(shouldRetryDownload(null, IllegalStateException("boom"), 0))
        assertFalse("无状态码也无异常时无从判断，按不重试处理", shouldRetryDownload(null, null, 0))
    }

    @Test
    fun `retry delays grow three ten thirty seconds then stop`() {
        assertEquals(3_000L, downloadRetryDelayMs(0))
        assertEquals(10_000L, downloadRetryDelayMs(1))
        assertEquals(30_000L, downloadRetryDelayMs(2))
        assertEquals(0L, downloadRetryDelayMs(3))
        assertEquals(0L, downloadRetryDelayMs(-1))
    }

    @Test
    fun `resume plan restores only interrupted records in input order`() {
        val pending = listOf(
            pending("a", DownloadState.STATE_RUNNING),
            pending("b", DownloadState.STATE_PAUSED),
            pending("c", DownloadState.STATE_QUEUED),
            pending("d", DownloadState.STATE_FAILED),
            pending("e", DownloadState.STATE_DONE),
            pending("f", DownloadState.STATE_RUNNING)
        )
        val plan = resumePlan(pending, liveIds = emptySet(), enabled = true)
        assertEquals(listOf("a", "c", "f"), plan.map { it.videoId })
    }

    @Test
    fun `resume plan skips jobs that are already live and respects the disabled switch`() {
        val pending = listOf(
            pending("a", DownloadState.STATE_QUEUED),
            pending("b", DownloadState.STATE_RUNNING)
        )
        assertEquals(
            listOf("b"),
            resumePlan(pending, liveIds = setOf("a"), enabled = true).map { it.videoId }
        )
        assertEquals(emptyList<PendingDownload>(), resumePlan(pending, emptySet(), enabled = false))
    }

    @Test
    fun `resume plan ignores retry counters - an interrupted retry still resumes`() {
        // 进程在自动重试中途被杀：records 的 attempts 可能 >0，但仍属"中断"，下次启动可续。
        val plan = resumePlan(
            listOf(pending("a", DownloadState.STATE_RUNNING, attempts = 2)),
            liveIds = emptySet(),
            enabled = true
        )
        assertEquals(listOf("a"), plan.map { it.videoId })
    }

    @Test
    fun `failure card reports clamped retry count only for the failed state`() {
        assertEquals(0, downloadCardFailureReason(DownloadState.STATE_FAILED, 0))
        assertEquals(2, downloadCardFailureReason(DownloadState.STATE_FAILED, 2))
        assertEquals(
            "超过上限时按上限展示，避免 UI 出现 第 7/3 次",
            DOWNLOAD_MAX_AUTO_RETRIES,
            downloadCardFailureReason(DownloadState.STATE_FAILED, 7)
        )
        assertNull(downloadCardFailureReason(DownloadState.STATE_PAUSED, 1))
        assertNull(downloadCardFailureReason(DownloadState.STATE_RUNNING, 1))
        assertNull(downloadCardFailureReason(DownloadState.STATE_QUEUED, 0))
        assertNull(downloadCardFailureReason(DownloadState.STATE_DONE, 0))
        assertNull(downloadCardFailureReason("legacy-unknown", 0))
    }
}
