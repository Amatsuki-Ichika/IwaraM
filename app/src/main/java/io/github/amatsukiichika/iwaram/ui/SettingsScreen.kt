package io.github.amatsukiichika.iwaram.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.ui.browse.coverTargetFor
import io.github.amatsukiichika.iwaram.ui.components.AppEntryCard
import kotlinx.coroutines.CancellationException

/**
 * 设置的二级分类。主页只列分类名，点进分类子页才是功能选项。
 *
 * 共 5 类：
 * - 通用（原「外观」+「内容分级」合并，页内两个集合区块：外观 / 内容分级）；
 * - 播放（页内两个集合区块：默认 / 超分）；
 * - 存储 / 开发者 / 关于。
 * 页内的"集合区块"= [SettingsSection] 小标题 + 卡片，不占一层页面。
 */
internal enum class SettingsCategory(val icon: ImageVector, val title: String) {
    // 图标用 Tune（横向推子）而非 Palette：本页是"外观 + 内容分级"的通用设置，
    //   调色盘容易让人误以为只有主题色，Tune 是「偏好/通用设置」的通用符号。
    GENERAL(Icons.Filled.Tune, "通用"),
    PLAYBACK(Icons.Filled.Fullscreen, "播放"),
    STORAGE(Icons.Filled.FolderOpen, "存储"),
    DEVELOPER(Icons.Filled.Code, "开发者"),
    ABOUT(Icons.Filled.Info, "关于")
}

