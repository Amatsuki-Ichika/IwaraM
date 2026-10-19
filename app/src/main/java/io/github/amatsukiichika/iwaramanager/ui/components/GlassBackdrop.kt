package io.github.amatsukiichika.iwaramanager.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

/**
 * 全 app 唯一的「玻璃背景源 + 玻璃配方」模块。
 *
 * 背景源分两级：视图级 [rememberGlassViewBackdrop] / [glassViewBackdrop]（挂在某个视图上的玻璃）；
 * 内容级 [rememberGlassContentBackdrop] / [glassContentBackdropSource]（底栏，支持冻结录制）。
 * 玻璃配方 [glassBarEffects] / [glassPillEffects] 与可用性判据 [glassBackdropAvailable] 也集中于此。
 *
 * 补底色不变式：页面底色由 Scaffold 的修饰器在内容区之外绘制（`MainActivity` 里
 * `.clip(pageShape).background(colorScheme.background)` + `containerColor = Color.Transparent`），
 * 而玻璃的背景源只录制内容区那棵子树，该子树内没有底色，于是录出的层在卡片缝隙处全是透明像素
 * （alpha = 0）：模糊算法在透明半侧无像素可采，卡片外边缘会残留一条硬线；空白处只剩罩色。
 * 因此录制任何背景源前必须先用 [glassBackdropBaseColor] 铺满该层再录内容；本模块是唯一允许创建
 * 背景源的地方，以保证这条不变式不会被漏掉。
 *
 * 接入方式：要录的子树挂 [glassViewBackdrop]（不能包含消费点自身，否则自采样会致 RenderThread 崩溃），
 * 消费点与源互为兄弟，用 `Modifier.drawBackdrop(backdrop = 源, highlight = null, effects = 配方)` 绘制；
 * 消费点不得位于其源的录制子树内，glow / 描边等边缘装饰在玻璃档下关闭。
 */

/**
 * 玻璃背景源的"补底色"色值，全 app 唯一读取处。
 *
 * 必须与 `MainActivity` 里 Scaffold 那处 `.background(...)` 同源（都是 `colorScheme.background`），
 * 否则玻璃背后会透出一层错色的底。改主题或页面底色时只需改这里和 Scaffold 那一处。
 */
@Composable
fun glassBackdropBaseColor(): Color = MaterialTheme.colorScheme.background

/**
 * 玻璃管线此刻是否可用：材质方案为液态玻璃，且 API 31+（RenderEffect 的下限）。
 * 不满足时所有背景源返回 null，消费点自动退回色罩路径，不会露出未模糊的底。
 */
@Composable
fun glassBackdropAvailable(): Boolean =
    LocalGlassSettings.current.mode.isLiquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/* --------------------------------------------------------------------------------------------
 * 一、视图级背景源（挂在某个视图/滚动内容上）
 * ------------------------------------------------------------------------------------------ */

/**
 * 视图级玻璃背景源：把"挂了这个修饰器的那棵子树"录下来作为玻璃的背景。
 * 只有液态玻璃档 + API 31+ 才返回非 null，其他档返回 null，零录制成本。
 *
 * 录制内容里先铺 [glassBackdropBaseColor]（补底色不变式，见文件头）。
 * 用 [rememberUpdatedState] 读色值，主题切换后录制内容立刻跟随，不会残留旧底色。
 */
@Composable
fun rememberGlassViewBackdrop(): LayerBackdrop? {
    if (!glassBackdropAvailable()) return null
    // 组合期取一次 State；真正取值发生在**绘制相位**（录制 lambda 里），主题变化自动跟随。
    val baseColor = rememberUpdatedState(glassBackdropBaseColor())
    // lambda 必须稳定（remember）—— 否则每帧重组都会造出新的 LayerBackdrop 对象。
    val onDraw: ContentDrawScope.() -> Unit = remember {
        {
            drawRect(color = baseColor.value)
            drawContent()
        }
    }
    return rememberLayerBackdrop(onDraw = onDraw)
}

