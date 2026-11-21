package io.github.amatsukiichika.iwaram.data

import io.github.amatsukiichika.iwaram.DownloadConcurrency
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载并发闸门（[DownloadConcurrency]）的契约测试。
 *
 * 时间与等待都注入成假的，测试不 sleep、不依赖真实线程调度：
 * 用 [CompletableDeferred] 让"等待"停在确定的位置，用可推进的假时钟验证退避窗口。
 */
class DownloadConcurrencyTest {

    @Test
    fun `two downloads can hold both slots without waiting`() = runBlocking {
        val concurrency = DownloadConcurrency(2, clock = { 0L }, pause = { error("两个额度都空，不该等待") })
        concurrency.acquire()
        concurrency.acquire()
        concurrency.release()
        concurrency.release()
    }

    @Test
    fun `release returns the slot to the next download`() = runBlocking {
        val concurrency = DownloadConcurrency(2, clock = { 0L }, pause = { error("归还后已有空位，不该等待") })
        concurrency.acquire()
        concurrency.acquire()
        concurrency.release()
        // 若 release 没把额度还回来，这里会进 pause 直接失败。
        concurrency.acquire()
        concurrency.release()
        concurrency.release()
    }

    @Test
    fun `third download waits until a slot frees`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val waits = mutableListOf<Long>()
        val concurrency = DownloadConcurrency(2, clock = { 0L }, pause = { waitMs ->
            waits += waitMs
            gate.await()
        })
        concurrency.acquire()
        concurrency.acquire()
        var thirdAcquired = false
        val third = launch {
            concurrency.acquire()
            thirdAcquired = true
        }
        yield()
        assertFalse("两个额度都被占着时，第三个下载必须等", thirdAcquired)
        assertTrue("等待必须走 pause 而不是忙等", waits.isNotEmpty())
        assertEquals(250L, waits.first())
        concurrency.release()
        gate.complete(Unit)
        third.join()
        assertTrue("空出一个额度后第三个下载应当拿到", thirdAcquired)
    }

    @Test
    fun `rate limit waits out the backoff window before taking a slot`() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val concurrency = DownloadConcurrency(2, clock = { now }, pause = { waitMs ->
            waits += waitMs
            now += waitMs
        })
        concurrency.onRateLimited(3_000L)
        concurrency.acquire()
        // 即便有空额度，退避窗口没走完也不许开新下载。
        assertEquals(listOf(3_000L), waits)
        assertTrue(now >= 4_000L)
    }

    @Test
    fun `a second rate limit never shortens the backoff`() = runBlocking {
        var now = 0L
        val waits = mutableListOf<Long>()
        val concurrency = DownloadConcurrency(2, clock = { now }, pause = { waitMs ->
            waits += waitMs
            now += waitMs
        })
        concurrency.onRateLimited(5_000L)
        now = 1_000L
        concurrency.onRateLimited(1_000L)
        concurrency.acquire()
        assertEquals(listOf(4_000L), waits)
    }

    @Test
    fun `rate limit keeps concurrency at one until a success`() = runBlocking {
        var now = 5_000L
        val gate = CompletableDeferred<Unit>()
        val waits = mutableListOf<Long>()
        val concurrency = DownloadConcurrency(2, clock = { now }, pause = { waitMs ->
            waits += waitMs
            gate.await()
        })
        concurrency.onRateLimited(1_000L)
        now = 6_000L
        concurrency.acquire()
        var secondAcquired = false
        val second = launch {
            concurrency.acquire()
            secondAcquired = true
        }
        yield()
        assertFalse("降级后只剩一个并发额度，第二个下载必须等", secondAcquired)
        concurrency.release()
        gate.complete(Unit)
        second.join()
        assertTrue(secondAcquired)
    }

    @Test
    fun `a success restores one slot after a rate limit`() = runBlocking {
        val concurrency = DownloadConcurrency(2, clock = { 0L }, pause = { error("成功后应恢复两个额度") })
        concurrency.onRateLimited(3_000L)
        concurrency.onSucceeded()
        concurrency.acquire()
        concurrency.acquire()
        concurrency.release()
        concurrency.release()
    }
}
