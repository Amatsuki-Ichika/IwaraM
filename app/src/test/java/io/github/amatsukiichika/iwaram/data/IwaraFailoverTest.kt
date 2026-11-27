package io.github.amatsukiichika.iwaram.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 主/备域回退的异常集合（Medium-4 回归）。
 *
 * 主域被防火墙黑洞丢包时不会立刻拒绝连接，而是在 connectTimeout 后抛 SocketTimeoutException；
 * 只认 DNS/连接拒绝会让整个会话滞留在一个不可达的主域上。反过来，其他 IO 异常与取消
 * 不能触发重试 —— 那会把可能已到达服务器的请求再发一次。
 */
class IwaraFailoverTest {

    @Test
    fun dnsConnectionRefusedAndConnectTimeoutAreRetryable() {
        assertTrue(isFailoverRetryable(UnknownHostException("apiq.iwara.tv")))
        assertTrue(isFailoverRetryable(ConnectException("Connection refused")))
        assertTrue(isFailoverRetryable(SocketTimeoutException("connect timed out")))
    }

    @Test
    fun otherNetworkFailuresAndCancellationAreNotRetried() {
        assertFalse(isFailoverRetryable(IOException("unexpected end of stream")))
        assertFalse(isFailoverRetryable(SocketException("Connection reset")))
        assertFalse(isFailoverRetryable(CancellationException("cancelled")))
        assertFalse(isFailoverRetryable(IllegalStateException("boom")))
    }

    @Test
    fun subscriptionRetriesConnectTimeoutOnceOnTheSameBase() {
        val seen = mutableListOf<IwaraBrowseService>()
        assertThrows(SocketTimeoutException::class.java) {
            runBlocking {
                IwaraClient.callSubscription {
                    seen += it
                    throw SocketTimeoutException("connect timed out")
                }
            }
        }
        assertEquals("连接超时也必须原地重试一次", 2, seen.size)
        assertSame("重试必须打在同一个 base 上", seen[0], seen[1])
    }
}
