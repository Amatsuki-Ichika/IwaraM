package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadControlTest {

    @Test
    fun `pause and cancel are consumed exactly once`() {
        val control = DownloadControl()
        control.requestPause("v1")
        assertEquals(DownloadControl.Intent.PAUSE, control.consumeStop("v1"))
        assertNull(control.consumeStop("v1"))
        assertFalse(control.hasPending("v1"))
    }

    @Test
    fun `cancel wins over a pause requested at the same time`() {
        val control = DownloadControl()
        control.requestPause("v1")
        control.requestCancel("v1")
        assertEquals(DownloadControl.Intent.CANCEL, control.consumeStop("v1"))
    }

    @Test
    fun `a later pause does not downgrade a pending cancel`() {
        val control = DownloadControl()
        control.requestCancel("v1")
        control.requestPause("v1")
        assertTrue(control.consumeCancel("v1"))
        // 取消已被消费；暂停没有覆盖取消意图，因此不应留下任何意图。
        assertFalse(control.hasPending("v1"))
    }

    @Test
    fun `consuming a cancel also consumes the pause it overrode`() {
        val control = DownloadControl()
        control.requestPause("v1")
        control.requestCancel("v1")
        // 取消覆盖了先前的暂停：出队检查消费取消后不应残留任何意图。
        // 排队中的任务被取消即丢弃，一个过期的暂停意图没有后续消费者，留着只会误伤下次下载。
        assertTrue(control.consumeCancel("v1"))
        assertFalse(control.hasPending("v1"))
        assertNull(control.consumeStop("v1"))
    }

    @Test
    fun `clear drops intent so a restarted download is not stopped by an old request`() {
        val control = DownloadControl()
        control.requestCancel("v1")
        control.clear("v1")
        assertFalse(control.consumeCancel("v1"))
        assertNull(control.consumeStop("v1"))
    }

    @Test
    fun `intents are per video`() {
        val control = DownloadControl()
        control.requestPause("v1")
        assertNull(control.consumeStop("v2"))
        assertEquals(DownloadControl.Intent.PAUSE, control.consumeStop("v1"))
    }
}