/**
 * 把 [rememberGlassViewBackdrop] 的源挂到"要录的那棵子树"上（唯一挂载入口）。
 * null 时是空修饰器 ⇒ 调用点可以无条件写。
 */
fun Modifier.glassViewBackdrop(backdrop: LayerBackdrop?): Modifier =
    then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)

/* --------------------------------------------------------------------------------------------
 * 二、内容级背景源（底栏专用：需要"拉伸冻结"）
 * ------------------------------------------------------------------------------------------ */

/**
 * 主内容液态玻璃的自有背景源：实现库的 [Backdrop] 接口，把自有离屏层 + 绘制相位录制门接回库的
 * blur/lens 消费管线。
 *
 * 底栏不能用库的 `Modifier.layerBackdrop`：库在摘掉提供方修饰器后会把层坐标置空，消费端随即早退、
 * 什么都不画（并非保留上一帧）。而拉伸过滚动期间必须停录，库又没有暂停 API，只能摘修饰器，结果就是
 * 拉伸后底栏材质整个消失、只剩遮罩。改为自有层后，录制门在绘制相位判定
 * （[glassContentBackdropSource] 里 hold 时跳过 record），层引用永不摘下，冻结期间消费端继续采样上一帧。
 *
 * 效果链（blur/lens）由消费端施加在它自己的加垫记录层上，与本源的实现无关
 * （DrawBackdropNode 的记录块只是调用 [Backdrop.drawBackdrop] 把源画进消费端层，再对整层套 RenderEffect），
 * 所以本类换成自有层不影响玻璃质感。
 *
 * `layerBlock` 参数有意忽略：库只把它转发给 [Backdrop.drawBackdrop] 的末位参数，在 `LayerBackdrop`
 * 里当反向补偿变换用，而节点自己的层由库的 `layoutLayerBlock` 布置、从不调用它。形变必须挂外层
 * `Modifier.graphicsLayer{}`（见 `TabGlassBar`），传到这里无效。
 */
class MainContentBackdrop : Backdrop {

    /** 主内容的离屏录制层（由 [rememberGlassContentBackdrop] 注入 `rememberGraphicsLayer()`）。 */
    var layer: GraphicsLayer? = null

    /** 主内容 Box 的布局坐标（源坐标系；由 [glassContentBackdropSource] 注入）。 */
    var sourceCoords: LayoutCoordinates? = null

    /** 源与消费端是两棵不同子树、位置随让位/横滑变化 ⇒ 必须坐标映射。 */
    override val isCoordinatesDependent: Boolean get() = true

    // 签名照编译器给出的接口实态（DrawScope 扩展 + coordinates 可空 + layerBlock 可空带默认）。
    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val layer = layer ?: return
        val source = sourceCoords ?: return
        val consumer = coordinates ?: return
        if (!source.isAttached || !consumer.isAttached) return
        // localPositionOf 走两节点的共同祖先，自动排除祖先的位移动画（让位/进出场横滑）——
        // "玻璃相对内容层"的位置本来就恒定。
        val offset = source.localPositionOf(consumer, Offset.Zero)
        translate(-offset.x, -offset.y) {
            drawLayer(layer)
        }
    }
}

/** 创建内容级背景源（内部持有它自己的 `GraphicsLayer`）—— 底栏唯一的创建入口。 */
@Composable
fun rememberGlassContentBackdrop(): MainContentBackdrop {
    val layer = rememberGraphicsLayer()
    val source = remember { MainContentBackdrop() }
    // 组合期直接挂（普通 var 不触发重组）；sourceCoords 由下面修饰器的 onGloballyPositioned 注入。
    source.layer = layer
    return source
}

/**
 * 把 [source] 挂到要录的那棵子树上（内容级唯一入口）：录制门在绘制相位读，
 * [record] 里读到的快照变化只触发重绘、不重组宿主；`record()` 为 false 时跳过录制、
 * 层保留上一帧，消费端继续采样旧帧，即"冻结"。
 *
 * 录制内容里先铺 [glassBackdropBaseColor]（补底色不变式，见文件头）。
 */
