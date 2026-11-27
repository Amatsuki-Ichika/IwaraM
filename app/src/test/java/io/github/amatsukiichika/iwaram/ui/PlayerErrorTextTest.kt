package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.i18n.AppLanguage
import io.github.amatsukiichika.iwaram.i18n.LanguageMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 播放错误面板的文案映射（[playerErrorText] / [buildPlayerErrorUi]）回归。
 *
 * 覆盖 HTTP 403、其他 4xx、网络域 2001/2002/1003、解析/解码域 3000..3999、未知码：
 * 每条分支都必须落到正确的人话短句，detail 轨保留错误码/HTTP/原因链；中英文都验证。
 *
 * 直接测纯映射函数而不是构造 [androidx.media3.common.PlaybackException]：它的构造器会摸
 * `android.os.Bundle` / `SystemClock`，JVM 单测里不可用；错误码用 media3 公开的数值常量
 * （2001/2002/1003/2004/3001 等，属于稳定 API）。
 */
class PlayerErrorTextTest {

    private companion object {
        // 与 androidx.media3.common.PlaybackException 的公开错误码一一对应
        const val IO_NETWORK_CONNECTION_FAILED = 2001
        const val IO_NETWORK_CONNECTION_TIMEOUT = 2002
        const val TIMEOUT = 1003
        const val IO_BAD_HTTP_STATUS = 2004
        const val PARSING_CONTAINER_MALFORMED = 3001
        const val REMOTE_ERROR = 1001
        const val VIDEO_FRAME_PROCESSING_FAILED = 7001
    }

    @Before
    fun useChinese() {
        AppLanguage.set(LanguageMode.ZH)
    }

    @After
    fun restoreDefaults() {
        AppLanguage.set(LanguageMode.ZH)
    }

    private fun text(
        errorCode: Int,
        errorCodeName: String = "ERROR_CODE_$errorCode",
        httpCode: Int? = null,
        srReason: String? = null,
        stackFrame: StackTraceElement? = null
    ): PlayerErrorUi = playerErrorText(
        errorCode = errorCode,
        errorCodeName = errorCodeName,
        httpCode = httpCode,
        srReason = srReason,
        stackFrame = stackFrame,
        chainMsg = "cause-chain"
    )

    @Test
    fun `403 gets the retry specific text and keeps the http status in detail`() {
        val ui = text(IO_BAD_HTTP_STATUS, errorCodeName = "ERROR_CODE_IO_BAD_HTTP_STATUS", httpCode = 403)
        assertEquals("播放地址被拒绝访问（可能已失效），请重试", ui.friendly)
        assertTrue(ui.detail.contains("ERROR_CODE_IO_BAD_HTTP_STATUS"))
        assertTrue(ui.detail.contains("服务器返回 HTTP 403"))
        assertTrue(ui.detail.contains("代码 2004"))
        assertTrue(ui.detail.contains("原因链：cause-chain"))
    }

    @Test
    fun `other 4xx uses the shared http mapping`() {
        val ui = text(IO_BAD_HTTP_STATUS, httpCode = 404)
        assertEquals("内容不存在或已被删除", ui.friendly)
        assertTrue(ui.detail.contains("服务器返回 HTTP 404"))
    }

    @Test
    fun `network and timeout codes map to one friendly message`() {
        listOf(IO_NETWORK_CONNECTION_FAILED, IO_NETWORK_CONNECTION_TIMEOUT, TIMEOUT).forEach { code ->
            assertEquals("错误码 $code 应给网络提示", "网络不稳定，请重试", text(code).friendly)
        }
    }

    @Test
    fun `the 3000 range maps to a codec message`() {
        assertEquals("该视频编码无法播放", text(PARSING_CONTAINER_MALFORMED).friendly)
    }

    @Test
    fun `an unknown code falls back to the generic retry message`() {
        assertEquals("播放出错，请重试", text(REMOTE_ERROR).friendly)
    }

    @Test
    fun `the super resolution reason and the deepest stack frame go into the detail track`() {
        val ui = text(
            VIDEO_FRAME_PROCESSING_FAILED,
            errorCodeName = "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED",
            srReason = "管线炸了",
            stackFrame = StackTraceElement("com.example.Foo", "bar", "Foo.kt", 42)
        )
        assertTrue(ui.detail.contains("超分原因：管线炸了"))
        assertTrue(ui.detail.contains("（com.example.Foo.bar:42）"))
        // 已记录的 SR 失败原因不改变友好文案（这里走兜底的一句）
        assertEquals("播放出错，请重试", ui.friendly)
    }

    @Test
    fun `english covers 403 network codec the generic fallback and the detail track`() {
        AppLanguage.set(LanguageMode.EN)

        val forbidden = text(IO_BAD_HTTP_STATUS, errorCodeName = "ERROR_CODE_IO_BAD_HTTP_STATUS", httpCode = 403)
        assertEquals("Playback URL access denied (it may have expired), please retry", forbidden.friendly)
        assertTrue(forbidden.detail.contains("server returned HTTP 403"))
        assertTrue(forbidden.detail.contains("code 2004"))
        assertTrue(forbidden.detail.contains("Cause chain: cause-chain"))

        assertEquals("Network unstable, please retry", text(IO_NETWORK_CONNECTION_FAILED).friendly)
        assertEquals("This video's codec cannot be played", text(PARSING_CONTAINER_MALFORMED).friendly)
        assertEquals("Playback failed, please retry", text(REMOTE_ERROR).friendly)

        val withFrame = text(
            VIDEO_FRAME_PROCESSING_FAILED,
            srReason = "pipeline broke",
            stackFrame = StackTraceElement("com.example.Foo", "bar", "Foo.kt", 42)
        )
        assertTrue(withFrame.detail.contains("; SR reason: pipeline broke"))
        assertTrue(withFrame.detail.contains(" (com.example.Foo.bar:42)"))
    }
}
