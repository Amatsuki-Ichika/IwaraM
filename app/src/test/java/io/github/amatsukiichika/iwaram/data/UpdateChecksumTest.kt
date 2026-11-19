package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * [UpdateChecksum] 的纯 JVM 单测：只喂字符串/字节，不联网、不碰文件。
 *
 * 覆盖 GitHub Release 附带的 .sha256 资产的真实写法（sha256sum 文本/二进制两种格式、只有
 * 摘要的行、CRLF）以及所有会被判为不合法的输入。摘要常量取自 SHA-256 的标准测试向量，
 * 不依赖被测代码自己的输出。
 */
class UpdateChecksumTest {

    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `parseExpectedHash reads the standard sha256sum line`() {
        assertEquals(abcHash, UpdateChecksum.parseExpectedHash("$abcHash  IwaraM-0.2.6-arm64-v8a.apk"))
        // 多行文件只看第一个非空行（sha256sum 一次只输出一行）。
        assertEquals(
            abcHash,
            UpdateChecksum.parseExpectedHash("$abcHash  IwaraM-0.2.6-arm64-v8a.apk\n$emptyHash  other.apk")
        )
    }

    @Test
    fun `parseExpectedHash tolerates extra spaces tabs and surrounding whitespace`() {
        assertEquals(abcHash, UpdateChecksum.parseExpectedHash("  $abcHash \t\t IwaraM-0.2.6-arm64-v8a.apk  "))
        assertEquals(abcHash, UpdateChecksum.parseExpectedHash("$abcHash\tIwaraM-0.2.6-arm64-v8a.apk"))
    }

    @Test
    fun `parseExpectedHash accepts the binary marker form`() {
        assertEquals(abcHash, UpdateChecksum.parseExpectedHash("$abcHash *IwaraM-0.2.6-arm64-v8a.apk"))
    }

    @Test
    fun `parseExpectedHash accepts a hash-only file`() {
        assertEquals(abcHash, UpdateChecksum.parseExpectedHash(abcHash))
        assertEquals(abcHash, UpdateChecksum.parseExpectedHash("$abcHash\n"))
    }

    @Test
    fun `parseExpectedHash normalizes uppercase hex`() {
        val uppercase = abcHash.uppercase()
        assertEquals(abcHash, UpdateChecksum.parseExpectedHash("$uppercase  IwaraM-0.2.6-arm64-v8a.apk"))
        assertEquals(abcHash, UpdateChecksum.parseExpectedHash(uppercase))
    }

    @Test
    fun `parseExpectedHash tolerates crlf blank lines and a byte order mark`() {
        val text = "\uFEFF\r\n\r\n  " + abcHash.uppercase() + "  IwaraM-0.2.6-arm64-v8a.apk\r\n"
        assertEquals(abcHash, UpdateChecksum.parseExpectedHash(text))
    }

    @Test
    fun `parseExpectedHash ignores the file name in the checksum line`() {
        // 文件名只是 sha256sum 的展示信息：GitHub 资产按同名配对，校验只认摘要。
        assertEquals(abcHash, UpdateChecksum.parseExpectedHash("$abcHash  other.apk"))
        assertEquals(abcHash, UpdateChecksum.parseExpectedHash("$abcHash *"))
    }

    @Test
    fun `parseExpectedHash rejects empty garbage and malformed digests`() {
        assertNull(UpdateChecksum.parseExpectedHash(""))
        assertNull(UpdateChecksum.parseExpectedHash("   \r\n\t\r\n"))
        assertNull(UpdateChecksum.parseExpectedHash("not a hash"))
        assertNull(UpdateChecksum.parseExpectedHash(abcHash.dropLast(1)))
        assertNull(UpdateChecksum.parseExpectedHash(abcHash + "0"))
        assertNull(UpdateChecksum.parseExpectedHash(abcHash.dropLast(1) + "g"))
    }

    @Test
    fun `normalizeHash lowercases and trims but rejects malformed values`() {
        assertEquals(abcHash, UpdateChecksum.normalizeHash(abcHash.uppercase()))
        assertEquals(abcHash, UpdateChecksum.normalizeHash("  $abcHash\t"))
        assertNull(UpdateChecksum.normalizeHash(null))
        assertNull(UpdateChecksum.normalizeHash(""))
        assertNull(UpdateChecksum.normalizeHash(abcHash.dropLast(1)))
        assertNull(UpdateChecksum.normalizeHash(abcHash.dropLast(1) + "g"))
        assertNull(UpdateChecksum.normalizeHash(" $abcHash extra"))
    }

    @Test
    fun `hashesMatch compares case insensitively and rejects malformed sides`() {
        assertTrue(UpdateChecksum.hashesMatch(abcHash, abcHash.uppercase()))
        assertTrue(UpdateChecksum.hashesMatch(abcHash.uppercase(), abcHash))
        assertFalse(UpdateChecksum.hashesMatch(abcHash, emptyHash))
        assertFalse(UpdateChecksum.hashesMatch(null, abcHash))
        assertFalse(UpdateChecksum.hashesMatch(abcHash, null))
        assertFalse(UpdateChecksum.hashesMatch("not-a-hash", "not-a-hash"))
    }

    @Test
    fun `sha256Hex matches the published test vectors`() {
        assertEquals(emptyHash, UpdateChecksum.sha256Hex(ByteArray(0)))
        assertEquals(abcHash, UpdateChecksum.sha256Hex("abc".toByteArray(Charsets.UTF_8)))
        assertEquals(abcHash, UpdateChecksum.sha256Hex("abc".byteInputStream()))
    }

    @Test
    fun `sha256Hex streams across buffer boundaries`() {
        val bytes = ByteArray(3 * 64 * 1024 + 7) { index -> (index * 31 + (index ushr 8)).toByte() }
        val expected = referenceSha256(bytes)
        assertEquals(expected, UpdateChecksum.sha256Hex(bytes))
        assertEquals(expected, UpdateChecksum.sha256Hex(bytes.inputStream()))
    }

    /** 独立算一份参考摘要，避免用被测的十六进制格式化逻辑给自己出期望值。 */
    private fun referenceSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (byte in digest) {
                append(HEX_DIGITS[byte.toInt() shr 4 and 0x0F])
                append(HEX_DIGITS[byte.toInt() and 0x0F])
            }
        }
    }

    private companion object {
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
