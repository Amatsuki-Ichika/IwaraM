package io.github.amatsukiichika.iwaram.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 手动 access token 的启动路径（Robolectric：需要真实 SharedPreferences + 数据库）。
 *
 * 落盘时 token 是 `v1:` 密文，启动时必须**解密后再**交给 [IwaraApi]。曾经这里直接读原始
 * prefs 值，于是密文被当成 Bearer 发出去 —— 元数据与 R18 鉴权全部 401，而 UI 没有任何入口
 * 能触发到这条路径，属于"哪天恢复手动 token 入口就立刻炸"的潜在回归。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualAccessTokenInitTest {

    private lateinit var context: Context
    private val defaultProvider = TokenCrypto.keyProvider

    private val prefs get() = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // 同 IwaraSessionTokenMigrationTest：先摘掉 Robolectric 注入的 Conscrypt，
        // 否则 JDK 的 jar 验签自检会把本 worker 的 JCE 拖死。
        java.security.Security.getProviders()
            .filter { it.javaClass.name.startsWith("org.conscrypt") }
            .forEach { java.security.Security.removeProvider(it.name) }
        val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        TokenCrypto.keyProvider = object : TokenCrypto.KeyProvider {
            override fun getOrCreateKey(): SecretKey? = key
        }
        IwaraApi.accessToken = null
    }

    @After
    fun tearDown() {
        TokenCrypto.keyProvider = defaultProvider
        IwaraApi.accessToken = null
    }

    private fun openRepository() {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            IwaraRepository(context, db)
        } finally {
            db.close()
        }
    }

    @Test
    fun `stored ciphertext is decrypted before it becomes the bearer token`() {
        val ciphertext = TokenCrypto.encrypt("manual-token")!!
        prefs.edit().putString("access_token", ciphertext).commit()

        openRepository()

        assertEquals("manual-token", IwaraApi.accessToken)
    }

    @Test
    fun `legacy plaintext keeps working`() {
        prefs.edit().putString("access_token", "legacy-manual-token").commit()

        openRepository()

        assertEquals("legacy-manual-token", IwaraApi.accessToken)
    }

    @Test
    fun `no manual token leaves the restored session token untouched`() {
        IwaraApi.accessToken = "session-token"

        openRepository()

        assertEquals("session-token", IwaraApi.accessToken)
    }
}
