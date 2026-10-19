package io.github.amatsukiichika.iwaramanager.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 错误文案两轨制的映射规则测试。
 * `friendlyIwaraError` 是**保守规则**——认不出的串原样透传，所以这里同时盯两件事：
 * 该转的必须转成指定人话、不该动的必须一字不差。
 */
class IwaraErrorTextTest {

    /* ---------- HTTP 码映射 ---------- */

    @Test
    fun `http 码映射到对应人话`() {
        assertEquals("没有权限访问该内容（可能需要登录，或作者已设为私密）", friendlyHttpError(401))
        assertEquals("没有权限访问该内容（可能需要登录，或作者已设为私密）", friendlyHttpError(403))
        assertEquals("内容不存在或已被删除", friendlyHttpError(404))
        assertEquals("内容不存在或已被删除", friendlyHttpError(410))
        assertEquals("请求太频繁，请稍后再试", friendlyHttpError(429))
        assertEquals("Iwara 服务器开小差了，请稍后再试", friendlyHttpError(500))
        assertEquals("Iwara 服务器开小差了，请稍后再试", friendlyHttpError(503))
        assertEquals("请求失败，请稍后重试", friendlyHttpError(400))
        assertEquals("请求失败，请稍后重试", friendlyHttpError(418))
    }

    /* ---------- describeIwaraError 各分支产出的技术串 ---------- */

    @Test
    fun `服务器错误前缀提取 HTTP 码并转人话`() {
        assertEquals(
            "内容不存在或已被删除",
            friendlyIwaraError("服务器错误（HTTP 404）：errors.notFound")
        )
        assertEquals(
            "没有权限访问该内容（可能需要登录，或作者已设为私密）",
            friendlyIwaraError("服务器错误（HTTP 403）")
        )
        assertEquals(
            "Iwara 服务器开小差了，请稍后再试",
            friendlyIwaraError("服务器错误（HTTP 502）：Bad Gateway")
        )
    }

    @Test
    fun `网络异常前缀转人话`() {
        assertEquals("网络连接异常，请检查网络后重试", friendlyIwaraError("网络异常：SSLException"))
        assertEquals("网络连接异常，请检查网络后重试", friendlyIwaraError("网络异常：timeout"))
        // 裸「网络异常」（不带冒号，describeIwaraError 不会产出）按人话透传，不被 ASCII 兜底误伤
        assertEquals("网络异常", friendlyIwaraError("网络异常"))
    }

    @Test
    fun `无法连接已是人话原样透传`() {
        val s = "无法连接 Iwara 服务器，请检查网络或代理"
        assertEquals(s, friendlyIwaraError(s))
    }

    @Test
    fun `登录失败前缀统一成人话`() {
        assertEquals("登录失败，请稍后再试", friendlyIwaraError("登录失败（HTTP 500）"))
        assertEquals("登录失败，请稍后再试", friendlyIwaraError("登录失败：server exploded"))
        assertEquals("登录失败，请稍后再试", friendlyIwaraError("登录失败：服务器未返回令牌"))
    }

    @Test
    fun `获取用户信息失败前缀转人话`() {
        assertEquals("获取账号信息失败，请重新登录", friendlyIwaraError("获取用户信息失败（HTTP 401）"))
    }

    /* ---------- 已是人话的串一字不动 ---------- */

    @Test
    fun `已人话的中文串原样透传`() {
        assertEquals("邮箱或密码错误", friendlyIwaraError("邮箱或密码错误"))
        assertEquals("请求过于频繁，请稍后再试", friendlyIwaraError("请求过于频繁，请稍后再试"))
        assertEquals("登录已过期，请重新登录", friendlyIwaraError("登录已过期，请重新登录"))
        assertEquals("会话已过期，请重新登录", friendlyIwaraError("会话已过期，请重新登录"))
        assertEquals("请先登录后再点赞", friendlyIwaraError("请先登录后再点赞"))
        assertEquals("请求失败", friendlyIwaraError("请求失败"))
        // 含英文但主体是人话的串（如「已加入下载队列」族）也不能被 ASCII 兜底误伤
        assertEquals("订阅流需要登录后使用", friendlyIwaraError("订阅流需要登录后使用"))
    }

    /* ---------- 兜底 ---------- */

    @Test
    fun `纯 ASCII 技术串转通用兜底`() {
        assertEquals("操作失败，请稍后重试", friendlyIwaraError("java.lang.NullPointerException"))
        assertEquals("操作失败，请稍后重试", friendlyIwaraError("Unable to invoke no-args constructor"))
    }

    @Test
    fun `空串给兜底`() {
        assertEquals("操作失败，请稍后重试", friendlyIwaraError(""))
    }
}
