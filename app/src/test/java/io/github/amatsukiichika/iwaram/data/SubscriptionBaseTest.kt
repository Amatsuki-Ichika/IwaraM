package io.github.amatsukiichika.iwaram.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ConnectException

/**
 * 订阅流的「兜底不换服务器」回归用例。
 *
 * 背景：
 * - `apiq.iwara.tv` 会静默忽略 `?subscribed=true`，返回全站最新视频，订阅页会显示成"视频栏的
 *   最新视频"而且不报错（把订阅流钉到 api.iwara.tv 修的就是这个）。
 * - 若网络兜底写成 `catch (…ConnectException/UnknownHostException) { block(serviceFor(BASE_PRIMARY)) }`，
 *   而 `BASE_PRIMARY` 就是 apiq，就会把"钉死"抵消掉：api.iwara.tv 首次连接偶发抖动时请求静默落到
 *   apiq，订阅页显示全站最新视频。
 *
 * 这个用例锁死契约：网络类失败只能原地重试同一个 base。
 * 判据用 service 实例相等 —— `serviceFor(base)` 按 base 缓存实例，换了 base 就一定是另一个实例，
 * 所以换到 apiq 的实现会让本用例失败。
 */
class SubscriptionBaseTest {

    @Test
    fun `网络失败后原地重试同一个 base，绝不换到 apiq`() {
        val seen = mutableListOf<IwaraBrowseService>()
        assertThrows(ConnectException::class.java) {
            runBlocking {
                IwaraClient.callSubscription {
                    seen += it
                    throw ConnectException("模拟首次连接失败")
                }
            }
        }
        assertEquals("必须重试一次（原地）", 2, seen.size)
        assertSame(
            "两次必须打在同一个 base 上（换 base 就等于把请求打到会忽略 subscribed 的 apiq）",
            seen[0], seen[1]
        )
    }

    @Test
    fun `首次成功时不重试`() {
        var calls = 0
        val result = runBlocking {
            IwaraClient.callSubscription {
                calls++
                "ok"
            }
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }
}
