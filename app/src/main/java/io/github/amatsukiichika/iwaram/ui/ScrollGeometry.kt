package io.github.amatsukiichika.iwaram.ui

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import kotlin.math.floor
import kotlin.math.roundToInt

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

/**
 * 带通栏头的折叠几何（[headerScrollGeometry]）的单位 → (`条目下标`, `条目内像素偏移`)。
 *
 * [unit] 是折叠后的整行号（与 [seekTarget] 的整数部分同轴，但**未**按 itemsPerUnit 展开），
 * [offsetPxInUnit] 是该行内的像素偏移（[seekTarget] 的第二项）。
 *
 * - 头内（折叠行号在 [headerUnits] 内，或像素落点还没越过实测头高）：落在头条目 0 上，
 *   偏移 = **整行 + 行内**的像素和，并夹在 [headerHeightPx] 内 —— [headerScrollGeometry] 把
 *   头的折算行数取整，像素判断补上"取整方向"造成的边界偏差，夹取防止把列表滚过整个头；
 * - 头之后：按每行 [itemsPerRow] 个条目（网格 = 列数，列表 = 1）展开回内容条目下标。
 *
 * 只取行内偏移会丢掉整行部分：头折算好几行时，点头部任意位置都只滚到离顶部不到一行处，
 * 落点不在手指下方（ADR 0012 的连续滚动条正是要消掉这种跳变）。
 */
