package io.github.amatsukiichika.iwaram.ui

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import kotlin.math.floor

/**
 * 滚动进度几何（纯数据 + 纯函数，单测见 `app/src/test/java/.../ui/ScrollGeometryTest.kt`）。
 *
 * 不能用「首个可见下标 / (总数 − 可见数)」：网格一行有 2 个（或更多）条目，
 * `firstVisibleItemIndex` 每滚一行跳 2，进度一格一格蹦；可见项数 = 列数 × 行数，分母也不对，
 * 拇指长度偏大、到底时又提前贴底。加上网格每行高度不等于条目高度，纯下标映射不可能连续。
 *
 * 这里把滚动量统一换算成单位（网格 = 行，列表 = 条目），并把单位内的像素偏移也算进去
 * （`−首个可见单位的 offset ÷ 单位高`）。于是：
 * - [scrollFraction] 连续（行内滚动会让拇指平滑滑动，不再跳格）；
 * - [seekTarget] 与 [scrollFraction] 严格互逆（单测保证）—— 不互逆的话，按下拇指的瞬间列表会跳到别处；
 * - [thumbSpanFraction] 按"可见单位 / 总单位"算，长列表的拇指长度才是真实比例。
 *
 * [visibleUnits] 为 Float，网格按 `视口高 ÷ 行高`（含小数）计：行高均匀的网格里这个值恒定，
 * 不随"半行进出"每帧 ±1 抖动。
 *
 * 三个量都只依赖"当前可见窗口"，不需要知道总高度（懒布局本来也不知道）。
 */
internal data class ScrollGeometry(
    /** 总单位数（网格 = 总行数，列表 = 总条目数）。 */
    val totalUnits: Int,
    /** 可见单位数（网格 = 视口高 ÷ 行高，含小数；列表 = 可见条目数）。 */
    val visibleUnits: Float,
    /** 首个可见单位的下标（网格 = 行号）。 */
    val firstUnit: Int,
    /** 首个可见单位被滚出视口的像素（≥ 0）。 */
    val unitOffsetPx: Float,
    /** 单位高（像素）。0 = 未知，此时行内偏移按 0 处理（退化成旧的下标映射）。 */
    val unitSizePx: Float,
    /** 一个单位包含几个条目（网格 = 列数，列表 = 1）。 */
    val itemsPerUnit: Int
)

/** 还能滚动的单位数（≤ 0 表示内容不足一屏）。 */
internal val ScrollGeometry.scrollSpanUnits: Float
    get() = (totalUnits - visibleUnits).coerceAtLeast(0f)

/** 连续滚动比例 0f..1f（含单位内偏移）。 */
internal fun ScrollGeometry.scrollFraction(): Float {
    val span = scrollSpanUnits
    if (span <= 0f) return 0f
    val frac = if (unitSizePx > 0f) (unitOffsetPx / unitSizePx).coerceIn(0f, 1f) else 0f
    return ((firstUnit + frac) / span).coerceIn(0f, 1f)
}

/** 拇指长度占轨道比例 = 可见单位 / 总单位（下限 5% 保证可抓，上限 100%）。 */
internal fun ScrollGeometry.thumbSpanFraction(): Float =
    if (totalUnits <= 0) 1f else (visibleUnits / totalUnits).coerceIn(0.05f, 1f)

/**
 * 拖动比例 → (`目标条目下标`, `条目内像素偏移`)。
 * 与 [scrollFraction] 严格互逆：把 [scrollFraction] 的结果喂回来，得到的就是当前（下标, 偏移）。
 */
internal fun ScrollGeometry.seekTarget(fraction: Float): Pair<Int, Int> {
    val span = scrollSpanUnits
    if (span <= 0f) return 0 to 0
    val target = fraction.coerceIn(0f, 1f) * span
    val unit = floor(target).toInt().coerceIn(0, floor(span).toInt())
    val frac = (target - unit).coerceIn(0f, 1f)
    val offsetPx = if (unitSizePx > 0f) (frac * unitSizePx).toInt() else 0
    return (unit * itemsPerUnit.coerceAtLeast(1)) to offsetPx
}

