package io.github.amatsukiichika.iwaramanager.ui

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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaramanager.ui.COVER_SHIFT_DP
import io.github.amatsukiichika.iwaramanager.ui.browse.accentChipColors
import io.github.amatsukiichika.iwaramanager.ui.browse.coverTargetFor
import io.github.amatsukiichika.iwaramanager.ui.components.AppDialog
import io.github.amatsukiichika.iwaramanager.ui.components.AppEntryCard
import io.github.amatsukiichika.iwaramanager.ui.components.AppSlider
import io.github.amatsukiichika.iwaramanager.ui.components.GlassMode
import io.github.amatsukiichika.iwaramanager.ui.theme.THEME_COLOR_OPTIONS
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.width

private enum class ThemeOption(val label: String, val value: String) {
    SYSTEM("跟随系统", "system"),
    LIGHT("浅色", "light"),
    DARK("深色", "dark")
}

private enum class OrientationOption(val label: String, val value: String) {
    AUTO("自动", "auto"),
    SMART("智能识别", "smart"),
    LANDSCAPE("横向", "landscape"),
    PORTRAIT("竖向", "portrait")
}

/** 默认分辨率选项：值为 API 档位名（Source 精确匹配；数字档缺失时回退最高可用档）。 */
private val QUALITY_OPTIONS = listOf(
    "Source" to "Source",
    "1080p" to "1080",
    "720p" to "720",
    "540p" to "540",
    "480p" to "480",
    "360p" to "360"
)

/**
 * 设置的二级分类。主页只列分类名，点进分类子页才是功能选项。
 *
 * 共 5 类：
 * - 通用（原「外观」+「内容分级」合并，页内两个集合区块：外观 / 内容分级）；
 * - 播放（页内两个集合区块：默认 / 超分）；
 * - 存储 / 开发者 / 关于。
 * 页内的"集合区块"= [SettingsSection] 小标题 + 卡片，不占一层页面。
 */
private enum class SettingsCategory(val icon: ImageVector, val title: String) {
    // 图标用 Tune（横向推子）而非 Palette：本页是"外观 + 内容分级"的通用设置，
    //   调色盘容易让人误以为只有主题色，Tune 是「偏好/通用设置」的通用符号。
    GENERAL(Icons.Filled.Tune, "通用"),
    PLAYBACK(Icons.Filled.Fullscreen, "播放"),
    STORAGE(Icons.Filled.FolderOpen, "存储"),
    DEVELOPER(Icons.Filled.Code, "开发者"),
    ABOUT(Icons.Filled.Info, "关于")
}

/**
 * 设置页的集合区块标题（唯一实现）。
 *
 * 用于"同一页里把设置分成几组"（通用页的外观 / 内容分级；播放页的默认 / 超分）——
 * 不占一层页面，只是一个小标题 + 其下卡片。
 */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
        content()
    }
}

/** 长说明弹窗的内容（标题 = 行名，正文 = 完整解释）。 */
private data class InfoData(val title: String, val text: String)

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

