package io.github.amatsukiichika.iwaram.ui

/*
 * 卡片标签行的**单行自适应**布局规则（纯逻辑，单测见 TagRowLayoutTest）。
 *
 * 目标（用户 2026-11 反馈）：标签行永远只占一行，按卡片**实际可用宽度**决定能放下几个；
 * 放不下的收进 "+N" 药丸；最后一枚自己太长时用省略号截断，且这一枚仍算"已显示"，
 * "+N" 只统计它**后面**真正没显示出来的标签数量（省略号本身不算一个标签）。
 *
 * 规则：
 *   1. 可用宽度足够放下全部 ⇒ 全部显示，无 "+N"、无省略号；
 *   2. 否则优先"整枚显示更多标签 + +N"，逐枚回退，直到放得下；
 *   3. 若连"整枚 + +N"都放不下，把最后一枚压到剩余宽度并用省略号；
 *   4. 连第一枚都放不下 ⇒ 只显示省略号那一枚（或只剩 "+N"，见 availablePx 极小的分支）。
 *
 * 所有宽度都是**像素**：调用点用 TextMeasurer 量出文字宽 + 药丸水平内边距后传进来，
 * 于是这里不需要 Density / 字体，可以纯 JVM 单测。
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
 * @param chipWidthsPx     每个标签药丸的完整宽度（文字宽 + 左右内边距，px），顺序即显示顺序
 * @param gapPx            药丸之间的水平间距（px）
 * @param ellipsisChipMinPx 截断到"只剩省略号"时允许的最小药丸宽度（px），必须小于等于正常药丸宽度
 * @param plusWidthPx      给定隐藏数量 → "+N" 药丸的宽度（px）；N 的位数会影响宽度
 * @param availablePx      标签行可用宽度（px）
 */
internal fun layoutTagRow(
    chipWidthsPx: List<Int>,
    gapPx: Int,
    ellipsisChipMinPx: Int,
    plusWidthPx: (Int) -> Int,
    availablePx: Int
): TagRowLayout {
    val n = chipWidthsPx.size
    if (n == 0) return TagRowLayout(0, false, 0, 0)
    val gap = gapPx.coerceAtLeast(0)
    val avail = availablePx

    // 前 k 枚（整枚）的宽度和：k=0 时为 0；k 枚之间有 k-1 个间距。
    fun prefixWidth(k: Int): Int {
        var sum = 0
        for (i in 0 until k) sum += chipWidthsPx[i]
        if (k > 1) sum += gap * (k - 1)
        return sum
    }

    // 从"能显示最多"往回收：先试整枚，再试把第 k 枚截断。
    for (k in n downTo 1) {
        val hidden = n - k
        // a) 第 k 枚也整枚显示，其后 +N。
        val exact = prefixWidth(k) + if (hidden > 0) gap + plusWidthPx(hidden) else 0
        if (exact <= avail) return TagRowLayout(k, false, hidden, 0)

        // b) 第 k 枚截断到剩余宽度（至少保留省略号的最小宽度）。
        val beforeLast = prefixWidth(k - 1) + if (k > 1) gap else 0
        val afterLast = if (hidden > 0) gap + plusWidthPx(hidden) else 0
        val maxLast = avail - beforeLast - afterLast
        if (maxLast >= ellipsisChipMinPx) {
            return TagRowLayout(
                visibleCount = k,
                ellipsizeLast = true,
                hiddenCount = hidden,
                lastChipMaxPx = maxLast
            )
        }
    }

    // 连第一枚都放不下：只剩 +N（标签本身一个都不显示，N 记全部）。
    return TagRowLayout(0, false, n, 0)
}
