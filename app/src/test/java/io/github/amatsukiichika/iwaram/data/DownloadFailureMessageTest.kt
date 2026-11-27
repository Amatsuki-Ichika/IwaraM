package io.github.amatsukiichika.iwaram.data

import io.github.amatsukiichika.iwaram.i18n.AppLanguage
import io.github.amatsukiichika.iwaram.i18n.LanguageMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 下载失败文案随语言切换重新取词（Low-18）。
 *
 * 失败原因落盘的是机器可读 code，不再是 tr() 结果；[downloadFailureText] 必须：
 * - 已知 code 按**当前语言**出文案（中英各一条）；
 * - 旧记录（code = null）与未知 code 回退到当初落盘的那句，升级后旧数据仍可读；
 * - 带自动重试次数的 code 在两种语言里都保留次数。
 */
class DownloadFailureMessageTest {

    @Before
    fun useChinese() {
        AppLanguage.set(LanguageMode.ZH)
    }

    @After
    fun restoreChinese() {
        // AppLanguage 是进程级状态：每个用例前后都归位，避免影响同 JVM 的其他测试。
        AppLanguage.set(LanguageMode.ZH)
    }

    @Test
    fun `a known code renders in the current language`() {
        assertEquals(
            "下载地址已过期，请重新下载",
            downloadFailureText(DownloadFailureCode.URL_EXPIRED, null)
        )
        AppLanguage.set(LanguageMode.EN)
        assertEquals(
            "The download link expired. Please download again",
            downloadFailureText(DownloadFailureCode.URL_EXPIRED, null)
        )
    }

    @Test
    fun `switching language re-renders the same persisted code`() {
        // 同一条失败记录（同一个 code）在语言切换后必须换语言 —— 这正是原先预翻译落盘做不到的。
        val code = DownloadFailureCode.DELETE_BROKEN_FAILED
        val zh = downloadFailureText(code, "旧的中文副本")
        AppLanguage.set(LanguageMode.EN)
        val en = downloadFailureText(code, "旧的中文副本")
        assertEquals("无法删除损坏的旧文件，请手动删除后重试", zh)
        assertEquals("The broken file could not be deleted. Delete it manually and try again", en)
    }

    @Test
    fun `null and unknown codes fall back to the stored message`() {
        // 旧 JSON 没有 messageCode：无论当前语言如何，都显示当初落盘的那句。
        assertEquals("旧记录文案", downloadFailureText(null, "旧记录文案"))
        assertEquals("旧记录文案", downloadFailureText("code_from_a_newer_version", "旧记录文案"))
        AppLanguage.set(LanguageMode.EN)
        assertEquals("旧记录文案", downloadFailureText(null, "旧记录文案"))
        assertEquals("旧记录文案", downloadFailureText("code_from_a_newer_version", "旧记录文案"))
        assertNull(downloadFailureText("code_from_a_newer_version", null))
    }

    @Test
    fun `retry counts survive a language switch`() {
        val code = DownloadFailureCode.keptAfterRetries(2)
        assertEquals(
            "下载失败（已自动重试 2 次，半成品已保留，可重新下载续传）",
            downloadFailureText(code, null)
        )
        AppLanguage.set(LanguageMode.EN)
        assertEquals(
            "Download failed after 2 automatic retries (the partial file was kept; you can resume)",
            downloadFailureText(code, null)
        )
        val resumeCode = DownloadFailureCode.resumedAfterRetries(3)
        assertEquals(
            "Download failed after 3 automatic retries (you can resume)",
            downloadFailureText(resumeCode, null)
        )
    }

    @Test
    fun `a malformed retry suffix is not treated as a known code`() {
        // 前缀对但次数非法（0 / 负数 / 非数字）不能渲染成"已自动重试 0 次"：按未知 code 回退。
        assertEquals(
            "fallback",
            downloadFailureText(DownloadFailureCode.KEPT_RETRIES_PREFIX + "0", "fallback")
        )
        assertEquals(
            "fallback",
            downloadFailureText(DownloadFailureCode.RESUMED_RETRIES_PREFIX + "many", "fallback")
        )
    }
}
