package io.github.amatsukiichika.iwaram.ui.components

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.amatsukiichika.iwaram.R
import io.github.amatsukiichika.iwaram.i18n.tr
import kotlinx.coroutines.delay

/** 弹窗入场：遮罩淡入 + 卡片轻微放大（无位移、无过冲）。 */
private const val DIALOG_ENTER_MS = 180

/** 弹窗退场：比入场略快；窗口要多活这么久，等动画画完再销毁。 */
private const val DIALOG_EXIT_MS = 160

/**
 * 全仓库唯一的居中弹窗（独立窗口 + 全屏遮罩 + 居中卡片），视觉规格见
 * .zcode/plans/ui-mockups.html 的 .dialog（图 2-②、图 4、图 5）。
 *
 * 为什么是独立窗口而不是页面内的 AnimatedVisibility：页面内绘制要参与 Activity 的
 * 兄弟层排序，播放页 / 设置页 / 二级页都有自己的 zIndex，弹窗会被它们盖住
 * （用户反馈「完整性检查弹窗显示在播放页下面」）。独立 Dialog 窗口天然在所有窗口之上，
 * 顺序问题从根上消失；代价是窗口要自己管尺寸、系统栏和返回键。
 *
 * 窗口做法与 [AppBottomSheet] 的宿主完全一致（见 SheetDialogWrapper 的注释）：
 * 非浮窗全屏主题 + 自建 AbstractComposeView，避免 DialogLayout 把窗口压成「整屏−系统栏」。
 *
 * 两种用法：
 * - 说明（设置页说明入口等）：只传 [title] + [text] / [content]，默认单按钮「知道了」；
 * - 确认（清空历史、删除等）：传 [onConfirm] + [dismissText]；[confirmDestructive] 为 true 时
 *   确认按钮用 error 色（危险动作只做文字按钮，不做实心大按钮）。
 *
 * @param icon 卡片顶部的 44dp 圆形图标；null = 不画（老的说明类弹窗保持原样）。
 * @param iconTint 图标与圆底的强调色；null = primary。
 * @param titleTint 标题色；null = onSurface（损坏结果可传 error）。
 * @param dismissOnOutside 点遮罩 / 按返回是否关闭；运行中的检查传 false（只能点按钮取消）。
 * @param maxWidth 卡片最大宽度（默认 310dp，与 mockup 一致）。
 */
@Composable
fun AppDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    text: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    confirmText: String? = tr("知道了", "Got it"),
    onConfirm: (() -> Unit)? = null,
    dismissText: String? = null,
    confirmDestructive: Boolean = false,
    /** 确认按钮文字色；null = primary（[confirmDestructive] 时 error）。取消 / 关闭等中性动作用它压成灰色。 */
    confirmTint: Color? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    titleTint: Color? = null,
    dismissOnOutside: Boolean = true,
    maxWidth: Dp = 310.dp
) {
    val anchorView = LocalView.current
    val parentComposition = rememberCompositionContext()
    // 宿主每次重组可能传新 lambda；窗口只建一次，回调必须读最新值。
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentDismissOnOutside by rememberUpdatedState(dismissOnOutside)

    // 窗口比 visible 多活一个退场时长：宿主把 visible 置 false 时先把 fade + scale 播完再销毁窗口，
    // 否则同一个弹窗连续切换状态（UpdateDialogs）会直接闪断。
    var windowShown by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            windowShown = true
        } else if (windowShown) {
            // 比退场动画多留 80ms：动画最后一帧画完才销毁窗口，否则偶尔会闪一下截断。
            delay(DIALOG_EXIT_MS.toLong() + 80L)
            windowShown = false
        }
    }
    if (!windowShown) return

    val dialog = remember { AppDialogWindow(anchorView) }
    // 系统栏图标外观跟随主题（浅色主题 = 深色图标，暗色自动翻转）。
    val lightStatusBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
    SideEffect {
        // 每次重组重申窗口全屏 + 下发系统栏外观 + 更新返回键语义。
        dialog.updateWindowSize()
        dialog.syncSystemBars(lightStatusBars)
        dialog.onBackPressed = {
            if (currentDismissOnOutside) currentOnDismiss()
        }
    }

    DisposableEffect(dialog) {
        dialog.show()
        onDispose { dialog.dismiss() }
    }

    dialog.setContent(parentComposition) {
        AppDialogScaffold(
            visible = visible,
            onDismiss = { currentOnDismiss() },
            dismissOnOutside = dismissOnOutside,
            title = title,
            text = text,
            content = content,
            confirmText = confirmText,
            onConfirm = onConfirm,
            dismissText = dismissText,
            confirmDestructive = confirmDestructive,
            confirmTint = confirmTint,
            icon = icon,
            iconTint = iconTint,
            titleTint = titleTint,
            maxWidth = maxWidth
        )
    }
}

