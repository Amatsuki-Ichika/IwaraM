package io.github.amatsukiichika.iwaram.ui.components

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.amatsukiichika.iwaram.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 抽屉整体高度 = 屏高的 88%（把手也在这 88% 里），四个抽屉同一个值。 */
private const val SHEET_HEIGHT_FRACTION = 0.88f

/**
 * 在 [SHEET_HEIGHT_FRACTION] 的基础上再减掉两行（2 × 56dp = 112dp）。
 * 用固定 dp 而不是再降比例，"少两行"这个意图才不随屏高漂移。
 */
private val SHEET_HEIGHT_REDUCTION = 112.dp

/** 向下拖过这个比例就判定为"关"，否则弹回。 */
private const val DRAG_DISMISS_FRACTION = 0.25f

/**
 * 抽屉遮罩 alpha 的进程内镜像，给 Activity 侧的背景遮罩用
 * （遮罩本体 = MainActivity 根 Box 末尾的全屏单层黑，只在 Activity 画）。
 *
 * 注意：App 内抽屉从不叠开（筛选/诊断/动作面板互斥），这里按"最后写入者"同步即可；
 * 若未来允许叠开，必须改成"活跃抽屉计数 + 取最大 alpha"。
 */
internal object SheetScrimState {
    var alpha by mutableFloatStateOf(0f)
}

/**
 * 抽屉的内容视图 —— 故意不继承 `androidx.compose.ui.window.DialogLayout`。
 *
 * DialogLayout 在 `usePlatformDefaultWidth=false` 时，`internalOnMeasure` 强制按
 * `Configuration.screenWidthDp/HeightDp × density`（恰好扣掉系统栏）测量，`internalOnLayout`
 * 每次布局都把窗口 `setLayout(实测宽高)` 覆写回去，窗口因此永远被压成"整屏−系统栏"。
 * M3 官方抽屉（ModalBottomSheet）就是绕开它：自建内容视图 + 自己反复断言窗口全屏。
 * 本类继承 [AbstractComposeView]，没有那条覆写逻辑，窗口尺寸由 [SheetDialogWrapper] 自己管理。
 */
private class SheetDialogLayout(context: Context) : AbstractComposeView(context) {
    /** 抽屉内容；宿主在 show 之前注入（父组合上下文一起给，主题等 locals 从调用点继承）。 */
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)

    fun setContent(parent: CompositionContext, content: @Composable () -> Unit) {
        setParentCompositionContext(parent)
        this.content = content
    }

    @Composable
    override fun Content() {
        content?.invoke()
    }
}

/**
 * 抽屉宿主窗口 —— 复刻 M3 `ModalBottomSheetDialogWrapper` 的做法：
 * 自建 `ComponentDialog`（非浮窗主题 [R.style.IwaraDialogWindowTheme]）+ 构造器与每次重组
 * 双路径断言 `setLayout(MATCH_PARENT, MATCH_PARENT)`，窗口内容框 = 整屏，面板贴底。
 *
 * 生命周期/组合上下文：不拷贝 ViewTree owners（lifecycle 2.8 对 app 模块收窄了该 API），
 * 改走 `setParentCompositionContext`，抽屉内容继承调用点的全部 CompositionLocal
 * （LocalLifecycleOwner / LocalSavedStateRegistryOwner 等都在里面，collectAsStateWithLifecycle、
 * rememberSaveable 照常工作）。
 */
