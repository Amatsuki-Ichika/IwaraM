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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** 分段标记：label 为展示文本（如字母 A、月份、数量档位），firstIndex 为该段首项下标。 */
data class SectionMarker(val label: String, val firstIndex: Int)

/**
 * 通用右侧快捷滚动条 / 分段索引（各模式共用，样式统一）：
 * - 纵向等距排布分段标记，当前段高亮；
 * - 拖动时居中弹出气泡提示当前段；
 * - 点击或拖动到某段即跳转到对应首项。
 *
 * 当前下标收 **lambda**，当前段标签经 `derivedStateOf` 派生：滚动时下标每个条目步进都在变，
 * 但"当前段"只在**跨段**那一刻才变；高亮再由每颗 [SectionMarkerCell] 各自订阅（只在自身
 * isCurrent 翻转时收到通知），跨段时只有新旧两颗重组成色，其余全部跳过。若在组合相位直接读
 * currentIndex，滚动时整条分段栏（全部 marker）每个条目步进都全量重组一轮。
 */
@Composable
fun FastScrollbar(
    markers: List<SectionMarker>,
    currentIndex: () -> Int,
    onScrollToIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (markers.size < 2) return

    var dragging by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(-1) }
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
                            selectedIndex = idxFromY(pos.y)
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            selectedIndex = idxFromY(change.position.y)
                        },
                        onDragEnd = {
                            if (selectedIndex in markers.indices) {
                                onScrollToIndex(markers[selectedIndex].firstIndex)
                            }
                            dragging = false
                            selectedIndex = -1
                        },
                        onDragCancel = {
                            dragging = false
                            selectedIndex = -1
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
 * 进度/拇指长度必须放在**绘制相位**（`drawBehind`）算，不能用 `offset(y = …)` 之类在**组合相位**
 * 即时重载：`progress` 直接读 `layoutInfo`，它**每帧都变**，在组合相位读会让整条滚动条连同
 * animateXxxAsState **每帧重组 + 每帧重新布局**，滚动时掉帧。放绘制相位后滚动时零重组、零重排，
 * 只失效这一小块绘制。
 *
 * @param progress           0f..1f 当前滚动比例（调用方从 state 派生；只在绘制/指针相位读）
 * @param thumbSpanFraction  拇指长度占轨道比例（≈可见单位占总单位，0.05..1）
 * @param isScrolling        是否正在滚动（含惯性），驱动显隐
 * @param onScrollToFraction 拖拽/点击轨道时回调目标比例（0f..1f）
 * @param onDragProgress     拖动进度回调 —— 拖动中每帧给 0f..1f、结束/取消给
 *                           null，供「拖动居中气泡」([FastScrollbarBasicWithBubble]) 显示当前分节。
 */
@Composable
fun FastScrollbarBasic(
    progress: () -> Float,
    thumbSpanFraction: () -> Float,
    isScrolling: Boolean,
    onScrollToFraction: (Float) -> Unit,
    canScroll: Boolean = true,
    onDragProgress: ((Float?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (!canScroll) return
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
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

    val barAlpha by animateFloatAsState(if (showBar || dragging) 1f else 0f, tween(220), label = "barAlpha")
    val thumbWidth by animateDpAsState(if (dragging) 10.dp else 5.dp, tween(150), label = "thumbWidth")
    val thumbAlpha by animateFloatAsState(if (dragging) 1f else 0.55f, tween(150), label = "thumbAlpha")

    val density = LocalDensity.current
    val minThumbPx = with(density) { 48.dp.toPx() }
    val trackWidthPx = with(density) { 5.dp.toPx() }
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant
    val thumbColor = MaterialTheme.colorScheme.primary

    /** 轨道几何：拇指高 / 最大位移。绘制与命中共用同一套算式 ⇒「看见的」和「按到的」必然一致。 */
    fun thumbMetrics(barPx: Float): Pair<Float, Float> {
        val h = barPx.coerceAtLeast(0f)
        val span = thumbSpanFraction().coerceIn(0.05f, 1f)
        val thumbH = (h * span).coerceAtLeast(minThumbPx).coerceAtMost(h)
        return thumbH to (h - thumbH).coerceAtLeast(0f)
    }

    /** 手指 y → 比例：拇指顶部对齐轨道顶端，故「按住拇指中心」对应 y − 拇指高/2（与绘制同源）。 */
    fun yToProgress(y: Float): Float {
        val (thumbH, maxOffset) = thumbMetrics(barHeightPx)
        if (maxOffset <= 0f) return 0f
        return ((y - thumbH / 2f) / maxOffset).coerceIn(0f, 1f)
    }

    Box(
        modifier
            .alpha(barAlpha)
            .width(16.dp)
            .onSizeChanged { barHeightPx = it.height.toFloat() }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { pos ->
                        dragging = true
                        dragProgress = yToProgress(pos.y)
                        onScrollToFraction(dragProgress)
                        onDragProgress?.invoke(dragProgress)
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        dragProgress = yToProgress(change.position.y)
                        onScrollToFraction(dragProgress)
                        onDragProgress?.invoke(dragProgress)
                    },
                    onDragEnd = {
                        dragging = false
                        onDragProgress?.invoke(null)
                    },
                    onDragCancel = {
                        dragging = false
                        onDragProgress?.invoke(null)
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
            .drawBehind {
                val barH = size.height
                if (barH <= 0f) return@drawBehind
                val (thumbH, maxOffset) = thumbMetrics(barH)
                val shown = (if (dragging) dragProgress else progress()).coerceIn(0f, 1f)
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
 * 拖动跳转的"单协程"队列：把目标 (下标, 行内偏移) 写进一个 MutableState，由 snapshotFlow +
 * collectLatest 消费。
 *
 * 不在 `onScrollToFraction` 里逐帧 `scope.launch { scrollToItem(...) }`：拖动时比例每帧都在变，
 * 逐帧 launch 会堆一串协程、还会跟手指输入互相追打（"一拉就乱跳"）。这里永远只有一条协程，
 * 新目标直接顶掉旧目标。
 */
@Composable
private fun rememberSeekQueue(apply: suspend (Pair<Int, Int>) -> Unit): (Pair<Int, Int>) -> Unit {
    val current by rememberUpdatedState(apply)
    val target = remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { target.value }.filterNotNull().collectLatest { current(it) }
    }
    return remember { { value: Pair<Int, Int> -> target.value = value } }
}

/** 拇指长度的死区：变化小于该比例不更新（吸收"半行进出"的小幅抖动）。 */
private const val THUMB_SPAN_DEAD_BAND = 0.02f

/** LazyGrid 版基础滚动条：进度 / 拇指长度 / 跳转目标全部由 [ScrollGeometry] 的纯函数从 [state] 派生。 */
@Composable
fun FastScrollbarBasic(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    onDragProgress: ((Float?) -> Unit)? = null
) {
    // 组合相位只留一个**布尔**（只在"能不能滚"翻转时才重组）。不要在这里 destructure
    //   layoutInfo：它每帧都变，会把整条滚动条连同动画每帧重组一遍。
    val canScroll by remember {
        derivedStateOf {
            val li = state.layoutInfo
            li.visibleItemsInfo.isNotEmpty() && li.totalItemsCount > li.visibleItemsInfo.size
        }
    }
    val submitSeek = rememberSeekQueue { (index, offsetPx) -> state.scrollToItem(index, offsetPx) }
    // 拇指长度防闪烁：几何瞬空（visibleItemsInfo 短暂为空）时**保持上一帧长度**，
    //   不回退满格（否则拖动时实心部分会闪长闪短）；小幅变化被死区吸收。
    //   持有器是普通数组，只在绘制相位读写，不触发重组/重绘循环。
    val lastSpan = remember { floatArrayOf(1f) }
    FastScrollbarBasic(
        progress = { state.layoutInfo.toScrollGeometry()?.scrollFraction() ?: 0f },
        thumbSpanFraction = {
            val raw = state.layoutInfo.toScrollGeometry()?.thumbSpanFraction()
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
            state.layoutInfo.toScrollGeometry()?.let { submitSeek(it.seekTarget(f)) }
        },
        onDragProgress = onDragProgress,
        modifier = modifier
    )
}

/** LazyList 版基础滚动条：同上（单位 = 条目、每单位 1 个条目）。 */
@Composable
fun FastScrollbarBasic(
    state: LazyListState,
    modifier: Modifier = Modifier,
    onDragProgress: ((Float?) -> Unit)? = null
) {
    val canScroll by remember {
        derivedStateOf {
            val li = state.layoutInfo
            li.visibleItemsInfo.isNotEmpty() && li.totalItemsCount > li.visibleItemsInfo.size
        }
    }
    val submitSeek = rememberSeekQueue { (index, offsetPx) -> state.scrollToItem(index, offsetPx) }
    // 防闪烁持有器，同 LazyGrid 版（列表条目高度可变，几何仍按可见数计，
    //   抖动全靠"保持上一帧 + 死区"吸收）。
    val lastSpan = remember { floatArrayOf(1f) }
    FastScrollbarBasic(
        progress = { state.layoutInfo.toScrollGeometry()?.scrollFraction() ?: 0f },
        thumbSpanFraction = {
            val raw = state.layoutInfo.toScrollGeometry()?.thumbSpanFraction()
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
            state.layoutInfo.toScrollGeometry()?.let { submitSeek(it.seekTarget(f)) }
        },
        onDragProgress = onDragProgress,
        modifier = modifier
    )
}

/**
 * 连续滚动条 + 拖动居中气泡：本地库「全部 / 时间」视图用。
 * 行为 = [FastScrollbarBasic]（拖动实时滚动），拖动时屏幕中间显示当前分节文字
 * （样式与分段版逐字一致）；几何瞬空时沿用上一帧文字。
 */
@Composable
fun FastScrollbarBasicWithBubble(state: LazyGridState, markers: List<SectionMarker>, modifier: Modifier = Modifier) {
    var bubbleLabel by remember { mutableStateOf<String?>(null) }
    Box(modifier) {
        FastScrollbarBasic(
            state = state,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(horizontal = 2.dp),
            onDragProgress = { fraction ->
                bubbleLabel = fraction?.let { sectionLabelFor(fraction, state, markers) ?: bubbleLabel }
            }
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
fun FastScrollbarBasicWithBubble(state: LazyListState, markers: List<SectionMarker>, modifier: Modifier = Modifier) {
    var bubbleLabel by remember { mutableStateOf<String?>(null) }
    Box(modifier) {
        FastScrollbarBasic(
            state = state,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(horizontal = 2.dp),
            onDragProgress = { fraction ->
                bubbleLabel = fraction?.let { sectionLabelFor(fraction, state, markers) ?: bubbleLabel }
            }
        )
        ScrollBubble(
            visible = bubbleLabel != null,
            label = { bubbleLabel.orEmpty() },
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** 拖动比例 → 该位置的分节文字（[SectionMarker.firstIndex] 与列表/网格条目下标同轴）。 */
private fun sectionLabelFor(fraction: Float, state: LazyGridState, markers: List<SectionMarker>): String? {
    if (markers.isEmpty()) return null
    val geometry = state.layoutInfo.toScrollGeometry() ?: return null
    val (index, _) = geometry.seekTarget(fraction)
    return markers.lastOrNull { it.firstIndex <= index }?.label
}

private fun sectionLabelFor(fraction: Float, state: LazyListState, markers: List<SectionMarker>): String? {
    if (markers.isEmpty()) return null
    val geometry = state.layoutInfo.toScrollGeometry() ?: return null
    val (index, _) = geometry.seekTarget(fraction)
    return markers.lastOrNull { it.firstIndex <= index }?.label
}
