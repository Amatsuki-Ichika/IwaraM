package io.github.amatsukiichika.iwaram.ui

import kotlin.math.roundToInt

/**
 * 预测式返回手势的**松手速度估算器**（纯逻辑，可单测）。
 *
 * 背景：`PredictiveBackHandler` 的事件只带 progress（0f..1f），不带速度。退场动画若用
 * 固定时长 + 起始斜率为 0 的缓动（FastOutSlowIn），页面在松手瞬间会"先急减速、再缓慢滑出"，
 * 手感就是"松手顿一下"。这里对 progress 事件做时间差分 + EMA 平滑，估算松手瞬间的跟手速度，
 * 供退场动画按剩余距离换算时长（[exitDurationMs]），从冻结位置带着动量继续滑出。
 *
 * 用法：每次手势新建一个实例（或先 [reset]），每个事件喂一次 [onEvent]，松手时取 [velocity]。
 */
class BackVelocityTracker {
    private var lastProgress = 0f
    private var lastTimeNanos = 0L
    /** 是否已有基准事件（时间戳 0 是合法值，不能用 lastTimeNanos 充当"有没有"的标志）。 */
    private var hasLast = false

    /** EMA 平滑后的速度（进度/秒；往回拉为负）。 */
    private var smoothed = 0f

    /**
     * 记录一次手势事件。事件间隔超过 [MAX_GAP_NANOS]（系统卡顿/手势中断）时清零重算，
     * 不拿陈旧样本算出失真的速度；首个事件只记录基准点。
     */
    fun onEvent(progress: Float, timeNanos: Long) {
        val p = progress.coerceIn(0f, 1f)
        if (hasLast) {
            val dtNanos = timeNanos - lastTimeNanos
            if (dtNanos > MAX_GAP_NANOS) {
                smoothed = 0f
            } else if (dtNanos > 0L) {
                val instant = (p - lastProgress) / (dtNanos / 1_000_000_000f)
                smoothed = if (smoothed == 0f) instant else smoothed * EMA_DECAY + instant * (1f - EMA_DECAY)
            }
        }
        lastProgress = p
        lastTimeNanos = timeNanos
        hasLast = true
    }

    /** 松手速度（进度/秒）。只喂过一个事件（没实际拖动）或从未喂过时为 0。 */
    fun velocity(): Float = smoothed

    /** 手势取消/重新开始：清空历史，从零起算。 */
    fun reset() {
        lastProgress = 0f
        lastTimeNanos = 0L
        hasLast = false
        smoothed = 0f
    }

    companion object {
        /** EMA 衰减系数：历史权重 0.6（新样本权重 0.4，兼顾响应与抗抖）。 */
        private const val EMA_DECAY = 0.6f

        /** 事件间隔超过 150ms 视为中断（正常事件节拍 ≈ 8-16ms）。 */
        private const val MAX_GAP_NANOS = 150L * 1_000_000L
    }
}

/** 一次手势松手的"动量"：冻结的跟手进度（0f..1f）+ 松手速度（进度/秒）。 */
data class BackMomentum(
    val progress: Float,
    val velocityFrac: Float,
) {
    companion object {
        val ZERO = BackMomentum(0f, 0f)
    }
}

/** 退场时长的下限（ms）：再快也不能一闪而过。 */
const val MIN_EXIT_MS = 150

/**
 * 按剩余距离与松手速度换算退场时长（ms），钳制在 [MIN_EXIT_MS]..[maxMs]。
 *
 * 速度 ≤ 0（慢松手/没有估算值）时返回 [maxMs] —— 交给调用方的"无动量"规格处理。
 * 换算出的时长配快出慢停的 LinearOutSlowIn 缓动，观感就是"从手指松开的速度接着滑出去"。
 */
fun exitDurationMs(remainingPx: Float, velocityPxPerS: Float, maxMs: Int = 320): Int {
    if (velocityPxPerS <= 0f || remainingPx <= 0f) return maxMs
    return (remainingPx / velocityPxPerS * 1000f).roundToInt().coerceIn(MIN_EXIT_MS, maxMs)
}