/**
 * 设置分类子页：自带顶栏（返回箭头 = 关子页）+ 该分类的全部功能选项。
 *
 * 预测式返回：本 handler 比外层设置页的**后注册**（子页内容后组合）⇒ 子页开着时手势归它；
 * 跟手位移/冻结/取消与设置页本体同款（松手完成不复位，冻结位移交给退场滑出动画）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPage(
    category: SettingsCategory,
    vm: VideoViewModel,
    onBack: () -> Unit,
    onPickFolder: () -> Unit,
    onPickDownloadFolder: () -> Unit,
    backEnabled: Boolean,
    /** 宿主下发：子页进出场动画正在播（圆角自适应判据，同设置页本体的 pageAnimating）。 */
    animating: Boolean,
    /** 手势进度上抛（拖动中 0f..1f / 松手与取消 null）→ 设置主页实时联动回位。 */
    onBackProgress: (Float?) -> Unit = {}
) {
    // —— 窄流（从 SettingsScreen 移入：只有分类页需要这些值） ——
    val showTags by vm.showTagsFlow.collectAsStateWithLifecycle()
    val defaultR18 by vm.defaultR18Flow.collectAsStateWithLifecycle()
    val showR18Tag by vm.showR18TagFlow.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val themeColor by vm.themeColor.collectAsStateWithLifecycle()
    val fsOrientation by vm.fsOrientation.collectAsStateWithLifecycle()
    val debugDetails by vm.debugDetailsFlow.collectAsStateWithLifecycle()
    val qualityPref by vm.qualityPref.collectAsStateWithLifecycle()
    val downloadQualityPref by vm.downloadQualityPref.collectAsStateWithLifecycle()
    val srEnabled by vm.srEnabled.collectAsStateWithLifecycle()
    val srModel by vm.srModel.collectAsStateWithLifecycle()
    val rootName by vm.rootName.collectAsStateWithLifecycle()
    val downloadRootName by vm.downloadRootName.collectAsStateWithLifecycle()

    // 普通设置项的说明共用一个页面级弹窗（行只报标题+全文，不各自持弹窗）。
    var showInfo by remember { mutableStateOf(false) }
    var infoData by remember { mutableStateOf(InfoData("", "")) }
    fun showInfoDialog(title: String, text: String) {
        infoData = InfoData(title, text)
        showInfo = true
    }
    // 超分「方案」的说明是模型列表（富内容），单独一个弹窗。
    var showSrInfo by remember { mutableStateOf(false) }
    // 「运行状态 / 日志」面板（诊断入口在开发者页，debug 门控不变）。
    var showDiagnostics by remember { mutableStateOf(false) }

    // 跟手返回进度（只在子页内用；key(category) 重挂 = 每次打开从 0 开始）。
    var backProgress by remember { mutableFloatStateOf(0f) }
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

    // 圆角自适应：进出场/跟手中裁物理圆角，静止铺满收直角（与设置页本体同一套判据）。
    val pageShape = rememberAdaptivePageShape(
        exposed = remember(animating) {
            derivedStateOf { animating || backProgress > 0f }
        }.value
    )
    PredictiveBackHandler(enabled = backEnabled) { flow ->
        try {
            flow.collect { event ->
                backProgress = event.progress.coerceIn(0f, 1f)
                // 上抛给宿主：设置主页按 c = 1 − p 实时联动回位。
                onBackProgress(backProgress)
            }
            // 松手完成：不复位 backProgress —— 冻结位移留给退场滑出动画（与设置页本体一致）；
            // 进度上报置 null → 主页让位切回 tween 规格（随弹栈滑出同步回位）。
            onBackProgress(null)
            onBack()
        } catch (e: CancellationException) {
            backProgress = 0f
            onBackProgress(null)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = backProgress * screenWidthPx }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = pageShape,
            color = MaterialTheme.colorScheme.background
        ) {
            Column {
                TopAppBar(
                    title = { Text(category.title, style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 一设置项一卡（AppEntryCard），选项间距与主页同为 12dp。
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (category) {
                                // 通用 = 原「外观」+「内容分级」（页内两个集合区块）。
                                SettingsCategory.GENERAL -> GeneralContent(
                                    vm = vm,
                                    themeMode = themeMode,
                                    themeColor = themeColor,
                                    showTags = showTags,
                                    defaultR18 = defaultR18,
                                    showR18Tag = showR18Tag,
                                    onInfo = ::showInfoDialog
                                )
                                // 播放页内两个集合区块（默认 / 超分）。
                                SettingsCategory.PLAYBACK -> PlaybackContent(
                                    vm = vm,
                                    fsOrientation = fsOrientation,
                                    qualityPref = qualityPref,
                                    downloadQualityPref = downloadQualityPref,
                                    srEnabled = srEnabled,
                                    srModel = srModel,
                                    debugDetails = debugDetails,
                                    onInfo = ::showInfoDialog,
                                    onShowModels = { showSrInfo = true }
                                )
                                SettingsCategory.STORAGE -> StorageContent(
                                    vm = vm,
                                    rootName = rootName,
                                    downloadRootName = downloadRootName,
                                    onPickFolder = onPickFolder,
                                    onPickDownloadFolder = onPickDownloadFolder
                                )
                                SettingsCategory.DEVELOPER -> DeveloperContent(
                                    vm = vm,
                                    debugDetails = debugDetails,
                                    onOpenDiagnostics = { showDiagnostics = true }
                                )
                                SettingsCategory.ABOUT -> AboutContent()
                            }
                        }
                    }
                }
            }
        }

        // 说明弹窗（页面级单实例，盖住子页含顶栏）。
        AppDialog(
            visible = showInfo,
            onDismiss = { showInfo = false },
            title = infoData.title,
            text = infoData.text
        )
        // 超分「方案」说明（模型列表，富内容）。
        AppDialog(
            visible = showSrInfo,
            onDismiss = { showSrInfo = false },
            title = "超分方案说明",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SuperRes.modelsFor(debugDetails).forEach { m ->
                        Column {
                            Text(SuperRes.label(m), style = MaterialTheme.typography.titleSmall)
                            Text(
                                SuperRes.desc(m),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        "所有方案都按屏幕分辨率与总像素上限收敛放大倍率；源分辨率已超过屏幕时不再放大，只做增强。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    if (showDiagnostics) {
        DiagnosticsSheet(onDismiss = { showDiagnostics = false }, standalone = true)
    }
}

/* ---------- 各分类页内容（一设置项一张 AppEntryCard） ---------- */

/** 「通用」页 = 原「外观」+「内容分级」合并，页内两个集合区块。 */
@Composable
private fun GeneralContent(
    vm: VideoViewModel,
    themeMode: String,
    themeColor: String,
    showTags: Boolean,
    defaultR18: Boolean,
    showR18Tag: Boolean,
    onInfo: (String, String) -> Unit
) {
    SettingsSection("外观") {
        AppearanceContent(
            vm = vm,
            themeMode = themeMode,
            themeColor = themeColor,
            showTags = showTags,
            onInfo = onInfo
        )
    }
    SettingsSection("内容分级") {
        ContentRatingContent(
            vm = vm,
            defaultR18 = defaultR18,
            showR18Tag = showR18Tag,
            onInfo = onInfo
        )
    }
}

/** 「外观」区块 = 主题与显示 + 半透明材质（材质项已从开发者页移入）。 */
@Composable
private fun AppearanceContent(
    vm: VideoViewModel,
    themeMode: String,
    themeColor: String,
    showTags: Boolean,
    onInfo: (String, String) -> Unit
) {
    AppEntryCard(title = "主题模式", subtitle = "浅色 / 深色 / 跟随系统") {
        ChoiceChips(
            options = ThemeOption.entries.map { it.label to it.value },
            current = themeMode,
            onSelect = vm::setThemeMode
        )
    }
    AppEntryCard(
        title = "卡片显示标签",
        subtitle = "在视频卡片底部显示标签，关闭以保持简洁",
        trailing = { Switch(checked = showTags, onCheckedChange = vm::setShowTags) }
    )
    AppEntryCard(title = "主题色", subtitle = "应用强调色，全局生效") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            THEME_COLOR_OPTIONS.forEach { opt ->
                val selected = themeColor == opt.value
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isSystemInDarkTheme()) opt.dark else opt.light)
                        .then(
                            if (selected) Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            ) else Modifier
                        )
                        .clickable { vm.setThemeColor(opt.value) },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "已选中",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
    // ---- 半透明材质（原在开发者页，已移入外观页）----
    // 一根不透明度条 + 一个磨砂方案：底栏玻璃 / 所有角标胶囊 / 回顶按钮统一生效
    //（实现在 ui/components/GlassMaterial.kt，一处定义）。
    BarTuningCard(
        title = "材质不透明度",
        subtitle = "底栏 · 顶栏 · 悬浮条 · 回顶按钮 统一生效（0% = 完全透明）",
        value = vm.glassOpacityFlow.collectAsStateWithLifecycle().value,
        onValueChange = vm::setGlassOpacity
    )
    val glassMode = GlassMode.of(vm.glassModeFlow.collectAsStateWithLifecycle().value)
    AppEntryCard(
        title = "材质",
        subtitle = glassMode.label,
        info = "「关」= 实心不透明，材质系统整体关闭；「透明」= 半透明色罩、无模糊（零成本，默认）；" +
            "「磨砂」= 真实背景模糊 + 边缘折射（AndroidLiquidGlass，需要 Android 12+，折射部分 13+）。",
        onInfo = onInfo
    ) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            ChoiceChips(
                options = GlassMode.options,
                current = glassMode.ordinal.toString(),
                onSelect = { vm.setGlassMode(it.toIntOrNull() ?: 1) }
            )
        }
    }
}