@Composable
fun Modifier.glassContentBackdropSource(
    source: MainContentBackdrop,
    record: () -> Boolean
): Modifier {
    val baseColor = rememberUpdatedState(glassBackdropBaseColor())
    return this
        .onGloballyPositioned { source.sourceCoords = it }
        .drawWithContent {
            val layer = source.layer
            if (record() && layer != null) {
                layer.record {
                    // 补底色：见文件头的补底色不变式。
                    drawRect(color = baseColor.value)
                    this@drawWithContent.drawContent()
                }
                drawLayer(layer)
            } else {
                drawContent()
            }
        }
}

/* --------------------------------------------------------------------------------------------
 * 三、玻璃配方（全 app 唯一处 —— 想调模糊/折射只改这里）
 * ------------------------------------------------------------------------------------------ */

/**
 * 底栏的玻璃配方（唯一）。只有模糊、没有 lens：库内嵌 AGSL（`LensKt`）里 lens 只在距形状边界
 * `refractionHeight` 以内的折射带做位移采样，带外 `return content.eval(coord)` 纯透传，
 * 因此它天生只影响两端/边缘，不改变栏中间的观感。需要"液态"感时可以在这里加回 `lens(...)`。
 *
 * @param blurPx 模糊半径（像素）。默认值/档位在 `GlassMaterial`（液态玻璃档 = `material.blurRadius`）。
 */
fun BackdropEffectScope.glassBarEffects(blurPx: Float) {
    blur(blurPx)
}

/**
 * **胶囊 / 回顶按钮**的玻璃配方（唯一）：小半径模糊 + 轻折射
 * （3dp 折射带 / 8dp 位移 —— 按钮只有 44dp，参数比底栏小一档）。
 */
fun BackdropEffectScope.glassPillEffects() {
    blur(14f)
    lens(3.dp.toPx(), 8.dp.toPx())
}

/* --------------------------------------------------------------------------------------------
 * 四、顶栏玻璃（本地页 / Iwara 页 / 播放页的「上面那一块」）
 * ------------------------------------------------------------------------------------------ */

/**
 * 顶栏块的两条过渡带。
 *
 * ```
 *   实心 100% ──┐                                  顶栏（状态栏 + 标题栏）
 *               └── 过渡一 20dp ──┐
 *                                 └── 药丸到搜索栏整块 = 全局不透明度（= 外观页「材质不透明度」）
 *                                    └── 过渡二 16dp ──┐
 *                                                      └── 0（材质到此为止，卡片从这里"干净"）
 * ```
 *
 * - [TOPBAND_SOLID_TO_BODY]（过渡一）：从实心底边（`solidTop`）往下这一段里，浓度由 100% 缓动降到
 *   全局不透明度，只动罩色，材质本身不遮。
 * - [TOPBAND_BODY_TO_EDGE]（过渡二）：材质盒在内容盒下方多铺这一段，在此段把材质淡到 0
 *   （底边不再有硬边，卡片滚上来是"缓缓浮现"）。必须"多铺"，否则这段落在搜索框背后被控件挡住，
 *   肉眼看不出过渡。
 *
 * 浓度只由罩色表达、body 一格不遮（与底栏同一做法），液态玻璃即真模糊；只有过渡二那一段在库自己的
 * 录制层里擦（`onDrawBackdrop` + `DstIn`），于是整条材质 = 一次 drawBackdrop、单层、无嵌套离屏，
 * 与底栏完全同构。
 */
private val TOPBAND_SOLID_TO_BODY = 20.dp

/**
 * 过渡二（最底下那条）的长度。
 *
 * 材质盒在内容盒下方多铺这一段、并在这段里淡到 0。
 * 同时决定 `bandExtend`（材质盒向下内扩的那一段）与遮罩长度，改这一个数两处一起变，
 * 罩色与遮罩不会对不上（[bandFadeStops] 共用同一份形状）。
 */
