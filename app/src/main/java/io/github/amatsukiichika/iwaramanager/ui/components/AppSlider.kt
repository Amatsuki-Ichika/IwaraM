package io.github.amatsukiichika.iwaramanager.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全 app 唯一的滑动条实现，参照系统亮度滑条：粗圆角轨道 + 白色圆点。
 * 圆点缩在轨道内部（直径 < 轨道厚度），按下略放大、松手弹簧弹回；
 * 颜色跟随全局主题（已填充 = `primary`，未填充 = 主题中性色）。
 *
 * 三条不变量（几何实现在 [AppSliderGeometry]，单测见 `AppSliderGeometryTest`）：
 *
 * ```
 * ① 白点圆心  ≡  蓝条圆头圆心        （任何档、任何位置，含按下放大过程）
 * ② 白点整圆  ⊂  蓝条圆头整圆 ⊂ 轨道   （半径 6/8dp < 圆头 11dp）
 * ③ 行程端点 = 半个轨道厚（圆头半径）⇒ 白点**永不接触**轨道两端
 * ```
 *
 * 行程 = `[半个轨道厚, 轨道宽 − 半个轨道厚]`，圆心只用名义值（与渲染径无关），
 * 蓝条右端 = `圆心 + 半个轨道厚`；0% 时蓝条恰是一段完整圆，中点位置不变。
 * 值（[value] / `onValueChange`）即时跟手，只有画出来的位置用短弹簧追上
 * （`dampingRatio = 1f, stiffness = 1400f`）；两个动画值只在 `drawBehind` 的 lambda 里读。
 *
 * 交互与 M3 一致：按下即生效（点哪跳哪）、可拖、值域 0f..1f；触控高度 ≥44dp。
 */
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** 轨道厚度（全圆角）。它的**一半**既是蓝条圆头半径，也是**行程端点**（见类注释 ③）。 */
    trackHeight: Dp = 22.dp,
    /**
     * 圆点常态直径（默认 12dp）。必须 ≤ trackHeight − 4dp，否则就不叫"缩在轨道里"了。
     */
    thumbSize: Dp = 12.dp,
    /**
     * 按下 / 拖动时的圆点直径（默认 16dp，仍在轨道内）。
     * 它只影响绘制：行程 / 蓝条长度 / 白点圆心都与它无关（见类注释 ① ②）。
     */
    thumbPressedSize: Dp = 16.dp
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    /** 常态 / 按下直径（px）。 */
    val thumbBasePx = with(density) { thumbSize.toPx() }
    val thumbPressedPx = with(density) { thumbPressedSize.toPx() }
    /** 半个轨道厚 = 蓝条圆头半径 = 行程端点（三条不变量都挂在它上面）。 */
    val capRadiusPx = with(density) { trackHeight.toPx() } / 2f

    /**
     * 渲染直径（动画值：常态 ↔ 按下）。只用于绘制（圆点半径）。
     * 拿 State 而不是 `by` 委托值：它只在绘制相位读（见下面的 `drawBehind`），
     * 写成 `by` 会在组合期解包，按下/松手的每一帧都会重组调用方。
     */
    val thumbPxState = animateFloatAsState(
        targetValue = if (dragging) thumbPressedPx else thumbBasePx,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 700f),
        label = "appSliderThumb"
    )
    /** 显示分数（弹性跟手），见类注释"弹性跟手"。同样只在绘制相位读。 */
    val shownFractionState = animateFloatAsState(
        targetValue = value.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 1f, stiffness = 1400f),
        label = "appSliderFollow"
    )

    val trackShape = RoundedCornerShape(percent = 50)
    val fraction = value.coerceIn(0f, 1f)
    val trackColor = trackSliderTrackColor()
    val fillColor = MaterialTheme.colorScheme.primary

    /** 反解：手指横坐标 → 值（与 [AppSliderGeometry.nominalCenterX] 严格互逆 ⇒ 按下不跳）。 */
    fun fractionOf(x: Float): Float = AppSliderGeometry.fractionAt(widthPx.toFloat(), capRadiusPx, x)

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 触控高度：轨道虽薄，整条都要能按住、拖住。
            .heightIn(min = 44.dp)
            .onSizeChanged { widthPx = it.width }
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    onValueChange(fractionOf(down.position.x))
                    val id = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == id } ?: break
                        if (!change.pressed) break
                        onValueChange(fractionOf(change.position.x))
                        change.consume()
                    }
                    // 松手：圆点弹回常态大小（位置由跟手弹簧自己收尾，不需要额外归零）。
                    dragging = false
                }
            }
    ) {
        // ---- 轨道 / 蓝条 / 白点：全部在绘制相位画（读动画值不重组；见类注释"读法纪律"）----
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(trackHeight)
                .clip(trackShape)
                .drawBehind {
                    val cy = size.height / 2f
                    val thumbR = thumbPxState.value / 2f
                    // 唯一的几何口径：圆心 ≡ 蓝条圆头圆心 ≡ 白点圆心（不变量 ①）
                    val center = AppSliderGeometry.drawCenterX(
                        trackW = widthPx.toFloat(),
                        capRadius = capRadiusPx,
                        thumbRadius = thumbR,
                        fraction = shownFractionState.value
                    )
                    // ① 未填充轨道底（不能直接用 surfaceContainerHighest —— 卡片同色会隐形）
                    drawRect(color = trackColor)
                    // ② 已填充段：右端 = 圆心 + 半个轨道厚 ⇒ 它的圆头圆心正好落在白点圆心上
                    //    （不变量 ①）；端点 = 半个轨道厚 ⇒ 圆头永远完整地画在轨道里（不变量 ③）。
                    drawRoundRect(
                        color = fillColor,
                        size = Size(
                            width = (center + capRadiusPx).coerceIn(0f, size.width),
                            height = size.height
                        ),
                        cornerRadius = CornerRadius(capRadiusPx, capRadiusPx)
                    )
                    // ③ 白点：与 ② 同源圆心、半径更小 ⇒ 永远同心、永远被蓝条包住（不变量 ① ②）
                    drawCircle(color = Color.White, radius = thumbR, center = Offset(center, cy))
                }
        )
    }
}

