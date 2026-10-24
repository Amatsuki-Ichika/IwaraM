package io.github.amatsukiichika.iwaramanager.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.amatsukiichika.iwaramanager.ui.DiagLog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

/** 底栏选项数据：图标**成对**（实心/空心），随选中切换并带过渡。 */
data class GlassTabItem(
    val label: String,
    val filled: ImageVector,
    val outlined: ImageVector
)

/** 某一段的目标位（dp，相对玻璃栏左上角；onGloballyPositioned 采集）。 */
internal data class GlassPillBounds(val x: Dp, val y: Dp, val w: Dp, val h: Dp)

/* ---------- 玻璃几何 ---------- */

/** 玻璃高度**基准**。实际高度 = 基准 × [glassScale]。 */
internal val BAR_HEIGHT_BASE = 72.dp

/** 玻璃左右内缩**基准**（实际值见 [glassSideInset]）。 */
internal val BAR_SIDE_BASE = 22.dp

/** 玻璃距屏幕底（含手势条）的内缩 —— 锚点位于底边的由来。 */
internal val BAR_BOTTOM_INSET = 10.dp

/**
 * 底栏大小 0f..1f → 等比比例 k（0.70~1.00）。
 *
 * 高度与可见宽度**按同一个 k 收**，锚点 = **底边中心**（高度从上方收、左右对称内收），
 * 图标 24dp 与 labelSmall 文字**不变**。k=1 时即基准尺寸（72dp / 22dp）。
 */
internal fun glassScale(sizeValue: Float): Float = 0.70f + 0.30f * sizeValue.coerceIn(0f, 1f)

/**
 * 左右内缩 = 基准 22dp + "可见宽度按 k 收窄"的那一半。
 *
 * 基准可见宽 = 屏宽 − 44dp；收窄后可见宽 = 基准 × k ⇒ 每侧多收 `(1−k) × 基准宽 / 2`。
 * 这样"高度比例"与"宽度比例"严格一致（等比），底边中心始终是锚点。
 */
internal fun glassSideInset(sizeValue: Float, screenWidthDp: Int): Dp {
    val k = glassScale(sizeValue)
    return BAR_SIDE_BASE + ((1f - k) * (screenWidthDp - 44f) / 2f).dp
}


/* ---------- 微反馈物理（纯函数，无 Compose 依赖；单测见 TabGlassBarLogicTest） ---------- */

/** 跟手档位（0f..1f）→ 横向位移系数：拖动 dx 的最大跟踪比例（1.0 档 ≈ 0.12）。 */
internal fun glassTrackingFactor(amount: Float): Float = amount.coerceIn(0f, 1f) * 0.12f

/** 形变档位（0f..1f）→ 挤压峰值：scaleX 最大增量（1.0 档 ≈ 0.05）。 */
internal fun glassDeformPeak(amount: Float): Float = amount.coerceIn(0f, 1f) * 0.05f

/** 原始位移 clamp 到 ±[maxPx]（横竖各调一次；app 的微动上限比手指幅度小一个量级）。 */
internal fun glassClamp(raw: Float, maxPx: Float): Float = raw.coerceIn(-maxPx, maxPx)

/**
 * 拖动水平位移（归一到 [GLASS_STRETCH_SPAN_PX]）→ 挤压量。
 * scaleX = 1 + [glassStretch]，scaleY = 1 − [glassStretch] × 0.6（横向拉伸、纵向压缩）。
 */
internal fun glassStretch(dxFraction: Float, peak: Float): Float =
    abs(dxFraction).coerceAtMost(1f) * peak

/** 形变随位移的归一跨度：|dx| 达到这个像素数即到峰值（≈ 半屏滑动的拇指行程）。 */
internal const val GLASS_STRETCH_SPAN_PX = 300f

/**
 * 分段的内边距常量 —— 与内容 Row 的 padding **同一来源**。
 * 解析式段位（segBounds）与命中测试（hoverSegment 调用处）都从这里推导，
 * 保证"吸附目标 / 命中网格 / 视觉分段"三者逐像素一致，避免各写一份数字漂移。
 * 取值 4dp 与纵向内缩相等，让玻璃栏的圆帽圆心（距栏边 barH/2 = 30.06dp）
 * 与选中药丸的圆帽圆心（内缩 + pillH/2 = 4 + 26.06 = 30.06dp）**重合** = 同心圆
 * （内缩更大时圆心会横向错开，外弧与内药丸弧度不匹配）。
 */
