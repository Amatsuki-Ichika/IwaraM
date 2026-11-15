package io.github.amatsukiichika.iwaram.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 分段标记：label 为展示文本（如字母 A、月份、数量档位），firstIndex 为该段首项下标。 */
data class SectionMarker(val label: String, val firstIndex: Int)

/**
 * 通用右侧快捷滚动条 / 分段索引（各模式共用，样式统一）：
 * - 纵向等距排布分段标记，当前段高亮；
 * - 拖动时居中弹出气泡提示当前段，拖动中列表跟手：手指经过哪一段就滚到那一段首项；
 * - 点击段标记跳转到该段。
 *
 * 当前下标收 **lambda**，当前段标签经 derivedStateOf 派生：滚动时下标每个条目步进都在变，
 * 但"当前段"只在**跨段**那一刻才变；高亮再由每颗 [SectionMarkerCell] 各自订阅（只在自身
 * isCurrent 翻转时收到通知），跨段时只有新旧两颗重组成色，其余全部跳过。若在组合相位直接读
 * currentIndex，滚动时整条分段栏（全部 marker）每个条目步进都全量重组一轮。
 */
@Composable
fun FastScrollbar(
    markers: List<SectionMarker>,
    currentIndex: () -> Int,
    onScrollToIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 拖动开始/结束回调：供页面把「正在拖滚动条」并入玻璃背景冻结判据。null = 不关心。 */
    onDraggingChange: ((Boolean) -> Unit)? = null
) {
    if (markers.size < 2) return

    var dragging by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(-1) }
    // 已经下发过滚动请求的段号（拖动中同段不重复跳；普通数组持有，避免多一轮重组）。
    val appliedIndex = remember { intArrayOf(-1) }
    var barHeightPx by remember { mutableFloatStateOf(0f) }

    // 当前段标签：下标每步进都重算命中，但只有标签值（跨段）变化才通知读者重组。
    val currentLabelState = remember(markers) {
        derivedStateOf {
            markers.lastOrNull { it.firstIndex <= currentIndex() }?.label
                ?: markers.first().label
        }
    }
    val showText = markers.size <= 30

    fun idxFromY(y: Float): Int {
        if (barHeightPx <= 0f) return 0
        return ((y / barHeightPx) * markers.size).toInt().coerceIn(0, markers.size - 1)
    }

    /** 拖动中列表跟手：跨到新段就把列表滚到该段首项（分段索引没有连续比例，按段步进）。 */
    fun applyIndex(index: Int) {
        if (index !in markers.indices || index == appliedIndex[0]) return
        appliedIndex[0] = index
        onScrollToIndex(markers[index].firstIndex)
    }

    Box(modifier = modifier) {
        // 拖动时的居中气泡提示（气泡本体抽成 ScrollBubble，与连续滚动条共用）。
        // label 收 lambda：拖动每帧只重组气泡内容，不波及整条索引栏。
        ScrollBubble(
            visible = dragging,
            label = {
                if (selectedIndex in markers.indices) {
                    markers[selectedIndex].label
                } else {
                    currentLabelState.value
                }
            },
            modifier = Modifier.align(Alignment.Center)
        )

        // 右侧索引条：细窄、透明，用小字淡色标记，尽量不遮挡内容。
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(16.dp)
                .fillMaxHeight()
                .onSizeChanged { barHeightPx = it.height.toFloat() }
                .pointerInput(markers) {
                    detectVerticalDragGestures(
                        onDragStart = { pos ->
                            dragging = true
                            onDraggingChange?.invoke(true)
                            appliedIndex[0] = -1
                            val idx = idxFromY(pos.y)
                            selectedIndex = idx
                            // 按下即跟手（拖过触摸滑动阈值才走到这里，单纯点按仍归 clickable）。
                            applyIndex(idx)
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val idx = idxFromY(change.position.y)
                            selectedIndex = idx
                            applyIndex(idx)
                        },
                        onDragEnd = {
                            applyIndex(selectedIndex)
                            dragging = false
                            onDraggingChange?.invoke(false)
                            selectedIndex = -1
                            appliedIndex[0] = -1
                        },
                        onDragCancel = {
                            dragging = false
                            onDraggingChange?.invoke(false)
                            selectedIndex = -1
                            appliedIndex[0] = -1
                        }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            markers.forEachIndexed { i, m ->
                SectionMarkerCell(
                    label = m.label,
                    showText = showText,
                    currentLabel = currentLabelState,
                    dragging = { dragging },
                    onClick = { onScrollToIndex(m.firstIndex) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 单颗分段标记：在自己作用域内读当前段/拖动态 —— 跨段时只有新旧两颗重组成色，其余跳过
 * （见 [FastScrollbar] 的注释）。
 */
@Composable
private fun SectionMarkerCell(
    label: String,
    showText: Boolean,
    currentLabel: State<String>,
    dragging: () -> Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCurrent by remember(label) {
        derivedStateOf { !dragging() && currentLabel.value == label }
    }
    Box(
        modifier = modifier
            .width(16.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        if (showText) {
            Text(
                text = label,
                fontSize = 8.sp,
                lineHeight = 8.sp,
                maxLines = 1,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
            )
        } else {
            Box(
                Modifier
                    .size(5.dp)
                    .background(
                        if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        CircleShape
                    )
            )
        }
    }
}

/* ---------- 基础滚动条（无分段，连续比例；触摸放大、空闲淡出） ---------- */

/**
 * 拖动时的居中分节气泡（与 [FastScrollbarBasicWithBubble] 共用同一样式）。
 * [label] 收 lambda：值在气泡内容作用域里读，拖动每帧只重组气泡本体。
 */
@Composable
private fun ScrollBubble(visible: Boolean, label: () -> String, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.8f),
        exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.8f)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Text(
                text = label(),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

/**
 * 基础滚动条核心：细轨道 + 比例拇指，拖拽/点击轨道跳转。
 * 滚动或拖拽时淡入，空闲 1.2s 后淡出；拖拽时拇指加宽变主色。
 *
 * 进度/拇指长度必须放在**绘制相位**（drawBehind）算，不能用 offset(y = …) 之类在**组合相位**
 * 即时重载：progress 直接读 layoutInfo，它**每帧都变**，在组合相位读会让整条滚动条连同
 * animateXxxAsState **每帧重组 + 每帧重新布局**，滚动时掉帧。放绘制相位后滚动时零重组、零重排，
 * 只失效这一小块绘制。
 *
 * 拖动行为分两档：
 * - 给了 [onDragFractionDelta]（列表/网格状态版）：拖动把"这一帧手指比例的增量"交给它，
 *   由它换算成像素后**同步**派发给列表（不经过协程、不取消上一帧增量），所以跟手且连续；
 * - 没给时沿用旧语义：每帧把目标比例交给 [onScrollToFraction] 跳转。
 *
 * 点击轨道始终走 [onScrollToFraction]（一次性跳转）。
 *
 * 松手时把 dragProgress **钉住**在最后一帧的值，直到真实进度追平（差 < 0.002）或 300ms 超时，
 * 才交还绘制权 —— 否则拇指会在松手瞬间跳回列表真实进度，表现为"松手回跳"。
 *
 * @param progress           0f..1f 当前滚动比例（调用方从 state 派生；只在绘制/指针相位读）
 * @param thumbSpanFraction  拇指长度占轨道比例（≈可见单位占总单位，0.05..1）
 * @param isScrolling        是否正在滚动（含惯性），驱动显隐
 * @param onScrollToFraction 点击轨道时回调目标比例（0f..1f）
 * @param onDragProgress     拖动进度回调 —— 拖动中每帧给 0f..1f、结束/取消给
 *                           null，供拖动居中气泡（[FastScrollbarBasicWithBubble]）显示当前分节。
 * @param onDragFractionDelta 拖动中每帧"手指比例增量"（fraction 差值，可正可负）；
 *                           null = 旧跳转语义（每帧按目标比例跳）。
 */
@Composable
fun FastScrollbarBasic(
    progress: () -> Float,
    thumbSpanFraction: () -> Float,
    isScrolling: Boolean,
    onScrollToFraction: (Float) -> Unit,
    canScroll: Boolean = true,
    onDragProgress: ((Float?) -> Unit)? = null,
    onDragFractionDelta: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (!canScroll) return
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    // 松手后"钉住"最后一帧拖动值，等真实进度追上再交还绘制权（杜绝松手回跳）。
    var holdDragProgress by remember { mutableStateOf(false) }
    // 递增令牌：拖动开始/松手都 +1，让"追平等待"协程重启或被取消。
    var holdTick by remember { mutableIntStateOf(0) }
    var showBar by remember { mutableStateOf(false) }
    var barHeightPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isScrolling, dragging) {
        if (isScrolling || dragging) {
            showBar = true
        } else {
            delay(1200)
            showBar = false
        }
    }

    // 松手后的"追平"等待：真实进度与钉住值差 < 0.002 就立刻交还；追不上最多等 300ms，
    // 不会把滚动条永久锁在旧位置。
    LaunchedEffect(holdTick) {
        if (holdTick == 0 || !holdDragProgress) return@LaunchedEffect
        withTimeoutOrNull(300L) {
            snapshotFlow { progress() }.first { abs(it - dragProgress) < 0.002f }
        }
        holdDragProgress = false
    }

    val barAlpha by animateFloatAsState(if (showBar || dragging) 1f else 0f, tween(220), label = "barAlpha")
    val thumbWidth by animateDpAsState(if (dragging) 10.dp else 5.dp, tween(150), label = "thumbWidth")
    val thumbAlpha by animateFloatAsState(if (dragging) 1f else 0.55f, tween(150), label = "thumbAlpha")

    val density = LocalDensity.current
    val minThumbPx = with(density) { 48.dp.toPx() }
    val trackWidthPx = with(density) { 5.dp.toPx() }
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant
    val thumbColor = MaterialTheme.colorScheme.primary

    /** 轨道几何：拇指高 / 最大位移。绘制与命中共用同一套算式 ⇒"看见的"和"按到的"必然一致。 */
    fun thumbMetrics(barPx: Float): Pair<Float, Float> {
        val h = barPx.coerceAtLeast(0f)
        val span = thumbSpanFraction().coerceIn(0.05f, 1f)
        val thumbH = (h * span).coerceAtLeast(minThumbPx).coerceAtMost(h)
        return thumbH to (h - thumbH).coerceAtLeast(0f)
    }

    /** 手指 y → 比例：拇指顶部对齐轨道顶端，故"按住拇指中心"对应 y − 拇指高/2（与绘制同源）。 */
    fun yToProgress(y: Float): Float {
        val (thumbH, maxOffset) = thumbMetrics(barHeightPx)
        if (maxOffset <= 0f) return 0f
        return ((y - thumbH / 2f) / maxOffset).coerceIn(0f, 1f)
    }

    // 上一次推送的手指比例；< 0 = 本次按下还没记起点（按下第一帧只记起点、不发增量）。
    var lastFraction by remember { mutableFloatStateOf(-1f) }

    /** 拖动每帧：给跟手/气泡的回调；按下第一帧由状态版自己判断"只记起点不滚"。 */
    fun pushDragFraction(fraction: Float) {
        if (onDragFractionDelta != null) {
            // 增量 = 本帧比例 − 上帧比例：状态版拿它换算像素并同步滚动。
            //   不在滚动条里直接调 scrollBy（suspend）：拖动时每帧都 launch/取消协程会丢增量 ⇒ 一顿一顿。
            val last = lastFraction
            lastFraction = fraction
            if (last >= 0f) onDragFractionDelta(fraction - last)
        } else {
            onScrollToFraction(fraction)
        }
        onDragProgress?.invoke(fraction)
    }

    /** 结束拖动（松手/取消）：钉住最后一帧值，交给上面的追平协程。 */
    fun finishDrag(keepHold: Boolean) {
        dragging = false
        holdDragProgress = keepHold
        holdTick++
        onDragProgress?.invoke(null)
    }

    Box(
        modifier
            .alpha(barAlpha)
            .width(16.dp)
            .onSizeChanged { barHeightPx = it.height.toFloat() }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { pos ->
                        // 上一轮松手的钉住/等待到此作废。
                        holdDragProgress = false
                        holdTick++
                        dragging = true
                        lastFraction = -1f
                        dragProgress = yToProgress(pos.y)
                        pushDragFraction(dragProgress)
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        dragProgress = yToProgress(change.position.y)
                        pushDragFraction(dragProgress)
                    },
                    onDragEnd = {
                        lastFraction = -1f
                        finishDrag(keepHold = true)
                    },
                    onDragCancel = {
                        lastFraction = -1f
                        finishDrag(keepHold = false)
                    }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    onScrollToFraction(yToProgress(pos.y))
                }
            }
            // 轨道 + 拇指全部在**绘制相位**算：滚动时只失效这一小块绘制，零重组、零重排。
            //   （进度若读进组合相位，而 layoutInfo 每帧都变 ⇒ 整条滚动条每帧重组 + 每帧重新
            //   布局，滚动时掉帧。）
            //   命中与绘制共用 thumbMetrics（同为顶部对齐），所以"看见的"和"按到的"不会错位。
            //   松手后的一小段时间里仍画钉住的 dragProgress（见 finishDrag 的注释）。
            .drawBehind {
                val barH = size.height
                if (barH <= 0f) return@drawBehind
                val (thumbH, maxOffset) = thumbMetrics(barH)
                val shown = (if (dragging || holdDragProgress) dragProgress else progress()).coerceIn(0f, 1f)
                val y = shown * maxOffset
                val trackW = trackWidthPx
                drawRoundRect(
                    color = trackColor.copy(alpha = if (dragging) 0.16f else 0.08f),
                    topLeft = Offset(size.width - trackW, 0f),
                    size = Size(trackW, barH),
                    cornerRadius = CornerRadius(trackW / 2f)
                )
                val thumbW = with(density) { thumbWidth.toPx() }
                drawRoundRect(
                    color = thumbColor.copy(alpha = thumbAlpha),
                    topLeft = Offset(size.width - thumbW, y),
                    size = Size(thumbW, thumbH),
                    cornerRadius = CornerRadius(thumbW / 2f)
                )
            }
    )
}

/**
 * 拖动"比例→像素"的比例尺持有器：按下（第一次拖动事件）时记一次 [ScrollGeometry.spanPx]，
 * 整次拖动都用它换算像素。
 *
 * 为什么不用实时几何：拖动期间 layoutInfo 每帧都变，比例↔像素的关系会跟着漂移（拇指走到哪、
 * 内容跟到哪就对不上），用按下时的快照才是一一对应的。
 */
private class DragPixelScale {
    var spanPx = 0f
        private set

    /** 首次调用时用 [geometry] 采快照，之后返回同一比例尺；几何暂时为空返回 0。 */
    fun resolve(geometry: () -> ScrollGeometry?): Float {
        if (spanPx <= 0f) spanPx = geometry()?.spanPx ?: 0f
        return spanPx
    }

    fun reset() {
        spanPx = 0f
    }
}

/** 拇指长度的死区：变化小于该比例不更新（吸收"半行进出"的小幅抖动）。 */
private const val THUMB_SPAN_DEAD_BAND = 0.004f

/**
 * 比例 →"折叠单位"行号（与 [ScrollGeometry.seekTarget] 的整数部分同轴，但不受 itemsPerUnit 展开影响）。
 */
private fun ScrollGeometry.unitAt(fraction: Float): Int {
    val span = scrollSpanUnits
    if (span <= 0f) return 0
    return floor(fraction.coerceIn(0f, 1f) * span).toInt()
}

/** 内容行数（通栏头条目之外的条目，按列折算成行）。 */
private fun contentRowCount(totalItems: Int, headerItemCount: Int, columns: Int): Int {
    val cols = columns.coerceAtLeast(1)
    val contentItems = (totalItems - headerItemCount).coerceAtLeast(0)
    return (contentItems + cols - 1) / cols
}

/** 通栏头折算成的"单位数"：总单位数 − 内容行/条目数（headerScrollGeometry 的折叠口径）。 */
private fun foldedHeaderUnits(geometry: ScrollGeometry, contentUnits: Int): Int =
    (geometry.totalUnits - contentUnits).coerceAtLeast(0)

/**
 * 网格滚动条几何：有通栏头时把网格**按行合成**后交给 [headerScrollGeometry]（它的单位是行/条目，
 * 直接喂多列网格会把"每行 2 条"当"每单位 1 条"算）；没有通栏头时沿用原来的网格行几何。
 */
private fun LazyGridState.scrollbarGeometry(headerItemCount: Int, headerHeightPx: () -> Int): ScrollGeometry? {
    val li = layoutInfo
    val headerPx = if (headerItemCount > 0) headerHeightPx() else 0
    if (headerItemCount <= 0 || headerPx <= 0) {
        // 无通栏头：保持原网格（按行）几何 —— headerScrollGeometry 的单位是列表条目，
        //   多列网格直接传会把行数算错（进度/拇指都会偏）。
        val first = li.visibleItemsInfo.firstOrNull() ?: return null
        val last = li.visibleItemsInfo.lastOrNull() ?: return null
        return gridScrollGeometry(
            totalItems = li.totalItemsCount,
            firstRow = first.row,
            lastRow = last.row,
            columns = li.visibleItemsInfo.maxOf { it.column } + 1,
            firstRowTopPx = first.offset.y,
            rowHeightPx = first.size.height,
            beforePad = li.beforeContentPadding,
            afterPad = li.afterContentPadding,
            viewportMainAxisPx = li.viewportSize.height
        )
    }
    val visible = li.visibleItemsInfo
    if (visible.isEmpty()) return null
    val columns = (visible.maxOfOrNull { it.column } ?: 0) + 1
    val contentRows = contentRowCount(li.totalItemsCount, headerItemCount, columns)
    // 可见项按"折叠行"分组：通栏头 = 第 0 行，内容第 k 行 = 1 + k。
    //   行高取该行可见条目的最大高度（行高均匀时精确，末行残留条目不会把整行算矮）。
    val rowHeights = LinkedHashMap<Int, Int>()
    var firstRow = -1
    for (v in visible) {
        val row = if (v.index < headerItemCount) 0 else 1 + (v.index - headerItemCount) / columns
        if (firstRow < 0 || row < firstRow) firstRow = row
        val h = if (row == 0) headerPx else v.size.height
        if (h > (rowHeights[row] ?: 0)) rowHeights[row] = h
    }
    if (firstRow < 0) return null
    // scrollOffset：头还在视口里时 = 头被滚出的像素；头已滚出时 = 首内容行的行内偏移。
    //   注意 LazyGridLayoutInfo 不提供 firstVisibleItemScrollOffset（只有 *State 才有），
    //   这里按"首个可见项的 −offset.y"取，语义与 layoutInfo 的 scrollOffset 一致。
    val firstOffset = if (firstRow == 0) {
        val headerItem = visible.firstOrNull { it.index < headerItemCount } ?: return null
        (-headerItem.offset.y).coerceAtLeast(0)
    } else {
        val firstContent = visible.firstOrNull { it.index >= headerItemCount } ?: return null
        (-firstContent.offset.y).coerceAtLeast(0)
    }
    val sizes = ArrayList<Int>(rowHeights.size)
    for (row in rowHeights.keys.sorted()) {
        if (row >= firstRow) sizes.add(rowHeights.getValue(row))
    }
    return headerScrollGeometry(
        firstVisibleItemIndex = firstRow,
        firstVisibleItemScrollOffset = firstOffset,
        headerHeightPx = headerPx,
        // 合成后"通栏头"只占一行：头内若由多个 item 拼成，已按实测总高合并。
        headerItemCount = 1,
        visibleItemSizesPx = sizes,
        totalItemCount = 1 + contentRows,
        viewportHeightPx = li.viewportSize.height
    )
}

/**
 * 列表滚动条几何：直接交给 [headerScrollGeometry]（无通栏头或头高还没量到时，它会自动退化成
 * 等价的列表几何，调用方不必分支）。
 */
private fun LazyListState.scrollbarGeometry(headerItemCount: Int, headerHeightPx: () -> Int): ScrollGeometry? {
    val li = layoutInfo
    // firstVisibleItemIndex / firstVisibleItemScrollOffset 只有 *State 才有（layoutInfo 不提供），
    //   语义与 headerScrollGeometry 的参数一致：首项下标 + 被滚出视口的像素。
    return headerScrollGeometry(
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        headerHeightPx = if (headerItemCount > 0) headerHeightPx() else 0,
        headerItemCount = headerItemCount,
        visibleItemSizesPx = li.visibleItemsInfo.map { it.size },
        totalItemCount = li.totalItemsCount,
        viewportHeightPx = li.viewportSize.height
    )
}

/**
 * 点击轨道：比例 →（条目下标, 条目内像素偏移）。
 * 通栏头几何给的是"折叠行号"，这里先减掉头折算行、再按列展开回条目下标。
 */
private fun LazyGridState.seekFor(
    geometry: ScrollGeometry,
    fraction: Float,
    headerItemCount: Int,
    headerHeightPx: () -> Int
): Pair<Int, Int> {
    val seek = geometry.seekTarget(fraction)
    val headerPx = if (headerItemCount > 0) headerHeightPx() else 0
    if (headerItemCount <= 0 || headerPx <= 0) return seek
    val li = layoutInfo
    val columns = if (li.visibleItemsInfo.isNotEmpty()) li.visibleItemsInfo.maxOf { it.column } + 1 else 1
    val headerUnits = foldedHeaderUnits(geometry, contentRowCount(li.totalItemsCount, headerItemCount, columns))
    val unit = geometry.unitAt(fraction)
    return if (unit < headerUnits) {
        0 to seek.second
    } else {
        (headerItemCount + (unit - headerUnits) * columns) to seek.second
    }
}

/** 列表版 [seekFor]：单位 = 条目，头折算行减掉后直接就是条目下标。 */
private fun LazyListState.seekFor(
    geometry: ScrollGeometry,
    fraction: Float,
    headerItemCount: Int,
    headerHeightPx: () -> Int
): Pair<Int, Int> {
    val seek = geometry.seekTarget(fraction)
    val headerPx = if (headerItemCount > 0) headerHeightPx() else 0
    if (headerItemCount <= 0 || headerPx <= 0) return seek
    val contentItems = (layoutInfo.totalItemsCount - headerItemCount).coerceAtLeast(0)
    val headerUnits = foldedHeaderUnits(geometry, contentItems)
    val unit = geometry.unitAt(fraction)
    return if (unit < headerUnits) {
        0 to seek.second
    } else {
        (headerItemCount + (unit - headerUnits)).coerceAtLeast(0) to seek.second
    }
}

/**
 * LazyGrid 版基础滚动条：进度 / 拇指长度 / 跳转目标全部由 [ScrollGeometry] 的纯函数从 [state] 派生。
 *
 * 拖动走**像素增量滚动**：拖动中只记录上一帧的比例，新比例到来时按按下时的比例尺换成像素。
 * 同一帧内的多份增量由 [FrameDeltaBatch] 合并后只派发一次 dispatchRawDelta（高采样屏一帧会来
 * 好几个 pointer 事件）；按下第一帧先用一次 suspend scrollBy(0f) 抢占滚动 mutex，取消仍在跑的
 * 惯性 fling / 点轨道触发的 scrollToItem —— 否则两股位移叠加，拖动会一顿一顿。
 *
 * @param headerItemCount 通栏头占几个条目（通常 1；0 = 没有通栏头）
 * @param headerHeightPx  通栏头实测高度（px；调用方用 onSizeChanged 量）。0/未量到 = 按无头处理
 */
@Composable
fun FastScrollbarBasic(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    headerItemCount: Int = 0,
    headerHeightPx: () -> Int = { 0 },
    onDragProgress: ((Float?) -> Unit)? = null,
    /** 拖动开始/结束回调：供页面把「正在拖滚动条」并入玻璃背景冻结判据。null = 不关心。 */
    onDraggingChange: ((Boolean) -> Unit)? = null
) {
    // 组合相位只留一个**布尔**（只在"能不能滚"翻转时才重组）。不要在这里 destructure
    //   layoutInfo：它每帧都变，会把整条滚动条连同动画每帧重组一遍。
    val canScroll by remember(headerItemCount) {
        derivedStateOf {
            val li = state.layoutInfo
            li.visibleItemsInfo.isNotEmpty() && li.totalItemsCount > li.visibleItemsInfo.size
        }
    }
    val scope = rememberCoroutineScope()
    // 拖动用的像素比例尺（按下时采一次几何快照）。
    val dragScale = remember { DragPixelScale() }
    // 拖动增量批量器：同一帧内的多份增量合并成一次 dispatchRawDelta。
    val deltaBatch = remember { FrameDeltaBatch() }
    // 本轮拖动是否已抢占过滚动 mutex（见 onDragProgress）。
    val dragStarted = remember { booleanArrayOf(false) }
    // 拇指长度防闪烁：几何瞬空（visibleItemsInfo 短暂为空）时**保持上一帧长度**，
    //   不回退满格（否则拖动时实心部分会闪长闪短）；小幅变化被死区吸收。
    //   持有器是普通数组，只在绘制相位读写，不触发重组/重绘循环。
    val lastSpan = remember { floatArrayOf(1f) }
    // 几何只在绘制/指针相位读（layoutInfo 每帧都变，读进组合相位会每帧重组）。
    fun geometry(): ScrollGeometry? = state.scrollbarGeometry(headerItemCount, headerHeightPx)

    FastScrollbarBasic(
        progress = { geometry()?.scrollFraction() ?: 0f },
        thumbSpanFraction = {
            val raw = geometry()?.thumbSpanFraction()
            when {
                raw == null -> lastSpan[0]
                abs(raw - lastSpan[0]) > THUMB_SPAN_DEAD_BAND -> {
                    lastSpan[0] = raw
                    raw
                }
                else -> lastSpan[0]
            }
        },
        isScrolling = state.isScrollInProgress,
        canScroll = canScroll,
        // 点击轨道：换算回条目下标，一次 scrollToItem 跳到位（离散操作，不逐帧堆积）。
        onScrollToFraction = { f ->
            geometry()?.let { g ->
                val (index, offsetPx) = state.seekFor(g, f, headerItemCount, headerHeightPx)
                scope.launch { state.scrollToItem(index, offsetPx) }
            }
        },
        // 拖动：把"手指比例增量"按按下时的比例尺换成像素，同一帧只派发一次。
        //   不走 suspend 的 scrollBy：逐帧 launch/取消协程会丢增量 ⇒ 拖动一顿一顿；
        //   但每份增量都同步 dispatchRawDelta 又会让一帧内做多次滚动布局，所以先用
        //   FrameDeltaBatch 合并，等下一帧开始再一次性派发（列表仍然逐帧跟手）。
        onDragFractionDelta = { fractionDelta ->
            val px = fractionDelta * dragScale.resolve { geometry() }
            if (px.isFinite() && abs(px) > 0.01f && deltaBatch.add(px)) {
                scope.launch {
                    withFrameNanos { }
                    val delta = deltaBatch.drain()
                    if (delta.isFinite() && delta != 0f) state.dispatchRawDelta(delta)
                }
            }
        },
        onDragProgress = { f ->
            if (f == null) {
                dragScale.reset()  // 松手/取消：下次按下重新采快照
                // 把还没派发的最后一小段补上（不丢尾巴），批量器归零。
                val rest = deltaBatch.drain()
                if (rest.isFinite() && rest != 0f) state.dispatchRawDelta(rest)
                dragStarted[0] = false
            } else if (!dragStarted[0]) {
                // 按下第一帧：用 suspend scrollBy(0f) 抢 mutex，取消在途的惯性 fling /
                //   点轨道触发的 scrollToItem 动画。dispatchRawDelta 不经过 mutatorMutex，
                //   不取消的话两股位移叠加会让页面一顿一顿/跳。
                dragStarted[0] = true
                scope.launch { state.scrollBy(0f) }
            }
            onDraggingChange?.invoke(f != null)
            onDragProgress?.invoke(f)
        },
        modifier = modifier
    )
}

/** LazyList 版基础滚动条：同上（单位 = 条目、每单位 1 个条目；通栏头按内容行折算）。 */
@Composable
fun FastScrollbarBasic(
    state: LazyListState,
    modifier: Modifier = Modifier,
    headerItemCount: Int = 0,
    headerHeightPx: () -> Int = { 0 },
    onDragProgress: ((Float?) -> Unit)? = null,
    /** 拖动开始/结束回调：供页面把「正在拖滚动条」并入玻璃背景冻结判据。null = 不关心。 */
    onDraggingChange: ((Boolean) -> Unit)? = null
) {
    val canScroll by remember(headerItemCount) {
        derivedStateOf {
            val li = state.layoutInfo
            li.visibleItemsInfo.isNotEmpty() && li.totalItemsCount > li.visibleItemsInfo.size
        }
    }
    val scope = rememberCoroutineScope()
    val dragScale = remember { DragPixelScale() }
    val deltaBatch = remember { FrameDeltaBatch() }
    val dragStarted = remember { booleanArrayOf(false) }
    // 防闪烁持有器，同 LazyGrid 版（条目高度可变，几何按可见数计，抖动全靠"保持上一帧 + 死区"吸收）。
    val lastSpan = remember { floatArrayOf(1f) }
    fun geometry(): ScrollGeometry? = state.scrollbarGeometry(headerItemCount, headerHeightPx)

    FastScrollbarBasic(
        progress = { geometry()?.scrollFraction() ?: 0f },
        thumbSpanFraction = {
            val raw = geometry()?.thumbSpanFraction()
            when {
                raw == null -> lastSpan[0]
                abs(raw - lastSpan[0]) > THUMB_SPAN_DEAD_BAND -> {
                    lastSpan[0] = raw
                    raw
                }
                else -> lastSpan[0]
            }
        },
        isScrolling = state.isScrollInProgress,
        canScroll = canScroll,
        onScrollToFraction = { f ->
            geometry()?.let { g ->
                val (index, offsetPx) = state.seekFor(g, f, headerItemCount, headerHeightPx)
                scope.launch { state.scrollToItem(index, offsetPx) }
            }
        },
        onDragFractionDelta = { fractionDelta ->
            val px = fractionDelta * dragScale.resolve { geometry() }
            if (px.isFinite() && abs(px) > 0.01f) state.dispatchRawDelta(px)
        },
        onDragProgress = { f ->
            if (f == null) dragScale.reset()
            onDraggingChange?.invoke(f != null)
            onDragProgress?.invoke(f)
        },
        modifier = modifier
    )
}

/**
 * 连续滚动条 + 拖动居中气泡：本地库"全部 / 时间"视图用。
 * 行为 = [FastScrollbarBasic]（拖动实时像素滚动），拖动时屏幕中间显示当前分节文字
 * （样式与分段版逐字一致）；几何瞬空时沿用上一帧文字。
 *
 * @param emptyLabel markers 为空时的气泡文字（作者页把作者名钉在气泡里；null = 不弹气泡）
 */
@Composable
fun FastScrollbarBasicWithBubble(
    state: LazyGridState,
    markers: List<SectionMarker>,
    modifier: Modifier = Modifier,
    headerItemCount: Int = 0,
    headerHeightPx: () -> Int = { 0 },
    emptyLabel: String? = null,
    onDraggingChange: ((Boolean) -> Unit)? = null
) {
    var bubbleLabel by remember { mutableStateOf<String?>(null) }
    Box(modifier) {
        FastScrollbarBasic(
            state = state,
            headerItemCount = headerItemCount,
            headerHeightPx = headerHeightPx,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(horizontal = 2.dp),
            onDragProgress = { fraction ->
                bubbleLabel = when {
                    fraction == null -> null
                    markers.isNotEmpty() -> sectionLabelFor(fraction, state, markers, headerItemCount, headerHeightPx) ?: bubbleLabel
                    else -> emptyLabel
                }
            },
            onDraggingChange = onDraggingChange
        )
        ScrollBubble(
            visible = bubbleLabel != null,
            label = { bubbleLabel.orEmpty() },
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** 列表版：同 [FastScrollbarBasicWithBubble]（LazyListState 无公共接口与 GridState 互通，各写一份）。 */
@Composable
fun FastScrollbarBasicWithBubble(
    state: LazyListState,
    markers: List<SectionMarker>,
    modifier: Modifier = Modifier,
    headerItemCount: Int = 0,
    headerHeightPx: () -> Int = { 0 },
    emptyLabel: String? = null,
    onDraggingChange: ((Boolean) -> Unit)? = null
) {
    var bubbleLabel by remember { mutableStateOf<String?>(null) }
    Box(modifier) {
        FastScrollbarBasic(
            state = state,
            headerItemCount = headerItemCount,
            headerHeightPx = headerHeightPx,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(horizontal = 2.dp),
            onDragProgress = { fraction ->
                bubbleLabel = when {
                    fraction == null -> null
                    markers.isNotEmpty() -> sectionLabelFor(fraction, state, markers, headerItemCount, headerHeightPx) ?: bubbleLabel
                    else -> emptyLabel
                }
            },
            onDraggingChange = onDraggingChange
        )
        ScrollBubble(
            visible = bubbleLabel != null,
            label = { bubbleLabel.orEmpty() },
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** 拖动比例 → 该位置的分节文字（[SectionMarker.firstIndex] 与列表/网格条目下标同轴）。 */
private fun sectionLabelFor(
    fraction: Float,
    state: LazyGridState,
    markers: List<SectionMarker>,
    headerItemCount: Int,
    headerHeightPx: () -> Int
): String? {
    if (markers.isEmpty()) return null
    val geometry = state.scrollbarGeometry(headerItemCount, headerHeightPx) ?: return null
    val itemIndex = state.seekFor(geometry, fraction, headerItemCount, headerHeightPx).first
    return markers.lastOrNull { it.firstIndex <= itemIndex }?.label
}

private fun sectionLabelFor(
    fraction: Float,
    state: LazyListState,
    markers: List<SectionMarker>,
    headerItemCount: Int,
    headerHeightPx: () -> Int
): String? {
    if (markers.isEmpty()) return null
    val geometry = state.scrollbarGeometry(headerItemCount, headerHeightPx) ?: return null
    val itemIndex = state.seekFor(geometry, fraction, headerItemCount, headerHeightPx).first
    return markers.lastOrNull { it.firstIndex <= itemIndex }?.label
}
