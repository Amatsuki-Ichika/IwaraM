package io.github.amatsukiichika.iwaram.ui.browse

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.ui.COVER_DIM_ALPHA
import io.github.amatsukiichika.iwaram.ui.COVER_MOTION_MS
import io.github.amatsukiichika.iwaram.ui.COVER_SHIFT_DP
import io.github.amatsukiichika.iwaram.ui.BackVelocityTracker
import io.github.amatsukiichika.iwaram.ui.VideoViewModel
import io.github.amatsukiichika.iwaram.ui.exitDurationMs
import io.github.amatsukiichika.iwaram.ui.rememberAdaptivePageShape
import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * 进入/退场参数，与 MainActivity 的设置页/播放页覆盖层一致。
 * 退场与进入都**只滑不淡** —— 页面下面压着"栈底黑垫"+ 让位后被压暗的主内容，
 * 一旦页面 alpha 下降就会透出那块黑；进入时底页已**左移 15%**、右边本就露出一条边，
 * 若页面带 200ms 淡入，那段时间它是半透明的，底页右边那条边/黑垫会**透过页面**显示出来。
 * 只滑后页面从第一帧起就是不透明的，从右侧滑入时先盖住右边那条，再往左盖 —— 即"卡片盖上去"的正常观感。
 */
private const val ENTER_SLIDE_MS = 320
private const val EXIT_SLIDE_MS = 320

/** 动作面板（ModalBottomSheet 独立窗口）不参与页面栈渲染。 */
internal fun IwaraOverlay.isActionSheet(): Boolean =
    this is IwaraOverlay.VideoActions || this is IwaraOverlay.ImageActions

/**
 * 覆盖层"仍在屏上"的最上层序号：活栈顶页与退场页里取最大者（null = 完全离场）。
 *
 * 退场页的序号通常更大；"上一页还在退场时又压入新页"则取新页 —— 两者都要参与绘制排序，
 * 只认活栈会让弹出最后一页的那一帧 zIndex 塌到 0（正在滑出的页被播放页盖住）。
 */
internal fun overlayOnScreenTopSeq(liveTopSeq: Long?, leavingTopSeq: Long?): Long? =
    listOfNotNull(liveTopSeq, leavingTopSeq).maxOrNull()

/**
 * Iwara 二级页覆盖层栈的宿主：评论/作者主页/关系列表/播放列表详情/图片/帖子/收藏/播放列表/下载列表/关注/tag 页。
 * 放在根 Box（与设置/播放页同层）全屏盖住顶栏与底栏。栈内所有页常驻组合，返回上一页时是"原地露出"：
 * 滚动位置、已加载数据都还在，也不会重新请求。
 *
 * 过渡动画与设置页/播放页一致，且只有**一个**时序源：同步求差（上一帧栈快照与当前栈在组合期直接做差，
 * 退场页当帧进渲染列表）；退场页由 `AnimatedVisibility` 自己保留，动画跑完（`isIdle && !currentState`）
 * 回调宿主移除；手势完成即 `onPop()`，冻结位移原样留着让该页播标准退场，不自己补间。
 * 一切按 [OverlayEntry.seq] 比对（不用 data class 结构相等）：key = seq，二次进入是全新组合，
 * 避免"参数相同的两页共用组合 key → 层序错乱 / 残留隐藏态导致整页不可见"。
 *
 * 卡片化让位：每页 Surface 用 [rememberAdaptivePageShape] 自适应屏幕物理圆角，且**只在看得见时裁弧**
 * （进出场/跟手/被盖让位中）；被上层盖住的页左移 [COVER_SHIFT_DP] 并压暗 [COVER_DIM_ALPHA]，
 * 跟手时被它盖住的页按 `c = 1 - p` 实时回位/变亮；主界面的让位由 [onBackProgress] 汇报到 MainActivity
 * 统一驱动（栈底由 MainActivity 垫黑，让位/滑动时才露出圆角与黑垫）。
 */
