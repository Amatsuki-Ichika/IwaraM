package io.github.amatsukiichika.iwaram.ui.browse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FeedPager 取消传播回归用例。
 *
 * 旧实现 `catch (t: Throwable)` 会把 CancellationException 写进 error 状态：
 * force 刷新取代旧任务靠 generation 检查侥幸挡住，但 scope 级取消（VM 销毁）
 * 没有 generation 变化，取消会被当成「加载失败」留进状态。
 * 修复后取消一律原样上抛，绝不落进错误态。
 *
 * 等待全部改为「信号 + 有界 join」：用 Unconfined 让 load() 同步跑到首个挂起点，
 * 再用 CompletableDeferred / Job.join 等任务真正结束，不再用固定间隔轮询赌机器速度。
 */
class FeedPagerCancellationTest {

    @Test
    fun `scope cancellation is rethrown - never lands in error state`() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val pager = FeedPager<String>(scope, 10, { it }, { "ERR:${it.javaClass.simpleName}" })
        // 第一批永不返回：挂起直到被取消。
        val started = CompletableDeferred<Unit>()
        pager.load {
            started.complete(Unit)
            awaitCancellation()
        }
        // Unconfined：load 返回时协程体已启动并挂起。
        assertTrue("加载协程应已启动", started.isCompleted)
        scope.cancel()
        // join 是确定性的「取消路径已执行完」信号；有界等待把回归从挂死变成失败。
        withTimeout(5_000) { scopeJob.join() }
        assertNull("被取消的加载不得写成错误态", pager.state.value.error)
        // 任务被取消后没有任何状态写入，loading 停留在 true（无人负责把它置回 false）。
        assertTrue(pager.state.value.loading)
    }

    @Test
    fun `force refresh supersedes in-flight load - old task dies quietly, new task lands`() = runBlocking {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Unconfined)
        val pager = FeedPager<String>(scope, 2, { it }, { "ERR:${it.javaClass.simpleName}" })
        val started = CompletableDeferred<Unit>()
        pager.load {
            started.complete(Unit)
            awaitCancellation()
        }
        assertTrue("旧加载应已启动", started.isCompleted)
        // force：取消旧任务并从第 0 页重拉；旧任务的取消必须安静退场。
        // Unconfined + 立即返回的 fetch：load() 返回时新状态已经落定。
        pager.load(force = true) { page -> listOf("n$page") }
        assertEquals(listOf("n0"), pager.state.value.items)
        assertNull("被取代的旧任务不得污染错误态", pager.state.value.error)
        scope.cancel()
        withTimeout(5_000) { scopeJob.join() }
    }
}
