package io.github.amatsukiichika.iwaramanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Query

/**
 * 订阅流契约用例（"订阅页变成所有最新的视频" 回归）。
 *
 * 根因：API 主域从 `api.iwara.tv` 换成 `apiq.iwara.tv` 之后，`GET /videos?subscribed=true` 在
 * apiq 上被静默忽略 —— 不报错、不过滤，直接返回全站最新视频。修复 = 订阅流固定走 api.iwara.tv
 * （[IwaraClient.callSubscription]）。
 *
 * 这组用例钉住两件"改坏了既不报错也不崩、只会悄悄退化"的事：
 * 1. 订阅流的域名必须是 api.iwara.tv（不能再被粘性主/备切换带回 apiq）；
 * 2. `/videos` 查询参数的声明位置 —— 调用方（`IwaraClientViewModel.loadMoreSubscribed`）
 *    是按位置传参的 `videos(page, limit, sort, rating, subscribed, user)`，参数一旦挪位，
 *    `subscribed = true` 就会被塞进别的参数，订阅流再次退化成全站。
 */
class IwaraSubscriptionContractTest {

    @Test
    fun `subscription feed is pinned to api iwara tv`() {
        assertEquals("https://api.iwara.tv/", IwaraClient.subscriptionBase)
        // 反向断言：绝不能落到主域 apiq（它忽略 subscribed=true）。
        assertFalse("订阅流不得走 apiq（会静默忽略 subscribed）", IwaraClient.subscriptionBase.contains("apiq"))
        assertTrue(IwaraClient.subscriptionBase.startsWith("https://"))
    }

    /** 取一个 Retrofit 方法上按声明顺序排列的 @Query 名（suspend 末尾的 Continuation 无注解，会被过滤掉）。 */
    private fun queryNames(methodName: String): List<String> =
        IwaraBrowseService::class.java.methods
            .first { it.name == methodName }
            .parameterAnnotations
            .map { arr -> arr.filterIsInstance<Query>().firstOrNull()?.value }
            .filterNotNull()

    @Test
    fun `videos keeps its positional query order`() {
        assertEquals(
            listOf("page", "limit", "sort", "rating", "subscribed", "user", "tags"),
            queryNames("videos")
        )
    }

    @Test
    fun `subscribed stays the 5th parameter and is nullable Boolean`() {
        val m = IwaraBrowseService::class.java.methods.first { it.name == "videos" }
        // suspend 函数编译后末尾多一个 Continuation 参数；前 7 个才是声明参数，下标 4 = subscribed。
        // 类型是**装箱** java.lang.Boolean（而不是原生 boolean）⇒ 签名里确实是 `Boolean?`：
        // 非订阅调用（视频流 / tag 检索）传 null，Retrofit 会直接省略该 query 参数。
        assertEquals("java.lang.Boolean", m.parameterTypes[4].name)
    }

    @Test
    fun `images also carries subscribed at position 5`() {
        assertEquals(
            // 末尾多一个 `user`（作者页「图片」分段按作者过滤），位置 5 仍是 subscribed。
            listOf("page", "limit", "sort", "rating", "subscribed", "user"),
            queryNames("images")
        )
    }
}
