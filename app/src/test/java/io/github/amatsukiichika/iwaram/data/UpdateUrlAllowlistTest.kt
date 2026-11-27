package io.github.amatsukiichika.iwaram.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新下载地址的 https + 主机白名单规则（Low-5 回归）。
 *
 * 解析出的 apkUrl/shaUrl 与 OkHttp 每一跳重定向目标都走同一条规则：被篡改的
 * releases 响应不能把安装包和它的"期望校验和"指到任意主机。
 * 纯 JVM 逻辑（okhttp3.HttpUrl），不需要 Robolectric。
 */
class UpdateUrlAllowlistTest {

    @Test
    fun acceptsHttpsUrlsOnTheThreeGithubDownloadHosts() {
        assertEquals(
            "https://github.com/Amatsuki-Ichika/IwaraM/releases/download/v0.2.3/IwaraM-0.2.3-arm64-v8a.apk",
            allowedUpdateUrl(
                "https://github.com/Amatsuki-Ichika/IwaraM/releases/download/v0.2.3/IwaraM-0.2.3-arm64-v8a.apk"
            )
        )
        assertEquals(
            "https://objects.githubusercontent.com/github-production-release-asset/1",
            allowedUpdateUrl("  https://objects.githubusercontent.com/github-production-release-asset/1  ")
        )
        assertEquals(
            "https://release-assets.githubusercontent.com/github-production-release-asset/2",
            allowedUpdateUrl("https://release-assets.githubusercontent.com/github-production-release-asset/2")
        )
    }

    @Test
    fun rejectsPlainHttpEvenOnAnAllowlistedHost() {
        assertNull(allowedUpdateUrl("http://github.com/IwaraM-0.2.3-arm64-v8a.apk"))
        assertNull(allowedUpdateUrl("http://objects.githubusercontent.com/IwaraM-0.2.3-arm64-v8a.apk"))
    }

    @Test
    fun rejectsHttpsOnAHostOutsideTheAllowlist() {
        assertNull(allowedUpdateUrl("https://example.com/IwaraM-0.2.3-arm64-v8a.apk"))
        assertNull(allowedUpdateUrl("https://api.github.com/repos/Amatsuki-Ichika/IwaraM"))
        // 后缀相似的域名不算白名单主机。
        assertNull(allowedUpdateUrl("https://github.com.evil.example/IwaraM-0.2.3-arm64-v8a.apk"))
        assertNull(allowedUpdateUrl("https://evilgithub.com/IwaraM-0.2.3-arm64-v8a.apk"))
    }

    @Test
    fun userInfoTrickIsCheckedAgainstTheActualHost() {
        // HttpUrl 把主机解析为 evil.example，而不是 @ 前的 github.com。
        assertNull(allowedUpdateUrl("https://github.com@evil.example/IwaraM-0.2.3-arm64-v8a.apk"))
    }

    @Test
    fun rejectsMissingRelativeOrUnparseableAddresses() {
        assertNull(allowedUpdateUrl(null))
        assertNull(allowedUpdateUrl(""))
        assertNull(allowedUpdateUrl("   "))
        assertNull(allowedUpdateUrl("github.com/IwaraM-0.2.3-arm64-v8a.apk"))
        assertNull(allowedUpdateUrl("/IwaraM-0.2.3-arm64-v8a.apk"))
        assertNull(allowedUpdateUrl("ftp://github.com/IwaraM-0.2.3-arm64-v8a.apk"))
    }

    @Test
    fun redirectTargetsAreJudgedByTheSameRule() {
        // 模拟 OkHttp 解析出的 30x Location：白名单主机放行，其它主机或明文拒绝。
        val allowed = "https://release-assets.githubusercontent.com/github-production-release-asset/3".toHttpUrl()
        assertTrue(isAllowedUpdateUrl(allowed))
        val blocked = "https://evil.example/IwaraM-0.2.3-arm64-v8a.apk".toHttpUrl()
        assertFalse(isAllowedUpdateUrl(blocked))
        val downgraded = "http://github.com/IwaraM-0.2.3-arm64-v8a.apk".toHttpUrl()
        assertFalse(isAllowedUpdateUrl(downgraded))
    }
}