/**
 * 滑动条的唯一几何口径（纯函数，无 Compose 依赖；单测见 `AppSliderGeometryTest`）。
 *
 * 三条不变量（见 [AppSlider] 类注释）：
 * ① 白点圆心 ≡ 蓝条圆头圆心 —— 两者都取 [drawCenterX] 的同一个返回值；
 * ② 白点整圆 ⊂ 蓝条圆头整圆（`thumbRadius ≤ capRadius`）⊂ 轨道 `[0, trackW]`；
 * ③ 行程端点 = 半个轨道厚（[capRadius]）⇒ 白点永不接触轨道两端
 *   （按下 3dp / 常态 5dp，随渲染半径变化）。
 */
internal object AppSliderGeometry {

    /**
     * 名义圆心（≡ 蓝条圆头圆心），px。
     *
     * 行程 = `[capRadius, trackW − capRadius]` —— 端点取「半个轨道厚」而不是「半个圆点」：
     * 轨道两端是半径 = 半个轨道厚的**半圆**，它的圆心固定在离端头 `capRadius` 处 ⇒
     * 圆点与它同心时，白点离轨道两端至少 `capRadius − thumbRadius`（永不贴边）。
     * 中点不动（`fraction = 0.5` ⇒ `trackW / 2`）。
     */
    fun nominalCenterX(trackW: Float, capRadius: Float, fraction: Float): Float {
        val travel = (trackW - 2f * capRadius).coerceAtLeast(0f)
        return capRadius + travel * fraction.coerceIn(0f, 1f)
    }

    /**
     * 反解：手指横坐标 → 值。与 [nominalCenterX] **严格互逆**（按下不跳、拖动线性）。
     * 轨道比两个圆头还窄（退化）时返回 0。
     */
    fun fractionAt(trackW: Float, capRadius: Float, x: Float): Float {
        val travel = trackW - 2f * capRadius
        if (travel <= 0f) return 0f
        return ((x - capRadius) / travel).coerceIn(0f, 1f)
    }

    /**
     * 绘制圆心 = 名义圆心（按下**不位移**：它只由 [fraction] 决定，与渲染半径无关）。
     *
     * [thumbRadius] 只在"轨道比两个圆头还窄"的退化情形下兜底（正常宽度下夹取**从不生效**，
     * 因为行程端点已经保证 `|center ± thumbRadius| ⊆ [0, trackW]`）。
     */
    fun drawCenterX(trackW: Float, capRadius: Float, thumbRadius: Float, fraction: Float): Float {
        val nominal = nominalCenterX(trackW, capRadius, fraction)
        if (trackW <= 0f) return nominal
        val lo = thumbRadius.coerceAtMost(trackW / 2f)
        val hi = (trackW - lo).coerceAtLeast(lo)
        return nominal.coerceIn(lo, hi)
    }
}

/**
 * 未填充轨道的底色。
 *
 * 不能直接用 `surfaceContainerHighest`：`AppEntryCard` 用的 M3 `Card` 默认容器色正是
 * `surfaceContainerHighest`（本工程 `Theme.kt` 里它 = `lerp(底, 主题色, 0.075f)`），
 * 轨道会与卡片完全同色、整条隐形。
 *
 * 因此再向 `onSurfaceVariant` 推 22% —— 深浅色下都比卡片深/灰一档（看得出"槽"），
 * 又不像 `outline` 那样抢眼；同时跟随主题色。
 */
@Composable
internal fun trackSliderTrackColor(): Color =
    lerp(
        MaterialTheme.colorScheme.surfaceContainerHighest,
        MaterialTheme.colorScheme.onSurfaceVariant,
        0.22f
    )