@Composable
private fun ContentRatingContent(
    vm: VideoViewModel,
    defaultR18: Boolean,
    showR18Tag: Boolean,
    onInfo: (String, String) -> Unit
) {
    AppEntryCard(
        title = "默认 R18",
        subtitle = "只显示 R18 内容（在线与本地）",
        info = "开启后隐藏分级筛选项，在线与本地都只显示 R18 内容；关闭后恢复手动分级筛选。",
        onInfo = onInfo,
        trailing = { Switch(checked = defaultR18, onCheckedChange = vm::setDefaultR18) }
    )
    AppEntryCard(
        title = "显示 R18 标签",
        subtitle = "在卡片右上角显示 R18 角标",
        info = "只给确实为 R18 的内容打角标；未匹配到 iwara 的本地视频不打标。",
        onInfo = onInfo,
        trailing = { Switch(checked = showR18Tag, onCheckedChange = vm::setShowR18Tag) }
    )
}

/** 播放页 = 两个集合区块：「默认」（播放页三项）与「超分」。 */
@Composable
private fun PlaybackContent(
    vm: VideoViewModel,
    fsOrientation: String,
    qualityPref: String,
    downloadQualityPref: String,
    srEnabled: Boolean,
    srModel: String,
    debugDetails: Boolean,
    onInfo: (String, String) -> Unit,
    onShowModels: () -> Unit
) {
    SettingsSection("默认") {
        AppEntryCard(title = "全屏方向", subtitle = "智能识别=按视频画面宽高比自动选横/竖屏") {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ChoiceChips(
                    options = OrientationOption.entries.map { it.label to it.value },
                    current = fsOrientation,
                    onSelect = vm::setFsOrientation
                )
            }
        }
        AppEntryCard(
            title = "默认分辨率",
            subtitle = "在线视频优先使用的清晰度",
            info = "该档位不可用时自动回退到最高可用档。",
            onInfo = onInfo
        ) {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ChoiceChips(
                    options = QUALITY_OPTIONS,
                    current = qualityPref,
                    onSelect = vm::setQualityPref
                )
            }
        }
        AppEntryCard(
            title = "下载分辨率",
            subtitle = "下载在线视频优先使用的清晰度",
            info = "与在线播放的「默认分辨率」互相独立；该档位不可用时自动回退到最高可用档。",
            onInfo = onInfo
        ) {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ChoiceChips(
                    options = QUALITY_OPTIONS,
                    current = downloadQualityPref,
                    onSelect = vm::setDownloadQualityPref
                )
            }
        }
    }
    // 原「超分」分类整体移入（不再单独占主页一格）。
    SettingsSection("超分") {
        SuperResContent(
            vm = vm,
            srEnabled = srEnabled,
            srModel = srModel,
            debugDetails = debugDetails,
            onInfo = onInfo,
            onShowModels = onShowModels
        )
    }
}

