package io.github.amatsukiichika.iwaramanager.data

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
 * IwaraSession token 加密迁移测试（Robolectric：需要真实 SharedPreferences）。
 *
 * 覆盖三条路径：
 * 1. 存量明文 → restore 时平滑迁移成密文，且"重启"后仍能读回原 token；
 * 2. KeyStore 不可用 → 降级明文，登录态不受影响；
 * 3. 密钥丢失 → 旧密文不可读，按未登录处理（宁可重登也不回退明文）。
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric 会把 Conscrypt 注册成最高优先级的 X.509 provider；JDK 的 JarVerifier
        // 静态自检用当前最高优先级 provider 验签，在 Conscrypt 下会抛
        // DIGEST_AND_KEY_TYPE_NOT_SUPPORTED ⇒ SecurityException("Framework jar verification
        // can not be initialized")，并把这个 worker JVM 的整个 JCE 拖死（之后任何
        // KeyGenerator/Cipher 都是 NoClassDefFoundError）。本测试要用软件 AES 密钥，
        // 先摘掉 Conscrypt 让自检走 JDK 自带的 SUN provider。
        java.security.Security.getProviders()
            .filter { it.javaClass.name.startsWith("org.conscrypt") }
            .forEach { java.security.Security.removeProvider(it.name) }
        useSoftwareKey()
    }

    @After
    fun tearDown() {
        TokenCrypto.keyProvider = defaultProvider
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

        // 模拟重启：密文再读一遍，token 不变。
        IwaraSession.restore(context)
        assertEquals("legacy-refresh-token", IwaraSession.refreshToken)
        assertEquals("legacy-access-token", IwaraSession.accessTokenFlow())
    }

    @Test
    fun `keystore unavailable falls back to plaintext and login keeps working`() {
        TokenCrypto.keyProvider = object : TokenCrypto.KeyProvider {
            override fun getOrCreateKey(): SecretKey? = null
        }
        prefs.edit().putString("iwara_refresh_token", "legacy-refresh-token").commit()

        IwaraSession.restore(context)
        assertTrue("加密不可用不得破坏登录态", IwaraSession.isLoggedIn)
        assertEquals("legacy-refresh-token", IwaraSession.refreshToken)
        assertEquals("降级路径保持明文原样", "legacy-refresh-token", prefs.getString("iwara_refresh_token", null))
    }

    @Test
    fun `key loss makes stored ciphertext unreadable - treated as logged out`() {
        val key = useSoftwareKey()
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
}