private class SheetDialogWrapper(
    /** 调用点的 View：取其 context 构建窗口。 */
    anchorView: View,
    onDismissRequest: () -> Unit
) : ComponentDialog(anchorView.context, R.style.IwaraDialogWindowTheme) {

    private val layout = SheetDialogLayout(context)

    init {
        setupWindow()
        // 返回键 → 关闭请求（原 Dialog dismissOnBackPress=true 的语义）。
        onBackPressedDispatcher.addCallback(this) { onDismissRequest() }
        setContentView(layout)
    }

    private fun setupWindow() {
        val window = window ?: return
        window.setBackgroundDrawableResource(android.R.color.transparent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        // 背景遮罩归一在 Activity：窗口自身不压暗、不画遮罩。
        window.setDimAmount(0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        updateWindowSize()
        // 照抄 Compose DialogWrapper：关掉 decor 的裁剪（面板越界动画不被硬裁）。
        (window.decorView as? ViewGroup)?.clipChildren = false
    }

    /** 每次重组重申窗口全屏（M3 同款双路径：构造器已断言一次，这里防任何后续覆写）。 */
    fun updateWindowSize() {
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    /** 状态栏/导航栏：透明 + 图标外观跟随主题（浅色主题 = 深色图标）。 */
    fun syncSystemBars(lightStatusBars: Boolean) {
        window?.let { w ->
            @Suppress("DEPRECATION")
            w.statusBarColor = AndroidColor.TRANSPARENT
            WindowCompat.getInsetsController(w, w.decorView).apply {
                isAppearanceLightStatusBars = lightStatusBars
                isAppearanceLightNavigationBars = lightStatusBars
            }
        }
    }

    fun setContent(parent: CompositionContext, content: @Composable () -> Unit) {
        layout.setContent(parent, content)
    }
}

/**
 * 自实现的底部抽屉，全仓库唯一实现。
 *
 * 不用 M3 的 `ModalBottomSheet`：material3 1.3.0 的 `ModalBottomSheetKt$ModalBottomSheet$3` 里
 * 硬编码了 `Box(Modifier.fillMaxSize().imePadding())`，把整张抽屉（含拖拽把手）包在里面，
 * 键盘一弹整张抽屉被顶上去，且没有任何参数能关掉（`contentWindowInsets` 只管内容那一层）。
 * 需要"把手永远不动、只有内容随键盘让位"时，M3 的抽屉结构上做不到，所以这里自己搭。
 *
 * - 宿主窗口（见 [SheetDialogWrapper]）：非浮窗全屏窗口，窗口尺寸自己断言（不用
 *   androidx.compose.ui.window.Dialog，它的 DialogLayout 会把窗口覆写回"整屏−系统栏"）；
 * - 把手：自己画的固定区，永远不参与键盘让位；
 * - 高度：打开那一刻按屏高算好并锁死（只在屏幕尺寸变化时重算），键盘/内容/切 tab 都不改变它；
 * - 键盘：自己读 `WindowInsets.ime`，只把它加在内容区底部，外框与把手纹丝不动，
 *   内容区被压缩、可滚动（聚焦的输入框会被 Compose 自动滚进可视区）；
 * - 背景遮罩：画在 Activity（全屏单层黑，alpha 由 [SheetScrimState] 逐帧镜像）；
 * - 关闭：点背景 / 按返回 / 向下拖过 1/4 三种都能关；只有开、关两态，不中间停。
 */
@Composable
fun AppBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 调用方持有的滚动状态（例如需要把某一行滚进可视区）；不传则内部自建。 */
    scrollState: ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val inner = scrollState ?: rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val anchorView = LocalView.current
    val parentComposition = rememberCompositionContext()

    // 高度锁死：只跟"屏幕高度"走（键盘不会改变它 —— 窗口设了 ADJUST_NOTHING）。
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val sheetHeight = remember(screenHeightDp) {
        (screenHeightDp * SHEET_HEIGHT_FRACTION).dp - SHEET_HEIGHT_REDUCTION
    }
    val sheetHeightPx = with(density) { sheetHeight.toPx() }

    // 进场/退场：0 = 完全就位，1 = 整块在屏幕下方。
    // scrim 不在本窗口绘制任何东西（遮罩归一到了 Activity），只作为 SheetScrimState 的动画驱动源。
    val offsetY = remember { Animatable(1f) }
    val scrim = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    fun requestClose() {
        if (closing) return
        closing = true
        scope.launch {
            launch { scrim.animateTo(0f, tween(180)) }
            offsetY.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
            currentOnDismiss()
        }
    }

    // 把遮罩 alpha 逐帧镜像给 Activity 侧的背景遮罩。
    LaunchedEffect(Unit) {
        snapshotFlow { scrim.value }.collect { SheetScrimState.alpha = it }
    }

    // 宿主窗口（自管，见 SheetDialogWrapper 注释）。remember 持有、组合离开时 dismiss。
    val dialog = remember {
        SheetDialogWrapper(anchorView, onDismissRequest = { requestClose() })
    }

    // 图标外观跟随主题（浅色主题 = 深色图标，暗色自动翻转）——组合期算值，SideEffect 里只下发窗口。
    val lightStatusBars = MaterialTheme.colorScheme.background.luminance() > 0.5f

    SideEffect {
        // 每次重组重申窗口全屏（M3 同款）+ 下发系统栏透明/图标外观。
        dialog.updateWindowSize()
        dialog.syncSystemBars(lightStatusBars)
    }

    DisposableEffect(dialog) {
        dialog.show()
        onDispose {
            dialog.dismiss()
            SheetScrimState.alpha = 0f
        }
    }

    LaunchedEffect(Unit) {
        launch { scrim.animateTo(1f, tween(200)) }
        offsetY.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
    }

    // 键盘高度只喂给内容区（把手与外框不碰它）。
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val bottomInset = with(density) { maxOf(imeBottom, navBottom).toDp() }

    dialog.setContent(parentComposition) {
        Box(Modifier.fillMaxSize()) {
            // 全屏透明点击层：点背景关抽屉（背景遮罩本体画在 Activity）。
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { requestClose() } }
            )

            Surface(
                modifier = modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(sheetHeight)
                    .offset { IntOffset(0, (offsetY.value * sheetHeightPx).roundToInt()) },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    // ---- 固定区：把手（这里也是"向下拖关闭"的手柄）----
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .draggable(
                                state = rememberDraggableState { delta ->
                                    scope.launch {
                                        offsetY.snapTo(
                                            (offsetY.value + delta / sheetHeightPx).coerceIn(0f, 1f)
                                        )
                                    }
                                },
                                orientation = Orientation.Vertical,
                                onDragStopped = {
                                    if (offsetY.value > DRAG_DISMISS_FRACTION) {
                                        requestClose()
                                    } else {
                                        scope.launch {
                                            offsetY.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
                                        }
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .padding(top = 12.dp, bottom = 10.dp)
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        )
                    }

                    // ---- 内容区：键盘高度只加在这里 ----
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(bottom = bottomInset)
                            .verticalScroll(inner)
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