internal fun ScrollGeometry.foldedSeekTarget(
    unit: Int,
    offsetPxInUnit: Int,
    headerUnits: Int,
    headerItemCount: Int,
    itemsPerRow: Int,
    headerHeightPx: Int
): Pair<Int, Int> {
    val targetPx = (unit * unitSizePx + offsetPxInUnit).toInt()
    if (unit < headerUnits || targetPx < headerHeightPx) {
        return 0 to targetPx.coerceIn(0, headerHeightPx.coerceAtLeast(0))
    }
    val contentUnit = unit - headerUnits
    return (headerItemCount + contentUnit * itemsPerRow.coerceAtLeast(1)) to offsetPxInUnit
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

/* ---------- 连续滚动：像素距离与「通栏头」几何 ---------- */

/**
 * 可滚动的总像素数：`scrollSpanUnits × unitSizePx`。
 *
 * 单位高未知（0）或内容不足一屏时结果为 0（视作不可滚动）；调用方不要拿它做除数，
 * 用 [scrollDeltaPx]，它会把 0 当成"不可滚"返回 0。
 */
internal val ScrollGeometry.spanPx: Float
    get() = (scrollSpanUnits * unitSizePx).coerceAtLeast(0f)

/**
 * 两个滚动比例之间的像素距离：`(target − current) × spanPx`。
 *
 * 比例先夹到 0f..1f —— 拖到轨道末端之外不会多滚（末端夹取），起点越界也从顶端算起。
 * [spanPx] 非正（内容不足一屏 / 单位高未知）或非有限值时返回 0f：不可滚的列表上拖动
 * 不该产生 NaN，也不该出现反向跳变。
 */
internal fun scrollDeltaPx(currentFraction: Float, targetFraction: Float, spanPx: Float): Float {
    if (!(spanPx > 0f) || !spanPx.isFinite()) return 0f
    if (currentFraction.isNaN() || targetFraction.isNaN()) return 0f
    val current = currentFraction.coerceIn(0f, 1f)
    val target = targetFraction.coerceIn(0f, 1f)
    return (target - current) * spanPx
}

/**
 * 带「通栏头」列表的滚动几何：单位 = 内容行，通栏头按实测像素折算成若干行计入总长。
 *
 * 普通 [listScrollGeometry] 把首项当"第一个单位"：头高远大于内容行高时，头还没滚出时
 * `firstUnit = 0`、`unitOffsetPx` 量的是**头内**偏移；头一滚出 `firstUnit` 变成 1，却按
 * **内容行高**折算 —— 同一段滚动前后分属两种比例，进度在边界处跳变。这里统一成像素口径：
 *
 * - 头高 ÷ 内容行高 = 头折算的单位数，与内容行一起进总长；
 * - 头未滚出：滚动量 = 头已滚出的像素 ÷ 内容行高（头内偏移也按同一行高连续折算）；
 * - 头已滚出：滚动量 = 头折算的单位数 + 已滚出的内容行数 + 首行内偏移 ÷ 内容行高。
 *
 * 于是「头刚滚出」边界两侧 [scrollFraction] 连续（单测按 ±0.001 断言），
 * [spanPx] 也等于真实可滚像素（内容总高 + 头高 − 视口高）。
 *
 * 参数与 `LazyListState` / `LazyGridState` 的 `layoutInfo` 对齐：
 * - [firstVisibleItemIndex] / [firstVisibleItemScrollOffset]：首个可见项的 `index` 与
 *   `−offset`（即被滚出视口的像素）；
 * - [headerHeightPx]：通栏头实测总高（调用方用 `onSizeChanged` 量）；
 * - [headerItemCount]：通栏头占几个 item（通常 1；多项拼成的头按实际数量传）；
 * - [visibleItemSizesPx]：当前可见项的高度，含头部项，顺序与 `visibleItemsInfo` 一致；
 * - [totalItemCount]：总条目数（`layoutInfo.totalItemsCount`）。建议必传；不传只能按
 *   可见窗口底端估一个下界，进度/拇指长度是近似值；
 * - [viewportHeightPx]：视口高（`layoutInfo.viewportSize.height`）。不传时可见行数按
 *   可见项高度之和估算（末项被视口裁掉时最多高估一行）。
 *
 * 返回 null（都不做除法）：
 * - 可见项里一个正高度的内容项都没有（只剩通栏头，即头高 ≥ 视口高）—— 测不到内容行高；
 * - 传了 [viewportHeightPx] 且 [headerHeightPx] > 视口高 —— 头独占一屏，给不出连续进度；
 * - 可见项列表为空。
 *
 * 没有通栏头（[headerItemCount] ≤ 0，或头高为 0）时退化为 [listScrollGeometry] 的等价结果，
 * 调用方不需要自己分支。
 *
 * 注意：本几何的单位是折叠后的内容行，[seekTarget] 返回的"行号"**含头折算行**；调用方要用
 * [foldedSeekTarget] 把整行号折算回（条目下标, 偏移）——拖动与点击轨道都走这条路径。
 */
internal fun headerScrollGeometry(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    headerHeightPx: Int,
    headerItemCount: Int,
    visibleItemSizesPx: List<Int>,
    totalItemCount: Int = 0,
    viewportHeightPx: Int = 0
): ScrollGeometry? {
    val index = firstVisibleItemIndex.coerceAtLeast(0)
    val offset = firstVisibleItemScrollOffset.coerceAtLeast(0)
    // 没有通栏头（或头高没测到）：直接给现有列表几何的等价结果，调用方不必分支。
    if (headerItemCount <= 0 || headerHeightPx <= 0) {
        return listScrollGeometry(
            totalItems = if (totalItemCount > 0) totalItemCount else index + visibleItemSizesPx.size,
            firstItemIndex = index,
            firstItemTopPx = -offset,
            firstItemHeightPx = visibleItemSizesPx.firstOrNull() ?: 0,
            visibleCount = visibleItemSizesPx.size
        )
    }
    // 通栏头比视口还高：它自己就占满一屏，给不出以内容行折算的连续进度（也绝不会除零）。
    if (viewportHeightPx > 0 && headerHeightPx > viewportHeightPx) return null
    // 可见项里还剩几个头部项：头部可能由多个 item 拼成，也可能已整段滚出。
    val visibleHeaderCount = (headerItemCount - index).coerceIn(0, visibleItemSizesPx.size)
    val contentSizesPx = visibleItemSizesPx.drop(visibleHeaderCount).filter { it > 0 }
    // 内容行高取可见内容项的最大高度：行高均匀时精确，又不会被"末项残缺/细分隔条"带偏。
    // 一个正高度的内容项都没有 ⇒ 只有通栏头可见，测不到行高，返回 null。
    val contentRowPx = contentSizesPx.maxOrNull()?.toFloat() ?: return null
    val headerUnits = headerHeightPx / contentRowPx
    // 头已滚出的像素：头部还有一截在视口里时 = 头总高 − 可见头剩余高；整段滚出时就是头总高。
    val headerScrolledPx = if (visibleHeaderCount > 0) {
        val visibleHeaderPx = visibleItemSizesPx.take(visibleHeaderCount).sum()
        (headerHeightPx - (visibleHeaderPx - offset)).coerceIn(0, headerHeightPx)
    } else {
        headerHeightPx
    }
    // 折叠后的滚动位置（单位 = 内容行）：头内偏移与内容行内偏移按同一个行高连续折算，
    // 于是「头刚滚出」的边界两侧进度严格相接。
    val scrolledUnits = if (visibleHeaderCount > 0) {
        headerScrolledPx / contentRowPx
    } else {
        headerUnits + (index - headerItemCount) + offset / contentRowPx
    }
    // 总长 = 头折算的行数 + 内容行数。没给总条目数时只能把"当前可见窗口的底端"当结尾估
    // 一个下界（进度/拇指是近似值），调用方应尽量传 layoutInfo.totalItemsCount。
    val contentItemsTotal = if (totalItemCount > 0) {
        (totalItemCount - headerItemCount).coerceAtLeast(0)
    } else {
        (visibleItemSizesPx.size - visibleHeaderCount).coerceAtLeast(0)
    }
    val totalUnits = (headerUnits + contentItemsTotal).roundToInt().coerceAtLeast(1)
    // 可见行数 = 视口高 ÷ 行高（精确）。没给视口高时按"可见项像素和 − 首项已滚出"估算，
    // 末项被视口裁掉时会高估（最多一行）。
    val visibleUnits = if (viewportHeightPx > 0) {
        viewportHeightPx / contentRowPx
    } else {
        (visibleItemSizesPx.sum() - offset).toFloat() / contentRowPx
    }.coerceAtLeast(1f)
    // firstUnit 以内容行为轴：0 = 列表最顶部（头未滚出）。
    val firstUnit = floor(scrolledUnits).toInt().coerceIn(0, totalUnits - 1)
    return ScrollGeometry(
        totalUnits = totalUnits,
        visibleUnits = visibleUnits,
        firstUnit = firstUnit,
        unitOffsetPx = (scrolledUnits - firstUnit).coerceIn(0f, 1f) * contentRowPx,
        unitSizePx = contentRowPx,
        itemsPerUnit = 1
    )
}