/** 网格布局信息 → 几何。没有可见项 / 总数为 0 时返回 null（调用方按 0 处理）。 */
internal fun LazyGridLayoutInfo.toScrollGeometry(): ScrollGeometry? {
    val first = visibleItemsInfo.firstOrNull() ?: return null
    val last = visibleItemsInfo.lastOrNull() ?: return null
    // 委托给纯函数本体：layoutInfo 是 sealed 接口（1.7 起）没法在单测里造假实现，
    // 映射逻辑放进 [gridScrollGeometry] 用普通值直测。
    return gridScrollGeometry(
        totalItems = totalItemsCount,
        firstRow = first.row,
        lastRow = last.row,
        columns = visibleItemsInfo.maxOf { it.column } + 1,
        firstRowTopPx = first.offset.y,
        rowHeightPx = first.size.height,
        beforePad = beforeContentPadding,
        afterPad = afterContentPadding,
        viewportMainAxisPx = viewportSize.height
    )
}

/** [toScrollGeometry] 的纯函数本体（网格）：所有取值都是普通类型，单测直供。 */
internal fun gridScrollGeometry(
    totalItems: Int,
    firstRow: Int,
    lastRow: Int,
    columns: Int,
    firstRowTopPx: Int,
    rowHeightPx: Int,
    beforePad: Int,
    afterPad: Int,
    viewportMainAxisPx: Int
): ScrollGeometry? {
    if (totalItems <= 0) return null
    // 列数取"可见项里最大的 column + 1"。可见集合里只要有完整的一行就是准确的（列表没有横向滚动）；
    // 只有"整页只剩末行几个残项"时才会偏小，而那种情况内容不足一屏、滚动条根本不显示。
    val cols = columns.coerceAtLeast(1)
    val rowH = rowHeightPx.toFloat()
    // 可见行数 = 视口高 ÷ 行高（扣掉上下 contentPadding，含小数）。
    // 行高未知（0）时退回"行号差"计法（末行残项场景本来就不足一屏、滚动条不显示）。
    val viewportMainAxis = (viewportMainAxisPx - beforePad - afterPad).coerceAtLeast(0).toFloat()
    val visibleRows = if (rowH > 0f) viewportMainAxis / rowH else (lastRow - firstRow + 1).toFloat()
    return ScrollGeometry(
        totalUnits = (totalItems + cols - 1) / cols,
        visibleUnits = visibleRows.coerceAtLeast(1f),
        firstUnit = firstRow.coerceAtLeast(0),
        unitOffsetPx = (-firstRowTopPx).toFloat().coerceAtLeast(0f),
        unitSizePx = rowH,
        itemsPerUnit = cols
    )
}

/** 列表布局信息 → 几何（单位 = 条目，列数 = 1；条目高度可变，可见数按实数条目计）。 */
internal fun LazyListLayoutInfo.toScrollGeometry(): ScrollGeometry? {
    val first = visibleItemsInfo.firstOrNull() ?: return null
    return listScrollGeometry(
        totalItems = totalItemsCount,
        firstItemIndex = first.index,
        firstItemTopPx = first.offset,
        firstItemHeightPx = first.size,
        visibleCount = visibleItemsInfo.size
    )
}

/** [toScrollGeometry] 的纯函数本体（列表）：普通类型直测。 */
internal fun listScrollGeometry(
    totalItems: Int,
    firstItemIndex: Int,
    firstItemTopPx: Int,
    firstItemHeightPx: Int,
    visibleCount: Int
): ScrollGeometry? {
    if (totalItems <= 0) return null
    return ScrollGeometry(
        totalUnits = totalItems,
        visibleUnits = visibleCount.toFloat().coerceAtLeast(1f),
        firstUnit = firstItemIndex,
        unitOffsetPx = (-firstItemTopPx).toFloat().coerceAtLeast(0f),
        unitSizePx = firstItemHeightPx.toFloat(),
        itemsPerUnit = 1
    )
}