internal val SEGMENT_INSET_DP = 4.dp

/** 分段的纵向内边距（药丸上下各留 4dp，撑满选项高度 —— TG 同款）。 */
internal val SEGMENT_INSET_VERTICAL_DP = 4.dp

/**
 * 解析式段位（**纯函数**，单测见 TabGlassBarLogicTest）。
 *
 * 分段等 weight 均分（Row 水平内边距 [SEGMENT_INSET_DP]、纵向 [SEGMENT_INSET_VERTICAL_DP]），
 * 几何完全确定：bounds[i] = ([SEGMENT_INSET_DP] + i·segW, [SEGMENT_INSET_VERTICAL_DP], segW, barH − 2·[SEGMENT_INSET_VERTICAL_DP])。
 * 不用 onGloballyPositioned 采集：采集点挂在形变 graphicsLayer **之后**，拖动拉伸期间
 * （scale 最高 ≈1.03）采到的坐标被整层缩放污染（右端误差 ~5-8dp）；松手后若不再发生布局
 * pass，污染值残留成吸附目标 —— 表现为"药丸停在正确格子但位置偏"。
 * 宽度来自 onSizeChanged（**尺寸**不受图层变换影响），命中测试同步扣除同一内边距（调用处）。
 */
internal fun segmentBounds(barWidth: Dp, barHeight: Dp, count: Int): List<GlassPillBounds> {
    if (barWidth <= 0.dp || count <= 0) return emptyList()
    val segW = (barWidth - SEGMENT_INSET_DP * 2) / count
    return List(count) { i ->
        GlassPillBounds(
            x = SEGMENT_INSET_DP + segW * i,
            y = SEGMENT_INSET_VERTICAL_DP,
            w = segW,
            h = barHeight - SEGMENT_INSET_VERTICAL_DP * 2
        )
    }
}

/**
 * 拖动时**内容层的滞后上限**（dp）。
 *
 * 刻度上的 scale 本来就在父层（图标/文字会一起缩放），
 * 但只靠等比缩放，肉眼只会觉得"整条放大了一点点" —— 所以再给内容一点**滞后**：
 * 框体先动、里面的东西略跟不上（有上限），这才是"拉扯感"。
 */
internal val CONTENT_LAG_DP = 4.dp

/**
 * 拖动落点 → 命中的分段下标（带 [deadZonePx] 越界死区防抖）。
 * 手指跨过分段边界不足 [deadZonePx] 时**不**切换（保持 [current]），消掉边界处的来回抖动。
 */
internal fun hoverSegment(x: Float, width: Int, count: Int, deadZonePx: Float, current: Int): Int {
    // 非法输入原样返回 current（调用方以 `hit in items.indices` 兜底），不做 clamp 免得伪造出段下标。
    if (count <= 0 || width <= 0) return current
    val segW = width.toFloat() / count
    val idx = (x / segW).toInt().coerceIn(0, count - 1)
    if (idx == current) return current
    val depthInto = min(x - idx * segW, (idx + 1) * segW - x)
    return if (depthInto < deadZonePx) current else idx
}

/**
 * TG 同款悬浮「柔光玻璃」底栏（唯一调用点 IwaraBottomBar）。
 *
 * 玻璃 = surface 半透明（按亮度自动取）+ 上缘高光 + 双层柔光投影；微反馈 = 横竖双向微动
 * （y = x×0.6、声明式 spring 粘滞）+ 微形变（横向拉伸/纵向压缩、底部锚定）；跨段 4dp 死区 +
 * TextHandleMove 触觉（compose-ui 1.7.3 无 SegmentTick）；与 [PillSelector] 不构成重复实现。
 *
 * 单击可切换；拖动中药丸中心连续跟手指（snap、不跳段），松手 spring 吸附到最近段；
 * 选中药丸撑满选项高度（上下只留 4dp）。
 * 几何：高度与左右内缩**等比缩放**（锚点 = 底边中心，图标与文字大小不变），
 * 见 [BAR_HEIGHT_BASE] / [glassScale] / [glassSideInset]；材质来自 [glassMaterialFor]（不写死颜色）；
 * 拖动时内容层带**滞后**（见 [CONTENT_LAG_DP]）。
 *
 * @param backdrop 液态玻璃的背景源（实现库 [com.kyant.backdrop.Backdrop] 接口；宿主创建并将主内容录进自有层）。
 *   null / API<31 → 自动退回半透明；宿主在二级页/播放页在屏时也传 null。
 */
