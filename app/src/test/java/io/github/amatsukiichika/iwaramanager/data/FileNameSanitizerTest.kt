package io.github.amatsukiichika.iwaramanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameSanitizerTest {

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
    fun `caps length at 120`() {
        val long = "x".repeat(300)
        assertEquals(120, FileNameSanitizer.sanitize(long).length)
    }

    /**
     * 截断按 **Unicode 码点**而非 UTF-16 码元。
     * 一颗 emoji 占两个码元（代理对），按码元切可能把它劈成半个字符 ⇒ 非法代理项进了文件名。
     */
    @Test
    fun `truncation never splits a surrogate pair`() {
        val emoji = "\uD83D\uDE00" // 代理对：1 个码点 / 2 个码元
        val name = emoji.repeat(200)
        val out = FileNameSanitizer.sanitize(name)

        assertEquals("必须截到 120 个码点", 120, out.codePointCount(0, out.length))
        assertEquals("120 个码点 = 240 个码元", 240, out.length)
        assertEquals(name.substring(0, 240), out)
        assertTrue(
            "结果里不允许出现孤立代理项（首/尾都是完整字符）",
            !Character.isHighSurrogate(out.last()) && !Character.isLowSurrogate(out.first())
        )
    }

    @Test
    fun `blank result falls back to video`() {
        assertEquals("video", FileNameSanitizer.sanitize(" . "))
    }
}
