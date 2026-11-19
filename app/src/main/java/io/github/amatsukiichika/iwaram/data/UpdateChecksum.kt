package io.github.amatsukiichika.iwaram.data

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * 更新包 SHA-256 校验的纯逻辑。
 *
 * 只做文本解析、十六进制规范化与摘要计算，不碰网络和文件：OkHttp 下载与缓存文件留在
 * [UpdateInstaller]，这里可以全部用普通 JVM 单测覆盖（见 UpdateChecksumTest）。
 * 校验文件是 GitHub Release 附带的 .sha256 资产，通常形如
 * `"<hex>  IwaraM-x.y.z-arm64-v8a.apk"`（sha256sum 的输出）。
 */
internal object UpdateChecksum {

    /** SHA-256 十六进制串固定 64 个字符；校验文件里的其它长度一律视为不合法。 */
    private const val HASH_LENGTH = 64

    private val HASH_PATTERN = Regex("[0-9a-fA-F]{$HASH_LENGTH}")

    private const val SHA256_ALGORITHM = "SHA-256"
    private const val BUFFER_SIZE = 64 * 1024
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * 从 .sha256 文件内容里取出期望的 SHA-256。
     *
     * 兼容 sha256sum 的三种写法：`<hex>  <file>`（文本模式）、`<hex>  *<file>`（二进制模式）
     * 和只有 `<hex>` 的一行；大小写不敏感，容忍 CRLF、BOM、首尾空白与空行。
     * 文件名不参与判断：资产名只用来配对下载地址（见 [GitHubReleaseClient]）。
     *
     * @return 64 位小写十六进制摘要；内容为空或首行不是合法摘要时返回 null。
     */
    fun parseExpectedHash(text: String): String? {
        val firstLine = text.removePrefix("\uFEFF")
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        // sha256sum 用空白分隔摘要与文件名：取第一段即可，后面的文件名/二进制标记都不影响摘要。
        return normalizeHash(firstLine.takeWhile { !it.isWhitespace() })
    }

    /**
     * 规范化 SHA-256 十六进制串：去首尾空白、校验长度与字符集、统一转小写。
     *
     * @return 64 位小写十六进制摘要；null、空白或格式不合法时返回 null。
     */
    fun normalizeHash(value: String?): String? {
        val candidate = value?.trim().orEmpty()
        return if (HASH_PATTERN.matches(candidate)) candidate.lowercase() else null
    }

    /** 大小写不敏感地比较两个摘要；任一侧不合法都按不匹配处理。 */
    fun hashesMatch(expected: String?, actual: String?): Boolean {
        val expectedHash = normalizeHash(expected) ?: return false
        val actualHash = normalizeHash(actual) ?: return false
        return expectedHash == actualHash
    }

    /** 计算 [bytes] 的 SHA-256，返回小写十六进制串。 */
    fun sha256Hex(bytes: ByteArray): String = sha256Hex(ByteArrayInputStream(bytes))

    /** 流式计算 [input] 的 SHA-256，返回小写十六进制串；不关闭调用方的流。 */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().toLowerHex()
    }

    private fun ByteArray.toLowerHex(): String {
        val hex = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            hex.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return hex.toString()
    }
}