/** 窗口内的画面：全屏遮罩 + 居中卡片。遮罩自己画（窗口透明、dimAmount = 0）。 */
@Composable
private fun AppDialogScaffold(
    visible: Boolean,
    onDismiss: () -> Unit,
    dismissOnOutside: Boolean,
    title: String,
    text: String?,
    content: (@Composable ColumnScope.() -> Unit)?,
    confirmText: String?,
    onConfirm: (() -> Unit)?,
    dismissText: String?,
    confirmDestructive: Boolean,
    confirmTint: Color?,
    icon: ImageVector?,
    iconTint: Color?,
    titleTint: Color?,
    maxWidth: Dp
) {
    // 首帧先画隐藏态、下一帧再切到 visible：visible = true 直接挂载时才有入场动画，
    // 否则 AnimatedVisibility 首帧就算"已显示"，卡片会硬出现。
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val shown = appeared && visible
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(DIALOG_ENTER_MS)),
            exit = fadeOut(tween(DIALOG_EXIT_MS))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .pointerInput(dismissOnOutside) {
                        detectTapGestures {
                            // 运行中的弹窗不响应点遮罩：避免「以为取消了其实 IO 还在读」。
                            if (dismissOnOutside) onDismiss()
                        }
                    }
            )
        }

        // 卡片层只做居中，不拦截全屏点击：拦截层一旦铺满屏幕，"点卡片外面关闭"就永远到不了遮罩。
        // 点卡片本身的点击由卡片自己的 pointerInput 吃掉，避免穿透到遮罩。
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = shown,
                enter = scaleIn(
                    initialScale = 0.92f,
                    animationSpec = tween(DIALOG_ENTER_MS, easing = LinearOutSlowInEasing)
                ) + fadeIn(tween(DIALOG_ENTER_MS)),
                exit = scaleOut(
                    targetScale = 0.94f,
                    animationSpec = tween(DIALOG_EXIT_MS, easing = LinearOutSlowInEasing)
                ) + fadeOut(tween(DIALOG_EXIT_MS))
            ) {
                AppDialogCard(
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
                    title = title,
                    text = text,
                    content = content,
                    confirmText = confirmText,
                    onDismiss = onDismiss,
                    onConfirm = onConfirm,
                    dismissText = dismissText,
                    confirmDestructive = confirmDestructive,
                    confirmTint = confirmTint,
                    icon = icon,
                    iconTint = iconTint,
                    titleTint = titleTint,
                    maxWidth = maxWidth
                )
            }
        }
    }
}

