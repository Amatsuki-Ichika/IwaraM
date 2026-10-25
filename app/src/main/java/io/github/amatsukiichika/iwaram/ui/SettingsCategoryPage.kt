package io.github.amatsukiichika.iwaram.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaram.ui.components.AppDialog
import kotlinx.coroutines.CancellationException

/* ---------- 设置分类子页（原 SettingsScreen.kt 分类子页区块） ---------- */
/** 长说明弹窗的内容（标题 = 行名，正文 = 完整解释）。 */
private data class InfoData(val title: String, val text: String)

/**
 * 设置分类子页：自带顶栏（返回箭头 = 关子页）+ 该分类的全部功能选项。
 *
 * 预测式返回：本 handler 比外层设置页的**后注册**（子页内容后组合）⇒ 子页开着时手势归它；
 * 跟手位移/冻结/取消与设置页本体同款（松手完成不复位，冻结位移交给退场滑出动画）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryPage(
    category: SettingsCategory,
    vm: VideoViewModel,
    onBack: () -> Unit,
    onPickFolder: () -> Unit,
    onPickDownloadFolder: () -> Unit,
    backEnabled: Boolean,
    /** 宿主下发：子页进出场动画正在播（圆角自适应判据，同设置页本体的 pageAnimating）。 */
    animating: Boolean,
    /** 手势进度上抛（拖动中 0f..1f / 松手与取消 null）→ 设置主页实时联动回位。 */
    onBackProgress: (Float?) -> Unit = {},
    /** 手势松手完成：回调（冻结进度, 松手速度 进度/秒），上抛给设置主页供子页退场衔接动量。 */
    onBackReleased: (Float, Float) -> Unit = { _, _ -> }
) {
    // —— 窄流（从 SettingsScreen 移入：只有分类页需要这些值） ——
    val showTags by vm.showTagsFlow.collectAsStateWithLifecycle()
    val defaultR18 by vm.defaultR18Flow.collectAsStateWithLifecycle()
    val showR18Tag by vm.showR18TagFlow.collectAsStateWithLifecycle()
    val hideUnmatched by vm.hideUnmatchedFlow.collectAsStateWithLifecycle()
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
        // 每次手势一个新实例：对 progress 事件做时间差分，估算松手瞬间的跟手速度。
        val velocity = BackVelocityTracker()
        try {
            flow.collect { event ->
                backProgress = event.progress.coerceIn(0f, 1f)
                velocity.onEvent(backProgress, System.nanoTime())
                // 上抛给宿主：设置主页按 c = 1 − p 实时联动回位。
                onBackProgress(backProgress)
            }
            // 松手完成：不复位 backProgress —— 冻结位移留给退场滑出动画（与设置页本体一致）；
            // 进度上报置 null → 主页让位切回 tween 规格（随弹栈滑出同步回位）。
            // 动量先于弹栈上抛：子页退场滑出按"剩余距离 / 松手速度"衔接时长。
            onBackProgress(null)
            onBackReleased(backProgress, velocity.velocity())
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
                                    hideUnmatched = hideUnmatched,
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