private val TOPBAND_BODY_TO_EDGE = 16.dp

/** 渐变停靠点夹取用的极小步长（`Brush` 的停靠点必须严格递增）。 */
private const val BAND_EPS = 1e-4f

/**
 * 过渡带的缓入缓出（smoothstep）。
 *
 * 线性剖面在带的起点/终点处斜率由 0 突变成 1，眼睛读得出"一条带的起边"（Mach band）；过渡二的落差
 * 是全局不透明度 → 0（默认 78%），比过渡一的 100% → 78% 大 3.5 倍，因此同样生硬时下面那条更显眼。
 * 缓动两端一阶导为 0，两端都没有可辨的折线，代价是中段略陡（1.5× 线性），所以两条带各加长一档
 * （16→20dp / 24→32dp）把中段斜率拉回去。若要更软只改上面两个常量。
 *
 * 罩色与遮罩目前同几何：`onDrawBackdrop` 由库先 `canvas.translate(padding, padding)` 再调用，
 * 坐标即节点盒坐标；单条 `blur` 时 `BlurKt` 因 `renderEffect == null` 不抬 padding，录制层尺寸
 * 等于节点盒尺寸，`onDrawSurface` 的 `size.height` 等于 `nodeHeightPx`，故盒底确实淡到 0、没有硬切。
 * 但这条等式是巧合而非不变式：`glassBarEffects` 一旦变成两条以上效果（如加回 `lens`），`BlurKt`
 * 会把 padding 抬到半径，录制层比节点盒四周各大一圈，`onDrawSurface` 的坐标随之相对节点盒偏移
 * `(p, p)`、高度多 `2p`，那时的罩色要先 `translate(p, p)`、剖面按 `nodeHeightPx` 算
 * （`p = (size.height − nodeHeightPx) / 2`）。改效果链前先看这段。
 */
