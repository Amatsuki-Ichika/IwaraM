package io.github.amatsukiichika.iwaram.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动条拖动增量的按帧合并（回归：a20ea8a 去掉 suspend 队列后，高采样屏一帧多份
 * dispatchRawDelta 会在一帧内触发多次滚动布局，拖动一顿一顿）。
 */
class FrameDeltaBatchTest {

    @Test
    fun firstAddSchedulesAFrameAndLaterAddsInTheSameFrameDoNot() {
        val batch = FrameDeltaBatch()

        assertTrue(batch.add(3f))
        assertFalse(batch.add(4f))
        assertFalse(batch.add(-2f))
    }

    @Test
    fun drainReturnsTheSumOfDeltasAddedSinceTheLastDrain() {
        val batch = FrameDeltaBatch()
        batch.add(3f)
        batch.add(4f)
        batch.add(-2f)

        assertEquals(5f, batch.drain(), 1e-6f)
    }

    @Test
    fun drainWithoutAddsReturnsZero() {
        val batch = FrameDeltaBatch()

        assertEquals(0f, batch.drain(), 0f)
    }

    @Test
    fun drainClearsTheBatchSoTheNextAddSchedulesAgain() {
        val batch = FrameDeltaBatch()
        batch.add(7f)
        assertEquals(7f, batch.drain(), 1e-6f)

        assertEquals(0f, batch.drain(), 0f)
        assertTrue(batch.add(1f))
        assertEquals(1f, batch.drain(), 1e-6f)
    }

    @Test
    fun nonFiniteDeltasAreIgnoredAndDoNotDisturbTheScheduledFrame() {
        val batch = FrameDeltaBatch()

        assertFalse(batch.add(Float.NaN))
        assertFalse(batch.add(Float.POSITIVE_INFINITY))
        assertEquals(0f, batch.drain(), 0f)

        assertTrue(batch.add(2f))
        assertFalse(batch.add(Float.NaN))
        assertEquals(2f, batch.drain(), 1e-6f)
    }
}
