package io.github.amatsukiichika.iwaramanager.ui.browse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
 */
class FeedPagerCancellationTest {

    @Test
    fun `scope cancellation is rethrown - never lands in error state`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pager = FeedPager<String>(scope, 10, { it }, { "ERR:${it.javaClass.simpleName}" })
        // 第一批永不返回：挂起直到被取消。
        pager.load { awaitCancellation() }
        while (!pager.state.value.loading) delay(10)
        scope.cancel()
        // 给 catch 路径留出执行窗口：若吞掉取消，error 会写成 ERR:JobCancellationException。
        repeat(50) { delay(10) }
        assertNull("被取消的加载不得写成错误态", pager.state.value.error)
        // 任务被取消后没有任何状态写入，loading 停留在 true（无人负责把它置回 false）。
        assertTrue(pager.state.value.loading)
    }

    @Test
    fun `force refresh supersedes in-flight load - old task dies quietly, new task lands`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pager = FeedPager<String>(scope, 2, { it }, { "ERR:${it.javaClass.simpleName}" })
        pager.load { awaitCancellation() }
        while (!pager.state.value.loading) delay(10)
        // force：取消旧任务并从第 0 页重拉；旧任务的取消必须安静退场。
        pager.load(force = true) { page -> listOf("n$page") }
        while (pager.state.value.items.isEmpty()) delay(10)
        assertEquals(listOf("n0"), pager.state.value.items)
        assertNull("被取代的旧任务不得污染错误态", pager.state.value.error)
        scope.cancel()
    }
}
