package io.github.amatsukiichika.iwaram.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换片时的超分状态清理边界（Medium-13）。
 *
 * 装片路径曾无条件 [SuperResStatus.reset]，把 7001 刚写下的 pipelineFailed 与原因当场抹掉，
 * chip 的「SR 自动停用」和诊断原因永远活不过一次自动恢复。换片只许清实测值；
 * 完整清零只发生在换视频或重新打开 SR 时。
 */
class SuperResStatusResetTest {

    @After
    fun tearDown() {
        SuperResStatus.reset()
    }

    @Test
    fun `measurement reset keeps the pipeline failure and its reason`() {
        SuperResStatus.reset()
        SuperResStatus.pipelineFailed = true
        SuperResStatus.reason = "特效管线失败（7001）"
        SuperResStatus.activeScale = 2f
        SuperResStatus.engine = "Anime4K"
        SuperResStatus.sourceWidth = 1920
        SuperResStatus.sourceHeight = 1080
        SuperResStatus.outputWidth = 2560
        SuperResStatus.outputHeight = 1440

        SuperResStatus.resetMeasurements()

        assertTrue("自动停用提醒要留到重新打开 SR", SuperResStatus.pipelineFailed)
        assertEquals("特效管线失败（7001）", SuperResStatus.reason)
        assertNull(SuperResStatus.activeScale)
        assertNull(SuperResStatus.engine)
        assertNull("上一部片的源分辨率必须清掉", SuperResStatus.sourceText)
        assertNull(SuperResStatus.outputText)
    }

    @Test
    fun `full reset clears the pipeline failure when the video changes`() {
        SuperResStatus.reset()
        SuperResStatus.pipelineFailed = true
        SuperResStatus.reason = "boom"
        SuperResStatus.degraded = true

        SuperResStatus.reset()

        assertFalse(SuperResStatus.pipelineFailed)
        assertNull(SuperResStatus.reason)
        assertFalse(SuperResStatus.degraded)
        assertNull(SuperResStatus.sourceText)
    }
}
