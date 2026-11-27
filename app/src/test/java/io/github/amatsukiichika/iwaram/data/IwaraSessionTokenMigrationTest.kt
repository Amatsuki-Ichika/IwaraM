package io.github.amatsukiichika.iwaram.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * IwaraSession token 存储策略测试（Robolectric：需要真实 SharedPreferences）。
 *
 * 覆盖：
 * 1. 存量明文 → restore 时平滑迁移成密文，且"重启"后仍能读回原 token；
 * 2. KeyStore 不可用 → 写成显式 `plain:` 值并上报降级，登录态不受影响；密钥恢复后自愈；
 * 3. 密钥丢失 → 旧密文不可读，按未登录处理（宁可重登也不回退明文）；
 * 4. 旧「手动粘贴 access_token」残留被清理，IwaraSession 是唯一 token 来源；
 * 5. restoreAsync 在 IO 完成恢复，鉴权注入经有界等待拿到 token。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IwaraSessionTokenMigrationTest {

    private lateinit var context: Context
    private val defaultProvider = TokenCrypto.keyProvider

    private val prefs get() = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun useSoftwareKey(): SecretKey {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        TokenCrypto.keyProvider = object : TokenCrypto.KeyProvider {
            override fun getOrCreateKey(): SecretKey? = key
        }
        return key
    }

    private fun useUnavailableKeystore() {
        TokenCrypto.keyProvider = object : TokenCrypto.KeyProvider {
            override fun getOrCreateKey(): SecretKey? = null
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Conscrypt 由 app/build.gradle.kts 的 robolectric.conscryptMode=OFF 统一关闭
        //（data/AGENTS.md 规定的唯一出处）。这里绝不再改 JVM 级 Security provider：
        // 无恢复的 removeProvider 会把整个测试 JVM 的 JCA 行为改掉，让套件重新依赖类执行顺序。
        useSoftwareKey()
        prefs.edit().clear().commit()
        // IwaraSession 是 object：清掉上一个测试留下的恢复状态与内存 token。
        IwaraSession.resetRestoreStateForTest()
    }

    @After
    fun tearDown() {
        TokenCrypto.keyProvider = defaultProvider
        IwaraSession.resetRestoreStateForTest()
    }

    @Test
    fun `legacy plaintext tokens migrate to ciphertext on restore and survive a restart`() {
        prefs.edit()
            .putString("iwara_refresh_token", "legacy-refresh-token")
            .putString("iwara_access_token", "legacy-access-token")
            .commit()

        IwaraSession.restore(context)
        assertTrue(IwaraSession.isLoggedIn)
        assertEquals("legacy-refresh-token", IwaraSession.refreshToken)
        assertEquals("legacy-access-token", IwaraSession.accessTokenFlow())
        assertTrue("存量明文应被迁移成 v1: 密文", prefs.getString("iwara_refresh_token", null)!!.startsWith("v1:"))
        assertTrue(prefs.getString("iwara_access_token", null)!!.startsWith("v1:"))
        assertFalse("迁移成功不算降级", IwaraSession.tokenStorageDegraded.value)
        assertFalse(prefs.getBoolean("iwara_token_storage_degraded", true))

        // 模拟重启：密文再读一遍，token 不变。
        IwaraSession.restore(context)
        assertEquals("legacy-refresh-token", IwaraSession.refreshToken)
        assertEquals("legacy-access-token", IwaraSession.accessTokenFlow())
    }

    @Test
    fun `an access token without a refresh token is not restored`() {
        // 没有 refresh token 的 access token 不能独立构成会话；旧实现会把它读回内存，
        // 登出后仍能从磁盘"复活"并注入到请求里。
        prefs.edit().putString("iwara_access_token", "orphan-access-token").commit()

        IwaraSession.restore(context)

        assertFalse(IwaraSession.isLoggedIn)
        assertNull(IwaraSession.accessTokenFlow())
        assertFalse("孤立 access token 应从盘上清掉", prefs.contains("iwara_access_token"))
    }

    @Test
    fun `keystore unavailable stores explicit plaintext prefix and reports degraded`() {
        useUnavailableKeystore()
        prefs.edit().putString("iwara_refresh_token", "legacy-refresh-token").commit()

        IwaraSession.restore(context)
        assertTrue("加密不可用不得破坏登录态", IwaraSession.isLoggedIn)
        assertEquals("legacy-refresh-token", IwaraSession.refreshToken)
        assertEquals(
            "降级写入必须带显式 plain: 前缀，不能落裸明文",
            "plain:legacy-refresh-token",
            prefs.getString("iwara_refresh_token", null)
        )
        assertTrue("降级状态必须对界面可见", IwaraSession.tokenStorageDegraded.value)
        assertTrue("降级状态要持久化", prefs.getBoolean("iwara_token_storage_degraded", false))

        // 重启后仍可读回（plain: 前缀是自描述格式），降级标记保持。
        IwaraSession.restore(context)
        assertEquals("legacy-refresh-token", IwaraSession.refreshToken)
        assertTrue(IwaraSession.tokenStorageDegraded.value)
    }

    @Test
    fun `degraded plaintext token heals back to ciphertext once the keystore recovers`() {
        useUnavailableKeystore()
        prefs.edit().putString("iwara_refresh_token", "legacy-refresh-token").commit()
        IwaraSession.restore(context)
        assertTrue(prefs.getString("iwara_refresh_token", null)!!.startsWith("plain:"))

        // 系统密钥库恢复可用：下一次 restore 应把 plain: 值重新加密。
        useSoftwareKey()
        IwaraSession.restore(context)
        assertEquals("legacy-refresh-token", IwaraSession.refreshToken)
        assertTrue("自愈后应为 v1: 密文", prefs.getString("iwara_refresh_token", null)!!.startsWith("v1:"))
        assertFalse("恢复加密后降级标记要清除", IwaraSession.tokenStorageDegraded.value)
        assertFalse(prefs.getBoolean("iwara_token_storage_degraded", true))
    }

    @Test
    fun `key loss makes stored ciphertext unreadable - treated as logged out`() {
        useSoftwareKey()
        prefs.edit().putString("iwara_refresh_token", TokenCrypto.encrypt("secret-refresh")!!).commit()

        IwaraSession.restore(context)
        assertEquals("secret-refresh", IwaraSession.refreshToken)

        // 模拟 KeyStore 被系统重置：换一把密钥。
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        TokenCrypto.keyProvider = object : TokenCrypto.KeyProvider {
            override fun getOrCreateKey(): SecretKey? = other
        }
        IwaraSession.restore(context)
        assertFalse("密钥丢失后旧密文不可读，按未登录处理", IwaraSession.isLoggedIn)
        assertNull(IwaraSession.refreshToken)
    }

    @Test
    fun `legacy manually pasted token key is removed on restore`() {
        // 旧版本「手动粘贴 access_token」的落盘键：功能入口已删除，恢复时清理残留。
        prefs.edit()
            .putString("iwara_refresh_token", "legacy-refresh-token")
            .putString("access_token", "legacy-manual-token")
            .commit()

        IwaraSession.restore(context)
        assertFalse("旧的第二 token 来源必须被清理", prefs.contains("access_token"))
        assertEquals("legacy-refresh-token", IwaraSession.refreshToken)
    }

    @Test
    fun `restoreAsync completes on io and awaitAccessToken returns the restored token`() {
        prefs.edit()
            .putString("iwara_refresh_token", "legacy-refresh-token")
            .putString("iwara_access_token", "legacy-access-token")
            .commit()

        IwaraSession.restoreAsync(context)
        // 鉴权注入路径：有界等待恢复完成后拿到 token（不依赖调用线程是主线程）。
        assertEquals("legacy-access-token", IwaraSession.awaitAccessToken())
        assertEquals("legacy-refresh-token", IwaraSession.refreshToken)
        assertTrue("恢复完成后应已迁移为密文", prefs.getString("iwara_refresh_token", null)!!.startsWith("v1:"))
    }

    @Test
    fun `the test JVM runs without a conscrypt provider`() {
        // 这条不变量把"workaround 只有一个出处"钉住：Conscrypt 只应由
        // app/build.gradle.kts 的 robolectric.conscryptMode=OFF 关闭。
        // 删掉那条 Gradle 属性（或有人在测试里手动注册 Conscrypt）时这里立即变红，
        // 而不是让套件退化成依赖类执行顺序的随机失败。
        val leaked = java.security.Security.getProviders()
            .filter { it.javaClass.name.startsWith("org.conscrypt") }
            .map { it.name }
        assertTrue(
            "单元测试 JVM 注册了 Conscrypt provider $leaked；请保留 app/build.gradle.kts 的 " +
                "robolectric.conscryptMode=OFF，不要在测试里改 JVM 级 Security provider",
            leaked.isEmpty()
        )
    }
}
