package io.github.amatsukiichika.iwaramanager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载队列不变量回归用例。
 *
 * 旧实现让 worker 以 `withTimeoutOrNull(1500){queue.receive()} ?: break` 空闲退出，
 * 退出窗口内新任务入队但新 `drainQueue()` 因 `workerRunning` CAS 失败直接 return，任务便**永久滞留**
 * Channel，表现为下载卡在 running，只能重启 App。
 *
 * 修复后是常驻单 worker（[drainDownloadQueue]）：没有空闲退出，任何时刻入队的任务都会被串行消费。
 * 本测试把"入队-消费-空闲"循环作为可注入纯协程函数来测（Service 本体依赖 Android 框架，无法在
 * 本地 JVM 直接实例化）。
 */
class DownloadQueueInvariantTest {

    private fun job(id: String) = DownloadService.Job(
        videoId = id, title = "t-$id", url = "https://example/$id", fileName = "$id.mp4", quality = "Source"
    )

    /** 记录型 execute：把消费到的 videoId 按序记录，模拟 runDownload 的占位。 */
    private fun recorder(log: MutableList<String>) = { j: DownloadService.Job ->
        log.add(j.videoId)
        Unit
    }

    @Test
    fun `job enqueued after worker went idle is still executed - the race window is gone`() = runBlocking {
        // 旧竞态的复现场景：第一个任务消费完后 worker 空闲退出的窗口内，新任务入队。
        // 常驻 worker 不退出，无论何时入队都必须被消费。
        val queue = Channel<DownloadService.Job>(Channel.UNLIMITED)
        val executed = mutableListOf<String>()
        val worker = launch { drainDownloadQueue(queue, execute = recorder(executed)) }

        queue.send(job("a"))
        // 等第一个任务被消费完（模拟旧的"空闲判定窗口"）
        while (!executed.contains("a")) delay(10)
        queue.send(job("b"))
        queue.send(job("c"))
        queue.close()

        // for-in 在 close 后仍会消费完已入队的元素
        while (executed.size < 3) delay(10)
        assertEquals(listOf("a", "b", "c"), executed)
        worker.join()
    }

    @Test
    fun `rapid burst of enqueues is fully consumed in order`() = runBlocking {
        val queue = Channel<DownloadService.Job>(Channel.UNLIMITED)
        val executed = mutableListOf<String>()
        repeat(5) { queue.send(job("v$it")) }
        queue.close()
        drainDownloadQueue(queue, execute = recorder(executed))
        assertEquals(listOf("v0", "v1", "v2", "v3", "v4"), executed)
    }

    @Test
    fun `onIdle fires only when queue is empty, not between back-to-back tasks`() = runBlocking {
        val queue = Channel<DownloadService.Job>(Channel.UNLIMITED)
        val executed = mutableListOf<String>()
        val idleCount = mutableListOf<Int>()
        queue.send(job("a"))
        queue.send(job("b"))
        queue.close()
        drainDownloadQueue(
            queue,
            execute = recorder(executed),
            onIdle = { idleCount.add(1) }
        )
        assertEquals(listOf("a", "b"), executed)
        assertTrue("连排任务中间不得触发 onIdle（否则前台通知会闪烁）", idleCount.size == 1)
    }

    @Test
    fun `cancellation propagates - no failure path, no further tasks executed`() = runBlocking {
        // 模拟服务销毁（scope.cancel）：execute 抛 CancellationException 时，
        // 循环必须原样传播 —— 不吞、不继续跑后续任务，也就不会走"下载失败"通知路径。
        val queue = Channel<DownloadService.Job>(Channel.UNLIMITED)
        val executed = mutableListOf<String>()
        queue.send(job("running"))
        queue.send(job("queued"))
        queue.close()
        val worker = launch {
            try {
                drainDownloadQueue(
                    queue,
                    execute = { j ->
                        if (j.videoId == "running") {
                            // 模拟 runDownload 的取消语义：读流被取消时 CancellationException
                            // 原样抛出（catch (e: CancellationException) { throw e }），
                            // 绝不会先走"失败通知/失败记录"路径。
                            throw CancellationException("service destroyed")
                        }
                        executed.add(j.videoId)
                    },
                    onIdle = { }
                )
            } catch (e: CancellationException) {
                // 传播到循环外 = 取消生效；在此吞掉取消本身以便断言后续状态。
            }
        }
        worker.join()
        assertTrue("取消的任务不得记为已执行", executed.isEmpty())
        assertEquals("排队中的任务不得在取消后被消费", emptyList<String>(), executed)
    }
}
