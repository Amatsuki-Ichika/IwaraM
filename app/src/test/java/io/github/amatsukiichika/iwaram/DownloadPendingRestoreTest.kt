package io.github.amatsukiichika.iwaram

import io.github.amatsukiichika.iwaram.data.PendingDownload
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 持久化记录 → 列表条目的恢复映射回归用例（杀后台后排队任务不能凭空消失）。
 *
 * 契约：
 * - queued / running / 未知历史值 → 一律按 queued（排队中断）呈现，「继续」按钮走重新解析路径；
 * - paused / failed 保持原状态与原文案；
 * - docUri 原样透传（排队记录为 null，UI 的「取消」按钮必须能接受 null）。
 */
class DownloadPendingRestoreTest {

    private fun pending(state: String, docUri: String? = null) = PendingDownload(
        videoId = "v1", title = "t", fileName = "v1.mp4", quality = "Source",
        received = 128, total = -1, docUri = docUri, updatedAt = 0L, state = state
    )

    @Test
    fun `queued record restores as queued interruption`() {
        val t = pendingToTask(pending(DownloadState.STATE_QUEUED))
        assertEquals(DownloadState.STATE_QUEUED, t.state)
        assertEquals("下载中断（点「继续」重新下载）", t.message)
        assertEquals(null, t.docUri)
    }

    @Test
    fun `running record killed by process death restores as queued interruption`() {
        val t = pendingToTask(pending(DownloadState.STATE_RUNNING))
        assertEquals(DownloadState.STATE_QUEUED, t.state)
        assertEquals("下载中断（点「继续」重新下载）", t.message)
    }

    @Test
    fun `paused and failed keep their state and message`() {
        val paused = pendingToTask(pending(DownloadState.STATE_PAUSED, docUri = "content://x"))
        assertEquals(DownloadState.STATE_PAUSED, paused.state)
        assertEquals("已暂停", paused.message)
        assertEquals("content://x", paused.docUri)

        val failed = pendingToTask(pending(DownloadState.STATE_FAILED))
        assertEquals(DownloadState.STATE_FAILED, failed.state)
        assertEquals("下载失败（可重新下载续传）", failed.message)
    }

    @Test
    fun `unknown legacy state falls back to queued interruption`() {
        val t = pendingToTask(pending("legacy-unknown"))
        assertEquals(DownloadState.STATE_QUEUED, t.state)
        assertEquals("下载中断（点「继续」重新下载）", t.message)
    }

    @Test
    fun `queued tasks stay visible in the active list`() {
        // activeDownloadTasks 只排除 DONE —— 排队/中断的记录必须留在列表里（续传入口可达）。
        val list = listOf(
            pendingToTask(pending(DownloadState.STATE_QUEUED)),
            pendingToTask(pending(DownloadState.STATE_RUNNING)),
        )
        assertEquals(2, activeDownloadTasks(list).size)
    }
}
