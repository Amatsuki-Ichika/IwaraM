package io.github.amatsukiichika.iwaram.data

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TokenCrypto 算法层单测（纯 JVM，注入软件 AES 密钥）：
 * Robolectric 没有 AndroidKeyStore，密钥获取抽成 [TokenCrypto.KeyProvider] 正是为了这里。
 */
class TokenCryptoTest {

    private val defaultProvider = TokenCrypto.keyProvider

    /** 注入一把软件密钥；返回它以便测试"密钥丢失"场景。 */
    private fun useSoftwareKey(): SecretKey {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        TokenCrypto.keyProvider = object : TokenCrypto.KeyProvider {
            override fun getOrCreateKey(): SecretKey? = key
        }
        return key
    }

    @After
    fun tearDown() {
        TokenCrypto.keyProvider = defaultProvider
    }

    @Test
    fun `roundtrip - ciphertext is versioned and decrypts to the original`() {
        useSoftwareKey()
        val plain = "eyJhbGciOiJIUzI1NiJ9.payload.签名部分"
        val stored = TokenCrypto.encrypt(plain)
        assertTrue("密文必须带 v1: 版本前缀", stored!!.startsWith("v1:"))
        assertEquals(plain, TokenCrypto.decrypt(stored))
    }

    @Test
    fun `same plaintext encrypts to different ciphertexts - random IV`() {
        useSoftwareKey()
        val a = TokenCrypto.encrypt("same-token")!!
        val b = TokenCrypto.encrypt("same-token")!!
        assertNotEquals("每次加密必须用新 IV", a, b)
        assertEquals("same-token", TokenCrypto.decrypt(a))
        assertEquals("same-token", TokenCrypto.decrypt(b))
    }

    @Test
    fun `legacy plaintext passthrough - stored value without prefix reads back as-is`() {
        useSoftwareKey()
        assertEquals("eyJlegacy.jwt.token", TokenCrypto.decrypt("eyJlegacy.jwt.token"))
    }

    @Test
    fun `tampered ciphertext decrypts to null - GCM tag check`() {
        useSoftwareKey()
        val stored = TokenCrypto.encrypt("secret-token")!!
        val data = java.util.Base64.getDecoder().decode(stored.removePrefix("v1:"))
        data[data.size - 1] = (data[data.size - 1].toInt() xor 0x01).toByte()
        val tampered = "v1:" + java.util.Base64.getEncoder().encodeToString(data)
        assertNull("被篡改的密文必须解密失败", TokenCrypto.decrypt(tampered))
    }

    @Test
    fun `key loss - ciphertext becomes unreadable instead of failing hard`() {
        useSoftwareKey()
        val stored = TokenCrypto.encrypt("secret-token")!!
        // 换一把不同的密钥，模拟 KeyStore 被系统重置。
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        TokenCrypto.keyProvider = object : TokenCrypto.KeyProvider {
            override fun getOrCreateKey(): SecretKey? = other
        }
        assertNull(TokenCrypto.decrypt(stored))
    }

    @Test
    fun `keystore unavailable - encrypt degrades to null, legacy plaintext still readable`() {
        TokenCrypto.keyProvider = object : TokenCrypto.KeyProvider {
            override fun getOrCreateKey(): SecretKey? = null
        }
        assertNull("不可加密时应返回 null 让调用方降级明文", TokenCrypto.encrypt("secret"))
        assertNull("无密钥时密文不可读", TokenCrypto.decrypt("v1:" + java.util.Base64.getEncoder().encodeToString(ByteArray(32))))
        assertEquals("旧明文原样透传", "plain.jwt", TokenCrypto.decrypt("plain.jwt"))
    }

    @Test
    fun `malformed short ciphertext returns null`() {
        useSoftwareKey()
        assertNull(TokenCrypto.decrypt("v1:AAAA"))
    }
}
