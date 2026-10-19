package io.github.amatsukiichika.iwaramanager.data

import io.github.amatsukiichika.iwaramanager.data.IwaraSession.classifyTokenError
import io.github.amatsukiichika.iwaramanager.data.IwaraSession.mapLoginError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * refresh token 刷新错误的分类真值表。
 *
 * `obtainAccessToken` 只把服务器明确拒绝的情况（HTTP 401/403，或响应中含 invalidLogin）判为
 * 认证失败并清除会话；服务器 5xx / 429 等瞬时故障不清除 refresh token，
 * 否则一次服务端抖动就会丢失全部登录态、必须重新输入密码。
 */
class IwaraSessionErrorTest {

    @Test
    fun `auth rejections are classified as auth errors`() {
        assertTrue(classifyTokenError(401, null))
        assertTrue(classifyTokenError(403, null))
        assertTrue(classifyTokenError(400, "invalidLogin"))
        assertTrue(classifyTokenError(400, "some prefix invalidLogin suffix"))
    }

    @Test
    fun `transient server errors are NOT auth errors`() {
        assertFalse(classifyTokenError(500, null))
        assertFalse(classifyTokenError(500, "Internal Server Error"))
        assertFalse(classifyTokenError(502, "bad gateway"))
        assertFalse(classifyTokenError(429, null))
        assertFalse(classifyTokenError(400, null))
        assertFalse(classifyTokenError(400, ""))
    }

    @Test
    fun `user-facing messages stay stable`() {
        // 401/403/invalidLogin → 登录态语义文案
        assertEquals("邮箱或密码错误", mapLoginError(401, null))
        assertEquals("邮箱或密码错误", mapLoginError(403, "invalidLogin"))
        // 瞬时错误各自保留描述文案。
        assertEquals("请求过于频繁，请稍后再试", mapLoginError(429, null))
        assertEquals("登录失败：boom", mapLoginError(500, "boom"))
        assertEquals("登录失败（HTTP 503）", mapLoginError(503, null))
    }

    @Test
    fun `isAuthError flag defaults to false so plain throws never clear session`() {
        // 携带分类的构造：auth 错误为 true
        assertTrue(IwaraSession.IwaraApiException("邮箱或密码错误", true).isAuthError)
        // 默认构造不置此标记：如 200 但解析不到 token 的"会话已过期，请重新登录"不触发 clear
        assertFalse(IwaraSession.IwaraApiException("会话已过期，请重新登录").isAuthError)
    }
}
