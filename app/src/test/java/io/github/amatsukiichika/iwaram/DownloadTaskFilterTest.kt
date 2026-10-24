package io.github.amatsukiichika.iwaram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「正在下载」列表过滤的回归用例。
 *
 * 若过滤条件等价于 `!it.finished`，而 [DownloadState.Task.finished] = DONE **或 FAILED**
 * ⇒ **失败任务被整条隐藏**，`when` 里的 STATE_FAILED 分支（重新下载 / 移除）成了不可达的死代码，
 * 失败后既看不到也续不了。
 *
 * 失败与暂停恰恰是最需要续传入口的状态，必须留在列表里 —— 这个纯函数就是这条契约的唯一出口。
 */
class DownloadTaskFilterTest {

    private fun task(id: String, state: String) =
        DownloadState.Task(videoId = id, title = "t-$id", state = state)

    @Test
    fun `failed tasks stay visible so the resume entry is reachable`() {
        val list = listOf(
            task("run", DownloadState.STATE_RUNNING),
            task("pause", DownloadState.STATE_PAUSED),
            task("fail", DownloadState.STATE_FAILED),
            task("done", DownloadState.STATE_DONE),
        )
        val ids = activeDownloadTasks(list).map { it.videoId }
        assertEquals(listOf("run", "pause", "fail"), ids)
        assertTrue("失败任务必须可见，否则「重新下载/续传」入口消失", ids.contains("fail"))
    }

    @Test
    fun `only completed tasks are filtered out`() {
        assertTrue(activeDownloadTasks(listOf(task("ok", DownloadState.STATE_DONE))).isEmpty())
    }

    @Test
    fun `relative order is preserved`() {
        val list = listOf(
            task("a", DownloadState.STATE_FAILED),
            task("b", DownloadState.STATE_DONE),
            task("c", DownloadState.STATE_PAUSED),
        )
        assertEquals(listOf("a", "c"), activeDownloadTasks(list).map { it.videoId })
    }
}
