package io.github.amatsukiichika.iwaram

import io.github.amatsukiichika.iwaram.data.PendingDownload
import io.github.amatsukiichika.iwaram.data.autoResumePlanBeforeRestore
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

    private fun pending(state: String, docUri: String? = null, videoId: String = "v1") = PendingDownload(
        videoId = videoId, title = "t", fileName = "v1.mp4", quality = "Source",
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
    fun `auto resume plan is computed from the pre-restore snapshot`() {
        // 启动自动继续的接线顺序：先取"恢复前"的 liveIds，再 restoreIfAbsent。
        // 反例（旧接线）在下面第二段 —— 恢复后任务自己也在列表里，计划恒空。
        val pending = listOf(
            pending(DownloadState.STATE_RUNNING, videoId = "a"),
            pending(DownloadState.STATE_QUEUED, videoId = "b"),
            pending(DownloadState.STATE_PAUSED, videoId = "c"),
        )
        val liveBeforeRestore = emptyList<DownloadState.Task>()
        assertEquals(
            "进程刚重启时内存列表为空：被中断的 queued/running 全部应续，paused 不续",
            listOf("a", "b"),
            autoResumePlanBeforeRestore(pending, liveBeforeRestore, enabled = true).map { it.videoId }
        )

        val liveAfterRestore = pending.map { pendingToTask(it) }
        assertEquals(
            "先恢复再取 liveIds 会得到空计划（0.2.2 自动继续不工作的根因）",
            emptyList<PendingDownload>(),
            autoResumePlanBeforeRestore(pending, liveAfterRestore, enabled = true)
        )
    }

    @Test
    fun `auto resume plan still skips tasks that are genuinely already running`() {
        // 配置变化/Activity 重建而进程还活着：服务内存列表里的任务不能再入队一次。
        val pending = listOf(
            pending(DownloadState.STATE_RUNNING, videoId = "a"),
            pending(DownloadState.STATE_QUEUED, videoId = "b"),
        )
        val live = listOf(DownloadState.Task("a", "t", state = DownloadState.STATE_RUNNING))
        assertEquals(
            listOf("b"),
            autoResumePlanBeforeRestore(pending, live, enabled = true).map { it.videoId }
        )
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