@Composable
fun IwaraOverlayHost(
    overlays: List<OverlayEntry>,
    onPop: () -> Unit,
    cvm: IwaraClientViewModel,
    /** 作者页「视频」栏要读本地库（离线优先），需要本地库的 ViewModel。 */
    vm: VideoViewModel,
    actions: IwaraNavActions,
    /**
     * 预测式返回手势进度：拖动中回调 0f..1f，松手/取消回调 null。
     * 主界面据此**实时**让位/回位（MainActivity 的 cover 计算）。
     */
    onBackProgress: (Float?) -> Unit,
    /**
     * 绘制顺序由"打开序号"决定，宿主的 Box 需要拿到
     * `Modifier.zIndex(rank)` —— 只改绘制顺序、不动调用位置（子树 remember 状态不搬家）。
     */
    modifier: Modifier = Modifier,
    /**
     * 整个栈被**更新的一层**（后打开的播放页/设置页）盖住 → 栈顶页照常让位
     * （左移 15% + 压暗 + 收圆角），与栈内被盖页完全一致。false = 没被外部盖。
     */
    coveredByOther: Boolean = false,
    /** 不是最上层时让出系统返回键（PredictiveBackHandler 后注册者优先）。 */
    backEnabled: Boolean = true,
    /**
     * 退场动画还没播完的顶层页序号（null = 没有退场页）。
     *
     * 调用方据此把退场页继续算作"在屏"：zIndex 与返回键归属都要认它 ——
     * 否则弹出最后一页后、动画结束前的 320ms 里，播放页/设置页会把它盖住（滑出看不见），
     * 第二次返回也会穿透到主页面（清筛选 / 退出 App）。
     */
    onLeavingTopSeqChange: (Long?) -> Unit = {},
    /** 动作面板里的「检查文件完整性」；null = 不显示该行（仅已下载的视频显示）。 */
    onCheckIntegrity: ((IwaraVideoItem) -> Unit)? = null
) {
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    // 让位量用固定 dp（[COVER_SHIFT_DP]，条目卡首字恰被屏缘盖住的口径）。
    val coverShiftPx = with(LocalDensity.current) { COVER_SHIFT_DP.toPx() }

    // ---- 跟手位移（预测式返回）：0f..1f；与设置页/播放页同款，只 snapTo、不做任何补间 ----
    // 手势绑定的层用**序号**表示（null = 没在拖）。
    // 这两个状态**只在绘制相位 / 协程里读**（见下面的 translationFraction / gestureCover 与
    //   OverlayPage 的 graphicsLayer）：组合相位一旦读它们，拖动时每来一个 progress 事件就会
    //   重组整页（作者页头部+吸顶栏、播放页顶底栏全部重跑）；只在绘制相位读，拖动就只剩重绘。
    var gestureSeq by remember { mutableStateOf<Long?>(null) }
    var gestureProgress by remember { mutableFloatStateOf(0f) }
    // 已松手弹栈、但退场动画还在播的页 → 松手瞬间冻结的位移比例。退场期间必须继续按这个位移绘制，
    // 否则会"瞬移回屏幕中央再滑出"；动画播完由 onExited 移除。
    // 用 map（key = seq）而非单个变量：允许上一页还在退场时立刻对下一层发起新手势，互不干扰。
    val frozenGesture = remember { mutableStateMapOf<Long, Float>() }
    // 与 frozenGesture 配对的松手速度（进度/秒）：退场动画按"剩余距离 / 该速度"换算时长、
    // 用快出慢停缓动衔接，消除"松手先停一下"的手感。0 = 没有可用速度（慢松手/非手势退出）。
    val frozenVelocity = remember { mutableStateMapOf<Long, Float>() }

    // ---- 退场页生命周期 ----
    // 上一帧的栈快照：与当前栈**同步**做差（不走 effect），退场页当帧就进渲染列表 → 不闪帧。
    val prevStack = remember { mutableStateOf<List<OverlayEntry>>(emptyList()) }
    val leavingPages = remember { mutableStateListOf<OverlayEntry>() }

    val prev = prevStack.value
    val enteredNow = overlays.filter { e -> prev.none { it.seq == e.seq } && !e.isActionSheet }
    val goneNow = prev.filter { e -> overlays.none { it.seq == e.seq } && !e.isActionSheet }
    for (g in goneNow) {
        if (leavingPages.none { it.seq == g.seq }) leavingPages.add(g)
    }
    leavingPages.removeAll { e -> overlays.any { it.seq == e.seq } }
    SideEffect { prevStack.value = overlays.toList() }

    Box(modifier.fillMaxSize()) {
        // 渲染列表 = 当前栈 + 退场页（动作面板除外）。退场页追加在末尾 → 正好压在露出的页之上
        //（Box 子节点顺序 = 绘制顺序）。
        val live = livePageStack(overlays)
        val leaving = leavingPages.filter { e -> overlays.none { it.seq == e.seq } && !e.isActionSheet }
        val renderList = live + leaving
        // 退场页仍算"在屏"：把最上面那一页的序号报给调用方（绘制顺序与返回键归属要用）。
        SideEffect { onLeavingTopSeqChange(leaving.maxOfOrNull { it.seq }) }
        renderList.forEachIndexed { renderIdx, entry ->
            // 退场页不在活跃栈里 → indexOf 返回 -1，顺带就是 isLeaving 判定。
            val liveIndex = live.indexOfFirst { it.seq == entry.seq }
            val isLeaving = liveIndex < 0
            // 覆盖态**只由真实栈序决定**（纯函数，可单测）。
            //   栈被**外部更新的层**（播放页/设置页）盖住时，栈顶页同样要让位
            //   （`covered && liveIndex == live.lastIndex`）；让位动效由 OverlayPage 内部的声明式
            //   补间给出，与栈内被覆盖是同一套。
            val covered = isCoveredAt(live, liveIndex) ||
                (coveredByOther && liveIndex == live.lastIndex)
            // 跟手位移比例：**只在绘制相位读**（OverlayPage 的 graphicsLayer / 绘制 lambda 里调用）。
            //   返回 lambda 而不是 Float：组合相位不读 gestureProgress，拖动时本页零重组、只重绘。
            //   正在拖动的那层读实时进度；已松手正在退场的那层读冻结值（常量）。
            val translationFraction: () -> Float = if (isLeaving) {
                val frozen = frozenGesture[entry.seq] ?: 0f
                { frozen }
            } else {
                { if (gestureSeq == entry.seq) gestureProgress else 0f }
            }
            // 手势拖动中，被它盖住的页实时让位：c = 1 - p（p 越大越接近原位/原亮度）。
            //   同为 lambda：拖动时只有绘制/动画相位读实时进度。
            val gestureCover: () -> Float? = {
                val gesture = gestureSeq
                if (!isLeaving && gesture != null) {
                    val index = live.indexOfFirst { it.seq == gesture }
                    if (index > liveIndex && index >= 0) 1f - gestureProgress else null
                } else {
                    null
                }
            }
            // 退场规格用的**数值**：冻结位移与松手速度只在开始退场的那一次组合里读一次
            //   （退场动画把冻结位移当起点、按"剩余距离 / 松手速度"换算时长）。
            val exitTranslationFraction = if (isLeaving) frozenGesture[entry.seq] ?: 0f else 0f
            // 手势弹出的页带着松手速度退场（px/s）；按钮关闭 = 0 → 走默认规格。
            val exitVelocityPxPerS =
                if (isLeaving) (frozenVelocity[entry.seq] ?: 0f) * screenWidthPx else 0f

            key(entry.seq) {
                OverlayPage(
                    entry = entry,
                    // 只有"这一帧刚出现在栈里"的页播进入动画；被露出的下层不播。
                    animateEnter = enteredNow.any { it.seq == entry.seq },
                    // 退场一律播标准动画（含手势层）：松手后从冻结位移继续滑出，与设置页/播放页一致。
                    animateExit = isLeaving,
                    gestureTranslationFraction = translationFraction,
                    exitTranslationFraction = exitTranslationFraction,
                    exitVelocityPxPerS = exitVelocityPxPerS,
                    covered = covered,
                    gestureCover = gestureCover,
                    coverShiftPx = coverShiftPx,
                    cvm = cvm,
                    vm = vm,
                    actions = actions,
                    onPop = onPop,
                    // 退场动画播完 → 移出渲染列表并解绑冻结位移（唯一时序源，不依赖定时器）。
                    onExited = {
                        leavingPages.removeAll { it.seq == entry.seq }
                        frozenGesture.remove(entry.seq)
                        frozenVelocity.remove(entry.seq)
                    }
                )
            }
        }
    }

    // 动作面板是独立窗口（ModalBottomSheet），自己接管整层显示与返回：
    //   面板打开时主窗口**不注册**返回（见下面的 enabled），让面板窗口的 OnBackInvokedDispatcher
    //   接管预测返回跟手；这里只负责把面板挂出来，覆盖层栈照常渲染（面板开关不丢栈的动画状态）。
    when (val sheet = overlays.lastOrNull()?.overlay) {
        is IwaraOverlay.VideoActions ->
            VideoActionsSheet(sheet.video, cvm, actions, { onPop() }, sheet.pickPlaylistDirectly, onCheckIntegrity)
        is IwaraOverlay.ImageActions ->
            ImageActionsSheet(sheet.image, cvm, actions) { onPop() }
        else -> Unit
    }

    // 预测式返回（设置页/播放页同款）：跟手右滑；越过阈值松手**立刻**弹栈、不加任何补间，
    // 取消则同步复位。取消路径不含挂起点，避免协程被打断导致状态卡住。
    //   enabled 只认**栈顶是覆盖层页、或它还在播退场动画**：动作面板是独立 Dialog 窗口、自己注册
    //   窗口级预测返回，主窗口再注册就会"以为有主、实际无回调"（或同一手势在两个窗口各拖一遍）；
    //   面板打开时主窗口不注册；被更上层（播放页/设置页）或外部盖住时同样让出。
    val actionSheetOnTop = overlays.lastOrNull()?.isActionSheet == true
    val topPage = overlays.lastOrNull { !it.isActionSheet }
    // 退场页仍算在屏：live 栈已空但退场动画没播完时也注册返回，
    //   把这一下吞掉（no-op）—— 否则第二个返回会交给主页面（清筛选 / 收起搜索）或直接退出 App。
    //   与 MainActivity 用"退场页仍占返回"对齐（见 onLeavingTopSeqChange）。
    val pageStillOnScreen = topPage != null || leavingPages.isNotEmpty()
    PredictiveBackHandler(
        enabled = backEnabled && pageStillOnScreen && !actionSheetOnTop && !coveredByOther
    ) { flow: Flow<BackEventCompat> ->
        // 只有退场页（live 栈已空）：整段手势消费掉，不弹栈、也不把进度传给主内容。
        if (topPage == null) {
            flow.collect { }
            return@PredictiveBackHandler
        }
        var completed = false
        // 每次手势一个新实例：对 progress 事件做时间差分，估算松手瞬间的跟手速度。
        val velocity = BackVelocityTracker()
        try {
            flow.collect { event ->
                // 若上一页还在退场（已不在栈里），本次手势重新绑定到当前栈顶（动作面板不参与）。
                gestureSeq = gestureSeq?.takeIf { s -> overlays.any { it.seq == s } }
                    ?: overlays.lastOrNull { !it.isActionSheet }?.seq
                gestureProgress = event.progress.coerceIn(0f, 1f)
                velocity.onEvent(gestureProgress, System.nanoTime())
                onBackProgress(gestureProgress)
            }
            completed = true
        } finally {
            // 手势状态**无条件释放**。若把复位写在 try 末尾 + catch 分支里，
            //   任何第三条退出路径（协程被取消/被打断）都会让 gestureProgress / 主内容的 cover 卡住
            //   ⇒ "复位不完全、底页位置错位"。finally 里不挂起，安全。
            val target = gestureSeq
            if (completed) {
                // 手势完成：先冻结位移与速度（退场页要按冻结值带着动量继续滑出，不跳回屏幕中央），
                // 再清空绑定。
                if (target != null) {
                    frozenGesture[target] = gestureProgress
                    frozenVelocity[target] = velocity.velocity()
                }
                gestureSeq = null
                gestureProgress = 0f
            } else {
                // 取消（没越过阈值松手 / 系统中断）—— **保留** gestureSeq、只把进度归零。
                //   这样被拖的那层 translation 立即回 0、被它盖住的那层 cover 立即回 1（snap 目标）；
                //   gestureSeq 留着，下一次手势的 collect 会重新绑定到当前栈顶（上面那行 takeIf 已兼容）。
                gestureProgress = 0f
                onBackProgress(null)
            }
        }
        // 手势完成才弹栈；取消（异常/中断）不弹。
        if (completed) {
            // 先弹栈（退场页已拿到冻结位移，这一帧就能从手指位置继续滑出），**下一帧**再清主内容的
            //   让位进度：同一帧里既退场又让底页回弹会闪一下（"先退场后清"）。
            onPop()
            try {
                withFrameNanos { }
            } finally {
                onBackProgress(null)
            }
        }
    }
}

