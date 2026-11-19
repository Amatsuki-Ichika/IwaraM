package io.github.amatsukiichika.iwaram.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.amatsukiichika.iwaram.ui.DiagLog
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 会话令牌的静态加密（encryption at rest）。
 *
 * Iwara 的 refresh token 是 3~4 周有效的长效凭证，此前以明文存于 SharedPreferences ——
 * `allowBackup="false"` 只挡住云备份与 adb backup，挡不住 root / 同用户本地读取。
 * 这里改为 AndroidKeyStore 的 AES-256-GCM 密钥落盘前加密：
 * - 密钥不出安全硬件（TEE/StrongBox 由系统决定），应用数据被整体拷走也解不出 token；
 * - 密文格式 `v1:<base64(iv || ciphertext)>`，带版本前缀，将来换算法/密钥可平滑升级；
 * - Keystore 不可用（个别 ROM 异常等）时**显式降级**：写路径落 `plain:<明文>` 并置 degraded
 *   标记（见 [sealForStorage]），读路径只按前缀识别明文 —— 降级因此可观测、可在设置页提示，
 *   而不是静默写裸明文；安全特性仍然不允许把登录功能搞挂。
 *
 * 密钥丢失（系统重置 KeyStore）时旧密文解不开：[decrypt] 返回 null，调用方按"未登录"
 * 处理，用户重新登录即可 —— 宁可丢会话也不回退明文存储。
 */
object TokenCrypto {

    /** 密文前缀；无此前缀的存量值视为旧版明文，读取时原样透传。 */
    internal const val PREFIX = "v1:"

    /**
     * 显式明文降级前缀：加密不可用时写入 `plain:<明文>`，与旧版裸明文区分，
     * 以便设置页提示用户"本机 token 以明文保存"。该前缀为保留字，旧版裸明文不可能以它开头。
     */
    internal const val PLAIN_PREFIX = "plain:"

    private const val KEY_ALIAS = "iwara_session_token"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** 加解密密钥的获取口。抽接口是为了 JVM 单测可注入软件密钥（Robolectric 没有 AndroidKeyStore）。 */
    internal interface KeyProvider {
        /** 返回 AES 密钥；Keystore 不可用时返回 null（调用方降级明文 / 按不可读处理）。 */
        fun getOrCreateKey(): SecretKey?
    }

    @Volatile
    internal var keyProvider: KeyProvider = AndroidKeyStoreKeyProvider

    /** 写路径的落盘值：[value] 真正写进 prefs，[degraded] 表示这次落盘已降级为明文。 */
    internal data class StoredToken(val value: String, val degraded: Boolean)

    /**
     * 把 token 封装为落盘值：优先 [encrypt] 的 `v1:` 密文；Keystore 不可用时降级为
     * `plain:<明文>` 并显式标记 [StoredToken.degraded]，由调用方持久化降级状态并提示用户。
     * 绝不返回裸明文。
     */
    internal fun sealForStorage(plain: String): StoredToken {
        val ciphertext = encrypt(plain)
        return if (ciphertext != null) {
            StoredToken(value = ciphertext, degraded = false)
        } else {
            StoredToken(value = PLAIN_PREFIX + plain, degraded = true)
        }
    }

    /** 加密 [plain]；Keystore 不可用或加密失败返回 null（调用方不得写裸明文，见 [sealForStorage]）。 */
    fun encrypt(plain: String): String? {
        val key = keyProvider.getOrCreateKey() ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            require(iv.size == GCM_IV_BYTES) { "unexpected GCM IV length: ${iv.size}" }
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(iv.size + ct.size)
            iv.copyInto(out)
            ct.copyInto(out, iv.size)
            PREFIX + Base64.getEncoder().encodeToString(out)
        } catch (t: Throwable) {
            DiagLog.log("TokenCrypto", "token 加密失败: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * 解密落盘的 token 值。
     * - `v1:` 密文解密失败（密钥被系统重置 / 数据被篡改）返回 null，调用方按未登录处理，
     *   绝不回退明文；
     * - [PLAIN_PREFIX] 是加密不可用时的显式降级明文（见 [sealForStorage]），去掉前缀原样返回；
     * - 旧版明文（无任何前缀）原样返回 —— 存量用户的平滑迁移依赖这一点。
     */
    fun decrypt(stored: String): String? {
        if (stored.startsWith(PLAIN_PREFIX)) return stored.removePrefix(PLAIN_PREFIX)
        if (!stored.startsWith(PREFIX)) return stored
        val key = keyProvider.getOrCreateKey() ?: return null
        return try {
            val data = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            if (data.size <= GCM_IV_BYTES) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, data, 0, GCM_IV_BYTES))
            String(cipher.doFinal(data, GCM_IV_BYTES, data.size - GCM_IV_BYTES), Charsets.UTF_8)
        } catch (t: Throwable) {
            DiagLog.log("TokenCrypto", "token 解密失败（密钥可能已被系统重置）: ${t.javaClass.simpleName}")
            null
        }
    }

    /** AndroidKeyStore 实现：首次调用生成 AES-256-GCM 密钥，之后复用同一把。 */
    internal object AndroidKeyStoreKeyProvider : KeyProvider {
        override fun getOrCreateKey(): SecretKey? = try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                generator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generator.generateKey()
            }
        } catch (t: Throwable) {
            DiagLog.log("TokenCrypto", "AndroidKeyStore 不可用: ${t.javaClass.simpleName}")
            null
        }
    }
}
