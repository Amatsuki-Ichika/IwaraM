package io.github.amatsukiichika.iwaram.ui

/*
 * 卡片标签行的**单行自适应**布局规则（纯逻辑，单测见 TagRowLayoutTest）。
 *
 * 目标（用户 2026-11 反馈）：标签行永远只占一行，按卡片**实际可用宽度**决定能放下几个；
 * 放不下的收进 "+N" 药丸；只有"某一枚标签本身放不下"时才用省略号截断它，
 * 且这一枚仍算"已显示" —— "+N" 只统计它**后面**真正没显示出来的标签数量
 * （省略号本身不是一个标签，也绝不允许出现"只有省略号"的药丸）。
 *
 * 规则：
 *   1. 可用宽度足够放下全部 ⇒ 全部整枚显示，无 "+N"、无省略号；
 *   2. 否则先找"整枚显示 k 枚 + 收 "+N""能放下的最大 k；
 *   3. 若第 k+1 枚截断后还能至少留下"省略号 + 一个字"的宽度 ⇒ 把这一枚截断显示，
 *      "+N" 的数只算它后面的（截断的这枚算已显示）；否则不截断，少显示一枚；
 *   4. 连一枚整枚都放不下 ⇒ 只显示 "+N"（N = 全部）；
 *   5. 空标签 ⇒ 什么都不画。
 *
 * 宽度都是**像素**：调用点用 SubcomposeLayout 组合出真实药丸量出宽度再传进来，
 * 不再用 TextMeasurer 估算（估算与真实排版有 1-2px 偏差，累积后会把最后一枚挤成省略号，
 * 这正是用户截图里出现"独立 … 药丸"的根因）；纯函数本身不依赖 Density/字体，可纯 JVM 单测。
 */

/** 标签行布局结果。 */
internal data class TagRowLayout(
    /** 完整显示的标签数（含被省略号截断的那一枚）。 */
    val visibleCount: Int,
    /** 最后一枚可见标签是否用省略号截断（true 时 [lastChipMaxPx] 是它的最大宽度）。 */
    val ellipsizeLast: Boolean,
    /** 收进 "+N" 的标签数量；0 = 不画 "+N"。 */
    val hiddenCount: Int,
    /** 被截断那枚标签允许的最大宽度（px）；不截断时为 0。 */
    val lastChipMaxPx: Int
)

/**
 * 计算标签行布局。
 *
 * @param chipWidthsPx     **已按显示顺序量出**的标签药丸宽度（px，文字宽 + 左右内边距）。
 *                         允许只量前若干枚（后面的宽未知 ⇒ 必然显示不了，只能收进 "+N"）。
 * @param totalTagCount    标签总数（可能大于 [chipWidthsPx] 的长度）
 * @param gapPx            药丸之间的水平间距（px）
 * @param truncatedMinPx   截断药丸至少要留下的宽度（px）—— 即"省略号 + 一个字"的药丸宽，
 *                         小于它就宁可把这一枚收进 "+N"，避免出现只有省略号的药丸
 * @param plusWidthPx      给定隐藏数量 → "+N" 药丸的宽度（px）；N 的位数影响宽度
 * @param availablePx      标签行可用宽度（px）
 */
internal fun layoutTagRow(
    chipWidthsPx: List<Int>,
    totalTagCount: Int,
    gapPx: Int,
    truncatedMinPx: Int,
    plusWidthPx: (Int) -> Int,
    availablePx: Int
): TagRowLayout {
    val n = totalTagCount
    if (n <= 0) return TagRowLayout(0, false, 0, 0)
    val measured = chipWidthsPx.size
    val gap = gapPx.coerceAtLeast(0)
    val avail = availablePx

    // 前 k 枚（整枚）的宽度和：k=0 时为 0；k 枚之间共有 k-1 个间距。
    fun prefixWidth(k: Int): Int {
        var sum = 0
        for (i in 0 until k) sum += chipWidthsPx[i]
        if (k > 1) sum += gap * (k - 1)
        return sum
    }

    // 1) 全部整枚放得下（要求每一枚的宽都已知）。
    if (measured >= n && prefixWidth(n) <= avail) {
        return TagRowLayout(n, false, 0, 0)
    }

    // 2) 找"整枚 k 枚 + +N"放得下的最大 k（k 不可能超过已量出的枚数）。
    var bestK = -1
    for (k in measured downTo 0) {
        val hidden = n - k
        if (hidden == 0) {
            // k == n 已在上面处理过；这里只可能是 n > measured 的异常边界，跳过。
            continue
        }
        val used = prefixWidth(k) + (if (k > 0) gap else 0) + plusWidthPx(hidden)
        if (used <= avail) {
            bestK = k
            break
        }
    }
    // 连"+N"都塞不下：仍然只画"+N"（由渲染层按可用宽度裁，但不该出现只有省略号的药丸）。
    if (bestK < 0) bestK = 0

    // 3) 尝试把下一枚标签截断后塞进来：这一枚必须已量出宽度（否则不知道它确实放不下）。
    if (bestK < measured) {
        val r = bestK + 1
        val hidden = n - r
        val before = prefixWidth(bestK) + (if (bestK > 0) gap else 0)
        val after = if (hidden > 0) gap + plusWidthPx(hidden) else 0
        val maxLast = avail - before - after
        if (maxLast >= truncatedMinPx) {
            return TagRowLayout(
                visibleCount = r,
                ellipsizeLast = true,
                hiddenCount = hidden,
                lastChipMaxPx = maxLast
            )
        }
    }

    return TagRowLayout(bestK, false, n - bestK, 0)
}