/** 卡片本体：surface 底、28dp 圆角、24 / 22 / 14 内边距；内容超高时整卡纵向滚动。 */
@Composable
private fun AppDialogCard(
    modifier: Modifier = Modifier,
    title: String,
    text: String?,
    content: (@Composable ColumnScope.() -> Unit)?,
    confirmText: String?,
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)?,
    dismissText: String?,
    confirmDestructive: Boolean,
    confirmTint: Color?,
    icon: ImageVector?,
    iconTint: Color?,
    titleTint: Color?,
    maxWidth: Dp
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = maxWidth),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 24.dp
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 14.dp)
        ) {
            if (icon != null) {
                val tint = iconTint ?: MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 19.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = titleTint ?: MaterialTheme.colorScheme.onSurface
            )
            if (!text.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.5.sp,
                        lineHeight = 22.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (content != null) {
                Spacer(Modifier.height(14.dp))
                content()
            }
            if (confirmText != null) {
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)
                ) {
                    // 次要按钮只在「确认回调存在 + 给了取消文案」时出现（与旧 API 语义一致）。
                    if (onConfirm != null && dismissText != null) {
                        AppDialogTextButton(
                            text = dismissText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onDismiss
                        )
                    }
                    AppDialogTextButton(
                        text = confirmText,
                        color = confirmTint ?: if (confirmDestructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        onClick = { (onConfirm ?: onDismiss).invoke() }
                    )
                }
            }
        }
    }
}

/** 文字按钮 pill：14sp、左右 16 / 上下 9、圆角 999、无实心底色（mockup 的 .btn）。 */
@Composable
private fun AppDialogTextButton(text: String, color: Color, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
            color = color
        )
    }
}

/**
 * 弹窗内容视图：与 [AppBottomSheet] 的 SheetDialogLayout 同款，继承 [AbstractComposeView]。
 *
 * 故意不用 androidx.compose.ui.window.DialogLayout：它在 usePlatformDefaultWidth = false 时按
 * Configuration.screenWidthDp / screenHeightDp 测量，并在 layout 阶段把窗口 setLayout 覆写回去，
 * 窗口永远等于「整屏−系统栏」。这里窗口尺寸由 [AppDialogWindow] 自己断言成全屏。
 */
private class AppDialogLayout(context: Context) : AbstractComposeView(context) {
    /** 弹窗内容；宿主在 show 之前注入（父组合上下文一起给，主题等 locals 从调用点继承）。 */
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
 * 弹窗宿主窗口：非浮窗全屏主题 + 全屏透明布局，遮罩与卡片都画在窗口内。
 *
 * 系统返回：ComponentDialog 在 API 33+ 会把窗口的 OnBackInvokedDispatcher 桥接进本 dialog
 * 的 OnBackPressedDispatcher（activity 1.13.0 已核验），所以这里注册的回调能拦到返回键；
 * 是否真的关闭由 [onBackPressed] 决定（运行中的弹窗吞掉返回，只能点按钮取消）。
 */
private class AppDialogWindow(
    anchorView: View
) : ComponentDialog(anchorView.context, R.style.IwaraDialogWindowTheme) {

    private val layout = AppDialogLayout(context)

    /** 返回键语义；由 AppDialog 每次重组按 dismissOnOutside 下发。 */
    var onBackPressed: () -> Unit = {}

    init {
        setupWindow()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onBackPressed()
                }
            }
        )
        setContentView(layout)
    }

    private fun setupWindow() {
        val window = window ?: return
        window.setBackgroundDrawableResource(android.R.color.transparent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        // 变暗与遮罩都在窗口内自绘（遮罩要在卡片下面、系统栏上面），窗口自身不压暗。
        window.setDimAmount(0f)
        // 进出场动画自己画，平台窗口动画关掉（否则同一动画会播两遍）。
        window.setWindowAnimations(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        updateWindowSize()
        // 照抄 Compose DialogWrapper：关掉 decor 的裁剪（scale 动画不被硬裁）。
        (window.decorView as? ViewGroup)?.clipChildren = false
    }

    /** 每次重组重申窗口全屏（构造器已断言一次，这里防任何后续覆写）。 */
    fun updateWindowSize() {
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    /** 状态栏 / 导航栏：透明 + 图标外观跟随主题（浅色主题 = 深色图标）。 */
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
