package io.github.amatsukiichika.iwaram.ui

/**
 * 滚动条拖动增量的"每帧一次派发"批量器。
 *
 * 背景：高采样率触摸屏一帧内会来好几个 pointer 事件，每份增量都同步 dispatchRawDelta 的话，
 * 一次拖动会在一帧里触发多次滚动测量/布局，中间结果全部白算（表现为一顿一顿而非顺滑）。
 * 这里按帧合并：第一次 [add] 返回 true（调用方据此调度一帧），同帧后续 add 只累加；
 * 帧回调里 [drain] 取走全部增量一次性派发，列表仍然跟手。
 *
 * 纯逻辑、不依赖 Compose（JVM 单测覆盖）：不持有协程，也不决定派发落点。
 */
internal class FrameDeltaBatch {
    private var pending = 0f
    private var scheduled = false

    /**
     * 累加一份增量。
     *
     * @return true = 本帧还没排过派发，调用方应调度一帧；false = 已排过（只累加）或增量非法。
     *         非有限值（NaN / Infinity）被直接忽略，不会污染已累计的增量。
     */
    fun add(delta: Float): Boolean {
        if (!delta.isFinite()) return false
        pending += delta
        if (scheduled) return false
        scheduled = true
        return true
    }

    /** 取走累计增量并复位（在帧回调里调用）。没有待派发增量时返回 0f。 */
    fun drain(): Float {
        val out = pending
        pending = 0f
        scheduled = false
        return out
    }
}
