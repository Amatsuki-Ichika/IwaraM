package io.github.amatsukiichika.iwaram.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预测式返回速度估算器与退场时长换算的用例。
 *
 * 背景：`PredictiveBackHandler` 只给 progress 不给速度；退场动画要按松手速度衔接
 * （消除"松手先停一下"的手感），估算失真会直接体现在手感上。
 */
class BackVelocityTrackerTest {

    /** 1ms 的纳秒数。 */
    private val ms = 1_000_000L

    /* ---------- 匀速拖动 ---------- */

    @Test
    fun `匀速拖动估算出稳定速度`() {
        val t = BackVelocityTracker()
        // 每 10ms 前进 0.04 → 4 进度/秒（进度控制在 0..1 内，估算器会把越界值钳掉）。
        var time = 0L
        var p = 0f
        t.onEvent(p, time)
        repeat(20) {
            time += 10 * ms
            p += 0.04f
            t.onEvent(p, time)
        }
        assertEquals(4f, t.velocity(), 0.05f)
    }

    @Test
    fun `快甩的速度明显高于慢拖`() {
        fun feed(stepProgress: Float, stepMs: Long, count: Int): Float {
            val t = BackVelocityTracker()
            var time = 0L
            var p = 0f
            t.onEvent(p, time)
            repeat(count) {
                time += stepMs * ms
                p += stepProgress
                t.onEvent(p, time)
            }
            return t.velocity()
        }
        val fast = feed(0.08f, 8, 10)   // 0.08 / 8ms = 10 进度/秒
        val slow = feed(0.02f, 16, 10)  // 0.02 / 16ms = 1.25 进度/秒
        assertEquals(10f, fast, 0.1f)
        assertEquals(1.25f, slow, 0.05f)
    }

    @Test
    fun `回拉速度为负`() {
        val t = BackVelocityTracker()
        var time = 0L
        var p = 0.8f
        t.onEvent(p, time)
        repeat(10) {
            time += 8 * ms
            p -= 0.08f
            t.onEvent(p, time)
        }
        assertEquals(-10f, t.velocity(), 0.1f)
    }

    @Test
    fun `事件间隔过长视为中断_速度清零重算`() {
        val t = BackVelocityTracker()
        t.onEvent(0f, 0)
        t.onEvent(0.04f, 10 * ms)
        assertEquals(4f, t.velocity(), 0.05f)
        // 290ms 没有事件（> 150ms 阈值）：按中断处理，不拿陈旧样本算速度。
        t.onEvent(0.05f, 300 * ms)
        assertEquals(0f, t.velocity(), 1e-4f)
        // 中断后重新起算。
        t.onEvent(0.09f, 310 * ms)
        assertEquals(4f, t.velocity(), 0.05f)
    }

    @Test
    fun `只有单个事件时速度为零`() {
        val t = BackVelocityTracker()
        t.onEvent(0.5f, 0)
        assertEquals(0f, t.velocity(), 1e-6f)
    }

    @Test
    fun `reset清空历史并重新起算`() {
        val t = BackVelocityTracker()
        t.onEvent(0f, 0)
        t.onEvent(0.04f, 10 * ms)
        t.reset()
        t.onEvent(0.5f, 500 * ms)
        assertEquals(0f, t.velocity(), 1e-6f)
        t.onEvent(0.54f, 510 * ms)
        assertEquals(4f, t.velocity(), 0.05f)
    }

    /* ---------- exitDurationMs ---------- */

    @Test
    fun `无速度或无剩余距离时用最大时长`() {
        assertEquals(320, exitDurationMs(500f, 0f))
        assertEquals(320, exitDurationMs(0f, 2000f))
        assertEquals(320, exitDurationMs(-1f, 2000f))
    }

    @Test
    fun `时长等于剩余距离除以速度`() {
        // 320px ÷ 2000px/s = 160ms。
        assertEquals(160, exitDurationMs(320f, 2000f))
    }

    @Test
    fun `换算结果钳制在上下限内`() {
        assertEquals(MIN_EXIT_MS, exitDurationMs(50f, 2000f))        // 25ms → 下限
        assertEquals(320, exitDurationMs(1000f, 500f))               // 2000ms → 上限
        assertEquals(200, exitDurationMs(4000f, 100f, maxMs = 200))  // 自定义上限
    }
}