/**
 * 设置页：统一收纳外观、播放、存储等自定义选项。
 *
 * 二级分类结构：主页只有分类入口（[SettingsCategory]），点进分类子页才是功能选项。
 * 子页过渡与全仓二级页同一套规格：整页横滑 320ms（只滑不淡）、主页让位（[COVER_SHIFT_DP] 左移 +
 * [COVER_DIM_ALPHA] 压暗 + 同速回位）、圆角自适应（[rememberAdaptivePageShape]）、
 * 预测式返回（子页 handler 后注册者优先）。
 *
 * 主页分类入口与子页每个设置项各是一张独立 [AppEntryCard]（「我的」页同款）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: VideoViewModel,
    onBack: () -> Unit,
    onPickFolder: () -> Unit,
    onPickDownloadFolder: () -> Unit,
    /** 预测式返回手势进度：拖动中回调 0f..1f，松手/取消回调 null（主界面据此实时让位）。 */
    onBackProgress: (Float?) -> Unit = {},
    /** 进出场动画是否正在播（宿主 visibleState 下发）：动画中圆角可见，静止后收成直角。 */
    pageAnimating: Boolean = false,
    /** 被更新的层盖住 → 与其它二级页一样让位（左移 + 压暗 + 收圆角）。 */
    covered: Boolean = false,
    /** 被盖住时让出系统返回键（PredictiveBackHandler 后注册者优先，不让就会被下层抢走手势）。 */
    backEnabled: Boolean = true
) {
    // 设置值全部收进分类子页（[CategoryPage]）按需收集；主页只剩分类入口、零收集。

    // 预测式返回：跟手右滑（与播放页同款，无透明度变化），越过阈值松手即退出。
    var backProgress by remember { mutableFloatStateOf(0f) }
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    // 被上层盖住时的让位进度（与播放页/覆盖层栈内被盖页同一套）。
    // 让位量用固定 dp（[COVER_SHIFT_DP]，条目卡首字恰被屏缘盖住的口径）。
    val coverShiftPx = with(LocalDensity.current) { COVER_SHIFT_DP.toPx() }
    // 拿 State 而不是 `by` 委托值：这个动画只在绘制相位读（graphicsLayer / drawWithContent），
    //   以及下面 derivedStateOf 的布尔判定里；在组合相位裸读会让 320ms 让位/退场动画的每一帧
    //   都重组整页设置页。
    val coverAnim = animateFloatAsState(
        targetValue = if (covered) 1f else 0f,
        animationSpec = tween(COVER_MOTION_MS, easing = FastOutSlowInEasing),
        label = "settingsCover"
    )
    // 圆角自适应：动画中/跟手中/被盖让位中弧线可见（四角裁屏幕物理圆角），静止铺满收成实心直角。
    val pageShape = rememberAdaptivePageShape(
        exposed = remember(pageAnimating) {
            derivedStateOf { pageAnimating || backProgress > 0f || coverAnim.value > 0f }
        }.value
    )
    PredictiveBackHandler(enabled = backEnabled) { flow ->
        try {
            flow.collect { event ->
                backProgress = event.progress.coerceIn(0f, 1f)
                onBackProgress(backProgress)
            }
            // 松手完成：**不复位** backProgress —— 冻结的位移留在原地，
            // 交给外层 AnimatedVisibility 的滑出动画（p×w → (p+1)×w，与二级页/播放页一致）。
            onBackProgress(null)
            onBack()
        } catch (e: CancellationException) {
            backProgress = 0f
            onBackProgress(null)
        }
    }

    // ---- 二级分类子页 ----
    // 用 plain remember（与 showDiagnostics 同理）：关掉设置页 = 状态丢弃，重开回主页。
    var openCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    // 退场动画期间 openCategory 已是 null，内容改用 lastCategory 渲染（SideEffect 回写，无组合期写入）。
    var lastCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    SideEffect { if (openCategory != null) lastCategory = openCategory }
    val subVisible = remember { MutableTransitionState(false) }
    SideEffect { subVisible.targetState = openCategory != null }
    // 主页被子页盖住 → 让位（同 CardStack 规格：左移 + 压暗，320ms 同速）。
    // 子页返回手势联动：子页拉住返回时主页实时回位（c = 1 − p）+ 变亮，语义与覆盖层栈的
    // gestureCover 一致（复用 [coverTargetFor]：手势中 snap、松手 tween 320ms）。
    var subBackProgress by remember { mutableStateOf<Float?>(null) }
    val mainCover = animateFloatAsState(
        targetValue = coverTargetFor(
            gestureCover = openCategory?.let { 1f - (subBackProgress ?: 0f) },
            covered = openCategory != null
        ),
        animationSpec = if (subBackProgress != null) {
            snap()
        } else {
            tween(COVER_MOTION_MS, easing = FastOutSlowInEasing)
        },
        label = "settingsMainCover"
    )

    Box(
        Modifier
            .fillMaxSize()
            // 右滑（跟手/退场）为正，被上层盖住的让位为负（左移）。作用于整层（含子页）。
            .graphicsLayer { translationX = backProgress * screenWidthPx - coverAnim.value * coverShiftPx }
            // .clip 必须写在 .background 之前（背景画在裁切之外不会被裁）。
            .clip(pageShape)
            .background(MaterialTheme.colorScheme.background)
            // 被上层盖住时压暗整层（在 clip 之内，跟圆角一起裁；绘制期读 coverAnim）。
            .drawWithContent {
                drawContent()
                if (coverAnim.value > 0.01f) {
                    drawRect(Color.Black.copy(alpha = COVER_DIM_ALPHA * coverAnim.value))
                }
            }
    ) {
        // 栈底黑垫观感：主页让位左移时，右侧露出的缝是黑色（与覆盖层栈一致）。
        // 主页自身背景不透明，静止时黑垫完全被盖住，零开销。
        Box(Modifier.fillMaxSize().background(Color.Black))
        // —— 主页：分类入口列表（被子页盖住时让位） ——
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = -mainCover.value * coverShiftPx }
                // 让位时压暗主页（绘制期读 mainCover，零重组）。
                .drawWithContent {
                    drawContent()
                    if (mainCover.value > 0.01f) {
                        drawRect(Color.Black.copy(alpha = COVER_DIM_ALPHA * mainCover.value))
                    }
                },
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 一条目一卡（AppEntryCard，「我的」页同款），大卡内分隔线与行距空白取消。
                items(SettingsCategory.entries) { category ->
                    AppEntryCard(
                        title = category.title,
                        icon = category.icon,
                        onClick = { openCategory = category }
                    )
                }
            }
        }

        // —— 二级分类子页：整页横滑盖上来（只滑不淡，淡入会透出底页让位的边） ——
        AnimatedVisibility(
            visibleState = subVisible,
            enter = slideInHorizontally(tween(320)) { it },
            exit = slideOutHorizontally(tween(320)) { it }
        ) {
            (openCategory ?: lastCategory)?.let { category ->
                key(category) {
                    CategoryPage(
                        category = category,
                        vm = vm,
                        onBack = { openCategory = null },
                        onPickFolder = onPickFolder,
                        onPickDownloadFolder = onPickDownloadFolder,
                        backEnabled = backEnabled && openCategory != null,
                        // 圆角自适应用：子页进出场动画中裁物理圆角（读 subVisible.isIdle，
                        // 只在动画起止两个布尔翻转点重组）。
                        animating = !subVisible.isIdle,
                        // 手势进度上抛 → 主页 mainCover 实时联动回位。
                        onBackProgress = { subBackProgress = it }
                    )
                }
            }
        }
    }
}
