package io.github.amatsukiichika.iwaram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「正在下载」列表合并与裁剪的回归用例。
 *
 * 旧实现用 `take(6)` 对整个列表一刀切：第 7 个任务起从界面整条消失，
 * 但 Job 仍在跑、持久化记录也还在，用户既看不到进度，也没有暂停/继续/取消入口。
 * 契约：**未完成任务一条都不能丢**，只对完成态历史封顶；同 videoId 去重、新任务在前。
 */
class DownloadTaskListTest {

    private fun task(id: String, state: String = DownloadState.STATE_RUNNING) =
        DownloadState.Task(videoId = id, title = "t-$id", state = state)

    private fun ids(tasks: List<DownloadState.Task>) = tasks.map { it.videoId }

    @Test
    fun `seven or more active tasks stay visible`() {
        val tasks = listOf(
            task("q1", DownloadState.STATE_QUEUED),
            task("q2", DownloadState.STATE_QUEUED),
            task("r1"),
            task("r2"),
            task("p1", DownloadState.STATE_PAUSED),
            task("f1", DownloadState.STATE_FAILED),
            task("r3"),
            task("q3", DownloadState.STATE_QUEUED),
        )
        val trimmed = trimDownloadTasks(tasks, doneLimit = 2)
        assertEquals(ids(tasks), ids(trimmed))
        assertEquals("第 7 条以后的活动任务也必须可见", ids(tasks), ids(activeDownloadTasks(trimmed)))
    }

    @Test
    fun `the oldest completed tasks are dropped and active ones stay`() {
        // 列表顺序即“最近在前”：完成态按出现顺序保留前 doneLimit 条。
        val tasks = listOf(
            task("done-newest", DownloadState.STATE_DONE),
            task("run"),
            task("done-old", DownloadState.STATE_DONE),
            task("pause", DownloadState.STATE_PAUSED),
            task("done-oldest", DownloadState.STATE_DONE),
        )
        assertEquals(
            listOf("done-newest", "run", "done-old", "pause"),
            ids(trimDownloadTasks(tasks, doneLimit = 2))
        )
    }

    @Test
    fun `non completed tasks are never capped`() {
        val tasks = (1..40).map { task("run-$it") }
        assertEquals(40, trimDownloadTasks(tasks, doneLimit = 1).size)
    }

    @Test
    fun `a zero done limit drops only completed tasks`() {
        val tasks = listOf(
            task("done", DownloadState.STATE_DONE),
            task("fail", DownloadState.STATE_FAILED),
            task("pause", DownloadState.STATE_PAUSED),
        )
        assertEquals(listOf("fail", "pause"), ids(trimDownloadTasks(tasks, doneLimit = 0)))
    }

    @Test
    fun `upsert puts the newest task first and replaces the same video`() {
        val merged = mergeDownloadTask(
            tasks = listOf(task("a", DownloadState.STATE_DONE), task("b")),
            task = task("b", DownloadState.STATE_PAUSED),
            doneLimit = 20
        )
        assertEquals(listOf("b", "a"), ids(merged))
        assertEquals(DownloadState.STATE_PAUSED, merged[0].state)
        assertEquals("同 videoId 只能有一条", 1, merged.count { it.videoId == "b" })
        assertTrue("完成态历史照常保留", merged.any { it.videoId == "a" })
    }

    @Test
    fun `upsert can show more than six active downloads`() {
        var tasks = emptyList<DownloadState.Task>()
        for (index in 1..9) {
            tasks = mergeDownloadTask(tasks, task("v$index"), doneLimit = 20)
        }
        assertEquals(9, activeDownloadTasks(tasks).size)
    }

    @Test
    fun `restore appends missing tasks without overriding live ones`() {
        val restored = listOf(
            task("a", DownloadState.STATE_PAUSED),
            task("b", DownloadState.STATE_PAUSED),
            task("c", DownloadState.STATE_FAILED),
        )
        val merged = mergeRestoredDownloadTasks(listOf(task("a")), restored, doneLimit = 20)
        assertEquals(listOf("a", "b", "c"), ids(merged))
        assertEquals(
            "已存在的活动条目不被恢复记录覆盖",
            DownloadState.STATE_RUNNING,
            merged[0].state
        )
    }

    @Test
    fun `restored tasks are not dropped by the done limit`() {
        val restored = (1..30).map { task("p$it", DownloadState.STATE_PAUSED) }
        val merged = mergeRestoredDownloadTasks(emptyList(), restored, doneLimit = 0)
        assertEquals(30, merged.size)
    }

    @Test
    fun `duplicate restored entries are merged`() {
        val restored = listOf(
            task("a", DownloadState.STATE_PAUSED),
            task("a", DownloadState.STATE_FAILED),
        )
        val merged = mergeRestoredDownloadTasks(emptyList(), restored, doneLimit = 20)
        assertEquals(1, merged.size)
        assertEquals(DownloadState.STATE_PAUSED, merged[0].state)
    }
}
