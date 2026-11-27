package io.github.amatsukiichika.iwaram.ui.browse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FeedPager.reset 回归用例。
 *
 * reset 是账号级分页器（订阅 / 收藏）唯一的清空出口：漏清 page/endReached 会让下一个账号
 * 接着上一个账号的页码往后翻，漏取消在途任务会让上一个账号的结果晚到后写回列表。
 */
class FeedPagerResetTest {

    private fun newPager(scope: CoroutineScope, pageSize: Int = 2): FeedPager<String> =
        FeedPager(scope, pageSize, { it }, { "ERR:${it.javaClass.simpleName}" })

    @Test
    fun `reset clears items page endReached and error`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pager = newPager(scope)
        // 第 0 页满页（2 条）⇒ page = 0、endReached = false；再让第 1 页失败留下 error。
        pager.load(force = true) { listOf("a", "b") }
        while (pager.state.value.items.isEmpty()) delay(10)
        pager.load { throw IllegalStateException("boom") }
        while (pager.state.value.error == null) delay(10)

        pager.reset()

        assertEquals("所有状态位必须回到初始态", FeedState<String>(), pager.state.value)
        assertNull(pager.state.value.error)
        scope.cancel()
    }

    @Test
    fun `reset cancels the in-flight load and its result never lands`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pager = newPager(scope)
        // 第 0 页满页 ⇒ endReached = false，第二批才能进入 loading。
        pager.load(force = true) { listOf("a", "b") }
        while (pager.state.value.items.isEmpty()) delay(10)
        // 第二批永不返回：挂起直到被 reset 取消。
        pager.load { awaitCancellation() }
        while (!pager.state.value.loading) delay(10)

        pager.reset()
        // 给被取消的任务留出执行窗口：若结果晚到写回，会重新出现 items/loading。
        repeat(50) { delay(10) }

        assertEquals("被取消的加载不得在 reset 后写回状态", FeedState<String>(), pager.state.value)
        scope.cancel()
    }

    @Test
    fun `a reset pager can load its first page again`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pager = newPager(scope)
        pager.load(force = true) { listOf("a") }
        while (pager.state.value.items.isEmpty()) delay(10)
        pager.reset()

        pager.load { page -> listOf("fresh$page") }
        while (pager.state.value.items.isEmpty()) delay(10)

        assertEquals("reset 后必须能重新从第 0 页开始", listOf("fresh0"), pager.state.value.items)
        assertEquals(0, pager.state.value.page)
        scope.cancel()
    }
}