/**
 * 单页覆盖层。四种角色各由参数表达（同一次组合内不会同时出现）：
 * - 静止：`animateEnter=false, animateExit=false` → 可见且不播任何动画（含"被弹出的页露出来"）；
 * - 进入：`animateEnter=true` → 右滑入 + 淡入；
 * - 退场：`animateExit=true` → 右滑出 + 淡出，播完回调 [onExited]；
 * - 跟手拖动中：二者皆 false，但 [gestureTranslationFraction]() > 0 → 只按手指位移绘制，不播动画。
 *
 * 卡片化：Surface 裁 [shape] 圆角；被上层盖住（[covered]）时左移 [coverShiftPx] 并压暗
 * [COVER_DIM_ALPHA]；手势拖动期间用 [gestureCover] 实时值，松手后从手指位置接着动画归零。
 * 组合 key 由宿主设为 `entry.seq`，状态机天然按"栈层"隔离 ——
 * 同一页被 pop 后快速重新 push 会拿到全新的 `MutableTransitionState`（不残留"已隐藏"状态）。
 */
@Composable
private fun OverlayPage(
    entry: OverlayEntry,
    animateEnter: Boolean,
    animateExit: Boolean,
    /** 跟手位移比例（0f..1f）。**lambda**：只在绘制相位读，拖动时不重组宿主、也不重组本页。 */
    gestureTranslationFraction: () -> Float,
    /** 退场起点的冻结位移比例（退场开始的那一次组合读一次的数值，退场规格用）。 */
    exitTranslationFraction: Float,
    /** 松手速度（px/s，仅手势弹出的页 > 0）：退场按"剩余距离 / 该速度"换算时长、带动量滑出。 */
    exitVelocityPxPerS: Float = 0f,
    covered: Boolean,
    /** 当前手势给本页的让位比例（null = 没有被手势盖住）。lambda，同 [gestureTranslationFraction]。 */
    gestureCover: () -> Float?,
    coverShiftPx: Float,
    cvm: IwaraClientViewModel,
    vm: VideoViewModel,
    actions: IwaraNavActions,
    onPop: () -> Unit,
    onExited: () -> Unit
) {
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    // 进入动画用初值直接触发：首帧即开始滑入，不会先空一帧。
    // 退场由 animateExit 翻 true 触发。用 **SideEffect**（组合提交后同步执行）而不是 LaunchedEffect：
    //   后者要等协程派发，松手到退场起播会多出一帧静止，正好落在"顿一下"的手感上。
    val visibleState = remember {
        MutableTransitionState(!animateEnter).apply { if (animateEnter) targetState = true }
    }
    SideEffect { if (animateExit) visibleState.targetState = false }

    // 退场动画跑完（已隐藏且动画空闲）→ 通知宿主移出渲染列表。动画时长即生命周期，无定时器。
    LaunchedEffect(visibleState.currentState, visibleState.isIdle) {
        if (visibleState.isIdle && !visibleState.currentState) onExited()
    }

    // 退场规格：手势弹出的页**只补剩余距离**（从冻结位移滑到恰好出屏），时长 = 剩余距离 / 松手速度
    // （钳制 150..320ms），缓动用快出慢停的 LinearOutSlowIn —— FastOutSlowIn 起始斜率为 0，
    //   松手瞬间页面急减速再缓滑，就是"顿一下"的来源。非手势退出（按钮关闭）没有动量，
    //   保持原 320ms FastOutSlowIn。spec 只在退场开始的那个组合算一次（之后入参不再变化）。
    // 退场起点位移（px）：冻结比例 × 屏宽。只在退场开始的那个组合算一次。
    val exitTranslationPx = exitTranslationFraction * screenWidthPx
    val exitSpec: FiniteAnimationSpec<IntOffset> =
        remember(animateExit, exitTranslationPx, exitVelocityPxPerS) {
            if (animateExit && exitVelocityPxPerS > 0f && exitTranslationPx < screenWidthPx) {
                tween(
                    exitDurationMs(screenWidthPx - exitTranslationPx, exitVelocityPxPerS, EXIT_SLIDE_MS),
                    easing = LinearOutSlowInEasing
                )
            } else {
                tween(EXIT_SLIDE_MS, easing = FastOutSlowInEasing)
            }
        }

    // 让位进度：0 = 完全露出（原位/原亮度），1 = 被上层完全盖住。
    // 用 Animatable + snapshotFlow —— 目标值唯一决定终态、协程常驻（不随重组被取消），不可能停在半路；
    //   跟手时 snapTo 手指位置（切帧级贴合），松手那一帧 gestureCover 变 null 与"下层页变成顶层"
    //   发生在同一次组合 ⇒ 目标变成 0、规格变 tween，而当前值正是最后贴上的手指位置 ⇒
    //   **从手指位置接着滑出**。组合相位不读动画值，跟手/覆盖目标都经 rememberUpdatedState 取最新。
    val coverAnim = remember { Animatable(if (covered) 1f else 0f) }
    val gestureCoverNow = rememberUpdatedState(gestureCover)
    val coveredNow = rememberUpdatedState(covered)
    LaunchedEffect(Unit) {
        snapshotFlow { gestureCoverNow.value() to coveredNow.value }
            .collectLatest { (following, isCovered) ->
                if (following != null) {
                    // 跟手：目标 = 手指位置，直接贴上（无补间）。
                    coverAnim.snapTo(following)
                } else {
                    coverAnim.animateTo(
                        targetValue = if (isCovered) 1f else 0f,
                        animationSpec = tween(COVER_MOTION_MS, easing = FastOutSlowInEasing)
                    )
                }
            }
    }

    // 二级页圆角只在看得见的时候存在：进出场动画中 / 跟手拖动中 / 被盖让位中（含静止被盖——
    //   它右侧那条边一直露着）。静止铺满时是实心直角矩形。
    // 依次读跟手位移 lambda 与让位动画值，但都包在 derivedStateOf 里 —— 只在布尔真的翻转时
    //   才让本层重组；直接把动画值当布尔用等于让让位动画的**每一帧**都重组整页
    //   （作者页头部+吸顶栏、播放页顶底栏全部重跑）。
    val translationFractionNow = rememberUpdatedState(gestureTranslationFraction)
    val exposedShape by remember {
        derivedStateOf {
            !visibleState.isIdle ||
                translationFractionNow.value() != 0f ||
                (gestureCoverNow.value() ?: coverAnim.value) > 0f
        }
    }
    val shape = rememberAdaptivePageShape(exposed = exposedShape)

    AnimatedVisibility(
        visibleState = visibleState,
        modifier = Modifier.fillMaxSize(),
        // 进入与退场都**只滑不淡** —— 淡入会让页面在前 200ms 半透明，
        //   透出底下被让位左移的底页（右边那条边）与黑垫。
        enter = slideInHorizontally(tween(ENTER_SLIDE_MS)) { it },
        // 退场目标 = 剩余距离（fullWidth − 冻结位移）：非手势退出位移为 0，目标 = 全宽，与旧行为一致。
        exit = slideOutHorizontally(exitSpec) { it - exitTranslationPx.roundToInt() }
    ) {
        Surface(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 跟手位移与让位位移都在**绘制相位**读（lambda / Animatable）：不触发重组/重排，只重绘。
                    translationX = translationFractionNow.value() * screenWidthPx -
                        (gestureCoverNow.value() ?: coverAnim.value) * coverShiftPx
                },
            shape = shape,
            color = MaterialTheme.colorScheme.background
        ) {
            Box(
                Modifier
                    // 被盖住时的压暗层（画在页面内容之上；顶层页 cover=0 时一个像素都不画）。
                    // 若写成 `if (coverFraction > 0.01f) { Box(…) }` —— 在**组合相位**
                    //   判断并读动画值，同样造成让位动画每帧重组整页。这里放在 drawWithContent：
                    //   重组 0 次、只重绘，语义一致（让位期间逐帧渐变、结束即不画）。
                    .drawWithContent {
                        drawContent()
                        // 跟手期间读实时值，其余读让位动画值 —— 都在绘制相位。
                        val cover = gestureCoverNow.value() ?: coverAnim.value
                        if (cover > 0.01f) {
                            drawRect(color = Color.Black, alpha = COVER_DIM_ALPHA * cover)
                        }
                    }
            ) {
                when (val overlay = entry.overlay) {
                    is IwaraOverlay.Comments -> CommentsOverlay(
                        overlay.type, overlay.targetId, overlay.title, cvm, actions
                    ) { onPop() }
                    is IwaraOverlay.Profile -> ProfileOverlay(
                        username = overlay.username,
                        cvm = cvm,
                        actions = actions,
                        onClose = { onPop() },
                        userId = overlay.userId,
                        displayName = overlay.displayName,
                        localAuthorKey = overlay.localAuthorKey,
                        localAuthorName = overlay.localAuthorName,
                        vm = vm
                    )
                    is IwaraOverlay.Relations -> RelationsOverlay(
                        overlay.userId, overlay.kind, cvm, actions
                    ) { onPop() }
                    is IwaraOverlay.PlaylistDetail -> PlaylistDetailOverlay(
                        overlay.playlistId, overlay.title, cvm, actions
                    ) { onPop() }
                    is IwaraOverlay.ImageViewer -> ImageViewerOverlay(overlay.image, cvm, actions) { onPop() }
                    is IwaraOverlay.PostDetail -> PostDetailOverlay(overlay.post, cvm, actions) { onPop() }
                    is IwaraOverlay.Favorites -> FavoritesOverlay(cvm, actions) { onPop() }
                    is IwaraOverlay.Playlists -> PlaylistsOverlay(cvm, actions) { onPop() }
                    is IwaraOverlay.Downloads -> DownloadsOverlay(cvm) { onPop() }
                    is IwaraOverlay.Following -> FollowingOverlay(cvm, actions) { onPop() }
                    is IwaraOverlay.WatchHistory -> WatchHistoryOverlay(actions) { onPop() }
                    is IwaraOverlay.TagVideos -> TagVideosOverlay(overlay.tagId, cvm, actions) { onPop() }
                    else -> {}
                }
            }
        }
    }
}