@Composable
private fun SuperResContent(
    vm: VideoViewModel,
    srEnabled: Boolean,
    srModel: String,
    debugDetails: Boolean,
    onInfo: (String, String) -> Unit,
    onShowModels: () -> Unit
) {
    AppEntryCard(
        title = "默认启用超分",
        subtitle = "播放时默认应用，本地 / 在线通用",
        info = "决定播放页打开时的默认超分状态；播放中可随时在超分菜单临时切换，不影响此默认值。",
        onInfo = onInfo,
        trailing = { Switch(checked = srEnabled, onCheckedChange = vm::setSrEnabled) }
    )
    // 把每个方案的适用场景收进弹窗，菜单/chip 上只放模型名。
    AppEntryCard(
        title = "方案",
        subtitle = "按模型名选择",
        info = "查看每个模型的适用场景",
        onInfo = { _, _ -> onShowModels() }
    ) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            ChoiceChips(
                options = SuperRes.modelsFor(debugDetails).map { SuperRes.label(it) to it },
                current = srModel,
                onSelect = vm::setSrModel
            )
        }
    }
}

@Composable
private fun StorageContent(
    vm: VideoViewModel,
    rootName: String,
    downloadRootName: String,
    onPickFolder: () -> Unit,
    onPickDownloadFolder: () -> Unit
) {
    AppEntryCard(title = "视频根目录", subtitle = "选择后自动递归导入其中所有视频") {
        Text(
            text = rootName.ifBlank { "未设置" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onPickFolder) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text("重新选择文件夹")
        }
    }
    AppEntryCard(title = "下载目录", subtitle = "在线视频的保存位置；默认与管理器根目录相同") {
        Text(
            text = downloadRootName.ifBlank { "默认（管理器根目录）" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPickDownloadFolder) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("选择文件夹")
            }
            if (downloadRootName.isNotBlank()) {
                TextButton(onClick = { vm.setDownloadRoot(null) }) {
                    Text("恢复默认")
                }
            }
        }
    }
}

@Composable
private fun DeveloperContent(
    vm: VideoViewModel,
    debugDetails: Boolean,
    onOpenDiagnostics: () -> Unit
) {
    // 它是总门控：页里除它以外的所有内容都只在开启后渲染（见下面的 if）。
    AppEntryCard(
        title = "开发者模式",
        trailing = { Switch(checked = debugDetails, onCheckedChange = vm::setDebugDetails) }
    )
    if (!debugDetails) return
    // 诊断入口：诊断日志常开记录，让任何页面复现的问题都能就地回看。
    AppEntryCard(
        title = "运行状态 / 日志",
        subtitle = "网络、超分与错误状态；日志可一键复制",
        onClick = onOpenDiagnostics
    )

}


/** 材质/调参滑条行（语义 = 0 完全透明 / 100 完全不透明），拖动即生效。
 *  [display] 让显示文案可自定义 —— 有些量的单位不是百分比（如 dp）。 */
@Composable
private fun BarTuningCard(
    title: String,
    subtitle: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    display: (Float) -> String = { "${(it * 100).roundToInt()}%" }
) {
    AppEntryCard(
        title = title,
        subtitle = subtitle
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppSlider(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f)
            )
            Text(
                display(value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(52.dp)
            )
        }
    }
}

@Composable
private fun AboutContent() {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    AppEntryCard(
        title = "版本",
        trailing = {
            Text(version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    )
}

/* ---------- 行组件 ---------- */

@Composable
private fun ChoiceChips(
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = current == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                colors = accentChipColors()
            )
        }
    }
}
