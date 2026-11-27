package io.github.amatsukiichika.iwaram

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 排队卡片补半成品地址的回归用例（审查 Medium-9）。
 *
 * 恢复/重试的 QUEUED 任务必须带上已知的 docUri：取消按钮把它传给
 * removePendingCompletely 删半成品文件；缺失就会把暂停留下的半截文件永久留在磁盘上
 * （之后还会被扫描当成 truncated 视频导入）。只补仍在排队的条目 ——
 * worker 已开跑（RUNNING）时不能把进度盖回排队态，DONE 的地址指向成品文件更不能被改写。
 */
class DownloadQueuedDocUriTest {

    private val id = "queued-doc-uri-test"

    @After
    fun tearDown() {
        DownloadState.remove(id)
    }

    @Test
    fun `a queued task picks up the recorded partial file address`() {
        DownloadState.upsert(DownloadState.Task(id, "t", state = DownloadState.STATE_QUEUED))

        DownloadState.fillDocUriIfQueued(id, "content://partial")

        val task = DownloadState.tasks.value.first { it.videoId == id }
        assertEquals(DownloadState.STATE_QUEUED, task.state)
        assertEquals("content://partial", task.docUri)
    }

    @Test
    fun `a running task is not rewound to queued by the fill`() {
        DownloadState.upsert(
            DownloadState.Task(id, "t", received = 5, state = DownloadState.STATE_RUNNING)
        )

        DownloadState.fillDocUriIfQueued(id, "content://partial")

        val task = DownloadState.tasks.value.first { it.videoId == id }
        assertEquals(DownloadState.STATE_RUNNING, task.state)
        assertNull("RUNNING 条目不得被补写/回退", task.docUri)
    }

    @Test
    fun `a completed task keeps its own address`() {
        DownloadState.upsert(
            DownloadState.Task(id, "t", state = DownloadState.STATE_DONE, docUri = "content://done")
        )

        DownloadState.fillDocUriIfQueued(id, "content://partial")

        assertEquals("content://done", DownloadState.tasks.value.first { it.videoId == id }.docUri)
    }
}