internal fun bandEase(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** 缓动的细分段数（每段 ≈ 4px @3x，肉眼看不出折线；再多只是白付绘制成本）。 */
private const val BAND_EASE_STEPS = 8

/**
 * 过渡带内的保留比例停靠点：`t ∈ 0..1 → 1 → 0`（S 形，两端一阶导为 0）。
 *
 * 罩色（[topBandStops]）与遮罩（[GlassTopBar] 的 `onDrawBackdrop` 里那条 `DstIn`）共用这一份形状；
 * 两份一旦不一致，材质与罩色会各淡各的、中间露出一段。
 */
internal fun bandFadeStops(): List<Pair<Float, Float>> {
    val n = BAND_EASE_STEPS
    return (0..n).map { i ->
        val t = i / n.toFloat()
        t to (1f - bandEase(t))
    }
}

/**
 * 顶栏块的浓度剖面（纯函数，单测见 `GlassTopBandTest`）。
 *
 * 返回 `(位置比例, 浓度)` 列表（位置 0..1 严格递增）：
 * `100%（实心）` → `solidTop` → 过渡一 → `bodyAlpha`（全局不透明度）→（可选）过渡二 → `0`。
 * 两条带内各插入 [bandFadeStops] 的中间停靠点（缓动，见 [bandEase]）。
 *
 * @param heightPx 材质盒高度（含下方为过渡二多铺的那一段）
 * @param solidTopPx 实心段高度（= 状态栏 + App 顶栏那一格）
 * @param bandSolidPx 过渡一长度
 * @param bandEdgePx 过渡二长度（只决定"从多高处开始淡"；材质盒的内扩在 `GlassTopBar` 里做）
 * @param bodyAlpha 全局不透明度（材质罩色的 alpha，0..1）
 * @param fadeEdge 是否做底边淡出（`fadeBottom = false` 的「我的」tab 不做）
 */
internal fun topBandStops(
    heightPx: Float,
    solidTopPx: Float,
    bandSolidPx: Float,
    bandEdgePx: Float,
    bodyAlpha: Float,
    fadeEdge: Boolean
): List<Pair<Float, Float>> {
    if (heightPx <= 0f) return emptyList()
    val body = bodyAlpha.coerceIn(0f, 1f)
    // 「我的」tab 这种"实心段就吃掉整条"的情形：整条实心。
    if (solidTopPx >= heightPx - 0.5f) return listOf(0f to 1f, 1f to 1f)
    val fr = { y: Float -> (y / heightPx).coerceIn(0f, 1f) }
    val p1 = fr(solidTopPx).coerceIn(0f, 0.99f)
    val p2 = fr(solidTopPx + bandSolidPx).coerceIn(p1 + BAND_EPS, 0.994f)
    val out = mutableListOf(0f to 1f, p1 to 1f)
    // 过渡一：实心 → 全局不透明度（缓存动的**镜像**：`1 + (body − 1) × ease(u)`）。
    bandFadeStops().drop(1).dropLast(1).forEach { (u, keep) ->
        out += (p1 + (p2 - p1) * u) to (1f + (body - 1f) * (1f - keep))
    }
    out += p2 to body
    if (fadeEdge) {
        val p3 = fr(heightPx - bandEdgePx).coerceIn(p2 + BAND_EPS, 0.996f)
        out += p3 to body
        // 过渡二：全局不透明度 → 0（缓动的**正向**：`body × keep(u)`；末点 keep(1) = 0）。
        bandFadeStops().drop(1).forEach { (u, keep) ->
            out += (p3 + (1f - p3) * u) to (body * keep)
        }
    } else {
        out += 1f to body
    }
    return out
}

/**
 * 悬浮顶栏：与底栏同一套材质，内容从它底下穿过。
 *
 * 顶栏若留在页面流里（网格在它下方、不穿过它），单纯改透明度看不出差别，所以做成浮在内容之上的
 * 半透明磨砂条，滚动时卡片从它底下经过并被糊掉。
 *
 * 结构（必须照此，否则会自采样崩溃或糊到空白）：
 * ```kotlin
 * val top = rememberGlassViewBackdrop()                              // ① 先建内容那一层的源
 * Box {
 *     Box(Modifier.fillMaxSize().glassViewBackdrop(top)) { 滚动内容 }  // ② 源挂在内容上
 *     GlassTopBar(backdrop = top) { 顶栏内容 }                        // ③ 条是源的兄弟节点
 * }
 * ```
 * 录制时先铺 `glassBackdropBaseColor()`（补底色不变式，见文件头）。
 *
 * 块内浓度：实心 →（20dp 过渡一）→ 全局不透明度 →（16dp 过渡二）→ 0，
 * 两条带内均为缓动剖面（见 [bandEase] / [bandFadeStops]）。
 * - 材质**只画一次** `drawBackdrop`（单层、无嵌套离屏）；
 * - 罩色走 [topBandStops]：液体档在 `onDrawSurface` 画渐变，无背景层的半透明档走
 *   `Modifier.drawBehind` 画同一份停靠点；
 * - 底边淡出（过渡二）在**库自己的录制层里**做（`onDrawBackdrop` + `DstIn`）：只擦最后
 *   [TOPBAND_BODY_TO_EDGE]（含 padding 外沿），**body 一格不碰** —— 遮罩与罩色**共用
 *   [bandFadeStops] 这一份形状**（两份不一致 = 中间露出一段）；
 * - 「关」档（实心实底）不参与过渡；`solidTop = 0`（播放页那条药丸条）不做过渡、
 *   底边按 16dp 渐隐。
 *
 * 缝隙点击：本块没有 pointer input 时，Compose 命中测试不按遮挡裁剪、只按几何 + 层级收集节点，
 * 空白缝隙处的点击会一路穿到底下的网格被卡片接走，所以内容层挂
 * `pointerInput{ detectTapGestures{} }` 吞掉 down/up、放行拖动（详见那处注释）。
 *
 * 调用方须做两件事：把本条实测高度喂给内容的 `contentPadding(top)`（注意是 contentPadding，
 * 不是给容器加 padding，后者会把视口整体推到条下面、卡片不再从底下穿过）；需要"内容从底下穿过"的
 * 观感时材质档位至少选到「半透明」。
 *
 * 与底栏共用的纪律：
 * - `highlight = null`：库默认的边缘光是条灰线；
 * - `shadow = null`：库默认注入 `Shadow.Default`（半径 24dp、offset (0, 4dp)，画在形状之外），
 *   否则顶栏底边会多出一条压在材质淡出段上的暗带；三个消费点都要显式关（`innerShadow` 默认即 null）；
 * - 效果链只有 `blur`（lens 天生只影响两端，会制造"两端有材质中间没有"）；
 * - `onDrawSurface` 画材质罩，否则浅色内容上看不清字。
 *
 * `fadeBottom`：下面紧贴另一条玻璃（App 顶栏）时传 false，两条拼成一块连续材质，交界处不会叠出
 * 更深的色带；它同时是"是否做过渡二"的总开关，「我的」tab 传 false ⇒ 不扩材质、不擦底、整条实心。
 *
 * `extendTop` / `extendBottom`：只要还是两次独立的 `drawBackdrop`，交界处必然有两条材质面
 * （库的 `drawBackdrop` 无法把材质画到自己布局盒之外），所以把"让位"那一段吃进本条自己的布局盒：
 * `extendTop` 向上、`extendBottom` 向下，内容盒不变（调用方配 `Modifier.offset(y = -extendTop)`）。
 */
@Composable
fun GlassTopBar(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    /**
     * 是否在本条底边做过渡（默认开）。下面紧贴着另一条玻璃时必须传 `false`（见上方说明）；
     * 它同时决定是否做过渡二（「我的」tab 传 false ⇒ 整条实心）。
     */
    fadeBottom: Boolean = true,
    /**
     * 把上边缘之外 `extendTop` 那一段也画成本条的材质（尺寸外扩、内容盒不变）。
     * 用于本条要吃到上面那条的位置、与它拼成一整块（见函数头）。默认 0 表示不扩。
     */
    extendTop: Dp = 0.dp,
    /**
     * 把下边缘之外 `extendBottom` 那一段也画成本条的材质。默认 0 表示不扩。
     * 过渡二要用的那段"多铺"是组件内部按 [TOPBAND_BODY_TO_EDGE] 加的，与此参数无关。
     */
    extendBottom: Dp = 0.dp,
    /**
     * 本条顶部 `solidTop` 那一段做成实心。0 表示不启用（底边 16dp 渐隐）。
     *
     * 取值口径：从本条节点盒的顶边往下量。三个主页面传的值 = `状态栏 + 64dp`（状态栏 + App 顶栏），
     * 因为那两块正是"应该看起来实心"的区域（内容区被 `padding(top = topBarHeight)` 让开了）。
     */
    solidTop: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val settings = LocalGlassSettings.current
    val isLight = cs.surface.luminance() > 0.5f
    val glassActive = backdrop != null &&
        settings.mode.isLiquid &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // 与底栏同一个角色（BAR）：同档同色、同一条降级链（背景层不可用 ⇒ 自动退成色罩）。
    val material = glassMaterialFor(
        role = GlassRole.BAR,
        settings = settings,
        surface = cs.surface,
        dark = !isLight,
        backdropActive = glassActive
    )
    val fadeH = 16.dp
    val density = LocalDensity.current

    // ---- 两条过渡带（见 [TOPBAND_SOLID_TO_BODY] / [TOPBAND_BODY_TO_EDGE]）----
    val solidTopPx = with(density) { solidTop.toPx() }
    val bandSolidPx = with(density) { TOPBAND_SOLID_TO_BODY.toPx() }
    val bandEdgePx = with(density) { TOPBAND_BODY_TO_EDGE.toPx() }
    // 「关」档是实心实底，不参与过渡（否则"实心"两个字就自相矛盾了）；
    // solidTop == 0（播放页那条药丸条）不做过渡。
    val bandActive = solidTopPx > 0f && settings.mode != GlassMode.OFF
    // 过渡二必须"多铺"：材质盒在内容盒下方内扩这一段（否则淡出段藏在搜索框背后、看不见）。
    val bandExtend = if (bandActive && fadeBottom) TOPBAND_BODY_TO_EDGE else 0.dp
    val extendTopPx = with(density) { extendTop.toPx() }.toInt()
    val userExtendBottomPx = with(density) { extendBottom.toPx() }.toInt()
    val extendBottomPx = userExtendBottomPx + with(density) { bandExtend.toPx() }.toInt()
    /**
     * 材质盒实测高度（px）。它目前恒等于 `onDrawSurface`/`onDrawBackdrop` 里的坐标基准（padding = 0，
     * 见 [bandEase] 注释），罩色与遮罩用的就是同一个盒高；遮罩照旧用它算，使那一句不依赖
     * "padding 恰好为 0"这个巧合。
     */
    var nodeHeightPx by remember { mutableIntStateOf(0) }

    /** 罩色（= 材质浓度）剖面，液体档与半透明档共用同一份停靠点。 */
    fun veilBrush(h: Float): Brush {
        val stops = if (bandActive) {
            topBandStops(
                heightPx = h,
                solidTopPx = solidTopPx,
                bandSolidPx = bandSolidPx,
                bandEdgePx = bandEdgePx,
                bodyAlpha = material.fill.alpha,
                fadeEdge = fadeBottom
            )
        } else {
            listOf(0f to material.fill.alpha, 1f to material.fill.alpha)
        }
        return Brush.verticalGradient(
            colorStops = stops.map { (p, a) -> p to material.fill.copy(alpha = a) }.toTypedArray(),
            startY = 0f,
            endY = h.coerceAtLeast(1f)
        )
    }

    // 材质本体（两种档位下画法不同，但都只画这**一次**）。
    val materialPaint: Modifier =
        if (glassActive && backdrop != null) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                highlight = null,
                // 库的默认投影必须显式关掉：drawBackdrop 里 ShadowElement / HighlightElement /
                //   InnerShadowElement 是无条件挂上的三个元素，shadow 默认值 = Shadow.Default
                //   （radius 24dp、offset = (0, radius/6 = 4dp)，画在形状之外），正好落在本条底边下方。
                //   材质在底边淡到 0 而投影照旧压在下面，纵向顺序 = 罩色 → 淡出尾段（几乎没有材质 =
                //   "透明长条"）→ 投影的暗带（"一条线分割"）→ 干净卡片。它钉在栏底、只有液态玻璃档有、
                //   改剖面/长度都不会消失，所以三个消费点（本条 / TabGlassBar / ScrollToTopButton）一起关。
                shadow = null,
                // 配方收敛在 GlassBackdrop 模块（唯一处）；半径取自材质（与底栏同一个来源）。
                effects = { glassBarEffects(material.blurRadius.toPx()) },
                // 过渡二在库自己的录制层里擦：这个 lambda 由库在 `recordLayer` 内调用，且库先
                //   `canvas.translate(padding, padding)`，所以这里的坐标即节点盒坐标（见 [bandEase] 注释）。
                //   只擦最后 [TOPBAND_BODY_TO_EDGE]（含 padding 外沿），body 一格不碰，材质管线与底栏
                //   完全同构（单层、无嵌套离屏、无整体遮罩）。形状用 [bandFadeStops]（与罩色同一份缓动），
                //   两份形状不一致时材质已淡没而罩色还留着（或反之），中间会露出一段。
                onDrawBackdrop = { drawBackdropSource ->
                    drawBackdropSource()
                    if (bandActive && fadeBottom) {
                        val h = nodeHeightPx.toFloat()
                        val h0 = (h - bandEdgePx).coerceAtLeast(0f)
                        if (h > 0f && h0 < size.height) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = bandFadeStops()
                                        .map { (u, keep) -> u to Color.Black.copy(alpha = keep) }
                                        .toTypedArray(),
                                    startY = h0,
                                    endY = h
                                ),
                                topLeft = Offset(0f, h0),
                                size = Size(size.width, size.height - h0),
                                blendMode = BlendMode.DstIn
                            )
                        }
                    }
                },
                // 这里 `size` = 录制层尺寸 = 节点盒尺寸（padding = 0，见 [bandEase] 注释），`veilBrush`
                //   的 0 点正好落在材质盒底边，与上面那条遮罩（`h0 = nodeHeightPx − 带宽` → `nodeHeightPx`）
                //   同一几何。一旦 `glassBarEffects` 变成多效果链（padding 会被抬到半径），必须改成
                //   `translate(pad, pad)` + 剖面按 `nodeHeightPx` 算（公式见 [bandEase] 注释）。
                onDrawSurface = { drawRect(brush = veilBrush(size.height)) }
            )
        } else {
            // 关档 = 实心；半透明档 = 色罩，两种都只画罩色。
            // 有过渡带时画渐变罩（实心 → 整块全局浓度 → 底边淡出），否则照旧一层纯色。
            if (bandActive) {
                Modifier.drawBehind { drawRect(brush = veilBrush(size.height)) }
            } else {
                // 注意：这里不能用 clipToBounds —— 底边那层"渐隐"是画在布局之外的，
                //   裁了就等于什么都没画，"自然过渡"会消失。
                Modifier.background(material.fill)
            }
        }

    Box(modifier.onSizeChanged { nodeHeightPx = it.height }) {
        // ---- 材质层（唯一一块材质；只有它会被渐变/淡出，栏内容不受影响）----
        Box(
            Modifier
                .matchParentSize()
                .then(materialPaint)
                .drawWithContent {
                    drawContent()
                    // 底边 16dp 渐隐：只在没有过渡带的调用点保留（播放页那条药丸条）。
                    // 下面紧跟着另一条玻璃（`fadeBottom = false`）或材质本来就往下铺满（`extendBottom > 0`）
                    // 时不画，否则会在连续材质里人为造一条变淡的带。
                    if (!bandActive && fadeBottom && userExtendBottomPx == 0 && settings.mode != GlassMode.OFF) {
                        val h = size.height
                        val fadePx = fadeH.toPx()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(material.fill, Color.Transparent),
                                startY = h,
                                endY = h + fadePx
                            ),
                            topLeft = Offset(0f, h),
                            size = Size(size.width, fadePx)
                        )
                    }
                }
        )

        // ---- 内容层（标题 / 药丸 / 搜索）----
        // 撑尺寸的 `padding` 在本兄弟上：它把外层 Box 撑大（= 材质盒，含 extendTop 与过渡二的内扩
        //   那一段），材质层用 `matchParentSize()` 跟着同一个尺寸，材质铺满整个材质盒。
        //   调用方仍要配合传 `Modifier.offset(y = -extendTop)`，抵消 padding 带来的内容下移，
        //   使内容位置不变、材质向外多铺 extendTop。
        // `pointerInput{ detectTapGestures{} }` 吃住本块空白缝隙里的点击：
        //   ① 命中顺序 = 子节点先于祖先、前景兄弟先于背景兄弟，药丸段/搜索框/联想行/输入框都在本条
        //      之前被派发，自己的点击不受影响；
        //   ② `detectTapGestures` 只吞 down/up、放行拖动，从缝隙起手照样能滑列表；
        //   ③ down 已被本条消费，后面那棵兄弟子树（网格）里的卡片 `clickable` 不再触发。
        //   这里只封本块：`extendTop`（状态栏 + App 顶栏那一格）与底部过渡带保持穿透，
        //   过渡带里的卡片仍能点开；要连它们一起封就把本条挂到外层 Box 上。
        Box(
            Modifier
                .padding(top = extendTop, bottom = extendBottom + bandExtend)
                .pointerInput(Unit) { detectTapGestures { } }
        ) { content() }
    }
}
