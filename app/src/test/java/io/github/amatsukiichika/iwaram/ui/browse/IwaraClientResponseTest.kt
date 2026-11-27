package io.github.amatsukiichika.iwaram.ui.browse

import io.github.amatsukiichika.iwaram.data.IwaraServerException
import io.github.amatsukiichika.iwaram.data.describeIwaraError
import io.github.amatsukiichika.iwaram.data.ensureIwaraSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class IwaraClientResponseTest {

    private fun rawResponse(code: Int): okhttp3.Response =
        okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://apiq.iwara.tv/test").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .build()

    @Test
    fun `204 no-content counts as success`() {
        // DELETE like 返 204；若按非空 ResponseBody 声明，会在 suspend 桥接层抛
        // KotlinNullPointerException，因此必须判成功且不抛。
        val resp: Response<ResponseBody> = Response.success(null, rawResponse(204))
        resp.ensureIwaraSuccess() // 不抛即通过
    }

    @Test
    fun `201 created counts as success`() {
        val resp: Response<ResponseBody> = Response.success(
            ResponseBody.create(null, "{}"), rawResponse(201)
        )
        resp.ensureIwaraSuccess()
    }

    @Test
    fun `non-2xx throws IwaraServerException with server message`() {
        val resp = Response.error<ResponseBody>(
            403,
            ResponseBody.create(null, """{"message":"forbidden by cloud"}""")
        )
        try {
            resp.ensureIwaraSuccess()
            throw AssertionError("expected IwaraServerException")
        } catch (e: IwaraServerException) {
            assertTrue(e.message!!.contains("HTTP 403"))
            assertTrue(e.message!!.contains("forbidden by cloud"))
        }
    }

    @Test
    fun `describeIwaraError maps io and unknown`() {
        assertTrue(describeIwaraError(IOException("boom")).startsWith("网络异常"))
        assertEquals("x", describeIwaraError(IllegalStateException("x")))
    }
}

class FeedPagerTest {

    /**
     * 测试用 scope 固定 Unconfined：load() 同步执行到首个挂起点，
     * 立即返回的 fake fetch 会在 load() 返回前写完状态，断言无需轮询或 delay 赌调度
     *（app/src/test/AGENTS.md：不得用真实时钟/睡眠做断言）。
     */
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun `sequential pages append and detect end`() {
        val scope = newScope()
        val pager = FeedPager<Int>(scope, 2, { it.toString() }) { "err" }
        pager.load { page -> List(2) { page * 2 + it } } // page0: [0,1]
        assertEquals(listOf(0, 1), pager.state.value.items)
        pager.load { page -> List(2) { page * 2 + it } } // page1: [2,3]
        assertEquals(listOf(0, 1, 2, 3), pager.state.value.items)
        pager.load { _ -> listOf(9) } // page2: 只有 1 条 → endReached
        assertEquals(listOf(0, 1, 2, 3, 9), pager.state.value.items)
        assertTrue(pager.state.value.endReached)
        scope.cancel()
    }

    /**
     * iwara 是偏移式分页，翻页期间列表在变，页与页之间会重叠，同一支视频会在
     * 第 N 页与第 N+1 页各出现一次；去重能避免列表里同一支视频出现两次。
     */
    @Test
    fun `cross page duplicates are dropped while page still advances`() {
        val scope = newScope()
        val pager = FeedPager<Int>(scope, 2, { it.toString() }) { "err" }
        pager.load { _ -> listOf(0, 1) }          // page0
        pager.load { _ -> listOf(1, 2) }          // page1 与上一页重叠 1 条
        // 重复的 1 被丢掉、新增的 2 补在后面、顺序不变。
        assertEquals(listOf(0, 1, 2), pager.state.value.items)
        // page 仍记"服务器第几页"，翻页语义不受去重影响。
        assertEquals(1, pager.state.value.page)
        // 满页但只新增 1 条 ⇒ 不算到底（后面还有新内容）。
        assertFalse(pager.state.value.endReached)
        scope.cancel()
    }

    /**
     * 整页都是"已见过的"条目时必须判定到底，
     * 否则列表会对这种空页持续请求（表现为滑到底一直转圈、什么都不出）。
     */
    @Test
    fun `a fully duplicated page ends paging`() {
        val scope = newScope()
        val pager = FeedPager<Int>(scope, 2, { it.toString() }) { "err" }
        pager.load { _ -> listOf(0, 1) }
        pager.load { _ -> listOf(0, 1) }          // 满页，但一条新的都没有
        assertEquals(listOf(0, 1), pager.state.value.items)
        assertTrue(pager.state.value.endReached)
        // 到底之后非强制加载被忽略 ⇒ 不再发空请求（load 同步返回，无需等窗口）。
        pager.load { _ -> listOf(7) }
        assertEquals(listOf(0, 1), pager.state.value.items)
        scope.cancel()
    }

    @Test
    fun `appendDistinct keeps order and reports how many were really new`() {
        val identity = { n: Int -> n.toString() }
        assertEquals(listOf(1, 2, 3) to 1, appendDistinct(listOf(1, 2), listOf(2, 3), identity))
        assertEquals(listOf(1, 2) to 0, appendDistinct(listOf(1, 2), listOf(1, 2), identity))
        assertEquals(emptyList<Int>() to 0, appendDistinct(emptyList(), emptyList(), identity))
        // 同一页内部重复也要去掉（服务端偶发同页重复）。
        assertEquals(listOf(5) to 1, appendDistinct(emptyList(), listOf(5, 5), identity))
    }

    @Test
    fun `force reload cancels in-flight load and restarts from page zero`() {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val scope = newScope()
        val pager = FeedPager<Int>(scope, 2, { it.toString() }) { "err" }
        // 第一次加载挂在 gate 上，模拟慢网络（Unconfined：load 返回时已进入 fetch）。
        pager.load(force = true) { page -> calls++; gate.await(); listOf(page) }
        assertEquals(1, calls)
        // 强制刷新：取消第一个任务，从第 0 页重拉。
        pager.load(force = true) { page -> calls++; listOf(100 + page) }
        assertEquals(listOf(100), pager.state.value.items)
        assertFalse(pager.state.value.loading)
        gate.complete(Unit) // 被取消任务的恢复点；其结果必须被代数守卫丢弃
        assertEquals(listOf(100), pager.state.value.items)
        scope.cancel()
    }

    @Test
    fun `error surfaces through describeError and force clears it`() {
        val scope = newScope()
        val pager = FeedPager<Int>(scope, 2, { it.toString() }) { "boom" }
        pager.load { _ -> throw IOException("offline") }
        assertEquals("boom", pager.state.value.error)
        pager.load(force = true) { page -> listOf(page) }
        assertEquals(listOf(0), pager.state.value.items)
        assertNull(pager.state.value.error)
        scope.cancel()
    }

    @Test
    fun `non-force load is ignored while loading and after end reached`() {
        val scope = newScope()
        val pager = FeedPager<Int>(scope, 2, { it.toString() }) { "err" }
        pager.load { page -> listOf(page) } // 1 条 < pageSize 2 → endReached
        assertTrue(pager.state.value.endReached)
        pager.load { page -> listOf(99 + page) } // endReached 时忽略
        assertEquals(listOf(0), pager.state.value.items)
        scope.cancel()
    }
}
