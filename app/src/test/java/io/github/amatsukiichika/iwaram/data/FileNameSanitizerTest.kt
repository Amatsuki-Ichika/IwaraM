package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件名清洗的回归用例。
 *
 * 旧实现把整串截到 120 个码点：长标题会连 “.mp4” 一起切掉，落盘文件没有扩展名，
 * 扫描器认不出视频类型；UTF-8 字节数也没有上限（中文/emoji 可达码点数的三四倍）。
 * 现在两条上限（码点 + 字节）都保留扩展名，并且不在字符中间切。
 */
class FileNameSanitizerTest {

    private fun utf8Bytes(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    @Test
    fun `replaces illegal filesystem characters`() {
        assertEquals("a＿b＿c＿d＿e＿f＿g＿h＿i＿", FileNameSanitizer.sanitize("a\\b/c:d*e?f\"g<h>i|"))
    }

    @Test
    fun `strips control characters`() {
        assertEquals("ab", FileNameSanitizer.sanitize("a\u0000\u0007b"))
    }

    @Test
    fun `trims leading and trailing dots and whitespace`() {
        assertEquals("video", FileNameSanitizer.sanitize("  .video. "))
    }

    @Test
    fun `caps length at 120 code points`() {
        assertEquals(120, FileNameSanitizer.sanitize("x".repeat(300)).length)
    }

    @Test
    fun `truncation keeps an ascii extension`() {
        val out = FileNameSanitizer.sanitize("x".repeat(300) + ".mp4")
        assertEquals(120, out.length)
        assertTrue("扩展名必须留在末尾", out.endsWith(".mp4"))
    }

    @Test
    fun `long chinese titles keep the extension inside the byte budget`() {
        val out = FileNameSanitizer.sanitize("中".repeat(300) + ".mp4")
        assertTrue("扩展名必须留在末尾", out.endsWith(".mp4"))
        assertTrue("UTF-8 字节数不得超过上限", utf8Bytes(out) <= FileNameSanitizer.MAX_BYTES)
        // 余量 196 字节 ⇒ 65 个汉字（195 字节）+ “.mp4”。
        assertEquals(65 + ".mp4".length, out.codePointCount(0, out.length))
    }

    @Test
    fun `long titles without an extension are capped by bytes too`() {
        val out = FileNameSanitizer.sanitize("中".repeat(300))
        assertEquals("66 个汉字 = 198 字节，是 200 字节内最长的前缀", 66, out.codePointCount(0, out.length))
        assertTrue(utf8Bytes(out) <= FileNameSanitizer.MAX_BYTES)
    }

    @Test
    fun `a numeric tail is not mistaken for an extension`() {
        assertEquals("part 1.5", FileNameSanitizer.sanitize("part 1.5"))
    }

    /**
     * 截断按 **Unicode 码点**而非 UTF-16 码元。
     * 一颗 emoji 占两个码元（代理对），按码元切可能把它劈成半个字符 ⇒ 非法代理项进了文件名；
     * 同时 4 字节/个的 emoji 也受字节上限约束（200 / 4 = 50 个）。
     */
    @Test
    fun `truncation never splits a surrogate pair`() {
        val emoji = "\uD83D\uDE00" // 代理对：1 个码点 / 2 个码元
        val name = emoji.repeat(200)
        val out = FileNameSanitizer.sanitize(name)

        assertEquals("必须截到 50 个码点（200 UTF-8 字节）", 50, out.codePointCount(0, out.length))
        assertEquals("50 个码点 = 100 个码元", 100, out.length)
        assertEquals(name.substring(0, 100), out)
        assertEquals(FileNameSanitizer.MAX_BYTES, utf8Bytes(out))
        assertTrue(
            "结果里不允许出现孤立代理项（首/尾都是完整字符）",
            !Character.isHighSurrogate(out.last()) && !Character.isLowSurrogate(out.first())
        )
    }

    @Test
    fun `blank result falls back to video`() {
        assertEquals("video", FileNameSanitizer.sanitize(" . "))
        assertEquals("video", FileNameSanitizer.sanitize(""))
    }

    @Test
    fun `compose keeps the fixed prefix and suffix intact`() {
        val out = FileNameSanitizer.compose(
            prefix = "[abc123] ",
            title = "长".repeat(300),
            suffix = " (Source).mp4"
        )
        assertTrue("身份标记不能被截断", out.startsWith("[abc123] "))
        assertTrue("档位与扩展名不能被截断", out.endsWith(" (Source).mp4"))
        assertTrue(utf8Bytes(out) <= FileNameSanitizer.MAX_BYTES)
    }

    @Test
    fun `compose cleans the title without touching the suffix`() {
        assertEquals(
            "[abc123] a＿b (720p).mp4",
            FileNameSanitizer.compose("[abc123] ", "a/b", " (720p).mp4")
        )
    }

    @Test
    fun `sanitize is idempotent on a composed download name`() {
        val composed = FileNameSanitizer.compose("[abc123] ", "长".repeat(300), " (Source).mp4")
        assertEquals(
            "落盘前 Service 还会 sanitize 一次，不能改变文件名",
            composed,
            FileNameSanitizer.sanitize(composed)
        )
    }

    @Test
    fun `compose falls back when nothing usable remains`() {
        assertEquals("video", FileNameSanitizer.compose("", "", ""))
    }
}
