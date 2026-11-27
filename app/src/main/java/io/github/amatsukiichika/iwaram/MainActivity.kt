package io.github.amatsukiichika.iwaram

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import io.github.amatsukiichika.iwaram.data.IwaraAccount
import io.github.amatsukiichika.iwaram.data.IwaraClient
import io.github.amatsukiichika.iwaram.data.IwaraFileRef
import io.github.amatsukiichika.iwaram.data.WatchHistoryEntity
import io.github.amatsukiichika.iwaram.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaram.data.resumeStartMs
import io.github.amatsukiichika.iwaram.data.autoResumePlanBeforeRestore
import io.github.amatsukiichika.iwaram.data.loadPendingDownloads
import io.github.amatsukiichika.iwaram.data.thumbnailFileIdFromUrl
import io.github.amatsukiichika.iwaram.i18n.AppLanguage
import io.github.amatsukiichika.iwaram.i18n.LanguageMode
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.i18n.trCount
import io.github.amatsukiichika.iwaram.ui.sourceDisplayName
import io.github.amatsukiichika.iwaram.ui.COVER_DIM_ALPHA
import io.github.amatsukiichika.iwaram.ui.COVER_MOTION_MS
import io.github.amatsukiichika.iwaram.ui.COVER_SHIFT_DP
import io.github.amatsukiichika.iwaram.ui.BackMomentum
import io.github.amatsukiichika.iwaram.ui.LibraryScreen
import io.github.amatsukiichika.iwaram.ui.LibraryTopBar
import io.github.amatsukiichika.iwaram.ui.IntegrityCheckController
import io.github.amatsukiichika.iwaram.ui.IntegrityCheckTarget
import io.github.amatsukiichika.iwaram.ui.OnlineNetState
import io.github.amatsukiichika.iwaram.ui.OnlineVideoExtras
import io.github.amatsukiichika.iwaram.ui.OnboardingScreen
import io.github.amatsukiichika.iwaram.ui.PlayerScreen
import io.github.amatsukiichika.iwaram.ui.QualityOption
import io.github.amatsukiichika.iwaram.ui.RelatedVideo
import io.github.amatsukiichika.iwaram.ui.SettingsScreen
import io.github.amatsukiichika.iwaram.ui.settings.UpdateDialogs
import io.github.amatsukiichika.iwaram.ui.settings.UpdateViewModel
import io.github.amatsukiichika.iwaram.ui.VideoViewModel
import io.github.amatsukiichika.iwaram.ui.exitDurationMs
import io.github.amatsukiichika.iwaram.ui.rememberAdaptivePageShape
import io.github.amatsukiichika.iwaram.ui.DiagLog
import io.github.amatsukiichika.iwaram.ui.browse.GlobalFilterSheet
import io.github.amatsukiichika.iwaram.ui.browse.HomeTab
import io.github.amatsukiichika.iwaram.ui.browse.IwaraClientViewModel
import io.github.amatsukiichika.iwaram.ui.browse.IwaraBottomBar
import io.github.amatsukiichika.iwaram.ui.browse.IwaraExploreScreen
import io.github.amatsukiichika.iwaram.ui.browse.IwaraTopBar
import io.github.amatsukiichika.iwaram.ui.browse.IwaraOverlayHost
import io.github.amatsukiichika.iwaram.ui.browse.CommentsPanel
import io.github.amatsukiichika.iwaram.ui.browse.isIwaraUserUuid
import io.github.amatsukiichika.iwaram.ui.browse.iwaraProfileTargetOf
import io.github.amatsukiichika.iwaram.ui.browse.BackOwner
import io.github.amatsukiichika.iwaram.ui.browse.backOwner
import io.github.amatsukiichika.iwaram.ui.browse.LocalDownloadedIwaraIds
import io.github.amatsukiichika.iwaram.ui.browse.LocalShowR18Tag
// Debug 模式开关：LocalDebugDetails 分发 + 错误文案两轨（人话/技术细节）
import io.github.amatsukiichika.iwaram.data.friendlyIwaraError
import io.github.amatsukiichika.iwaram.data.importFolder
import io.github.amatsukiichika.iwaram.ui.components.AppTopBar
import io.github.amatsukiichika.iwaram.ui.components.IntegrityCheckDialog
import io.github.amatsukiichika.iwaram.ui.components.GlassMode
import io.github.amatsukiichika.iwaram.ui.components.GlassSettings
import io.github.amatsukiichika.iwaram.ui.components.GlassTopBar
import io.github.amatsukiichika.iwaram.ui.components.LocalGlassSettings
import io.github.amatsukiichika.iwaram.ui.components.LocalMainContentScrollActivity
import io.github.amatsukiichika.iwaram.ui.components.MainContentScrollActivity
import io.github.amatsukiichika.iwaram.ui.components.glassContentBackdropSource
import io.github.amatsukiichika.iwaram.ui.components.rememberGlassContentBackdrop
import io.github.amatsukiichika.iwaram.ui.components.LocalDebugDetails
import io.github.amatsukiichika.iwaram.ui.components.horizontalSlideSpec
import io.github.amatsukiichika.iwaram.ui.components.SheetScrimState
import io.github.amatsukiichika.iwaram.ui.components.SearchFocusEvent
import io.github.amatsukiichika.iwaram.ui.components.SearchPhase
import io.github.amatsukiichika.iwaram.ui.components.TOP_BAR_CONTENT_HEIGHT
import io.github.amatsukiichika.iwaram.ui.components.reduceSearchPhase
import io.github.amatsukiichika.iwaram.ui.browse.isActionSheet
import io.github.amatsukiichika.iwaram.ui.browse.resetEntrancePlayed
import io.github.amatsukiichika.iwaram.ui.browse.IwaraMeScreen
import io.github.amatsukiichika.iwaram.ui.browse.profileRevertIndex
import io.github.amatsukiichika.iwaram.ui.browse.authorNavPlan
import io.github.amatsukiichika.iwaram.ui.browse.AuthorNavPlan
import io.github.amatsukiichika.iwaram.ui.browse.Segment
import io.github.amatsukiichika.iwaram.ui.browse.IwaraNavActions
import io.github.amatsukiichika.iwaram.ui.browse.IwaraOverlay
import io.github.amatsukiichika.iwaram.ui.browse.OverlayEntry
import io.github.amatsukiichika.iwaram.ui.browse.drawOrderSeqs
import io.github.amatsukiichika.iwaram.ui.browse.zIndexOf
import io.github.amatsukiichika.iwaram.ui.browse.layerStillOnScreen
import io.github.amatsukiichika.iwaram.ui.browse.overlayOnScreenTopSeq
import io.github.amatsukiichika.iwaram.ui.browse.buildEntity
import io.github.amatsukiichika.iwaram.ui.browse.downloadVideo
import io.github.amatsukiichika.iwaram.ui.browse.startDownloadById
import io.github.amatsukiichika.iwaram.ui.browse.loadPlaylists
import io.github.amatsukiichika.iwaram.ui.browse.loadRelatedVideos
import io.github.amatsukiichika.iwaram.ui.browse.loadVideoOnlineState
import io.github.amatsukiichika.iwaram.ui.browse.resolveOnlineUrl
import io.github.amatsukiichika.iwaram.ui.browse.toggleFollow
import io.github.amatsukiichika.iwaram.ui.browse.toggleLike
import io.github.amatsukiichika.iwaram.ui.theme.IwaraTheme
import io.github.amatsukiichika.iwaram.ui.theme.resolveDarkTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 覆盖层栈序号的进程级单调计数器。
 *
 * 必须放在组合之外：放进 `remember` 会随重组被打回初值（见 [MainActivity] 里 pushOverlay 的说明），
 * 一旦序号重复，Compose 的 `key(seq)` 就会把两个不同的页面当成同一份状态槽。
 */
private val overlaySeqCounter = java.util.concurrent.atomic.AtomicLong(0L)

/** 二级页覆盖层（设置页 / 播放页）的滑入滑出时长 —— 与 `IwaraOverlayHost` 的 ENTER/EXIT_SLIDE_MS 一致。 */
private const val PAGE_SLIDE_MS = 320

/*
 * 看门狗及其三个阈值常量已删除：它用整机帧间隔判断"玻璃太慢"，而冷启动 / 回前台的突发卡顿
 *（DB、缩略图解码、重建录制层）会轻易越过预热窗口，把「磨砂」档误降成「透明」，且降级标记
 * 在进程存活期间不会复位。真机跑不动时由用户在「设置 › 通用 › 外观 › 材质」里自行换档。
 */

/**
 * 拉伸过滚动后恢复重录的静默时长（毫秒）。
 * 松手后 EdgeEffect 还要自己回弹一段（回弹不再产生滚动事件），取 500ms 覆盖大部分回弹；
 * 期间底栏继续画上一帧离屏层（磨砂冻结），不透明。
 */
private const val OVERSCROLL_HOLD_SETTLE_MS = 500L

private fun nextOverlaySeq(): Long = overlaySeqCounter.incrementAndGet()


/**
 * 播放解析编排状态（从 [MainActivity.MainContent] 下沉）。
 *
 * 只收「数据槽 + 编排入口」：currentVideo / 在线解析产物 / 相关推荐等状态，以及
 * [startResolve] / [resolveAndPlay] / [playHistoryItem] 三个入口。层序分配、进出场动画与
 * 「页面开关」（showingPlayer / openPlayer / closePlayer）仍留在 MainContent —— 它们与
 * AnimatedVisibility / MutableTransitionState / 预测式返回强耦合；本类通过 [onOpenPlayer]
 * 回调请求开页，closePlayer 的清场部分走 [clearPlayback]。
 *
 * 由此 MainContent 顶层不再声明这些槽：点赞 / 地址解析 / 相关推荐到达时只重组真正读取
 * 它们的调用点，不再重跑整棵 MainContent 函数体。
 */