@Composable
fun TabGlassBar(
    items: List<GlassTabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    /** 液态玻璃背景层（宿主创建）。null = 走色罩路径。 */
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val isLight = cs.surface.luminance() > 0.5f
    val shape = RoundedCornerShape(50)
    // 等比缩放（锚点 = 底边中心）—— 高度从上方收、左右对称内收，图标与文字不变。
    // 大小/跟手/形变三根滑条已去掉，固定用 GlassMaterial.kt 的常量。
    val barHeight = BAR_HEIGHT_BASE * glassScale(GLASS_BAR_SIZE)
    val glassActive = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // **材质统一由 GlassMaterial 模块给出**（底色/叠加色相/模糊半径/噪点/上缘受光）。
    //   液态玻璃只在"方案选了它 + API 31+ + 宿主此刻给了背景层"时才算数
    //   （二级页/播放页在屏上时宿主会传 null ⇒ 自动按半透明档画，不会露馅）。
    val glassSettings = LocalGlassSettings.current
    val material = glassMaterialFor(
        role = GlassRole.BAR,
        settings = glassSettings,
        surface = cs.surface,
        dark = !isLight,
        backdropActive = glassActive
    )
    // 模糊半径来自**材质模块**（material.blurRadius）。
    val barBlurDp = material.blurRadius
    // 玻璃管线状态一次性诊断：在设置 › 开发者 › 诊断日志里能看到断在哪一层
    //   （未启用 = 宿主给了 null：降级/二级页在屏/非液态玻璃档/API<31；启用后若被看门狗降级
    //   会有单独一行"连续 3 帧超时"）。同时打印**实际下发到库的像素半径**，便于判断观感异常
    //   是否与半径有关。
    //   ⚠️ CompositionLocal 只能在组合期读 ⇒ 先取成普通值再进 LaunchedEffect（写进去会编译报错）。
    val glassModeLabel = glassSettings.mode.label
    LaunchedEffect(glassActive, barBlurDp, glassModeLabel) {
        val radiusPx = with(density) { barBlurDp.toPx() }
        DiagLog.log(
            "Backdrop",
            if (glassActive) {
                "底栏磨砂管线已启用（API ${Build.VERSION.SDK_INT}；材质=$glassModeLabel；" +
                    "折射已撤；下发 blur=$barBlurDp / ${radiusPx}px）"
            } else {
                "底栏玻璃管线未启用（backdrop=null：二级页在屏 / 拉伸冻结 / 非磨砂档，或 API<31）"
            }
        )
    }


    // 段位用**解析式**（纯函数 [segmentBounds]，宽度来自 onSizeChanged）。
    val barWidthPx = remember { mutableIntStateOf(0) }
    val barWidthDp = with(density) { barWidthPx.intValue.toDp() }
    val segLeftPx = with(density) { SEGMENT_INSET_DP.toPx() }
    val segBounds: List<GlassPillBounds> = segmentBounds(barWidthDp, barHeight, items.size)

    // ---- 长按拖动微反馈状态 ----
    var dragging by remember { mutableStateOf(false) }
    var rawDx by remember { mutableFloatStateOf(0f) }
    var rawDy by remember { mutableFloatStateOf(0f) }
    var hoverIndex by remember { mutableIntStateOf(-1) }
    // 拖动中手指的 x（栏内 px）；NaN = 不在拖动（药丸走吸附位）。
    var fingerX by remember { mutableFloatStateOf(Float.NaN) }

    val maxShiftX = with(density) { 8.dp.toPx() }
    val maxShiftY = with(density) { 5.dp.toPx() }
    val deadZone = with(density) { 4.dp.toPx() }

    // 跟手/回弹共用一条 spring：拖动中目标每帧变 → 追随有迟滞（粘滞感）；松手目标 0 → 回弹归零。
    val followSpec: FiniteAnimationSpec<Float> = if (dragging) {
        spring(dampingRatio = 0.75f, stiffness = 380f)
    } else {
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
    }
    val followX by animateFloatAsState(rawDx, followSpec, label = "glassFollowX")
    val followY by animateFloatAsState(rawDy, followSpec, label = "glassFollowY")

    val kx = glassTrackingFactor(GLASS_BAR_TRACKING)
    val shiftX = glassClamp(followX * kx, maxShiftX)
    val shiftY = glassClamp(followY * (kx * 0.6f), maxShiftY)
    val stretch = glassStretch(followX / GLASS_STRETCH_SPAN_PX, glassDeformPeak(GLASS_BAR_DEFORM))
    // 两层处理 ——
    //   ① 形变峰值较大（GLASS_BAR_DEFORM 0.6，24dp 图标上才看得出来）；
    //      刻度上的 scale 在**父层**，图标/文字/药丸都在里面 ⇒ 本来就会一起拉伸。
    //   ② 再给内容加一点**滞后**：框体先动、里面的东西略微跟不上（有上限，不会看起来错位）
    //      —— 这才是"拉扯感"的来源；只靠等比缩放，肉眼只会觉得"整条放大了一点点"。
    val contentLagPx = with(density) { CONTENT_LAG_DP.toPx() }
    val contentLag = glassClamp(-followX * 0.05f, contentLagPx)

    // 药丸点亮/吸附的目标段：拖动中 = 手指命中的段（死区防抖），松手 = 选中段。
    val pillIndex = if (dragging && hoverIndex >= 0) hoverIndex else selected
    val target = segBounds.getOrNull(pillIndex)
    // 药丸**首次落位不动画** —— segBounds 由 barWidthPx 推导（onSizeChanged
    //   布局后才有），首帧为空 ⇒ 否则药丸会从 0dp 弹到真实段位（冷启动可见）。首次拿到目标位 snap，
    //   之后的切换/拖动吸附照旧 spring。
    var pillPlaced by remember { mutableStateOf(false) }
    SideEffect { if (target != null) pillPlaced = true }
    val pillSpec: FiniteAnimationSpec<Dp> = when {
        dragging -> snap()
        !pillPlaced -> snap()
        else -> spring(dampingRatio = 0.85f, stiffness = 800f)
    }
    val pillW by animateDpAsState(target?.w ?: 0.dp, pillSpec, label = "glassPillW")
    val pillH by animateDpAsState(target?.h ?: 0.dp, pillSpec, label = "glassPillH")
    val pillY by animateDpAsState(target?.y ?: 0.dp, pillSpec, label = "glassPillY")
    // 拖动中药丸**中心连续跟手指**（clamp 在栏内防出圆角）；松手吸附回段位。
    //   夹取的右边界约束是"药丸**整宽** + 边距都在栏内"：`left ≤ barW − pillW − margin`。
    //   若误写成 `coerceIn(margin, barW − half − margin)`（把 `half` 当边距减了一次），
    //   右侧允许溢出的量约为 `half − margin`，表现为左侧"被顶死"、右侧却能拖出去的不对称。
    // px 域计算再转 dp（Dp 只有 coerceAtLeast/AtMost，没有 coerceIn）。
    val pillX: Dp = when {
        dragging && !fingerX.isNaN() && pillW > 0.dp -> with(density) {
            val halfPx = pillW.toPx() / 2
            val marginPx = 2.dp.toPx()
            // 药丸左缘的取值范围：左 ≥ margin，右 ≤ barW − pillW − margin（栏内留 2dp）。
            val maxLeft = (barWidthPx.intValue - pillW.toPx() - marginPx).coerceAtLeast(marginPx)
            (fingerX - halfPx).coerceIn(marginPx, maxLeft).toDp()
        }
        else -> target?.x ?: 0.dp
    }
    val pillXAnimated by animateDpAsState(pillX, pillSpec, label = "glassPillX")

    Box(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 高度随「底栏大小」等比缩放。
                .height(barHeight)
                // 整条微动 + 微形变挂**外层 graphicsLayer**，不能迁进 layerBlock：
                //   库把调用方传入的 `layerBlock` **只**转发给 `Backdrop.drawBackdrop(...)`（在
                //   LayerBackdrop 里当"反向补偿变换"用：glass 自己变形时让背景内容锚住不动）；
                //   节点自己的层由 `layoutLayerBlock` 单独布置，而那里面**只设 clip / shape /
                //   CompositingStrategy.Offscreen**，从头到尾不调用该 lambda ⇒ 迁进去 =
                //   形变彻底消失（MainContentBackdrop 更是直接忽略该参数）。
                //   ⇒ 用外层 graphicsLayer；layerBlock 参数不再传。
                .graphicsLayer {
                    translationX = shiftX
                    translationY = shiftY
                    scaleX = 1f + stretch
                    scaleY = 1f - stretch * 0.6f
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                // 双层柔光投影：远距主色调柔光 + 近距常规黑。
                // 浅色下柔影减半 —— 浅色玻璃贴浅底，投影过重会显脏。
                .shadow(
                    14.dp, shape, clip = false,
                    ambientColor = cs.primary.copy(alpha = if (isLight) 0.10f else 0.18f),
                    spotColor = cs.primary.copy(alpha = if (isLight) 0.12f else 0.22f)
                )
                .shadow(
                    5.dp, shape, clip = false,
                    ambientColor = Color.Black.copy(alpha = if (isLight) 0.14f else 0.24f),
                    spotColor = Color.Black.copy(alpha = if (isLight) 0.16f else 0.28f)
                )
                .clip(shape)
                // 液态玻璃 = AndroidLiquidGlass 的 drawBackdrop ——
                //   库按本节点的位置从主内容背景源采样（坐标换算它自己完成），效果链在
                //   `effects` 里声明；底色/色相仍由下面的 fill 子节点画（与半透明档同一份 material）。
                //   消费点在录制子树**之外**（bottomBar 槽），不会产生自采样绘制环。
                .then(
                    if (glassActive && backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            // highlight 必须显式置 null —— 库默认注入
                            //   Highlight.Default（白 50% + BlendMode.Plus + 45° 方向光的 SDF 边缘光，
                            //   Stroke 沿形状描一圈），会在边缘多出一道灰色描边。
                            highlight = null,
                            // shadow 也必须显式置 null —— 库默认注入 Shadow.Default
                            //   （radius 24dp / offset (0, 4dp)，画在形状之外）。本栏**自己**已经画了
                            //   「双层柔光投影」（见上面 `.shadow(14.dp, …)`）⇒ 不关就是**两层影**
                            //   （顶栏底边多出的"一条线分割"就是这个默认投影；顶栏已同修）。
                            shadow = null,
                            // 效果链配方说明：
                            // ① lens 只在"距形状边界 refractionHeight 以内"的折射带里做位移采样，
                            //    带外（栏的中间）纯透传，因此它只影响两端/上下边缘 ⇒ 撤掉后整条栏为均匀磨砂。
                            // ② blur 提到链首并去掉 vibrancy()：库的 padding 通道假设"第一个吃 padding 的效果
                            //    就是 blur"，且实际可用的消费链是 `blur(...) → lens(...)`、不含 vibrancy。
                            // ③ 半径见 GlassMaterial（8dp）：可用的实测区间约为按钮的 14px。
                            // 效果配方收敛到 `GlassBackdrop` 模块（全 app 唯一处，调模糊/折射只改那里）。
                            effects = { glassBarEffects(barBlurDp.toPx()) }
                        )
                    } else {
                        Modifier
                    }
                )
                .onSizeChanged { barWidthPx.intValue = it.width }
                .pointerInput(items.size) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            rawDx = 0f
                            rawDy = 0f
                            fingerX = offset.x
                            // 命中网格扣掉内边距 —— 视觉分段在 [SEGMENT_INSET_DP, barW − SEGMENT_INSET_DP] 里均分，
                            //   不扣的话每段命中区整体偏移且宽度略胖。
                            hoverIndex = hoverSegment(
                                offset.x - segLeftPx,
                                (barWidthPx.intValue - 2 * segLeftPx).toInt().coerceAtLeast(0),
                                items.size, 0f, -1
                            )
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            rawDx += change.position.x - change.previousPosition.x
                            rawDy += change.position.y - change.previousPosition.y
                            fingerX = change.position.x
                            val hit = hoverSegment(
                                change.position.x - segLeftPx,
                                (barWidthPx.intValue - 2 * segLeftPx).toInt().coerceAtLeast(0),
                                items.size, deadZone, hoverIndex
                            )
                            if (hit != hoverIndex) {
                                hoverIndex = hit
                                // compose-ui 1.7.3 无 SegmentTick，TextHandleMove 同为轻刻度感。
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        },
                        onDragEnd = {
                            val hit = hoverIndex
                            dragging = false
                            hoverIndex = -1
                            rawDx = 0f
                            rawDy = 0f
                            fingerX = Float.NaN
                            if (hit in items.indices) onSelect(hit)
                        },
                        onDragCancel = {
                            dragging = false
                            hoverIndex = -1
                            rawDx = 0f
                            rawDy = 0f
                            fingerX = Float.NaN
                        }
                    )
                }
        ) {
            // ---- 底色 + 叠加色相（全部来自 [glassMaterialFor]）----
            // 液态玻璃档下这层罩画在 drawBackdrop 的折射/模糊结果**之上**
            //   （fill 与半透明档**同值**，两档的区别只剩有没有模糊）。
            Box(
                Modifier
                    .matchParentSize()
                    .drawWithContent {
                        drawRect(color = material.fill)
                        material.tints.forEach { drawRect(color = it) }
                    }
            )
            if (target != null) {
                Box(
                    Modifier
                        .offset(x = pillXAnimated, y = pillY)
                        .size(width = pillW, height = pillH)
                        .clip(RoundedCornerShape(50))
                        .background(cs.primary.copy(alpha = 0.15f))
                )
            }
            // 上下 4dp 内缩：药丸撑满选项高度（TG 同款），段 bounds 即药丸目标位。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    // 内容层**滞后**（框体先动、内容略跟不上）—— 见 [CONTENT_LAG_DP]。
                    //   缩放（形变）由父层统一施加，所以图标/文字/药丸与框体是"一致"地拉伸的；
                    //   这里只额外加一点位移滞后，让"拉扯"看得见。
                    .graphicsLayer { translationX = contentLag }
                    // 内边距走常量 —— segBounds 与命中测试共用同一来源（防数字漂移）。
                    .padding(horizontal = SEGMENT_INSET_DP, vertical = SEGMENT_INSET_VERTICAL_DP),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    val lit = index == (if (dragging && hoverIndex >= 0) hoverIndex else selected)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            // 单击切换。
                            // indication = null —— 默认水波纹在玻璃上就是"扩散噪点"。
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null
                            ) { onSelect(index) }
                    ) {
                        GlassTabIcon(item, selected = lit)
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (lit) cs.primary else cs.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            // **只在浅色**补一道极淡描边 —— 浅玻璃贴浅底需要一条边界才不"糊成一片"；
            //   深色下不画（深色下描边会显出"边缘白线"）。
            //   液态玻璃激活时**不画** —— 玻璃自带折射边界感，再叠一道灰描边会多出灰色边缘线。
            //   画成最后一个子节点 = 压在图标/文字之上，但 1dp 内缘不会碰到内容。
            if (isLight && !glassActive) {
                Box(
                    Modifier
                        .matchParentSize()
                        .border(1.dp, cs.outlineVariant.copy(alpha = 0.5f), shape)
                )
            }
        }
    }
}

/**
 * 底栏 tab 图标：空心（常态）与实心（选中）同位叠放，alpha + scale 过渡 180ms
 * （Crossfade 会拆两层树，两个 Icon + 一个动画值更省）。
 */
@Composable
private fun GlassTabIcon(item: GlassTabItem, selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val progress by animateFloatAsState(if (selected) 1f else 0f, tween(150), label = "glassIcon")
    Box(Modifier.size(24.dp)) {
        Icon(
            item.outlined,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = 1f - progress
                    scaleX = 1f + 0.25f * progress
                    scaleY = 1f + 0.25f * progress
                }
        )
        Icon(
            item.filled,
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = progress
                    scaleX = 0.86f + 0.14f * progress
                    scaleY = 0.86f + 0.14f * progress
                }
        )
    }
}