private class PlayerResolutionState(
    private val vm: VideoViewModel,
    private val cvm: IwaraClientViewModel,
    private val context: Context,
    /**
     * debug 开关**读取器**：回调 lambda 里读不到 CompositionLocal，但也不能在构造期捕获快照 ——
     * 运行中切换开发者模式后，下一次解析失败必须立即按新开关取轨（面板与 Toast 同一来源）。
     */
    private val debugDetails: () -> Boolean,
) {
    var currentVideo by mutableStateOf<VideoEntity?>(null)
    var currentOnline by mutableStateOf<IwaraVideoItem?>(null)
    var onlineLiked by mutableStateOf(false)
    var onlinePlayUrl by mutableStateOf<String?>(null)
    /** 在线地址解析失败的说明文字。非空时留在播放页显示错误面板，不关页。 */
    var resolveError by mutableStateOf<String?>(null)
    // 全部可选清晰度与当前档名（分辨率切换用）。
    var onlineQualities by mutableStateOf<List<QualityOption>>(emptyList())
    var onlineQualityName by mutableStateOf<String?>(null)
    // 播放页「相关推荐」（在线 = related 接口结果；本地 = 同作者/同标签实时算）。
    var relatedVideos by mutableStateOf<List<IwaraVideoItem>>(emptyList())
    // 观看历史：本次打开的续播起点（ms），只有"从观看历史点开"才非 0；openPlayer(startMs) 是唯一写入口。
    var playerStartMs by mutableLongStateOf(0L)
    // 解析代数令牌：每次 startResolve 递增；过期回调（页面已关闭 / 已发起新解析）静默丢弃。
    var resolveGen by mutableLongStateOf(0L)

    /** MainContent 注入：打开播放页（层序 / 动画归它管）。 */
    lateinit var onOpenPlayer: (Long) -> Unit

    fun startResolve(item: IwaraVideoItem, closeOnFailure: Boolean) {
        resolveGen++
        val gen = resolveGen
        onlinePlayUrl = null
        resolveError = null
        cvm.resolveOnlineUrl(item) { result, err ->
            if (gen != resolveGen) return@resolveOnlineUrl // 过期回调：静默丢弃
            if (result == null) {
                // Toast 与面板副行按 debug 开关取轨（关=人话，开=技术细节原样）
                val text = err?.let { if (debugDetails()) it else friendlyIwaraError(it) }
                    ?: tr("无法播放该视频", "Unable to play this video")
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                resolveError = text
            } else {
                onlinePlayUrl = result.url
                onlineLiked = result.liked
                onlineQualities = result.qualities
                onlineQualityName = result.qualityName
                resolveError = null
            }
        }
    }

    /**
     * 点开即播：先用列表数据秒开播放页（播放区显示加载提示），
     * 播放地址在后台解析，解析完成自动开播；解析失败提示后退回。
     * [startMs] = 观看历史的续播起点（普通入口走默认 0）。
     */
    fun resolveAndPlay(item: IwaraVideoItem, startMs: Long = 0L) {
        // 本地已有同 id 视频（下载过/关联过）时直接播本地版本，省去解析与流量。
        vm.findLocalByIwaraId(item.id)?.let { local ->
            currentVideo = local
            currentOnline = item
            onlineLiked = item.liked == true
            onlinePlayUrl = null
            onlineQualities = emptyList()
            onlineQualityName = null
            // 开播放页一律走 onOpenPlayer(0L)（拿到新的层序号 = 画在最上面）。
            onOpenPlayer(startMs)
            return
        }
        currentVideo = cvm.buildEntity(item)
        currentOnline = item
        onlineLiked = item.liked == true
        onlinePlayUrl = null
        onlineQualities = emptyList()
        onlineQualityName = null
        onOpenPlayer(startMs)
        startResolve(item, closeOnFailure = true)
    }

    /**
     * 观看历史：从历史行点开。
     * 本地条目先查现库（元数据取最新；有 iwaraId 的话播放页在线互动也接得上），
     * 文件已不在库 → 有 iwaraId 转在线、否则提示。在线条目 → 用存档字段重建最小
     * IwaraVideoItem 走 [resolveAndPlay]（它本来就优先播本地副本，下载/关联过的自然合流）。
     * 续播起点由 [resumeStartMs] 决定（进度 ≥5s 且不足 95% 才续，否则从头）。
     */
    fun playHistoryItem(entry: WatchHistoryEntity) {
        val startMs = resumeStartMs(entry.positionMs, entry.durationMs)
        if (entry.isLocal) {
            val local = vm.findLocalByUri(entry.localUri)
            if (local != null) {
                currentVideo = local
                currentOnline = null
                onOpenPlayer(startMs)
                return
            }
            if (entry.iwaraId == null) {
                Toast.makeText(
                    context,
                    tr("文件已不在库中", "File is no longer in the library"),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            // 本地文件没了但 iwaraId 还在 → 落到下面的在线分支
        }
        val item = IwaraVideoItem(
            id = entry.iwaraId,
            title = entry.title,
            user = IwaraAccount(id = entry.authorId, username = entry.authorId, name = entry.author),
            file = IwaraFileRef(id = thumbnailFileIdFromUrl(entry.thumbnail))
        )
        resolveAndPlay(item, startMs)
    }

    /** closePlayer 的清场部分：作废在途解析并清空播放上下文。 */
    fun clearPlayback() {
    resolveError = null
    onlinePlayUrl = null
    onlineQualities = emptyList()
    onlineQualityName = null
    }
}

class MainActivity : ComponentActivity() {

    /** 离开应用即清空入场动画已播标记：回到 app 后的首次刷新重新播放（同会话内划走划回不重播）。 */
    override fun onStop() {
        super.onStop()
        resetEntrancePlayed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val app = application as IwaraApp
            val vm: VideoViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { VideoViewModel(app) }
                }
            )
            val cvm: IwaraClientViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { IwaraClientViewModel(app) }
                }
            )
            // 应用内更新：状态机随 Activity 存活；弹窗挂在 MainContent 根部，手动/自动检查共用。
            val updateVm: UpdateViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { UpdateViewModel(app) }
                }
            )

            LaunchedEffect(Unit) { vm.onStart() }

            // 跟随系统语言时，设备语言变化会重建 Activity 而 ViewModel 存活：分桶缓存
            //（月份 / 根目录 / 「未指定」作者等标签）仍是旧语言。这里把解析出的 Locale 报给
            // ViewModel 重算；rememberSaveable 让"上一次的 Locale"跨 Activity 重建保留，
            // 只有真的变了才重算（旋转屏幕等其它配置变化不重复计算）。
            val configLanguageTag = LocalConfiguration.current.locales[0].toLanguageTag()
            val lastConfigLanguageTag = rememberSaveable { mutableStateOf(configLanguageTag) }
            LaunchedEffect(configLanguageTag) {
                if (lastConfigLanguageTag.value != configLanguageTag) {
                    lastConfigLanguageTag.value = configLanguageTag
                    vm.onLanguageChanged()
                }
            }

            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val themeColor by vm.themeColor.collectAsStateWithLifecycle()
            // 状态栏/手势条系统文字的高对比实时取色：按应用实际解析出的主题（themeMode
            // 可强制 light/dark，与系统无关）设定 isAppearanceLight —— 浅色主题用深色系统文字，
            // 深色主题（黑背景）用白色系统文字。`enableEdgeToEdge()` 的 auto 样式只按系统 uiMode
            // 定初始值，应用内主题与系统不一致时就会黑底黑字。主题变化与回到前台
            //（覆盖"跟随系统"模式在后台被切换的情形）都刷新；播放页覆盖期间由 PlayerScreen 接管。
            val darkTheme = resolveDarkTheme(themeMode, isSystemInDarkTheme())
            DisposableEffect(darkTheme) {
                fun apply(light: Boolean) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = light
                    controller.isAppearanceLightNavigationBars = light
                }
                apply(light = !darkTheme)
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) apply(light = !darkTheme)
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            // 截图保护：FLAG_SECURE 挂在 Activity 窗口上，播放页/全屏/最近任务预览一并生效。
            // 关闭时清标记；不要放在 onDispose 里无条件清，否则组合重跑会把开启状态误清掉。
            val screenshotProtection by app.repository.screenshotProtection.collectAsStateWithLifecycle()
            DisposableEffect(screenshotProtection) {
                if (screenshotProtection) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                onDispose {
                    // 退出组合时不清标记：用户仍开着保护，清掉会让期间的系统截图恢复可见。
                }
            }

            IwaraTheme(
                darkTheme = darkTheme,
                themeColor = themeColor
            ) {
                // 跨页共享的显示偏好：在线卡片左上角「已下载」药丸的判据 + R18 角标开关。
                // 用 CompositionLocal 下发，免得逐层穿过所有信息流/作者页的调用点。
                val downloadedIds by vm.downloadedIwaraIds.collectAsStateWithLifecycle()
                // 只收集窄流：若收集整个 UiState（只为其中的 showR18Tag 一个布尔值），
                // 每次视频列表变化都会重跑这次包裹整棵树的重组。
                val showR18Tag by vm.showR18TagFlow.collectAsStateWithLifecycle()
                // Debug 模式开关（真源 repo.debugDetails）：分发进整棵树，
                // 各错误渲染点据此决定显示人话还是技术细节。
                val debugDetails by vm.debugDetailsFlow.collectAsStateWithLifecycle()
                // 半透明材质设置（不透明度 + 磨砂方案）下发全局一份，
                // 底栏与所有角标胶囊都从这里取。
                val glassOpacity by vm.glassOpacityFlow.collectAsStateWithLifecycle()
                val glassMode by vm.glassModeFlow.collectAsStateWithLifecycle()
                // 主内容滚动活动：主 tab 页往里登记「正在滚 / 正在拖滚动条」，主内容背景源的
                // 录制门据此停录（见 GlassBackdrop 模块第三之二节与决策 0019）。
                val mainScrollActivity = remember { MainContentScrollActivity() }
                CompositionLocalProvider(
                    // 仓库正门：UI 各处经 LocalAppRepository.current 取数据/设置，
                    // 不再各自强转 application（见 IwaraApp.kt 的定义）。
                    LocalAppRepository provides app.repository,
                    LocalDownloadedIwaraIds provides downloadedIds,
                    LocalShowR18Tag provides showR18Tag,
                    LocalDebugDetails provides debugDetails,
                    LocalGlassSettings provides GlassSettings(glassOpacity, GlassMode.of(glassMode)),
                    LocalMainContentScrollActivity provides mainScrollActivity
                ) {
                    MainContent(vm, cvm, app, updateVm)
                }
            }
        }
    }

    /**
     * 管理器顶栏标题里的「共 N 个视频」。
     *
     * 自己订阅 [VideoViewModel.uiState]，避免 [MainContent] 顶层收集整个 UiState ——
     * 否则每次 DB 变化 / 筛选 / 切换分类 / 搜索都要重跑整棵树（含覆盖层栈、播放页、
     * 设置页的全部入参）。这样只有这个 Text 会重组。
     *
     * 用 `derivedStateOf` 只取 `size`：列表内容变了但数量没变时（例如扫描期间同一批视频被重写），
     * 连这个 Text 都不用重组。
     */
    @Composable
    private fun VideoCountText(vm: VideoViewModel, modifier: Modifier = Modifier) {
        val uiState by vm.uiState.collectAsStateWithLifecycle()
        val count by remember { derivedStateOf { uiState.videos.size } }
        Text(
            text = tr(
                "共 $count 个视频",
                "$count " + trCount(count, "个视频", "video", "videos")
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
    }

    /**
     * 顶栏刷新按钮：图标旋转动画的数据源在这里自己收集。
     *
     * 各流先 `map { it.loading }` + `distinctUntilChanged()` 再收集：只有加载态翻转时才
     * 重组本按钮，列表内容变化与本按钮无关。若在 MainContent 顶层收集各信息流整流来拼
     * `refreshing` 布尔，任何翻页都会把 MainContent 整个函数体重跑一遍。
     * `videoSegment` 也在本组件内读（切段同样只波及这里）。
     */
    @Composable
    private fun RefreshButton(currentTab: HomeTab, vm: VideoViewModel, cvm: IwaraClientViewModel) {
        val videosLoading by remember {
            cvm.videos.map { it.loading }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(false)
        val subscribedLoading by remember {
            cvm.subscribed.map { it.loading }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(false)
        val searchLoading by remember {
            cvm.searchResults.map { it.loading }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(false)
        val sessionChecking by remember {
            cvm.session.map { it.checking }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(false)

        val refreshing = when (currentTab) {
            // 订阅已并入视频页顶部分类：订阅分段下看订阅流的加载态
            HomeTab.VIDEO -> if (cvm.videoSegment == Segment.SUBSCRIBED) {
                subscribedLoading
            } else {
                videosLoading || searchLoading
            }
            HomeTab.ME -> sessionChecking
            HomeTab.MANAGER -> false
        }

        IconButton(onClick = {
            when (currentTab) {
                HomeTab.MANAGER -> vm.refresh()
                // 刷新图标 = 最高优先级强制刷新整个 Iwara 页：按当前分段
                //（订阅/视频/图片/帖子）+ 当前筛选分发，停在图片/帖子分段时也真的重拉该分段。
                HomeTab.VIDEO -> cvm.refreshIwaraTab()
                HomeTab.ME -> {
                    cvm.refreshMe()
                    cvm.loadPlaylists(force = true)
                }
            }
        }) {
            // 刷新中图标持续旋转，给出可见反馈
            val rotation = remember { Animatable(0f) }
            LaunchedEffect(refreshing) {
                while (refreshing) {
                    rotation.animateTo(
                        rotation.value + 360f,
                        tween(600, easing = LinearEasing)
                    )
                }
            }
            Icon(
                Icons.Filled.Refresh,
                contentDescription = tr("刷新", "Refresh"),
                modifier = Modifier.graphicsLayer { rotationZ = rotation.value }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainContent(vm: VideoViewModel, cvm: IwaraClientViewModel, app: IwaraApp, updateVm: UpdateViewModel) {
        val context = this@MainActivity
        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            // 持久化读写权限：读用于扫描，写用于客户端下载进管理器库。
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure { e ->
                // 静默吞掉的话用户只会看到「扫描不到文件」，SAF 授权异常将无从归因。
                DiagLog.log("SAF", "扫描目录权限持久化失败 uri=$uri: $e")
            }
            vm.importFolder(uri)
        }
        // 下载目录选择器（设置页「下载目录」）：同样持久化读写权限。
        val downloadFolderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure { e ->
                DiagLog.log("SAF", "下载目录权限持久化失败 uri=$uri: $e")
            }
            vm.setDownloadRoot(uri)
        }

        // 首次使用引导：引导未完成时整屏渲染引导页（完成/跳过后写 prefs，之后不再出现）。
        // 只做状态编排——选目录复用上面两个 launcher，语言切换立即生效并落盘，其余偏好走 ViewModel。
        // 注意：这里提前 return，主界面那批 remember 在引导完成后才首次创建（Compose 按分支处理，安全）。
        val repository = LocalAppRepository.current
        val onboardingDone by repository.onboardingDone.collectAsStateWithLifecycle()
        // 完整性检查弹窗：播放页长按「已下载」/ 主页卡片动作面板 / 本地库长按菜单共用同一状态，
        // 挂在主界面根部；弹窗自带独立窗口，与覆盖层栈和播放页层级无关。
        val integrityScope = rememberCoroutineScope()
        val integrityController = remember {
            IntegrityCheckController(
                scope = integrityScope,
                check = { videoId -> repository.checkDownloadedIntegrity(videoId) },
                requestRedownload = { videoId -> DownloadService.requestRedownload(context, videoId) }
            )
        }
        // 界面语言：引导页与设置页共用；切换时先让 AppLanguage 生效（驱动重组）再落盘。
        val languagePref by repository.language.collectAsStateWithLifecycle()
        if (!onboardingDone) {
            val libraryName by repository.rootName.collectAsStateWithLifecycle()
            val downloadName by repository.downloadRootName.collectAsStateWithLifecycle()
            val defaultR18 by vm.defaultR18Flow.collectAsStateWithLifecycle()
            val showTags by vm.showTagsFlow.collectAsStateWithLifecycle()
            OnboardingScreen(
                language = LanguageMode.fromPref(languagePref),
                libraryName = libraryName.takeIf { it.isNotBlank() },
                downloadName = downloadName.takeIf { it.isNotBlank() },
                defaultR18 = defaultR18,
                showTags = showTags,
                onLanguageChange = { mode ->
                    AppLanguage.set(mode)
                    repository.setLanguage(mode.toPref())
                    // 刷新缓存好的库内标签（月份/根目录/未知作者等），不重扫。
                    vm.onLanguageChanged()
                },
                onPickLibrary = { folderPicker.launch(null) },
                onPickDownload = { downloadFolderPicker.launch(null) },
                onClearDownload = { vm.setDownloadRoot(null) },
                onDefaultR18Change = { vm.setDefaultR18(it) },
                onShowTagsChange = { vm.setShowTags(it) },
                // 两条收尾路径（开始使用/跳过）都先记录 18+ 自我声明：跳过路径已在引导页内
                // 经确认对话框同意，因此这里不再区分。
                onFinish = {
                    repository.setAdultConfirmed(true)
                    repository.setOnboardingDone(true)
                },
                onSkip = {
                    repository.setAdultConfirmed(true)
                    repository.setOnboardingDone(true)
                }
            )
            return
        }

        // 通知权限：先让用户看完引导页、看到应用要做什么，进入主界面后再请求一次。
        // 已授权直接跳过；用户拒绝过（shouldShowRequestPermissionRationale 为 true ⇒ 之前拉起过，
        // 被拒后系统也会返回 true）就不再每次冷启动重复弹系统对话框。
        val notifPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) return@LaunchedEffect
            if (this@MainActivity.shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            ) {
                return@LaunchedEffect
            }
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 应用启动自动继续下载：上次进程被打断的 queued/running 任务，重新解析新鲜地址后入队。
        // 只恢复"本来在跑"的任务；暂停/失败是用户显式动作，不自动开始（判据见 resumePlan）。
        LaunchedEffect(Unit) {
            val pending = withContext(Dispatchers.IO) { repository.loadPendingDownloads() }
            if (pending.isEmpty()) return@LaunchedEffect
            // 顺序是正确性的一部分：liveIds 必须取"恢复列表条目之前"的内存快照。
            // 先 restoreIfAbsent 再取，本轮要续的任务自己也变成了"已在跑"，计划恒空
            //（自动继续一直不工作的根因；接线纯函数见 autoResumePlanBeforeRestore）。
            val liveBeforeRestore = DownloadState.tasks.value
            // 把记录恢复进列表（与下载页的 restoreIfAbsent 幂等），中断任务在下载页可见。
            DownloadState.restoreIfAbsent(pending.map { pendingToTask(it) })
            if (!repository.getAutoResumeDownloads()) return@LaunchedEffect
            val plan = autoResumePlanBeforeRestore(
                pending = pending,
                liveTasksBeforeRestore = liveBeforeRestore,
                enabled = true
            )
            plan.forEach { p ->
                cvm.startDownloadById(
                    id = p.videoId,
                    onError = { err -> DiagLog.log("download", "自动继续失败 id=" + p.videoId + ": " + err) }
                )
            }
        }

        // 应用内更新：启动时按「检查频率」判断是否该静默检查一次（手动检查在设置页「关于」）。
        LaunchedEffect(Unit) { updateVm.autoCheckIfDue() }

        // 底栏当前 tab；默认管理器（保持既有主界面）。
        var currentTab by remember { mutableStateOf(HomeTab.MANAGER) }
        // 本地页搜索框的三态提升到这里（MainContent 切 tab 不重建）：顶栏子树在切 tab 时会被
        //   AnimatedContent 销毁重建，页面级 remember 保不住「展开到哪儿」。rememberSaveable 让
        //   旋转屏幕后恢复成「展开无焦点」，不会一回来就弹键盘。
        var librarySearchPhase by rememberSaveable { mutableStateOf(SearchPhase.COLLAPSED) }
        val searchFocusManager = LocalFocusManager.current
        val searchKeyboard = LocalSoftwareKeyboardController.current
        // 切 tab 一律去焦点 + 收键盘（不收回搜索框、不清词、不清结果）：
        //   Iwara 页搜一半切到「我的」再切回来，不会再自动拉起输入法。
        LaunchedEffect(currentTab) {
            cvm.onSearchTabSwitch()
            librarySearchPhase = reduceSearchPhase(librarySearchPhase, SearchFocusEvent.TabSwitch)
            searchFocusManager.clearFocus()
            searchKeyboard?.hide()
        }
        // 顶栏「共 N 个视频」由 TopAppBar 里的 VideoCountText 自己订阅（见该函数注释）；
        // 若在这里收集整个 vm.uiState，每次 DB 变化 / 筛选 / 搜索都要重跑整棵树。
        // 会话状态：播放页在线互动的「关注」门控（登录态变化极少，整流收集无碍）。
        // 加载态收集已下沉进 [RefreshButton]（map 成布尔再收集），本作用域不订阅任何信息流；
        // 若在这里顶层收集各信息流整流来拼「刷新按钮旋转」布尔，任何翻页都会重跑整个函数体。
        val sessionState by cvm.session.collectAsStateWithLifecycle()
        // 播放解析编排状态（从本函数下沉，见 [PlayerResolutionState] 的说明）：
        // currentVideo / 在线解析产物 / 相关推荐等槽都在里面，点赞、地址解析、相关推荐到达时
        // 只重组真正读取它们的调用点，不再重跑整棵 MainContent 函数体。
        // Debug 模式开关：本作用域内所有 Toast / 错误文案的取轨判据
        //（回调 lambda 里读不到 CompositionLocal，故传读取器、调用时再取最新值）。
        val debugDetails by vm.debugDetailsFlow.collectAsStateWithLifecycle()
        val debugDetailsReader = rememberUpdatedState(debugDetails)
        val prs = remember { PlayerResolutionState(vm, cvm, context) { debugDetailsReader.value } }

        // 播放页作为覆盖层叠在主页之上：返回时主页始终在下方，滑出即可露出真实主页内容。
        var showingPlayer by remember { mutableStateOf(false) }
        var showingSettings by remember { mutableStateOf(false) }
        /**
         * 播放页当前显示的视频标识（本地文件 URI + 在线 id）—— 换片时据此清掉上一支视频残留的解析错误。
         */
        var playerShownVideoKey by remember { mutableStateOf("") }
        // 两个二级页各自"手势松手动量"（冻结进度 + 松手速度）：手势松手时由页面回调写入，
        // 退场动画按"剩余距离 / 速度"换算时长、带动量衔接；开页时清空，非手势关闭读不到就是默认规格。
        var playerBackMomentum by remember { mutableStateOf<BackMomentum?>(null) }
        var settingsBackMomentum by remember { mutableStateOf<BackMomentum?>(null) }
        // mpv 试验播放页的独立状态槽（debug 开关下的开发者入口）。日常播放不走这里：
        // 引擎开关下 PlayerScreen 直接以 mpv 引擎打开（单页同细节），本槽只服务「试验页」（自选源）。
        // 全局筛选面板（顶栏刷新键旁入口）的开关：它是 ModalBottomSheet（独立窗口），
        // 不进二级页栈，与动作面板同一种"不占页面栈"的处理。
        var showingFilter by remember { mutableStateOf(false) }

        // 在线播放上下文：用于 PlayerScreen 的附加操作（点赞/评论/下载）与作者跳转分支。
        // 全部可选清晰度与当前档名（分辨率切换用）。
        // 不再撞组合 key，IwaraOverlayHost 的渲染/退场集合/冻结位移也全部按序号比对。
        // 序号必须是进程级单调计数器：`remember { 0L }` 只在首次组合执行 lambda、缓存的是 0，
        // `+= 1` 改的是当次组合执行的局部变量，下一次重组又回到 0，会导致所有栈条目撞同一个
        // `key(seq)` —— Compose 把它们当成同一份状态槽，后压入的页面复用前一个页面的 Animatable。
        val overlays = remember { mutableStateListOf<OverlayEntry>() }

        /**
         * 最近被弹出、但宿主（[IwaraOverlayHost]）还没播完 320ms 退场动画的覆盖层页序号。
         *
         * 退场页仍然算"在屏"：绘制顺序与返回键归属都必须继续认它（见下面的 overlayOnScreenSeq）。
         * 宿主退场动画播完（移出 leavingPages）后经 [IwaraOverlayHost.onLeavingTopSeqChange] 回报 null 清掉。
         */
        var overlayExitSeq by remember { mutableStateOf<Long?>(null) }

        fun popOverlay() {
            if (overlays.isNotEmpty()) {
                val removed = overlays.removeAt(overlays.lastIndex)
                // 页面（不是独立窗口的动作面板）弹出后还要播退场：当帧就记为"仍在屏"的最上层，
                // 等宿主下一帧回报的话，这一帧它的 zIndex 会塌到 0（被播放页/设置页盖住）。
                if (!removed.isActionSheet) overlayExitSeq = removed.seq
            }
        }

        /** 弹掉 [keepIndex] 之上的所有覆盖层页（[keepIndex] = -1 表示全弹）；理由同 [popOverlay]。 */
        fun popOverlaysAbove(keepIndex: Int) {
            val removed = overlays.drop(keepIndex + 1)
            while (overlays.size > keepIndex + 1) overlays.removeAt(overlays.lastIndex)
            removed.lastOrNull { !it.isActionSheet }?.let { overlayExitSeq = it.seq }
        }

        /** 从动作面板进入新页面时替换面板本身，避免返回时又回到面板。 */
        fun pushOverlay(overlay: IwaraOverlay) {
            val top = overlays.lastOrNull()?.overlay
            when {
                // 用新序号替换面板：面板本身不参与退场动画（isActionSheet 被排除），
                // 保留旧序号会让新页面继承"已存在"身份、没有入场动画，看起来像原地跳变。
                top is IwaraOverlay.VideoActions || top is IwaraOverlay.ImageActions ->
                    overlays[overlays.lastIndex] = OverlayEntry(nextOverlaySeq(), overlay)
                // 连点两下同一个页面（结构相等即同一页）不重复压栈，否则会叠出两层一模一样的页面。
                top == overlay -> Unit
                // 同一个作者页只允许在栈里存在一层：已经存在就回退到那一层（把上面的都弹掉），
                // 不再新压一层（新层会当帧盖住正在退场的播放页）。
                overlay is IwaraOverlay.Profile -> {
                    val idx = profileRevertIndex(overlays.map { it.overlay }, overlay)
                    if (idx >= 0) {
                        popOverlaysAbove(idx)
                    } else {
                        overlays.add(OverlayEntry(nextOverlaySeq(), overlay))
                    }
                }
                else -> overlays.add(OverlayEntry(nextOverlaySeq(), overlay))
            }
        }

        // ---- 三层二级页的绘制顺序由「打开序号」决定 ----
        // 若把层序写死（Scaffold → 压暗层 → 覆盖层栈 → 设置页 → 播放页，最后画的最上面），
        // "在播放页里点作者"时作者页只能从播放页下面滑进来；且 PredictiveBackHandler 按
        // 注册顺序定谁响应（后注册者优先），播放页注册得晚，在作者页上按返回实际会关掉播放页。
        // 设置页 / 播放页各自记一个"打开序号"（与覆盖层栈共用同一个进程级计数器），
        // 渲染时按序号给各层 `Modifier.zIndex(rank)` —— 只改绘制顺序、不动调用位置，
        // 子树的 remember 状态不搬家。
        var settingsSeq by remember { mutableStateOf(0L) }
        var playerSeq by remember { mutableStateOf(0L) }

        // ---- 播放页入场/退场合并成单一时序源（避免"快速退出再进入边缘卡出空隙"）----
        // 若入场由独立的 Animatable 驱动、退场交给 AnimatedVisibility 的 slideOutHorizontally，
        // 同一条位移就有两个主人：快速"退出→再进"时两条位移会叠加；且退场被打断时
        // AnimatedVisibility 仍保留播放页，PlayerScreen 内部 `backProgress`（预测返回松手时
        // 冻住的跟手位移）一直不复位，页面会带着残留位移停在屏上。
        // 现在（与 IwaraOverlayHost 同一范式：单一时序源 + 唯一生命周期）：
        //   · 一个 Animatable 同时管进和出（打开 → 补间到 0；关闭 → 补间到 1）；
        //   · AnimatedVisibility 的 enter/exit 双双置 None，只保留"挂载/移除"语义；
        //   · `playerMounted` 承担"退场动画未播完不许摘"；
        //   · 打断 = 从当前位置直接朝新目标补间，结构上不可能叠加、不可能停在半路；
        //   · PlayerScreen 收到 `entryToken`（每次打开都会变的 playerSeq），重进时清零 `backProgress`。
        // 值停在 1（屏外右）时打开必然滑入；已在台前（值 = 0）时 `animateTo(0f)` 是零帧动画。
        val playerSlide = remember { Animatable(1f) }   // 1 = 全屏外右；0 = 就位
        /**
         * 退场动画还没播完（播完才允许把播放页从组合里摘掉）。
         *
         * 该"挂载"标记必须同步置位（在 [closePlayer] 里设），不能靠 `LaunchedEffect` ——
         * `LaunchedEffect` 只在帧提交之后才跑，一旦那一帧被拖慢，页面就永远挂载不上。
         * 因此挂载与否是一个纯派生值：`playerMounted = showingPlayer || playerExitPending`，
         * 点下去那一帧就已经为 true。
         */
        var playerExitPending by remember { mutableStateOf(false) }
        var playerSliding by remember { mutableStateOf(false) }
        /**
         * 入场滑动是否已落位（补间到 0 之后置 true；下一次全新打开时复位）。
         *
         * 播放页据此**延迟渲染视频画面**：滑入期间只画视频封面遮罩，不建 PlayerView；
         * 落位后才挂载播放器画面，首帧出来前遮罩继续盖着，画面就绪后淡出。
         * 这样 320ms 位移里既不含整页首组合、也不含 SurfaceView 首次 attach，
         * 而用户看到的是"点一下 → 立刻滑入"，进入时是封面而不是黑屏或骨架壳。
         */
        var playerEntrySettled by remember { mutableStateOf(false) }
        val playerMounted = showingPlayer || playerExitPending
        // 播放页退场动画（下面的 LaunchedEffect）也要用屏宽换算"剩余距离"，声明提到它前面。
        val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
        LaunchedEffect(showingPlayer) {
            if (showingPlayer) {
                // 重新进场：撤销"待摘页"，并把位移补间回 0（打断退场时就是从当前位置接着滑回来）。
                playerExitPending = false
                playerSliding = true
                try {
                    playerSlide.animateTo(0f, tween(PAGE_SLIDE_MS, easing = FastOutSlowInEasing))
                    // 正常收敛后兜底归位（幂等；被打断时走不到这里，交给下一次 effect 续跑）。
                    playerSlide.snapTo(0f)
                } finally {
                    playerSliding = false
                }
                // 落位后才允许播放页挂 PlayerView：滑入动画里每帧只剩位移。
                playerEntrySettled = true
            } else if (playerExitPending) {
                playerSliding = true
                try {
                    // 手势松手 → 带动量退场：页内跟手位移（backProgress）冻在松手位置，
                    // 外层滑出只补"可见剩余距离"，时长 = 剩余距离 / 松手速度，缓动用快出慢停的
                    // LinearOutSlowIn —— FastOutSlowIn 起始斜率为 0，松手瞬间急减速再缓滑，
                    // 手感就是"顿一下"。非手势关闭（按钮/返回键）没有动量，保持原 320ms 规格。
                    val momentum = playerBackMomentum
                    playerBackMomentum = null
                    val exitSpec: AnimationSpec<Float> =
                        if (momentum != null && momentum.velocityFrac > 0f && momentum.progress < 1f) {
                            tween(
                                exitDurationMs(
                                    (1f - momentum.progress) * screenWidthPx,
                                    momentum.velocityFrac * screenWidthPx,
                                    PAGE_SLIDE_MS
                                ),
                                easing = LinearOutSlowInEasing
                            )
                        } else {
                            tween(PAGE_SLIDE_MS, easing = FastOutSlowInEasing)
                        }
                    playerSlide.animateTo(1f, exitSpec)
                } finally {
                    playerSliding = false
                }
                // 只有**正常播完**才摘页；被下一次打开打断时上面那个分支会撤销它。
                playerExitPending = false
            }
        }

        /** 打开播放页（必须走这里才能拿到新的层序号）。[startMs] = 观看历史续播起点（默认 0）。 */
        fun openPlayer(startMs: Long = 0L) {
            // 换片 / 全新打开时清掉上一支视频的解析错误：错误面板只属于发起解析的那一支视频。
            // 本地副本分支（resolveAndPlay 命中本地库）、相关推荐本地条目、作者页本地卡片
            // 都不经过 startResolve，不清就会把"无法播放"盖在新视频上。
            // 同时作废上一支视频的在途解析：它的迟到回调（失败时写 resolveError）不能落到新片上。
            val videoKey = (prs.currentVideo?.localUri ?: "") + "|" + (prs.currentOnline?.id ?: "")
            if (!showingPlayer || videoKey != playerShownVideoKey) {
                prs.resolveGen++
                prs.resolveError = null
            }
            playerShownVideoKey = videoKey
            prs.playerStartMs = startMs
            // 引擎开关不影响"打开哪个页"——PlayerScreen 单页按引擎运行，
            // 详情区/点赞/评论/下载在 mpv 引擎下原样可用。
            DiagLog.log("Player", "点击打开播放页（startMs=$startMs）")
            // 层序号决定绘制顺序，由这里分配；入场"要不要补"由单一时序源的值决定
            //（值停在 1 必然滑入；值已是 0 则零帧不动）。
            playerSeq = nextOverlaySeq()
            // 上一次手势的退场动量已消费/失效，开新页必须清掉。
            playerBackMomentum = null
            // **全新打开**（播放页当前没挂着）不沿用上一次会话的相关推荐；
            // 换片（showingPlayer 已 true）绝不在这里清 —— 旧推荐要保留到新推荐到达，
            // 否则详情列表瞬间变矮，"滚回顶部"动画会被 LazyColumn 的位置钳制吃掉（见相关推荐预取处的注释）。
            if (!showingPlayer) {
                prs.relatedVideos = emptyList()
                // 全新挂载 ⇒ 入场滑动期间先不挂视频画面（封面遮罩），落位后再渲染。
                playerEntrySettled = false
            }
            showingPlayer = true
        }
        prs.onOpenPlayer = { openPlayer(it) }

        /** 打开设置页（同上）。 */
        fun openSettings() {
            settingsSeq = nextOverlaySeq()
            settingsBackMomentum = null
            showingSettings = true
        }

        // ---- 二级页"卡片化让位" ----
        // 主内容被盖住的程度：0 = 完全露出（原位/原亮度），1 = 被完全盖住（左移 + 压暗）。
        // 三个来源（Iwara 覆盖层栈 / 设置页 / 播放页）各自在预测式返回拖动期间上报进度，
        // 这里取最大者。
        var overlayGesture by remember { mutableStateOf<Float?>(null) }
        var settingsGesture by remember { mutableStateOf<Float?>(null) }
        var playerGesture by remember { mutableStateOf<Float?>(null) }
        val liveOverlayCount = overlays.count { !it.isActionSheet }
        val coverTarget = maxOf(
            when {
                // 拖动的是"最底层的那个二级页" → 主内容跟着手指实时回位
                overlayGesture != null && liveOverlayCount <= 1 -> 1f - overlayGesture!!
                liveOverlayCount > 0 -> 1f
                else -> 0f
            },
            if (showingSettings) settingsGesture?.let { 1f - it } ?: 1f else 0f,
            if (showingPlayer) playerGesture?.let { 1f - it } ?: 1f else 0f
        )
        val coverGesturing = overlayGesture != null || settingsGesture != null || playerGesture != null
        // 让位驱动是声明式的：动画目标由 coverTarget 唯一决定，结构上不可能"停在半路"。
        // 跟手期间用 snap 规格，使动画当前值始终等于手指位置（若直接绕过动画值，
        // 松手那一帧从"手指位置"切回"补间值"会让主内容跳一下）。
        // 松手/取消时 coverTarget 会变，补间从最后贴上的手指位置接着走，无跳变。
        val cover by animateFloatAsState(
            targetValue = coverTarget,
            animationSpec = if (coverGesturing) {
                snap()
            } else {
                tween(COVER_MOTION_MS, easing = FastOutSlowInEasing)
            },
            label = "cover"
        )
        val coverShiftPx = with(LocalDensity.current) { COVER_SHIFT_DP.toPx() }

        // ---- 底栏真磨砂的背景录制 ----
        // 主内容区把自己录进离屏层，底栏再平移对齐 + 模糊后垫在玻璃底下，形成"背后糊成一片"的磨砂。
        // 用的都是 compose-ui 官方 API（`GraphicsLayer.record` + `BlurEffect` + `drawLayer`）。
        //
        // 录制门的取舍：
        //  ① 真模糊是可选档（默认 = 透明，零成本，见 repo 的 glassMode 默认值）；
        //  ② 录制门 = 主页面是否被完全遮挡：过渡动画期间与部分遮挡期间照常录制，只有 `cover` 到 1
        //     （覆盖动画收尾、主页面整块看不见）才停，切页过渡时材质不会中途消失；
        //  ③ 主 tab 页滚动 / 拖滚动条期间也停录（`mainScrollActivity`，各页自行上报）：
        //     长列表滚动时每帧重录整屏是一次额外的全屏离屏 pass，是掉帧的主要来源（决策 0019）；
        //     此时底栏与静态顶栏采样上一帧，停下立刻恢复实时。
        //  ④ 挂载标记同步派生（见 playerMounted），慢一帧也不会"打不开"。
        // 设置改为全局一份（[LocalGlassSettings]），这里只读。
        val glassSettings = LocalGlassSettings.current
        // 库的 LayerBackdrop 录制管线：blur 需 API 31+（lens 部分 33+，之下退化成纯模糊/无效果）。
        val barGlassConfigured = glassSettings.mode.isLiquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        // 玻璃门的判据是"**主页面是否已被完全遮挡**"，而非"有没有二级页在屏"。
        //
        // 判据用 `cover`（主内容被盖住的程度，0 = 完全露出 / 1 = 被完全盖住）：
        //   · 进入：cover 从 0 补间到 1 的整个过程都算"没完全遮挡"，全程渲染；
        //     cover 落到 1 才停；
        //   · 退出：coverTarget 一变 0，cover 当帧就掉下 1，立刻恢复渲染。
        // 用 `derivedStateOf` 而不是直接读 `cover`：`cover` 是 animateFloatAsState 的值，
        // 在组合期直接读会逐帧重组整个 MainContent；derivedStateOf 只在结果翻转时通知。
        val mainFullyCovered by remember { derivedStateOf { cover >= 1f } }
        val backdropEnabled = barGlassConfigured && !mainFullyCovered
        // 主内容滚动活动（onCreate 下发）：任一主 tab 页在滚 / 拖滚动条时停录主内容背景源，
        // 见上面录制门取舍③。
        val mainScrollActivity = LocalMainContentScrollActivity.current

        // ---- 拉伸过滚动期间冻结磨砂 ----
        // Compose 1.7.3 的拉伸过滚动（API 31+）在拉伸期间把列表内容重录进一个平台 RenderNode
        //（DrawStretchOverscrollModifier），而磨砂 backdrop 本身也是 RenderNode 录制再套
        // BlurEffect —— "嵌套平台 RenderNode × 外层 RenderEffect 模糊"这条合成路径会把该层
        // 内容丢成透明，底栏在信息流拉扯时会错误地透明。
        // 修法：拉到底/到顶还在拉（Y 轴 leftover ≠ 0）就 hold：停止重录（下面 drawWithContent
        // 直接 drawContent，离屏层保留上一帧），但不清 backdrop，底栏继续画上一帧 = 磨砂冻结一瞬；
        // OVERSCROLL_HOLD_SETTLE_MS 内无新 leftover（松手回弹结束）自动恢复。
        // hold 标记只在绘制相位读（drawWithContent 里读委托变量），翻转只触发重绘、不重组。
        var overscrollHold by remember { mutableStateOf(false) }
        val backdropEnabledLatest by rememberUpdatedState(backdropEnabled)
        val holdScope = rememberCoroutineScope()
        val overscrollGate = remember(holdScope) {
            object : NestedScrollConnection {
                private var resetJob: Job? = null
                private fun hold() {
                    if (!backdropEnabledLatest) return // 磨砂没开时不必管（也免得日志刷屏）
                    if (!overscrollHold) {
                        overscrollHold = true
                        DiagLog.log("Backdrop", "拉伸过滚动 → 冻结磨砂（停重录，底栏画上一帧离屏层）")
                    }
                    resetJob?.cancel()
                    resetJob = holdScope.launch {
                        delay(OVERSCROLL_HOLD_SETTLE_MS)
                        overscrollHold = false
                    }
                }

                // 只看 Y 轴 leftover；我们不消费（返回零），拉伸效果在更内层照常接手、手感不变。
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset {
                    if (available.y != 0f) hold()
                    return Offset.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (available.y != 0f) hold()
                    return Velocity.Zero
                }
            }
        }
        // 主内容的玻璃源由 GlassBackdrop 模块统一创建：自有录制层 + 自定义 Backdrop
        //（拉伸冻结需要"跳过录制但保留旧帧"，库的 `Modifier.layerBackdrop` 摘修饰器会直接画空白）
        // + 录制时补页面底色。细节见 `ui/components/GlassBackdrop.kt` 的文件头。
        val mainBackdrop = rememberGlassContentBackdrop()
        // 主内容自适应圆角：任何二级页开着（主内容被让位、右侧边露出）→ 四角裁屏幕物理圆角；
        //   全部关掉、主内容静止铺满 → 实心直角矩形，截图零穿帮。圆角收放与让位同速（320ms）。
        val mainExposed = overlays.isNotEmpty() || showingPlayer || showingSettings
        val pageShape = rememberAdaptivePageShape(exposed = mainExposed)

        // 三层的绘制顺序 = 各自"打开序号"的大小（大的画在上面），序号取自同一个进程级计数器，
        // 因此最新打开的页永远在最上面。覆盖层栈用"仍在屏"的最上层序号（含退场页；栈内各页
        // 之间的层序仍由 IwaraOverlayHost 按序渲染）。
        // 两个"进出场过渡状态"必须在这里声明：它们的 currentState/targetState 要参与下面的
        // 绘制顺序计算（见 drawSeqs）。各自的 SideEffect 仍在原处只负责下发目标值。
        val settingsVisible = remember { MutableTransitionState(false) }
        val playerVisible = remember { MutableTransitionState(false) }

        val overlayTopSeq = overlays.lastOrNull { !it.isActionSheet }?.seq
        // 覆盖层"仍在屏上"的最上层序号 = 活栈顶 ∪ 退场页（宿主 leavingPages 回报）。
        // 弹栈当帧 popOverlay 已把被弹页乐观记进 overlayExitSeq，宿主退场播完后再回报 null 清掉。
        val overlayOnScreenSeq = overlayOnScreenTopSeq(overlayTopSeq, overlayExitSeq)
        // 用于让位 / 让出返回键：只认"打开着"的层（按返回那一刻就该让位、让键）。
        val visibleSeqs = listOfNotNull(
            overlayTopSeq,
            settingsSeq.takeIf { showingSettings },
            playerSeq.takeIf { showingPlayer }
        )

        // ---- 绘制顺序必须把"正在退场的层"也算在场 ----
        // 按返回的瞬间 `showingPlayer` 变 false，`.zIndex(layerZ(playerSeq.takeIf { showingPlayer }))`
        // 的实参变成 null，`layerZ(null) = 0f`，播放页的 zIndex 从 1+n 当帧塌到 0（比覆盖层栈还低），
        // 正在向右滑出的播放页会被下面的页面盖住。
        // 因此：让位仍只认"打开着的层"（visibleSeqs 不动），只把绘制顺序换成 drawSeqs ——
        // 退场动画没播完（currentState 或 targetState 还有一帧为真）的层继续参与排序。
        // 用 `targetState` 一起判定是必要的：`SideEffect` 在组合之后才下发目标值，
        // 只写 `currentState` 会让"刚变 false 的那一帧"漏判，又闪一下层序。
        //（纯函数 [layerStillOnScreen] / [drawOrderSeqs] / [zIndexOf] 都在 IwaraCommon.kt，带单测。）
        // 注意：这两个布尔调用处也必须用；只改 `layerZ` 内部而调用处仍写 `takeIf { showingPlayer }`，
        // 按返回那一帧实参照样是 null，zIndex 照样塌。
        val settingsOnScreen = layerStillOnScreen(
            showingSettings, settingsVisible.currentState, settingsVisible.targetState
        )
        val playerOnScreen = layerStillOnScreen(
            showingPlayer, playerVisible.currentState, playerVisible.targetState
        )

        val drawSeqs = drawOrderSeqs(
            // 覆盖层传"仍在屏"的序号（含退场页），不是活栈顶：弹出最后一页后的退场期间，
            // 它仍要画在播放页/设置页之上 —— 否则滑出动画被不透明播放页盖住（只剩瞬间切换）。
            overlayTopSeq = overlayOnScreenSeq,
            settingsSeq = settingsSeq,
            settingsOnScreen = settingsOnScreen,
            playerSeq = playerSeq,
            playerOnScreen = playerOnScreen
        )

        /** 该层该拿到的 zIndex：比自己序号小的**在屏上**层数 + 1（0 留给底色/压暗层/主内容）。 */
        fun layerZ(seq: Long?): Float = zIndexOf(seq, drawSeqs)

        /**
         * 返回键归属：四层里只有一个能接手势（纯函数 backOwner，见 IwaraOverlayStack.kt）。
         * 规则与绘制顺序同源：引导 > 打开的面板 > 序号最大的在屏页面层 > 根部。
         * 正在退场（shown=false 但 AnimatedVisibility 仍组合）的层仍算在屏，与旧的
         * ownsBack 一致，避免 320ms 滑出窗口里按返回直接把 App 退出。
         * 引导页在主界面早退，不会与本层共存，所以 onboardingVisible 恒 false。
         */
        val backTarget = backOwner(
            onboardingVisible = false,
            sheetOpen = showingFilter || overlays.lastOrNull()?.isActionSheet == true,
            // 覆盖层同样认"仍在屏的退场页"：弹出最后一页后的退场窗口里，返回仍归覆盖层（宿主吞掉），
            // 不会落到主页面去清筛选 / 收起搜索，也不会退出 App。
            overlaySeq = overlayOnScreenSeq,
            overlayOnScreen = overlayOnScreenSeq != null,
            settingsSeq = settingsSeq,
            settingsOnScreen = settingsOnScreen,
            playerSeq = playerSeq,
            playerOnScreen = playerOnScreen
        )

        // 主页面被二级页盖住（作者页 / 播放页 / 设置页 / 面板）时交还搜索焦点并收键盘：
        //   从二级页返回主页面后不会再自动弹输入法（与切 tab 同一条收敛路径）。
        // 只读 backTarget 做触发，不参与层级判定；回到 ROOT 时不做任何"重新聚焦"。
        val mainCovered = backTarget != BackOwner.ROOT
        LaunchedEffect(mainCovered) {
            if (!mainCovered) return@LaunchedEffect
            cvm.defocusSearch()
            librarySearchPhase = reduceSearchPhase(librarySearchPhase, SearchFocusEvent.TabSwitch)
            searchFocusManager.clearFocus()
            searchKeyboard?.hide()
        }

        /** 被更新的一层盖住 → 与栈内被盖页一样让位（左移 15% + 压暗 + 收圆角）。
         *  让位进度由**各页自己**用 animateFloatAsState 补间（声明式：目标值唯一决定终态，
         *  不可能停在半路）—— 语义与覆盖层栈内被盖页完全一致，时长同 COVER_MOTION_MS。 */
        val overlayCovered = overlayTopSeq != null && visibleSeqs.any { it > overlayTopSeq }
        val settingsCovered = showingSettings && visibleSeqs.any { it > settingsSeq }
        val playerCovered = showingPlayer && visibleSeqs.any { it > playerSeq }


        /** 关闭播放页的唯一入口：关页 + 清播放上下文 + 作废在途解析。 */
        fun closePlayer() {
            // 重复关闭（退场动画播放中 / 动画刚播完、页面还没摘掉的那一两帧）必须是 no-op：
            // 无条件置 playerExitPending 会让它永远为 true —— LaunchedEffect(showingPlayer) 不会为
            // "已经是 false 的 showingPlayer"重跑，再也没人清，播放页会永远挂在屏外并占着返回键。
            if (!showingPlayer) return
            prs.resolveGen++ // 作废在途解析回调
            showingPlayer = false
            // 同步登记"退场动画还没播完"（挂载标记靠它派生，见 playerMounted 的说明）。
            playerExitPending = true
            prs.clearPlayback()
        }

        /**
         * 打开作者页的**唯一入口**（主页卡片 / 播放页作者名 / 评论作者 / 关系列表 / 本地库作者）。
         *
         * 与 [pushOverlay] 的差别只有一条：播放页不在覆盖层栈 [overlays] 里，回退到栈内作者页时
         * 若播放页正盖着它（播放页序号更大 = 画在上面），必须先关播放页 —— 否则"点作者"看起来
         * 毫无反应。规则抽在纯函数 [authorNavPlan]（单测 AuthorPageStackTest）。
         *
         * 栈里没有该作者页时**不关播放页**：作者页压在播放页之上，返回后还能接着看（被盖住时已暂停）。
         */
        fun openAuthorProfile(incoming: IwaraOverlay.Profile) {
            when (val plan = authorNavPlan(overlays, playerSeq, showingPlayer, incoming)) {
                is AuthorNavPlan.PopTo -> {
                    // 回退到栈内那一层：不改写已有作者页（复用原条目），也不重复压同一作者页。
                    popOverlaysAbove(plan.targetIndex)
                    if (plan.closesPlayer) closePlayer()
                }
                AuthorNavPlan.PushOnTop -> pushOverlay(incoming)
            }
        }

        val navActions = IwaraNavActions(
            playVideo = { item ->
                val top = overlays.lastOrNull()?.overlay
                if (top is IwaraOverlay.VideoActions || top is IwaraOverlay.ImageActions) popOverlay()
                prs.resolveAndPlay(item)
            },
            // 唯一入口：一律把账号对象里已有的 UUID 带进作者页。
            // 若只带 handle，作者页要多打一次 `GET /profile/{username}` 才能开始拉内容。
            openAccountProfile = { account ->
                // 账号对象偶尔 username 为空（关注边/缓存账号）：归一成「只有 UUID」，
                // 作者页因此不做无效的 /profile/ 请求，也不会把空 handle 拼进地址。
                val target = iwaraProfileTargetOf(account.username, account.id)
                openAuthorProfile(
                    IwaraOverlay.Profile(
                        username = target.handle.orEmpty(),
                        userId = target.uid,
                        displayName = account.name,
                        // 在线作者也带上本地分组键：作者页「视频」栏把已下载的该作者视频
                        // 排在最前面（离线时更是唯一来源）。
                        localAuthorKey = account.id,
                        localAuthorName = account.name
                    )
                )
            },
            openComments = { type, id, title -> pushOverlay(IwaraOverlay.Comments(type, id, title)) },
            openPlaylist = { id, title -> pushOverlay(IwaraOverlay.PlaylistDetail(id, title)) },
            openImage = { pushOverlay(IwaraOverlay.ImageViewer(it)) },
            openPost = { pushOverlay(IwaraOverlay.PostDetail(it)) },
            openVideoActions = { pushOverlay(IwaraOverlay.VideoActions(it)) },
            openImageActions = { pushOverlay(IwaraOverlay.ImageActions(it)) },
            openFavorites = { pushOverlay(IwaraOverlay.Favorites) },
            openPlaylists = { pushOverlay(IwaraOverlay.Playlists) },
            openDownloads = { pushOverlay(IwaraOverlay.Downloads) },
            openFollowing = { pushOverlay(IwaraOverlay.Following) },
            openWatchHistory = { pushOverlay(IwaraOverlay.WatchHistory) },
            playHistory = { prs.playHistoryItem(it) },
            playLocalVideo = { video ->
                // 作者页「视频」栏的本地条目：与本地库点卡片走完全同一条路径。
                val top = overlays.lastOrNull()?.overlay
                if (top is IwaraOverlay.VideoActions || top is IwaraOverlay.ImageActions) popOverlay()
                prs.currentVideo = video
                prs.currentOnline = null
                openPlayer()
            },
            openRelations = { uid, kind -> pushOverlay(IwaraOverlay.Relations(uid, kind)) },
            openSettings = { openSettings() },
            openTagVideos = { tag, online ->
                // 标签可能点自压在播放页之上的页面（播放页 → 作者页 → 点 tag）：
                // 与播放页自己的 tag chip 同一语义 —— 先关播放页，否则清栈后露出的是暂停的
                // 播放页，筛选结果被挡在它后面（用户以为点 tag 没反应）。
                if (showingPlayer) closePlayer()
                val top = overlays.lastOrNull()?.overlay
                when {
                    // 已在该 tag 页：不重复压栈（只有在线 tag 才有这个二级页）。
                    online && top is IwaraOverlay.TagVideos && top.tagId == tag -> {}
                    // 顶层已是别的 tag：原位替换。
                    online && top is IwaraOverlay.TagVideos ->
                        overlays[overlays.lastIndex] = overlays.last().copy(overlay = IwaraOverlay.TagVideos(tag))
                    // 点 tag 一律回到底页 + 就地筛选，不再留在作者页，也不再新压一层 tag 二级页。
                    // 来源由卡片传进来：在线卡片给的是 tag 标识（写进搜索框 `#tag`），
                    // 本地卡片给的是 tag 名字（hasTag() 按名字比对）。以前按 currentTab 猜，
                    // 从「我的」页打开播放列表或在播放页点 tag 时，在线 tag 会被当成地库筛选。
                    online -> {
                        popOverlaysAbove(-1)
                        // 先切回「视频」tab 与视频分段，再筛：否则底部还停在「我的」/别的分段。
                        // 顺序不能反：`selectVideoSegment` 内部会 `clearSearch()`，先筛后切会被清掉。
                        currentTab = HomeTab.VIDEO
                        cvm.selectVideoSegment(Segment.VIDEOS)
                        cvm.searchByTag(tag)
                    }
                    else -> {
                        popOverlaysAbove(-1)
                        vm.selectTag(tag)
                        currentTab = HomeTab.MANAGER
                    }
                }
            },
            close = { popOverlay() }
        )

        Box(Modifier.fillMaxSize()) {
        // 黑垫：主内容被二级页顶开（让位）时露出的底色。常态被不透明的 Scaffold 完全盖住，
        // 肉眼不可见；主内容的圆角裁切若与屏幕玻璃圆角重合，边角也不会露出黑。
        Box(Modifier.fillMaxSize().background(Color.Black))

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                // 让位：被盖住时左移 15% 屏宽。读 Animatable 放在 graphicsLayer 里（绘制期读）
                // → 每帧只重绘不重组。
                .graphicsLayer { translationX = -cover * coverShiftPx }
                // `.clip` 必须写在 `.background` 之前：Modifier 链里靠前的在外层，
                // 画在裁切之外的背景不会被裁。否则主内容会变成一块直角矩形，
                // 让位时露出的边与四角看不到黑垫。
                // 底色由我们自己画在裁切之内，不依赖 M3 Scaffold 内部 Surface 的绘制顺序。
                .clip(pageShape)
                .background(MaterialTheme.colorScheme.background),
            containerColor = Color.Transparent,
            // 顶栏从 Scaffold 的 topBar 槽位里搬走了：现在是内容区末尾的覆盖层
            //（同一窗口，能吃到真模糊）。槽位留空，内容不再被顶栏顶下去，卡片从顶栏底下滚过去。
            bottomBar = {
                // 悬浮玻璃药丸底栏。
                // 跟手/形变/大小已固定用 GlassMaterial.kt 里的默认常量；
                // 材质（底色/噪点/模糊）统一由 [LocalGlassSettings] 决定，这里只传背景层。
                IwaraBottomBar(
                    current = currentTab,
                    onSelect = { currentTab = it },
                    // 主界面不"干净在屏"时传 null，本帧不画也不录玻璃。
                    backdrop = if (backdropEnabled) mainBackdrop else null
                )
            }
        ) { innerPadding ->
            val bottomBarHeight = innerPadding.calculateBottomPadding()
            val topBarHeight = innerPadding.calculateTopPadding()
            // 本地 / Iwara 页**悬浮顶栏内容的实测高度**（px）——
            //   由下面那条**静态材质条**量出来，再回灌给两个页面的 `topPadding`
            //   （= 本条内容高 + [TOP_BAR_CONTENT_HEIGHT]，App 顶栏那一格）。
            var topBarContentPx by remember { mutableIntStateOf(0) }
            // 顶栏占位高度（Dp）= 上面那条的实测内容高 + App 顶栏那一格（两个页面共用一份）。
            val topBarPadDp = with(LocalDensity.current) { topBarContentPx.toDp() } + TOP_BAR_CONTENT_HEIGHT

            // ---- tab 内容：只给顶栏让位；底栏是悬浮玻璃药丸，内容穿到栏后才有
            //      "浮在页面上方"的观感。列表自身的滚动尾部让位由各 tab 的 contentPadding(bottom) 承担。
            // 录制（含补页面底色）与坐标注入统一收进 `GlassBackdrop` 模块的一个修饰器里；
            // 录制门在绘制相位读 —— hold（拉到底/到顶还在拉）、降级、二级页在屏三个门都在这里读
            //（快照变化只触发重绘、不重组 MainContent）；hold 时跳过 record ⇒ 层保留上一帧
            // ⇒ 底栏玻璃继续采样旧帧 = 冻结。
            // 这一层 Box 只为了把顶栏覆盖层叠在内容之上（顶栏是它的兄弟）。内容仍是被录制的那一层
            //（玻璃背景源挂在它上）；顶栏在录制子树之外，不会自采样。
            Box(Modifier.fillMaxSize()) {
            // 状态栏那一格的稳定底（修"三主页面互相切换状态栏闪黑"）。
            //
            // 原因：那一格的材质由页面自己的 `GlassTopBar(extendTop = 状态栏 + 64dp)` 画，而它活在
            //   下面那个 `AnimatedContent` 里。切 tab = 两页交叉淡入淡出（220ms），罩色在过渡期由
            //   两个页面各画一次、厚度连续变化（78% → 0 → 78%）；罩色半透明 ⇒ 底下透出的页面底色
            //   跟着明暗一闪，即"闪黑"（浅色主题下闪白）。
            //
            // 修法：把那一格的可见底从动画层里提出来 —— 在内容 Box 之内、`AnimatedContent` 之外铺
            //   一条高度 = 状态栏、颜色同 Scaffold 页底的实心条，并用负偏移移到状态栏那一格。
            //   · 放进内容 Box ⇒ 进玻璃源，符合"补底色"不变式（喂给玻璃的画面必须不透明）；
            //   · 在 `AnimatedContent` 之外 ⇒ 不参与 tab 淡入淡出，恒定不动；
            //   · 玻璃材质仍由主界面那条静态 `GlassTopBar` 从屏幕顶铺起并叠在它之上，材质仍是一次
            //     `drawBackdrop`；罩色再怎么变，底下都有同色底兜着。
            //   ⚠️ 颜色必须与 Scaffold 那处同源，改一个必须改另一个。
            Box(
                Modifier
                    .fillMaxSize()
                    .nestedScroll(overscrollGate)
                    .glassContentBackdropSource(mainBackdrop) {
                        backdropEnabled && !overscrollHold && mainScrollActivity?.isScrolling != true
                    }
                    .padding(top = topBarHeight)
            ) {
                if (topBarHeight > 0.dp) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(topBarHeight)
                            .offset(y = -topBarHeight)
                            // 这一条铺的是 `colorScheme.surface`：状态栏那一格的材质罩色 = `surface`
                            //   按材质不透明度上色（见 `glassMaterialFor` 的 fill = `surface.copy(alpha = t)`），
                            //   而切 tab 是两页交叉淡入淡出（220ms）⇒ 罩色的有效 alpha 连续变化。
                            //   底色与罩色同色后，这一格的最终颜色与罩色 alpha 无关（surface 叠 surface
                            //   恒等于 surface，本条又在录制层里、连模糊采样到的也是它）⇒ 淡出期间不再
                            //   透出近黑的 background，闪黑消失。深色 background `#121317` → surface
                            //   `#1B1D22`，浅色 `#F7F8FA` → `#FFFFFF`，肉眼均不可辨。
                            .background(MaterialTheme.colorScheme.surface)
                    )
                }
                // 主 tab 切换时 AnimatedContent 会把上一页从组合里摘掉；没有 SaveableStateProvider
                //   页内 remember 的滚动位置随之销毁，切回即回顶。这里按 tab 名保存可保存状态
                //   （LazyGridState 等 rememberSaveable 状态），切回时恢复原阅读位置。
                val tabStateHolder = rememberSaveableStateHolder()
                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = horizontalSlideSpec { target, initial -> if (target.ordinal >= initial.ordinal) 1 else -1 },
                    label = "tabContent"
                ) { tab ->
                    tabStateHolder.SaveableStateProvider(tab.name) {
                    when (tab) {
                        HomeTab.MANAGER -> LibraryScreen(
                            vm = vm,
                            // 底栏悬浮玻璃后内容要穿到底：列表尾部让位用 contentPadding(bottom)。
                            bottomPadding = bottomBarHeight,
                            // 顶栏占位由**主界面那条静态材质条**实测后下发
                            //   （悬浮顶栏已搬出本页 —— 否则它的满宽材质盒会跟着 tab 横滑、扫出一条竖线）。
                            topPadding = topBarPadDp,
                            // 只有"主页面在最上层"时，返回键才去逐层清筛选；
                            //   上层压着二级页/播放页/设置页/抽屉时，返回属于那一层
                            //   （否则从作者页返回的那一按会把本地页的筛选药丸顺手清掉）。
                            backEnabled = backTarget == BackOwner.ROOT,
                            // 搜索框三态与事件出口：转移规则集中在 SearchInteraction，本处只存跨 tab 的一份。
                            searchPhase = librarySearchPhase,
                            onSearchEvent = { event ->
                                librarySearchPhase = reduceSearchPhase(librarySearchPhase, event)
                            },
                            onOpenVideo = { video ->
                                prs.currentVideo = video
                                prs.currentOnline = null
                                openPlayer()
                            },
                            onImport = { folderPicker.launch(null) },
                            onOpenAuthor = { authorKey, authorName ->
                                // 本地库（卡片作者名 / 「作者」分类列表）→ 统一作者页。
                                // 「未指定」桶没有匹配信息，作者页据此完全不发网络请求。
                                // 本地分组键（VideoEntity.authorId）是 iwara handle（仓库匹配时存的是
                                // resp.user?.username），不是 UUID。若当 UUID 传给 userId，
                                // `/videos?user=<handle>`（端点要 UUID）永远拉空，在线时只有头部信息、
                                // 视频栏空白。因此把 handle 走 username，让作者页用 /profile/{handle}
                                // 解析出 UUID 再拉内容；本地栏仍靠 localAuthorKey（handle）照常渲染。
                                val unknown = authorKey == VideoViewModel.UNKNOWN_AUTHOR_KEY
                                val target = iwaraProfileTargetOf(authorKey.takeIf { !unknown }, null)
                                openAuthorProfile(
                                    IwaraOverlay.Profile(
                                        username = target.handle.orEmpty(),
                                        userId = target.uid,
                                        displayName = authorName,
                                        localAuthorKey = authorKey,
                                        localAuthorName = authorName
                                    )
                                )
                            },
                            // 本地库长按菜单的「检查文件完整性」（未匹配 iwaraId 的本地视频不启用）。
                            onCheckIntegrity = { entity ->
                                entity.iwaraId?.takeIf { it.isNotBlank() }?.let { id ->
                                    integrityController.open(IntegrityCheckTarget(id, entity.title))
                                }
                            }
                        )
                        HomeTab.VIDEO -> IwaraExploreScreen(
                            cvm, navActions,
                            bottomPadding = bottomBarHeight,
                            topPadding = topBarPadDp,
                            // 主页面在最上层时返回键先收搜索；压着二级页时返回属于那一层。
                            backEnabled = backTarget == BackOwner.ROOT
                        )
                        HomeTab.ME -> IwaraMeScreen(cvm, navActions, bottomPadding = bottomBarHeight)
                    }
                    }
                }
            }

                // 静态顶栏材质条：本地 / Iwara 页的悬浮顶栏（药丸栏 + 搜索行）搬到这里 —— 与 App 顶栏
                //   同一窗口、在主 tab 的 `AnimatedContent` 之外，因此不随 tab 横滑。
                // 材质盒是满宽的：留在页面里会跟着 tab 横滑、把左右边界扫进屏幕内（"顶栏边缘一条竖线"），
                //   搬走后材质恒定不动；材质仍只画一次 `drawBackdrop`（`extendTop` 吃到状态栏 + App
                //   顶栏那一格，整条无缝）。
                // 几何：本条原点即屏幕顶，`extendTop = 状态栏 + 64dp`，内容落在 `状态栏 + 64dp`；
                //   `topBarHeight` 即状态栏 inset（Scaffold 未占 topBar 槽位）。
                // 条内容仍随 tab 横滑但不带材质；三个 tab 一律组合本条：「我的」露出 `HomeTab.ME -> Unit`
                //   （空内容）走同一条 `AnimatedContent`，出场药丸栏照旧横滑出场，只剩 [状态栏 + 64dp]
                //   那一格材质（实心顶栏观感）。
                // 配套：`AppTopBar(drawOwnMaterial = false)` —— 同一格不画两层。
                GlassTopBar(
                    backdrop = if (backdropEnabled) mainBackdrop else null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth(),
                    // 本条不做底边过渡：`fadeBottom = false` ⇒ 材质盒不再向下多铺（`bandExtend = 0`）、
                    //   底边也不淡出（`topBandStops(fadeEdge = false)` 让末端保持全局不透明度、遮罩整段
                    //   跳过）⇒ 材质在内容盒底边干脆结束，不再有那条渐隐带。
                    //   ⚠️ 这只是本条（顶栏）的选择：`TOPBAND_BODY_TO_EDGE` 常量仍是 16dp，
                    //   其它消费点（播放页那条药丸条）行为不变。
                    fadeBottom = false,
                    // ⚠️ 这里不能写 `offset(y = -状态栏)`：本条的原点就是屏幕顶（父 Box 即屏幕），
                    //   再减一次会整条抬高一个状态栏的高度，药丸栏 / 搜索行会压到 App 顶栏那一行上。
                    extendTop = topBarHeight + TOP_BAR_CONTENT_HEIGHT,
                    solidTop = topBarHeight + TOP_BAR_CONTENT_HEIGHT
                ) {
                    // 测量点四周无 padding ⇒ 量到的严格是"本条内容高"，不含上面那 64dp。
                    Box(Modifier.fillMaxWidth().onSizeChanged { topBarContentPx = it.height }) {
                        AnimatedContent(
                            targetState = currentTab,
                            transitionSpec = horizontalSlideSpec { target, initial ->
                                if (target.ordinal >= initial.ordinal) 1 else -1
                            },
                            label = "topBarContent"
                        ) { tab ->
                            when (tab) {
                                HomeTab.MANAGER -> LibraryTopBar(
                                    vm,
                                    searchPhase = librarySearchPhase,
                                    onSearchEvent = { event ->
                                        librarySearchPhase = reduceSearchPhase(librarySearchPhase, event)
                                    }
                                )
                                HomeTab.VIDEO -> IwaraTopBar(cvm)
                                HomeTab.ME -> Unit
                            }
                        }
                    }
                }

                // 顶栏覆盖层：与主内容同一个窗口，所以能吃到**真模糊**；
                //   材质/档位/缓降链与底栏、三个悬浮顶栏完全一致（都走 GlassTopBar）。
                //   门控与底栏同一个（`backdropEnabled`：被二级页完全盖住、或非液态玻璃档 ⇒ 传 null 走色罩）。
                AppTopBar(
                    // 顶栏也接主内容的玻璃源（与底栏同一个门控）。
                    backdrop = if (backdropEnabled) mainBackdrop else null,
                    // 本地页 / Iwara 页在自己的内容区里紧贴着本条底边还有一条悬浮条，两条各自
                    //   `drawBackdrop` ⇒ 模糊分算 + 罩色两叠 = 交界处一条缝（割裂感）。
                    //   约定由下面那条向上外扩、吃下本条这一格（即主界面里的"静态顶栏材质条"，
                    //   见上方 `GlassTopBar(extendTop=…)` 的说明），本条不再自己画材质，只保留标题/动作
                    //   内容 ⇒ 全屏只剩一块材质。
                    //   三个 tab 都交给那条静态材质条供货（它对三个 tab 一律组合）：本条一律只画标题/
                    //   动作内容，同一格既不叠两层，切「我的」时出场的药丸栏也照旧横滑出场。
                    drawOwnMaterial = false,
                    title = {
                        Crossfade(
                            targetState = currentTab,
                            animationSpec = tween(220),
                            label = "tabTitle"
                        ) { tab ->
                            when (tab) {
                                // 本地标题与数量同行，与其他页单行标题垂直对齐
                                HomeTab.MANAGER -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr("本地", "Local"))
                                    VideoCountText(vm, Modifier.padding(start = 8.dp))
                                }
                                HomeTab.VIDEO -> Text("Iwara")
                                HomeTab.ME -> Text(tr("我的", "Me"))
                            }
                        }
                    },
                    actions = {
                        // 全局筛选器入口（刷新键旁边）。
                        // 正在过滤时图标变主色，提示当前开着筛选，避免"看不到内容"被当成缺陷。
                        // 读的是 StateFlow（不是整份 UiState），只有这个开关变化才重组这一处。
                        val filterState by GlobalFilterHolder.state.collectAsStateWithLifecycle()
                        // 图标跟着开关变形态 ——
                        //   生效 = **实心** + 主色（一眼看出"在过滤"）；未生效 = **空心** + 默认色
                        //   （只有颜色提示太弱，形态变化更直观）。
                        IconButton(onClick = { showingFilter = true }) {
                            Icon(
                                if (filterState.effective) Icons.Filled.FilterAlt else Icons.Outlined.FilterAlt,
                                contentDescription = tr("筛选", "Filter"),
                                tint = if (filterState.effective) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                }
                            )
                        }
                        // 刷新按钮独立成组件：加载态在它内部收集，
                        // 翻页/加载翻转只重组这一个按钮，不波及整页。
                        RefreshButton(currentTab, vm, cvm)
                    }
                )
            }   // Box(内容 + 顶栏覆盖层)
        }

        // 主内容压暗：画在二级页**之下**，所以只压暗主内容，不影响二级页本身。
        // alpha 写进 graphicsLayer（绘制期读）→ 不引起重组；黑底 + alpha 即等价于蒙一层黑。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = COVER_DIM_ALPHA * cover }
                .background(Color.Black)
        )

        // ---- 全局筛选面板 ----
        // ModalBottomSheet = 独立窗口（与动作面板同一套机制），不占页面栈、不影响任何层序计算。
        // 放在主内容之上、覆盖层栈之下：它盖住顶栏与主内容，开着的二级页仍在其后。
        if (showingFilter) {
            GlobalFilterSheet(
                vm = vm,
                cvm = cvm,
                onClose = { showingFilter = false }
            )
        }

        // ---- 二级页覆盖层栈（评论/主页/播放列表/图片/帖子/收藏/播放列表/tag）----
        // 参考播放页：根 Box 全屏盖住顶栏与底栏；栈内含播放器同款预测式返回（见 IwaraOverlayHost）。
        // zIndex 由"仍在屏"的最上层页序号决定（含退场页）：在播放页里点作者时，作者页的序号更大，
        // 画在播放页之上；弹出后它的退场动画同样画在播放页之上（不被不透明播放页盖住）。
        // 被更新的一层盖住时栈顶页照常让位（coveredByOther）；不是最上层就让出返回键。
        IwaraOverlayHost(
            overlays, ::popOverlay, cvm, vm, navActions,
            // 手势进度回传：拖动中实时让主内容跟着回位，松手/取消回调 null（由 cover 动画归位）
            onBackProgress = { overlayGesture = it },
            // 覆盖层"仍在屏"的序号（含退场页），与 drawSeqs / backOwner 同源。
            modifier = Modifier.zIndex(layerZ(overlayOnScreenSeq)),
            coveredByOther = overlayCovered,
            backEnabled = backTarget == BackOwner.OVERLAY,
            // 宿主回报退场页序号：退场动画播完（离开 leavingPages）后回报 null，清掉乐观记录。
            onLeavingTopSeqChange = { overlayExitSeq = it },
            // 在线卡片长按面板里的「检查文件完整性」（只有已下载的视频才显示该行）。
            onCheckIntegrity = { item ->
                item.id?.let { id ->
                    integrityController.open(IntegrityCheckTarget(id, item.title ?: tr("视频", "Video")))
                }
            }
        )

        // ---- 设置覆盖层：与播放页同层级（全屏盖住顶栏与底栏），自带「设置」顶栏 ----
        // 圆角自适应需要知道"页面在不在动"：显式 visibleState 把进出场动画状态下发给页面
        //（isIdle=false = 滑入/滑出中 → 圆角；静止 → 实心直角）。
        // `settingsVisible` 已在层序计算处声明（退场中的层必须继续参与 zIndex），这里只下发目标值。
        SideEffect { settingsVisible.targetState = showingSettings }
        AnimatedVisibility(
            visibleState = settingsVisible,
            // 层序 = 打开序号（后开的画在上面），只改绘制顺序、不动调用位置。
            modifier = Modifier
                .fillMaxSize()
                .zIndex(layerZ(settingsSeq.takeIf { settingsOnScreen })),
            // 进入只滑不淡：淡入那 200ms 页面是半透明的，会透出底下被让位左移的主内容与黑垫。
            enter = slideInHorizontally(tween(320)) { it },
            // 退场只滑不淡：二级页下面压着"栈底黑垫"+ 压暗层，页面一旦淡出就会透出那块黑。
            // 手势松手 → 只补"可见剩余距离"（跟手位移冻结在页内 graphicsLayer，与这里的滑出叠加），
            // 时长按剩余距离 / 松手速度缩放，缓动 LinearOutSlowIn 快出慢停 —— 消除"松手顿一下"；
            // 非手势关闭（返回箭头）没有动量，保持原 320ms 规格。
            exit = run {
                val m = settingsBackMomentum
                if (m != null && m.velocityFrac > 0f && m.progress < 1f) {
                    slideOutHorizontally(
                        tween(
                            exitDurationMs(
                                (1f - m.progress) * screenWidthPx,
                                m.velocityFrac * screenWidthPx
                            ),
                            easing = LinearOutSlowInEasing
                        )
                    ) { it - (m.progress * screenWidthPx).roundToInt() }
                } else {
                    slideOutHorizontally(tween(320)) { it }
                }
            }
        ) {
            SettingsScreen(
                vm = vm,
                updateVm = updateVm,
                onBack = { showingSettings = false },
                onPickFolder = { folderPicker.launch(null) },
                onPickDownloadFolder = { downloadFolderPicker.launch(null) },
                onBackProgress = { settingsGesture = it },
                // 手势松手动量回传（与播放页同款）：松手 → 退场动画带动量衔接。
                onBackReleased = { progress, velocity ->
                    settingsBackMomentum = BackMomentum(progress, velocity)
                },
                pageAnimating = !settingsVisible.isIdle,
                // 被更新的层盖住 → 让位（左移 + 压暗 + 收圆角）；此时让出返回键。
                covered = settingsCovered,
                backEnabled = backTarget == BackOwner.SETTINGS,
                language = LanguageMode.fromPref(languagePref),
                // 语言切换立即生效（AppLanguage 驱动重组）再落盘；仓库只负责持久化。
                onLanguageChange = { mode ->
                    AppLanguage.set(mode)
                    repository.setLanguage(mode.toPref())
                    // 刷新缓存好的库内标签（月份/根目录/未知作者等），不重扫。
                    vm.onLanguageChanged()
                },
                // 关于页「再次查看首次引导」：清掉引导完成标记，根部分支随即重新渲染引导页。
                onShowOnboarding = { repository.setOnboardingDone(false) }
            )
        }

        // ---- 播放覆盖层（置于 Scaffold 之上：真正全屏盖住底栏；本地与在线共用）----
        // 同设置页：显式 visibleState 下发"在不在动"给播放页（圆角自适应用）。
        // `playerVisible` 已上移（同 settingsVisible），这里只下发目标值。
        SideEffect { playerVisible.targetState = playerMounted }
        AnimatedVisibility(
            visibleState = playerVisible,
            // 层序 = 打开序号（后开的画在上面）。在播放页里点作者时作者页序号更大，画在播放页之上；
            // 反过来（播放页开在作者页之上）播放页序号更大，播放页在最上。
            modifier = Modifier
                .fillMaxSize()
                .zIndex(layerZ(playerSeq.takeIf { playerOnScreen }))
                // 唯一位移源 —— 进与出都由 playerSlide 驱动（见上面的说明）。
                // 绘制相位读值，只有这一层每帧失效，不重组/不重排。
                .graphicsLayer { translationX = playerSlide.value * screenWidthPx },
            // 入场不交给 AnimatedVisibility（它只在"可见状态由 false 变 true"时播 enter，
            // 而播放页被二级页盖住时可见状态本来就是 true，不会重播）。
            // 退场同样交给 playerSlide —— enter/exit 双双 None，
            // AnimatedVisibility 退回纯"挂载/移除"语义（挂载期由 playerMounted 控制）。
            enter = EnterTransition.None,
            exit = ExitTransition.None
        ) {
            prs.currentVideo?.let { video ->
                // 本地视频匹配到 iwara（有 iwaraId）时后台拉一次在线状态，
                // 把「点赞 / 评论 / 关注作者」接入本地播放页。连上服务器前整行隐藏（LOADING）、
                // 拉取失败置灰（UNAVAILABLE），网络不佳时不给出注定失败的操作入口。
                val localIwaraId = video.iwaraId?.takeIf { it.isNotBlank() && video.localUri.isNotBlank() }
                // 同一支视频的全部本地源（表里一行一个文件，这里已经折过组）。
                val localSourceList = vm.localSourcesOf(video)
                var localNet by remember(localIwaraId) { mutableStateOf(OnlineNetState.LOADING) }
                var localLiked by remember(localIwaraId) { mutableStateOf(false) }
                var localAuthorId by remember(localIwaraId) { mutableStateOf<String?>(null) }
                LaunchedEffect(localIwaraId) {
                    if (localIwaraId == null) return@LaunchedEffect
                    localNet = OnlineNetState.LOADING
                    cvm.loadVideoOnlineState(localIwaraId) { st, _ ->
                        if (st != null) {
                            localLiked = st.liked
                            localAuthorId = st.authorUserId
                            localNet = OnlineNetState.AVAILABLE
                        } else {
                            localNet = OnlineNetState.UNAVAILABLE
                        }
                    }
                }
                val localFollowing = localAuthorId?.let { cvm.followStates[it] } ?: false

                val curOnline = prs.currentOnline

                // 「相关推荐」静默预取：在线视频拉 related 接口（失败=空=不显示该区）；
                // 本地视频用本地同作者/同标签（无网络）。换片（relatedId 变化）自动重取。
                //
                // 换片时**不清空旧列表**：详情列表里"相关推荐"是一个装着全部推荐行的超高 item，
                // 若换片第一拍先清空，剩余内容会瞬间矮于视口，LazyColumn 在 measure 阶段直接把
                // 滚动位置钳制成 0 —— IntroColumn 的 animateScrollToItem(0) 虽然启动了却一帧
                // 可见位移都没有，"滚回顶部动画"就失效了（表现为瞬移回顶）。保留旧列表到新列表
                // 到达，列表不变矮，上滚动画全程可见；加载期间短暂显示上一片的推荐（黑场过渡
                // 盖着视频区，与主流视频应用一致）。回调带串台守卫：快速连点两片时，
                // 旧请求的迟到结果不覆盖新片。
                val relatedId = curOnline?.id
                LaunchedEffect(relatedId) {
                    if (relatedId != null) {
                        cvm.loadRelatedVideos(relatedId) { result ->
                            if (prs.currentOnline?.id == relatedId) prs.relatedVideos = result
                        }
                    } else {
                        // 切到本地视频：清掉在线推荐（relatedList 会走 localRelated 分支，
                        // 这里只是把状态收干净，给下一个在线视频留一份空表）。
                        prs.relatedVideos = emptyList()
                    }
                }
                // 本地推荐按视频实体缓存（播放期间库变化不重算，与列表冻结一致）。
                // 放到后台线程：1700+ 条库上这是全表扫描 + 排序，若直接跑在组合相位，
                // 会和入场滑入动画抢同一段时间。
                // 用 remember + LaunchedEffect 而不是 produceState：后者在 key 变化时会把值重置回
                // initialValue（空列表）→ 换片瞬间推荐区塌掉 → LazyColumn 钳制滚动位置为 0，
                // 滚回顶部的动画同样失效。这里保留上一片的列表直到新片的计算完成。
                val localRelated = remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
                LaunchedEffect(video.localUri) {
                    localRelated.value = withContext(Dispatchers.Default) { vm.localRelatedOf(video) }
                }
                val relatedList: List<RelatedVideo> = when {
                    curOnline != null -> prs.relatedVideos.map { RelatedVideo.Online(it) }
                    else -> localRelated.value.map { RelatedVideo.Local(it) }
                }
                val onlineExtras: OnlineVideoExtras? = when {
                    // 在线视频：netState 恒 AVAILABLE，含「下载」入口。
                    curOnline != null -> {
                        val item = curOnline
                        OnlineVideoExtras(
                            liked = prs.onlineLiked,
                            mediaUrl = prs.onlinePlayUrl,
                            // 简介 + 作者头像（列表 DTO 已带，零额外请求）。
                            description = item.body,
                            // 头像走 avatarUrl（i.iwara.tv/image/avatar/{id}/{name}）；
                            // thumbnailUrl 是视频封面模板（files.iwara.tv/.../thumbnail-00.jpg），
                            // 拿它拼头像必然 404，而 AvatarImage 没有兜底图 ⇒ 空白圆圈。
                            avatarUrl = IwaraClient.avatarUrl(item.user?.avatar),
                            qualities = prs.onlineQualities,
                            currentQuality = prs.onlineQualityName,
                            onRetry = { prs.startResolve(item, closeOnFailure = false) },
                            canFollow = item.user?.id != null && sessionState.loggedIn,
                            following = item.user?.id?.let { cvm.followStates[it] } ?: false,
                            onToggleFollow = {
                                val uid = item.user?.id
                                if (uid != null) {
                                    cvm.toggleFollow(uid, cvm.followStates[uid] ?: false) { _, err ->
                                        if (err != null) {
                                            // 按 debug 开关取轨（下同）
                                            // 页面已关不再弹孤立错误
                                            if (!showingPlayer) return@toggleFollow
                                            Toast.makeText(
                                                context,
                                                if (debugDetails) err else friendlyIwaraError(err),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            },
                            onToggleLike = {
                                val id = item.id ?: return@OnlineVideoExtras
                                // 乐观更新：先翻转界面，失败再回滚，点赞反馈更跟手。
                                val previous = prs.onlineLiked
                                prs.onlineLiked = !previous
                                cvm.toggleLike("video", id, previous) { ok, err ->
                                    // 迟到回调只属于发起它的那支视频：播放页已换片就静默丢弃，
                                    // 否则 A 的结果会覆盖 B 的点赞态（与上面相关推荐的 id 守卫同源）。
                                    if (prs.currentOnline?.id != id) return@toggleLike
                                    if (ok != null) prs.onlineLiked = ok
                                    else {
                                        prs.onlineLiked = previous
                                        // 页面已关不再弹孤立错误
                                        if (!showingPlayer) return@toggleLike
                                        Toast.makeText(
                                            context,
                                            err?.let { if (debugDetails) it else friendlyIwaraError(it) }
                                                ?: tr("操作失败", "Operation failed"),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            // 评论收进播放页详情区药丸条 tab，不再由此处打开。
                            onDownload = {
                                cvm.downloadVideo(
                                    item,
                                    onSuccess = {
                                        // 只有真的入队了才提示：解析详情、取清晰度、建签名地址
                                        // 都可能失败，先弹"已加入队列"再弹错误会把用户引导错。
                                        if (showingPlayer) {
                                            Toast.makeText(
                                                context,
                                                tr(
                                                    "已加入下载队列，可在通知栏查看进度",
                                                    "Queued for download; check the notification for progress"
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                ) { err ->
                                    // 页面已关不再弹孤立错误
                                    if (!showingPlayer) return@downloadVideo
                                    Toast.makeText(
                                        context,
                                        if (debugDetails) err else friendlyIwaraError(err),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            onSelectQuality = { q ->
                                prs.onlineQualityName = q.name
                                prs.onlinePlayUrl = q.url
                            }
                        )
                    }
                    // 本地已下载视频：只接互动（点赞/收藏/关注），受网络门控。
                    // 不传 showDownload：「下载」按钮的形态由 PlayerDetails 按 `video.localUri`
                    // 自己判定（本地播放 = 已下载完成态，按钮恒显示）。
                    localIwaraId != null -> OnlineVideoExtras(
                        liked = localLiked,
                        mediaUrl = null,
                        // 本地多源：把源文件喂给同一个清晰度 chip，点它即可换源。
                        qualities = localSourceList.let { list ->
                            if (list.size > 1) list.map { QualityOption(it.sourceDisplayName(), it.localUri) }
                            else emptyList()
                        },
                        currentQuality = localSourceList.firstOrNull { it.localUri == video.localUri }
                            ?.sourceDisplayName(),
                        netState = localNet,
                        canFollow = localAuthorId != null && sessionState.loggedIn,
                        following = localFollowing,
                        onSelectQuality = { q ->
                            // 换源 = 换一份本地文件；同一个 iwaraId ⇒ 观看进度照常续播。
                            localSourceList.firstOrNull { it.localUri == q.url }?.let { prs.currentVideo = it }
                        },
                        onToggleLike = {
                            val previous = localLiked
                            localLiked = !previous
                            cvm.toggleLike("video", localIwaraId, previous) { ok, err ->
                                if (ok != null) localLiked = ok
                                else {
                                    localLiked = previous
                                    // 页面已关不再弹孤立错误
                                    if (!showingPlayer) return@toggleLike
                                    Toast.makeText(
                                        context,
                                        err?.let { if (debugDetails) it else friendlyIwaraError(it) }
                                            ?: tr("操作失败", "Operation failed"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        // 评论收进播放页详情区药丸条 tab，不再由此处打开。
                        onToggleFollow = {
                            val uid = localAuthorId
                            if (uid != null) {
                                cvm.toggleFollow(uid, localFollowing) { _, err ->
                                    if (err != null) {
                                        // 页面已关不再弹孤立错误
                                        if (!showingPlayer) return@toggleFollow
                                        Toast.makeText(
                                            context,
                                            if (debugDetails) err else friendlyIwaraError(err),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    )
                    else -> null
                }
                // 评论 tab 目标 id：在线 = 视频 id；本地 = 匹配到的 iwaraId；
                // 都没有（纯本地未匹配）= null → tab 内占位提示，不发请求。
                val commentTargetId = curOnline?.id ?: localIwaraId
                // 顶部「更多」的目标：在线用现成 DTO；本地构造最小载荷（动作面板只用 id/title）。
                // 纯本地且没有 iwaraId → null，不显示这枚圆钮（面板里的操作大半无处落地）。
                val moreItem = curOnline
                    ?: localIwaraId?.takeIf { it.isNotBlank() }
                        ?.let { IwaraVideoItem(id = it, title = video.title) }
                PlayerScreen(
                    video = video,
                    localSources = localSourceList,
                    onDeleteLocalSources = { chosen, deleteFiles ->
                        vm.removeLocalSources(chosen, deleteFiles) { err ->
                            if (err != null) {
                                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    // 每次打开都会变的层序号 → 播放页据此把预测返回的跟手位移（backProgress）清零，
                    // 避免退场被打断重进时残留位移留在屏上。
                    entryToken = playerSeq,
                    // 入场滑动是否已落位：false 时本页只画封面遮罩、不挂 PlayerView。
                    entrySettled = playerEntrySettled,
                    onBack = { closePlayer() },
                    // 手势进度回传：拖动中让主内容实时回位（与覆盖层/设置页同一套让位动效）
                    onBackProgress = { playerGesture = it },
                    // 手势松手动量回传：退场动画按"剩余距离 / 速度"衔接，消除"松手顿一下"。
                    onBackReleased = { progress, velocity ->
                        playerBackMomentum = BackMomentum(progress, velocity)
                    },
                // 进出场动画状态由单一时序源给出（playerSlide 在跑 = 在动）：
                // enter/exit 都置 None 后 AnimatedVisibility 恒为 idle，只能读滑动本身。
                pageAnimating = playerSliding,
                    onSelectAuthor = { id ->
                        // 点作者名 = 直接进作者二级页（信息流/播放页统一走这条）。
                        // buildEntity 的 authorId 优先是 handle，只有 handle 缺失时才回退成 UUID，
                        // 那时用 userId 兜底，作者页照样能拉内容。
                        val u = prs.currentOnline?.user
                        val uuid = isIwaraUserUuid(id)
                        // 与另外两个入口同一套归一：handle 缺失时只带 UUID，避免空 handle 拉页。
                        val target = iwaraProfileTargetOf(
                            u?.username ?: id.takeIf { !uuid },
                            u?.id ?: id.takeIf { uuid }
                        )
                        // **不在这里关播放页**：作者页要压在播放页之上，返回后还能接着看。
                        // 只有"栈内已有该作者页、且播放页正盖着它"（作者页 → 进视频 → 再点作者）
                        // 才会由 openAuthorProfile 关掉播放页 —— 规则见 authorNavPlan。
                        openAuthorProfile(
                            IwaraOverlay.Profile(
                                username = target.handle.orEmpty(),
                                userId = target.uid,
                                displayName = u?.name
                            )
                        )
                    },
                    onSelectTag = { tag ->
                        // 在线视频的 tag：视频页内就地筛选（不弹页）；本地视频的 tag 保持管理器筛选。
                        if (prs.currentOnline != null) {
                            closePlayer()
                            navActions.openTagVideos(tag, true)
                        } else {
                            vm.selectTag(tag)
                            closePlayer()
                        }
                    },
                    onSelectFolder = { key ->
                        vm.selectFolder(key)
                        closePlayer()
                    },
                    online = onlineExtras,
                    // 相关推荐 + 点击分流（在线 = prs.resolveAndPlay，本地已下载则播本地；
                    // 本地 = 与本地库点卡片同通路）。
                    related = relatedList,
                    onOpenRelated = { relatedItem ->
                        when (relatedItem) {
                            is RelatedVideo.Online -> prs.resolveAndPlay(relatedItem.item)
                            is RelatedVideo.Local -> {
                                prs.currentVideo = relatedItem.video
                                prs.currentOnline = null
                                openPlayer()
                            }
                        }
                    },
                    // 观看历史：从历史页点开时带上续播起点（普通入口恒 0）。
                    startPositionMs = prs.playerStartMs,
                    // 被更新的层（后开的二级页）盖住 → 让位（左移 + 压暗 + 收圆角），并让出返回键。
                    covered = playerCovered,
                    backEnabled = backTarget == BackOwner.PLAYER,
                    // 地址解析失败 → 页内提示（不关页）；重试走"留在页面"的分支。
                    resolveError = prs.resolveError,
                    onRetryResolve = { prs.currentOnline?.let { prs.startResolve(it, closeOnFailure = false) } },
                    // 「收藏」（网站"添加到"）→ 动作面板直达"加入播放列表"模式。
                    // 在线用现成 DTO；本地构造最小载荷（sheet 只用 id/title）。面板是独立窗口，
                    // 盖在播放页之上；未登录时面板内提示。
                    onAddToPlaylist = {
                        val item = curOnline ?: IwaraVideoItem(id = localIwaraId, title = video.title)
                        pushOverlay(IwaraOverlay.VideoActions(item, pickPlaylistDirectly = true))
                    },
                    // 顶部「更多」= 视频动作面板（收藏 / 加入播放列表 / 下载 / 评论 / 作者主页）。
                    onOpenMore = moreItem?.let { item ->
                        { pushOverlay(IwaraOverlay.VideoActions(item)) }
                    },
                    // 长按「已下载」= 检查本地文件完整性（只有本地匹配到 iwaraId 时启用）。
                    onCheckIntegrity = localIwaraId?.let { id ->
                        { integrityController.open(IntegrityCheckTarget(id, video.title)) }
                    },
                    // 评论收进详情区药丸条 tab（在线 = 视频 id；本地 = 匹配到的 iwaraId；
                    // 本地未匹配 → null = tab 内占位提示）。面板与独立评论页共用同一实现。
                    commentsTargetId = commentTargetId,
                    commentsPane = {
                        commentTargetId?.let { id ->
                            CommentsPanel("video", id, video.title, cvm, navActions)
                        }
                    }
                )
            }
        }

        // 应用内更新弹窗：挂在主界面根部（弹窗自带独立 Dialog 窗口，与层序无关）；
        // 显示与否完全由 UpdateViewModel.state 驱动，手动/自动检查共用同一状态机。
        UpdateDialogs(updateVm = updateVm, onDismiss = {})

        // 完整性检查弹窗（确认 → 不确定进度 → 结果）：Broken 时控制器已自动请求重新下载。
        IntegrityCheckDialog(
            state = integrityController.state,
            onConfirm = { integrityController.confirm() },
            onCancel = { integrityController.cancel() },
            onDismissResult = { integrityController.dismissResult() },
            // 损坏时自动重下已入队；「查看下载」先关结果框再切页，避免弹窗压在新页面上。
            onOpenDownloads = {
                integrityController.dismissResult()
                pushOverlay(IwaraOverlay.Downloads)
            }
        )

        // 抽屉背景遮罩：全 app 唯一一层，Activity 全屏单层黑。抽屉浮窗全透明、只托面板；
        // alpha 由 SheetScrimState 逐帧镜像（AppBottomSheet 的 scrim Animatable 驱动）。
        // 归一成单层的原因：浮窗内容框与 Activity 各报各的 inset（实测 70 / 76 / 129 三个值），
        // 两层遮罩跨窗口对边界必然出洞。alpha 在绘制相位读：动画期间每帧只重绘这一个
        // Box（零重组）；无抽屉时恒 0，一个像素都不画。
        Box(
            Modifier
                .fillMaxSize()
                // zIndex 把 Activity 这层遮罩抬到播放页等正 zIndex 内容之上：
                // 抽屉窗口本身在所有窗口之上，只有遮罩画在 Activity 里，不抬高就会被播放页盖住。
                .zIndex(1_000f)
                .graphicsLayer { alpha = 0.32f * SheetScrimState.alpha }
                .background(Color.Black)
        )
        }
    }
}
