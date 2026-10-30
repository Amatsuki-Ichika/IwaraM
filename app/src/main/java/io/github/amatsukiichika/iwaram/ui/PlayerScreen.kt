package io.github.amatsukiichika.iwaram.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.amatsukiichika.iwaram.LocalAppRepository
import io.github.amatsukiichika.iwaram.data.IwaraClient
import io.github.amatsukiichika.iwaram.data.IwaraRepository
import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import io.github.amatsukiichika.iwaram.data.OkHttpDataSource
import io.github.amatsukiichika.iwaram.data.friendlyHttpError
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.watchHistoryKey
import io.github.amatsukiichika.iwaram.data.recordWatch
import io.github.amatsukiichika.iwaram.data.updateWatchProgress
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.components.LocalDebugDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 播放中无操作多久后自动隐藏控制条。 */
private const val AUTO_HIDE_MS = 3000L

/** 在线播放收到这些 HTTP 状态码时自动重新解析一次播放地址（地址短时效，可能刚好过期）。 */
private val AUTO_RETRY_HTTP_CODES = setOf(403, 404, 410)

/**
 * 拖动进度时的取帧节流间隔：两次 seek 之间至少间隔这么久。
 * 太小会把解码器压垮（画面反而跟不上手指），太大则画面切换不够连续；45ms 约 22 次/秒。
 */
private const val SCRUB_SEEK_INTERVAL_MS = 45L

/**
 * 在线视频（Iwara 客户端）播放时的附加入口与播放地址；本地视频播放传 null。
 * mediaUrl 为 null 表示地址还在后台解析（画面显示加载提示），到达后自动装片开播。
 */

// PlayerView 复用池（PlayerViewPool）已随播放页 UI 树迁至 PlayerScreenContent.kt。

@OptIn(UnstableApi::class)
@Suppress("UnsafeOptInUsageError") // lint 追踪不到 @Composable 函数体内的 @OptIn，兜底抑制
@Composable
fun PlayerScreen(
    video: VideoEntity,
    /** 同一支视频的全部本地源文件（多源共存时用来在播放页切换/管理）。 */
    localSources: List<VideoEntity> = emptyList(),
    /** 删除选中的本地源文件；文件删除由面板的开关决定。 */
    onDeleteLocalSources: (List<VideoEntity>, Boolean) -> Unit = { _, _ -> },
    /**
     * 本页的"打开令牌"（宿主传每次 openPlayer 都会变的层序号）。
     *
     * 用途只有一个但很关键：**把预测返回的跟手位移 [backProgress] 清零**。
     * 退场动画被打断时（快速"退出→立刻再进"），本页**不会被销毁** ⇒ remember 的 backProgress
     * 会保留上一次手势松手时冻住的值 ⇒ 页面带着那条残留位移停在屏上，
     * 左缘就露出底页的一条缝。令牌一变即复位。
     */
    entryToken: Long = 0L,
    onBack: () -> Unit,
    /** 预测式返回手势进度：拖动中回调 0f..1f，松手/取消回调 null（主界面据此实时让位）。 */
    onBackProgress: (Float?) -> Unit = {},
    /**
     * 预测式返回**松手完成**：回调（冻结的跟手进度, 松手速度 进度/秒），在 [onBack] 之前同步调用。
     * 宿主据此让退场动画按"剩余距离 / 速度"换算时长、从冻结位置带着动量滑出
     * （按钮/返回键关闭不会回调，退场走默认规格）。
     */
    onBackReleased: (Float, Float) -> Unit = { _, _ -> },
    /** 进出场动画是否正在播（宿主 visibleState 下发）：动画中圆角可见，静止后收成直角。 */
    pageAnimating: Boolean = false,
    onSelectAuthor: (String) -> Unit = {},
    onSelectTag: (String) -> Unit = {},
    onSelectFolder: (String) -> Unit = {},
    online: OnlineVideoExtras? = null,
    /**
     * 本页被**更新的**一层（后打开的二级页/设置页）盖住。
     * 语义与覆盖层栈内的"被盖页"一致 —— 左移 [COVER_SHIFT_DP] + 压暗
     * [COVER_DIM_ALPHA] + 四角收圆角，让位动效同 [COVER_MOTION_MS]。
     */
    covered: Boolean = false,
    /**
     * 是否允许本页响应系统返回手势。`PredictiveBackHandler` 是"后注册者优先"，
     * 被上层盖住时必须让出返回键，否则在作者页上按返回会把下面的播放页关掉。
     */
    backEnabled: Boolean = true,
    /**
     * 在线播放地址**解析失败**的原因文字（宿主下发）。
     * 非空时在页内显示错误面板（带重试/退出），不把整页强制关掉 ——
     * 断连、私密视频、外嵌视频都走这条路，是否退出不强制（详情页还有"打开原视频"可用）。
     */
    resolveError: String? = null,
    /** 页内错误面板的「重试」：重新解析播放地址。 */
    onRetryResolve: (() -> Unit)? = null,
    /**
     * 续播起点（毫秒）。只有从「观看历史」点开时非 0
     * （历史页用 resumeStartMs() 算好：进度 ≥5s 且不足总时长 95% 才续播，否则传 0）。
     * 走 setMediaItem(item, pos) 通路 —— 清晰度切换已在用同一路径，与"换特效后不 seek"的约束无涉。
     */
    startPositionMs: Long = 0L,
    /** 「相关推荐」列表（在线 = related 接口；本地 = 同作者/同标签）。 */
    related: List<RelatedVideo> = emptyList(),
    /** 点相关推荐条目（宿主分流：在线走既有 resolveAndPlay，本地走本地播放）。 */
    onOpenRelated: (RelatedVideo) -> Unit = {},
    /**
     * 详情区评论 tab 的目标 id（在线 = 视频 id / 本地 = 匹配到的 iwaraId）。
     * null = 没有可评论目标（tab 内显示占位提示）。
     */
    commentsTargetId: String? = null,
    /** 评论 tab 内容（宿主装配 [io.github.amatsukiichika.iwaram.ui.browse.CommentsPanel]，与独立评论页共用实现）。 */
    commentsPane: @Composable () -> Unit = {},
    /**
     * 「收藏」按钮（网站"添加到"）→ 打开加入播放列表面板；
     * null = 不显示按钮（本地视频未匹配 iwaraId）。
     */
    onAddToPlaylist: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    // HTTP 源走 OkHttp（IwaraClient.mediaHttp）：与播放地址解析同一网络栈与浏览器头，
    // 并对带 expires 的地址动态附加 X-Version 签名。Iwara 的 CDN/反爬对
    // DefaultHttpDataSource 底层的 HttpURLConnection 指纹不友好（设备端普遍 4004）；
    // 本地 content:// 走 ContentDataSource，不受这些头影响。
    // 播放器实例的"代数"。切 SR / 模型 = epoch++ 重建整个实例（理由见装片块）。
    var playerEpoch by remember { mutableIntStateOf(0) }
    // 换代装片时要不要自动开播：epoch++ 前捕获用户的播放意图，
    // 暂停中切模型就保持暂停（否则暂停调参会被强制开播）。
    // 取的是 playWhenReady（意图）而不是 isPlaying（事实）：错误回调时播放器已回到 IDLE，
    // isPlaying 恒为 false，用它会把"错误后自动恢复播放"变成静默暂停。
    var playOnNextPrepare by remember { mutableStateOf(true) }
    val player = remember(playerEpoch) {
        val httpFactory = OkHttpDataSource.Factory(IwaraClient.mediaHttp)
        // 解码器回退：首选解码器初始化失败时（如部分设备的 c2.android.aac.decoder
        //   Error 0x80000000）自动尝试下一个软/硬件解码器，而不是直接报错停播。
        //   media3 默认关闭，必须显式打开；只多一个 RenderersFactory，装片与 SR 通路不变。
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
            )
            .build()
    }
    // 所有**未按 player 作 key** 的手势/回调 lambda（拖动语义、双击、
    //   拖动起点、错误重试、返回暂停、播放暂停切换）一律经它读最新实例——切模型换代重建后，
    //   旧实例已释放，直接捕获 `player` 会读到冻结位置/静默失效（seek 起点回跳的根因）。
    val currentPlayer by rememberUpdatedState(player)
    // 性能时间线——「点击」→「组合完成」→「prepare」→「首帧」，四点连起来定位卡在哪。
    var composeLogged by remember(playerEpoch) { mutableStateOf(false) }
    SideEffect {
        if (!composeLogged) {
            composeLogged = true
            DiagLog.log(TAG, "播放页组合完成（第 ${playerEpoch + 1} 代）")
        }
    }
    // 用于预测式返回右滑的屏幕像素宽度（随屏幕方向变化）。
    val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    var isFullscreen by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    // 就地换片（点「相关推荐」）时视频实体会换 —— 清掉上一片的进度/时长，
    //   免得换片瞬间进度条残留旧值（装片后 400ms 轮询会立刻刷回真值）。
    //   按「视频身份」作 key（iwaraId + localUri，与观看历史同口径）：在线互切时两侧
    //   localUri 都是 ""，只看 localUri 永远不触发 → 新片会从旧片的进度起播
    //   （超过新片时长直接 EOS）。同片换清晰度/切模型不触发。
    LaunchedEffect(video.iwaraId, video.localUri) {
        positionMs = 0L
        durationMs = 0L
    }
    var isSeeking by remember { mutableStateOf(false) }
    // 在线视频**拖动进度条之后的加载**也要给加载动画（与进入时同款）。
    //   直接问播放器"是不是在缓冲"，在下面已有的 400ms 轮询里同步（只在变化时写）。
    var isBuffering by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var scrubTargetMs by remember { mutableStateOf<Long?>(null) }

    // 拖动进度（左右滑动视频 / 拖动底部进度条）时的取帧泵：
    // 用 CONFLATED 通道只保留最新目标位置，解码跟不上手指时永远追最新帧、不积压，
    // 因此拖动过程中视频画面会连续快速切换；节流间隔避免 seek 风暴把解码器压垮。
    val scrubRequests = remember { Channel<Long>(Channel.CONFLATED) }
    var wasPlayingBeforeScrub by remember { mutableStateOf(false) }

    /** 拖动目标统一出口：视频区横拖与底栏 SeekBar 共用同一条发送路径。 */
    fun dispatchScrub(targetMs: Long) {
        positionMs = targetMs
        scrubTargetMs = targetMs
        scrubRequests.trySend(targetMs)
    }

    LaunchedEffect(player) {
        for (target in scrubRequests) {
            // **拖动全程跟手**（反向拖动也须跟手）。卡死问题由下面的"画面停滞检测 + 自动微调"兜底。
            if (durationMs > 0) player.seekTo(target)
            delay(SCRUB_SEEK_INTERVAL_MS)
        }
    }

    // 逐帧心跳 + 画面停滞检测（自包含，已抽至 PlayerScreenEffects.kt）。
    PlayerFrameStallGuardEffect(player)

    // 开始拖动即暂停（画面稳定停在目标帧，与时间提示同步）；松手后精确落到最终帧并恢复原播放状态。
    LaunchedEffect(isSeeking) {
        if (isSeeking) {
            wasPlayingBeforeScrub = currentPlayer.playWhenReady
            if (currentPlayer.playWhenReady) currentPlayer.pause()
        } else {
            // 丢弃尚未处理的目标，避免松手后又跳回旧位置。
            while (scrubRequests.tryReceive().isSuccess) { /* drain */ }
            scrubTargetMs?.let { target ->
                currentPlayer.seekTo(target)
                positionMs = target
            }
            scrubTargetMs = null
            if (wasPlayingBeforeScrub) currentPlayer.play()
        }
    }

    // 预测式返回：拖动进度 0..1，驱动内容右滑露出主界面。
    var backProgress by remember { mutableFloatStateOf(0f) }
    // 每次"重新打开本页"都把跟手残留清零。
    //   正常路径下这里本来就是 0（首次组合），只有"退场被打断后重进"时它才是脏数据 ——
    //   页面带着上次冻住的位移停在屏上，表现为"边缘卡出空隙"。
    LaunchedEffect(entryToken) { backProgress = 0f }
    // 被上层盖住时的让位进度。与覆盖层栈内被盖页同款 ——
    //   声明式补间（目标值唯一决定终态，不会停在半路），时长同 COVER_MOTION_MS。
    // 拿 **State** 而不是 `by` 委托值 —— 它只在绘制相位（graphicsLayer / drawWithContent）
    //   和下面的 derivedStateOf 里读；在组合相位裸读会让 320ms 让位动画每帧重组整页播放页。
    // 让位量用固定 dp（[COVER_SHIFT_DP]，条目卡首字恰被屏缘盖住的口径）。
    val coverShiftPx = with(LocalDensity.current) { COVER_SHIFT_DP.toPx() }
    val coverAnim = animateFloatAsState(
        targetValue = if (covered) 1f else 0f,
        animationSpec = tween(COVER_MOTION_MS, easing = FastOutSlowInEasing),
        label = "playerCover"
    )
    // 圆角自适应：动画中/跟手中/被盖让位中弧线可见（四角裁屏幕物理圆角），静止铺满收成实心直角。
    // exposed 用 derivedStateOf —— 只在布尔翻转时重组（不要在组合相位直接读动画值）。
    //   `pageAnimating` 是普通参数，必须当 remember 的 key，否则捕获旧值。
    val pageExposed = remember(pageAnimating) {
        derivedStateOf { pageAnimating || backProgress > 0f || coverAnim.value > 0f }
    }.value
    val pageShape = rememberAdaptivePageShape(exposed = pageExposed)

    // 任何交互（点击、双击、拖动）都重置自动隐藏计时。
    fun pokeControls() {
        controlsVisible = true
        interactionTick++
    }

    // 播放错误的**两轨**文案——friendly 给普通模式（人话短句），detail 给 debug 模式
    // （错误码/HTTP/栈帧/原因链全串，并同时进 DiagLog）。
    var playError by remember { mutableStateOf<PlayerErrorUi?>(null) }
    // Debug 开关（组合期捕获一次；回调 lambda 里读不了 CompositionLocal）。
    val debugDetails = LocalDebugDetails.current
    // 在线播放自动重试标记：每个解析周期只自动重试一次，防止死循环。
    var autoRetried by remember { mutableStateOf(false) }
    // 1003 "Detaching surface timed out" 的自动恢复计数（防死循环，连发 2 次仍失败才报错面板）。
    var detachRetries by remember { mutableStateOf(0) }
    // 切模型相关 1000 错误（dropFrame 空队列）的自动恢复计数（同上，限 2 次）。
    var modelRetries by remember { mutableStateOf(0) }
    // 上面两个计数属于"当前这一次片源"；用它记住预算是哪一份片源挣来的。
    //   **不能按 playerEpoch 归零** —— 1000 的恢复就是换代，归零等于每轮都把额度还回去。
    var recoveryBudgetMediaUrl by remember { mutableStateOf<String?>(null) }
    // 本地视频清晰度标签的依据：播放器上报的分辨率高度（在线视频用 currentQuality）。
    var videoHeight by remember { mutableStateOf<Int?>(null) }
    var videoWidth by remember { mutableStateOf<Int?>(null) }
    // 首帧是否已渲染（当前代实例）。本地文件分辨率的读取顺延到它之后（见下面的 LaunchedEffect）。
    //   按 playerEpoch 作 key：任何换代（换片/换清晰度/切模型/错误恢复）都归零 ——
    //   换片加载提示要靠它区分"新片还没出首帧"与"播放中网络卡顿"；在线互切时
    //   localUri 不变，旧 key 清不掉，新片缓冲期会没有转圈。
    var firstFrameSeen by remember(playerEpoch) { mutableStateOf(false) }
    // 播放源统一入口：本地取 localUri，在线取后台解析出的 mediaUrl；
    // 键变化（含重试时先清空再赋值）即重新装片，本地/在线只有这一条装片路径。
    val mediaUrl = video.localUri.ifBlank { online?.mediaUrl.orEmpty() }
    // 当前片源是否含视频轨；null = 轨道信息还没上报（不能据此当作纯音频）。
    //   纯音频文件永远不会回调 onVideoSizeChanged，只能靠 onTracksChanged 判断 ——
    //   据此在视频区叠一个音频占位，避免本地音频文件播放时整块黑屏像卡死。
    //   状态对象不按 mediaUrl 作 key：listener 只在首次组合创建一次，若状态对象随
    //   换片被 remember 换新，listener 仍握着旧对象的引用，更新就写丢了；换片归零
    //   改由下面的 LaunchedEffect 负责。
    var hasVideoTrack by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(mediaUrl) { hasVideoTrack = null }
    // 超分：默认取设置页选择，播放器内可临时切换。
    // （声明在 listener 之前：7001 自动保播放需要在错误回调里读写 SR 开关。）
    val srRepo = LocalAppRepository.current
    var srEnabled by remember { mutableStateOf(srRepo.getSuperResEnabled()) }
    var srModel by remember { mutableStateOf(SuperRes.normalize(srRepo.getSuperResModel())) }
    // 观看历史 key：组合期推导一次（无 iwaraId 且无 localUri 的条目 key=null，不记录）；
    //   监听器只在首次组合创建，video 经 rememberUpdatedState 读最新值（与 currentOnline 同款）。
    val watchKey = remember(video.iwaraId, video.localUri) {
        watchHistoryKey(video.iwaraId, video.localUri)
    }
    val currentVideoState by rememberUpdatedState(video)
    // 监听器只在首次组合时创建一次，经 rememberUpdatedState 读取最新的在线播放上下文。
    val currentOnline by rememberUpdatedState(online)
    val currentMediaUrl by rememberUpdatedState(mediaUrl)
    // 续播起点同样经 rememberUpdatedState 读 —— 装片 effect 未把它列入
    //   key，lambda 捕获的是组合那刻的值（本页每次打开都新建组合，实际不会命中，但保持一致）。
    val currentStartPositionMs by rememberUpdatedState(startPositionMs)
    // 当前实例装着的片属于哪个视频（watchHistoryKey 口径；按 playerEpoch 归零）。
    //   换代前用它判断"是不是同一部片"：同片换代（换清晰度/重试重装/切模型/错误恢复）
    //   要精确续播；换了视频（推荐互切）则从续播起点（通常 0）起播。
    var preparedVideoKey by remember(playerEpoch) { mutableStateOf<String?>(null) }
    // 同片换代置 true，装片 effect 消费后清零（见装片 effect 的 resumePos 决策）。
    var resumeOnRebuild by remember { mutableStateOf(false) }

    // 换代重建的统一出口：同片则捕获旧实例此刻的进度/时长 → 记下播放状态 → playerEpoch++。
    //   换片重建绝不热替换特效、绝不在旧实例上重装片 —— media3 1.4.1 特效管线"运行中折腾"
    //   的三条死路（热替换→1000 dropFrame 空队列；热替换+重装→1003 detach 超时；
    //   换特效+seek→flush 死等 #1535）全部由此绕开。旧实例在下一帧组合时释放。
    fun prepareRebuild(reason: String) {
        // 只有"装的还是同一部片"才捕获进度：换片时页面进度属于旧片，
        //   由视频身份 effect 清零，装片走续播起点。watchKey 经组合刷新读最新视频。
        if (preparedVideoKey != null && preparedVideoKey == watchKey) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            player.duration.takeIf { it > 0 }?.let { durationMs = it }
            resumeOnRebuild = true
        }
        playOnNextPrepare = rebuildPlayIntent(player.playWhenReady, player.isPlaying)
        DiagLog.log(TAG, "$reason → 重建播放器实例（第 ${playerEpoch + 1} 代）")
        playerEpoch++
    }
    // 播放诊断面板（右上角 ⋮ 打开）。
    var showDiagnostics by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        // 使用规范的媒体音频通道并接管音频焦点：可打断后台音乐，失去焦点时自动暂停。
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                // 是否在缓冲 / 总时长走事件驱动 —— 拖动进度后的加载动画
                //   即时报（不必等 400ms 轮询那一拍）。下面的 400ms 轮询保留作兜底
                //   （部分设备事件缺失是常态；兜底只在值变化时写，双路径不冲突）。
                if (events.containsAny(Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_TIMELINE_CHANGED)) {
                    isBuffering = player.playbackState == Player.STATE_BUFFERING
                    player.duration.takeIf { it > 0 }?.let { durationMs = it }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                // 观看历史：暂停/自然播完的那一拍把进度落一笔（长会话另有 5 秒心跳）。
                if (!playing) {
                    srRepo.saveWatchProgress(
                        watchHistoryKey(currentVideoState.iwaraId, currentVideoState.localUri),
                        player
                    )
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // 诊断：开了特效后 media3 可能不上报新分辨率（上游 issue #2505），
                //   这行日志能直接看出"管线是否把分辨率改成了 2×"，是排查黑屏/黑边的第一线索。
                Log.i(TAG, "onVideoSizeChanged ${videoSize.width}x${videoSize.height} (px=${videoSize.pixelWidthHeightRatio})")
                DiagLog.log(TAG, "onVideoSizeChanged ${videoSize.width}x${videoSize.height}")
                if (videoSize.height > 0) {
                    videoHeight = videoSize.height
                    videoWidth = videoSize.width
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // 空轨道表 = 还没 prepare 完，不能据此判断"没有视频轨"；
                //   groups 非空才说明轨道信息已到，此时没有视频组 = 纯音频。
                if (tracks.groups.isNotEmpty()) {
                    hasVideoTrack = tracks.containsType(C.TRACK_TYPE_VIDEO)
                }
            }

            override fun onRenderedFirstFrame() {
                Log.i(TAG, "onRenderedFirstFrame sr=${SuperResStatus.engine ?: "off"}")
                DiagLog.log(TAG, "onRenderedFirstFrame sr=${SuperResStatus.engine ?: "off"}")
                // 给"分辨率读取"发信号 —— 首帧之后再读文件元数据（避免和 prepare 抢 IO）。
                firstFrameSeen = true
                // 观看历史：首帧即记一条（同 key 重看经唯一索引 REPLACE 原地覆盖=置顶）。
                //   进度/时长此刻常常还拿不到，交给 5 秒心跳与暂停/退出时的落笔补写。
                val watchedVideo = currentVideoState
                val key = watchHistoryKey(watchedVideo.iwaraId, watchedVideo.localUri)
                if (key != null) {
                    srRepo.recordWatch(
                        iwaraId = watchedVideo.iwaraId,
                        localUri = watchedVideo.localUri,
                        title = watchedVideo.title,
                        author = watchedVideo.author,
                        authorId = watchedVideo.authorId,
                        thumbnail = watchedVideo.localThumbnailPath ?: watchedVideo.thumbnailUrl,
                        positionMs = player.safePositionMs(),
                        durationMs = player.safeDurationMs()
                    )
                }
                super.onRenderedFirstFrame()
            }

            override fun onPlayerError(error: PlaybackException) {
                // 诊断：把错误码与整条 cause 链打进日志，便于一次定位问题。
                val chain = generateSequence<Throwable>(error) { it.cause }.take(6).toList()
                val chainMsg = chain.joinToString(" ← ") { it.message ?: it.javaClass.simpleName }
                Log.e(TAG, "onPlayerError ${error.errorCodeName}(${error.errorCode}): $chainMsg")
                DiagLog.log(TAG, "onPlayerError ${error.errorCodeName}(${error.errorCode})")
                DiagLog.log(TAG, "原因链: $chainMsg")
                chain.lastOrNull()?.stackTrace?.take(3)?.forEachIndexed { i, f ->
                    DiagLog.log(TAG, "栈${i + 1}: ${f.className}.${f.methodName}:${f.lineNumber}")
                }

                // 特效管线失败（7001）自动保播放 —— 管线已坏，SR 怎么重试都救不回来，
                //   自动关闭本次 SR、换直通特效、从当前进度重新 prepare。超分失败永不断播。
                if (error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED && srEnabled) {
                    DiagLog.log(TAG, "7001 → 自动关闭本次 SR，重建播放器实例恢复播放")
                    srEnabled = false
                    SuperResStatus.degraded = false
                    SuperResStatus.activeScale = null
                    SuperResStatus.pipelineFailed = true
                    SuperResStatus.reason = tr(
                        "特效管线失败（7001），已自动停用超分并恢复播放",
                        "Effect pipeline failed (7001); super resolution disabled and playback resumed"
                    )
                    // 出错时刻的进度/时长记进页面状态，恢复统一交给装片
                    //   effect 的 epoch 重建通路（srEnabled 翻 false 触发）——新实例上特效在首次
                    //   prepare 前重建，装片时经 resumePos 精确续播。若在回调里先对旧实例
                    //   setMediaItem+prepare 保播放、随后 epoch 重建又装一遍 = 在线源连缓冲两次，
                    //   且"管线刚坏再折腾旧实例"正是 1003 黑屏的诱因。
                    positionMs = currentPlayer.currentPosition.coerceAtLeast(0L)
                    currentPlayer.duration.takeIf { it > 0 }?.let { durationMs = it }
                    playError = null
                    return
                }

                // 1003 "Detaching surface timed out" 自动保播放 —— 旋转/全屏切换销毁
                //   Surface 时，ExoPlayer 要等特效管线腾出渲染线程 detach 旧表面；刚连续换过模型
                //   （管线反复重建）+ 管线繁忙时会等超时（典型场景：HQ→Lanczos→UP_S 连切后转屏
                //   偶发卡顿）。这不是数据或管线坏掉，从当前进度重新 prepare 即可无缝恢复；
                //   连发 2 次仍失败才落到错误面板（防死循环）。SR 保持开启，不置 pipelineFailed。
                if (error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT &&
                    chainMsg.contains("Detaching surface") && mayRetryRecovery(detachRetries)
                ) {
                    detachRetries++
                    DiagLog.log(TAG, "1003 Surface 切换超时 → 自动从当前进度恢复播放（第 $detachRetries 次）")
                    playError = null
                    recoverAtCurrentPosition(player, currentMediaUrl)
                    return
                }
                // 帧队列错位（1000 + NoSuchElementException）的兜底 —— media3 1.4.1 特效
                //   管线在 flush/换片/teardown 时机错位时，`VideoFrameRenderControl.dropFrame`
                //   会对空帧队列 remove（上游 androidx/media#1506 家族）。
                //   不再同实例重装片：坏掉的是特效管线的内部状态，re-prepare 不重建管线，
                //   SR 开着必然复发（0.1.0 的"重装片第1次→第2次→错误面板"循环就是这么来的）。
                //   改走换代重建 —— 同片换代，进度经 prepareRebuild 的 resumeOnRebuild 通路
                //   精确续播；连发 2 次仍失败才落错误面板（防死循环）。
                if (error.errorCode == PlaybackException.ERROR_CODE_UNSPECIFIED &&
                    chainMsg.contains("NoSuchElementException") && mayRetryRecovery(modelRetries)
                ) {
                    modelRetries++
                    DiagLog.log(TAG, "1000 dropFrame 队列错位 → 重建播放器实例恢复（第 $modelRetries 次）")
                    playError = null
                    prepareRebuild("1000 dropFrame 队列错位恢复")
                    return
                }
                // 解包 HTTP 层异常得到服务器状态码（如 403），便于精确定位；
                // 在线播放遇可重试状态码时自动重新解析一次（短时效地址可能刚好过期）。
                val http = chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                val httpCode = http?.responseCode
                if (currentOnline != null && !autoRetried &&
                    httpCode != null && httpCode in AUTO_RETRY_HTTP_CODES
                ) {
                    autoRetried = true
                    playError = null
                    currentOnline?.onRetry()
                } else {
                    playError = buildPlayerErrorUi(error, chain, chainMsg, httpCode)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // 已经装过的片源，用来识别"这次变化到底要不要重装片"。
    // 按 playerEpoch 重置 —— 播放器换代后新实例必然还没装过片。
    var preparedUrl by remember(playerEpoch) { mutableStateOf<String?>(null) }

    // media3 官方文档明确写了两条 ——
    //   ① `setVideoEffects` 必须在**首次 prepare 之前**至少调一次才会建立特效管线；
    //   ② 管线建立后 "effects can be changed during playback by subsequent calls"。
    //   但 ②在 1.4.1 实测不可依赖（运行中折腾管线 = 下面三条死路），所以本工程一律走 ①：
    //   特效只在**全新实例**上、prepare 前建立；任何 media 变化都走换代重建。
    LaunchedEffect(mediaUrl, srEnabled, srModel, playerEpoch) {
        // 「切模型经常性卡死」的根因（上游 issue #1535 / #1139）：
        //   在**换特效之后立刻 `seekTo(currentPosition)`** 去"强制重绘一帧"时，seek 会触发
        //   `DefaultVideoFrameProcessor.flush()`：flush 期间 SurfaceTexture 的 onFrameAvailable 回调
        //   会被吞掉 → 队列里那帧永远不被消费 → flush 等的 latch **永不归零** → 帧处理线程干等，
        //   播放**永久卡死**（不抛错、不出错面板，就是不动）。上游复现条件正是"setVideoEffects +
        //   多次 seek"，Android 14 上必现。⇒ 换特效**绝不 seek**，换代后新特效随首次 prepare 建立。
        //
        // 分支总纲 —— **绝不在已活的旧实例上折腾管线**：
        //   ① 热替换特效 → 1000 dropFrame 空队列；
        //   ② 热替换 + 重装片 → 1003 detach 超时 + 黑屏无报错；
        //   ③ 换特效 + seek → flush 死等（#1535）。
        //   任何"要换片/换特效，但实例上还装着上一片"的情况一律走 [prepareRebuild] 换代
        //   （旧实例释放 = 黑场过渡的画面来源）；只有全新实例才走到下面的标准装片分支。
        // 本实例上是否还装着上一片（活管线）。preparedUrl 按 playerEpoch 归零 —— 换代即视为全新。
        val hasLiveMedia = preparedUrl != null

        if (mediaUrl.isBlank()) {
            // 地址还没解析出来（在线首装前 / 换片瞬间 onlinePlayUrl 先清空再后台解析）。
            if (hasLiveMedia) {
                // 换片瞬间：旧片还装在当前实例上 → 立即换代释放，杜绝"新地址解析出来后
                //   装进还带着旧特效管线的实例"这条 1000 路径。同片地址重试（过期自动
                //   重解析）也走这里，按"同片"捕获进度续播。
                prepareRebuild("换片待解析，旧实例释放")
            } else {
                preparedUrl = null
                // pipelineFailed 时保留"SR 自动停用"提醒，其余残留状态清零。
                if (!srEnabled && !SuperResStatus.pipelineFailed) SuperResStatus.reset()
            }
            return@LaunchedEffect
        }
        val isMediaChanged = preparedUrl != mediaUrl
        if (!isMediaChanged) {
            // 同片切 SR / 模型 = **重建整个播放器实例**（playerEpoch++）。
            //   本 effect 因 key 变化重跑：preparedUrl 按 epoch 归零 ⇒ 走下面标准装片分支
            //   （新实例上特效在**首次 prepare 前**建立 = media3 官方标准路径）。
            //   代价：切模型 = 一次重建（本地近瞬时、在线重缓冲一次），进度经 prepareRebuild 保住。
            //   同片换档把连续点击折叠成一次重建：delay 会被下一次按键取消 ⇒ 天然防抖。
            delay(200)
            prepareRebuild("切模型 sr=$srEnabled/$srModel")
            return@LaunchedEffect
        }
        if (hasLiveMedia) {
            // 同一实例上换地址（本地换本地 / 换清晰度 / 自动重试重装）→ 换代重建，不热替换。
            prepareRebuild("换片")
            return@LaunchedEffect
        }
        // 管线替换失败也绝不把异常抛进组合（那会连带整页 UI 一起挂）：降级记录 + 原因进诊断面板。
        runCatching {
            player.setVideoEffects(
                if (srEnabled) SuperRes.buildEffects(context, srModel)
                else listOf(SuperRes.passthroughEffect())
            )
        }.onFailure {
            Log.e(TAG, "setVideoEffects 失败", it)
            DiagLog.log(TAG, "setVideoEffects 失败: ${it.message}")
            SuperResStatus.markDegraded(
                tr(
                    "特效链替换失败：${it.message ?: it.javaClass.simpleName}",
                    "Failed to replace the effect chain: ${it.message ?: it.javaClass.simpleName}"
                )
            )
        }
        // SR 自动停用（pipelineFailed）时保留状态，让 chip 继续显示"SR 自动停用"提醒；
        // 重新打开 SR（buildEffects 会 reset）或换视频时才真正清零。
        if (!srEnabled && !SuperResStatus.pipelineFailed) SuperResStatus.reset()
        preparedUrl = mediaUrl
        preparedVideoKey = watchKey
        playError = null
        autoRetried = false
        // 自动恢复的重试预算只在**换了片源**时清零。这里曾经无条件 `modelRetries = 0`，
        // 而 1000 的恢复路径正是"换代 → 新实例 → 标准装片"，于是每轮都把自己的额度还回去，
        // 上限形同不存在（同一部片可无限重建实例）。1003 的计数此前从不复位，
        // 换片后上一片的失败会吃掉新片的额度，这里一并按片源复位。
        if (shouldResetRecoveryBudget(recoveryBudgetMediaUrl, mediaUrl)) {
            recoveryBudgetMediaUrl = mediaUrl
            modelRetries = 0
            detachRetries = 0
        }
        videoHeight = null
        videoWidth = null
        // 换片/换清晰度时连超分的实测分辨率一起清掉 —— 否则诊断面板会继续显示
        // 上一部视频的"源分辨率"，看起来就像"分辨率识别错了"。
        SuperResStatus.reset()
        // 续播决策：同片换代（换清晰度/重试重装/切模型/错误恢复）→ prepareRebuild 已捕获
        //   进度，精确续播；换了视频 → 从续播起点起播（推荐切换 = 0；从历史点开 = 历史进度）。
        //   页面 positionMs/durationMs 不能当判据：400ms 轮询/事件写入与视频身份切换存在
        //   竞态，换片瞬间可能残留旧片的值 —— 它们只用于进度条显示，不用于装片决策。
        val resumePos = if (resumeOnRebuild && durationMs > 0) positionMs else currentStartPositionMs
        resumeOnRebuild = false
        if (resumePos > 0) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(mediaUrl)), resumePos)
        } else {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(mediaUrl)))
        }
        // prepare 前后打点（本地/在线的首帧耗时拆解用）
        DiagLog.log(TAG, "prepare 开始 resumePos=$resumePos")
        player.prepare()
        DiagLog.log(TAG, "prepare 返回")
        // 换代装片保持切模型那一刻的播放/暂停状态；首装与换清晰度恒为 true。
        player.playWhenReady = playOnNextPrepare
        playOnNextPrepare = true
    }

    // 清晰度显示：在线用当前档名；本地按分辨率智能映射（≥720→Source、≥480→480p、≥360→360p）。
    // 本地播放时在清晰度 chip 右下角叠加 ✓ 徽标（见 PlayerBottomBar），表示正在播放已下载的版本。
    val isLocal = video.localUri.isNotBlank()

    // 兜底：装了特效管线后，部分设备上 media3 不再回调 onVideoSizeChanged
    //（上游 issue #2505 家族）→ videoHeight 恒 null → 本地清晰度 chip 整个消失。
    // 这里用 MetadataRetriever 一次性读文件的真实分辨率，不依赖播放器回调。
    var localVideoHeight by remember(video.localUri) { mutableStateOf<Int?>(null) }
    // 宽高一起读 —— 诊断面板要显示"源分辨率 WxH"，只有高看不出是不是竖屏。
    var localVideoWidth by remember(video.localUri) { mutableStateOf<Int?>(null) }
    LaunchedEffect(video.localUri) {
        val src = video.localUri
        if (src.isNotBlank() && !src.startsWith("http")) {
            // **顺延到首帧之后**再读（最多等 1.5s，播放失败也照读）。
            //   若一进页面就 `MediaMetadataRetriever` 开文件 —— 会和 ExoPlayer 的 prepare
            //   抢同一份磁盘 IO/解码资源，而那几百毫秒正是入场滑入动画所在的时间窗。
            //   清晰度 chip 晚一拍出现没有观感代价（它在下半屏的底栏里）。
            withTimeoutOrNull(1500) {
                snapshotFlow { firstFrameSeen }.first { it }
            }
            val size = withContext(Dispatchers.IO) {
                runCatching {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(context, Uri.parse(src))
                        val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            ?.toIntOrNull()
                        val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            ?.toIntOrNull()
                        if (w != null && h != null && w > 0 && h > 0) w to h else null
                    } finally {
                        r.release()
                    }
                }.getOrNull()
            }
            if (size != null) {
                localVideoWidth = size.first
                localVideoHeight = size.second
            }
        }
    }

    val qualityLabel = online?.currentQuality ?: (videoHeight ?: localVideoHeight)?.let { h ->
        when {
            h >= 720 -> "Source"
            h >= 480 -> "480p"
            h >= 360 -> "360p"
            else -> "${h}p"
        }
    }

    // SR chip 文案在 PlayerBottomBar 内部计算 —— SuperResStatus 由
    //   GL 线程回填、读状态即订阅，若在页面顶层读，每次装片/切模型都触发 2-4 次整页重组；
    //   下放到底栏后只重组底栏这一小块。

    // 切出页面（ON_PAUSE 早于 ON_STOP，减少退出自动暂停的延迟）时立即暂停，避免后台继续出声。
    // 已抽至 PlayerScreenEffects.kt。
    PlayerPauseOnLeaveEffect(player)

    // 通知栏背景固定黑色，图标始终用浅色；导航栏仍跟随主题，退出播放页时恢复。
    // 退出恢复按**应用实际主题**（背景亮度）而非系统深浅色 ——
    //   应用内可强制 light/dark（themeMode），与系统不一致时按系统恢复就是"黑底黑字"。
    //   播放页自身的状态栏保持白图标（视频是黑底）；导航栏进入时跟应用主题。
    //   取色在**组合期**完成（onDispose 不是 Composable 上下文、读不了 MaterialTheme），
    //   并以它作 key ⇒ 主题在播放期间变化时 effect 重启、恢复值保持新鲜。
    val restoreLightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
    DisposableEffect(restoreLightBars) {
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = restoreLightBars
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            setSystemBarsHidden(activity, false)
            controller?.let { c ->
                c.isAppearanceLightStatusBars = restoreLightBars
                c.isAppearanceLightNavigationBars = restoreLightBars
            }
        }
    }

    // 全屏「智能识别」方向**不能只看 `onVideoSizeChanged` 来的
    //   `videoWidth/videoHeight`**（见上方的兜底注释）：装了特效管线后
    //   部分设备 media3 **不再回调该事件**（上游 issue #2505 家族），这两个值恒为 null
    //   ⇒ 智能模式"什么都不做" ⇒ 所有视频都变成竖屏播放。
    //   改以**播放器自己的 `player.videoSize`** 为准（它永远是最新的，不依赖任何回调），
    //   并在下面那条已经存在的 400ms 轮询里同步到状态（只在变化时写，不额外制造重组），
    //   保证"进全屏那一刻还没有画面"的情况下，画面一到就能补上方向。
    var playerAspectLandscape by remember { mutableStateOf<Boolean?>(null) }

    // **状态栏占位不做补间**（恢复瞬间插入）——补间会让内容在动画期间"先上移再下移"；
    //   全屏切换整体"一步到位"，没有中间态，也就没有画面放大 / 上移 / 后移。
    // 非全屏视频区在横屏下比屏幕还高（例：2400×1080 的屏 → 2400×1350，高出 270px），
    //   退出全屏时会把下面的详情页整块往下挤。对策是给视频区**限高**（最高半屏）：
    //   竖屏下 16:9 只占约 1/4 屏高、不生效；横屏下封顶，视频缩小居中（两侧留黑），详情页不再被挤出视野。
    //   这条是**布局正确性**的修正，与动画无关。
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp

    // 全屏时按设置项切换方向并沉浸（隐藏状态栏/导航栏）。已抽至 PlayerScreenEffects.kt。
    FullscreenOrientationEffect(
        isFullscreen = isFullscreen,
        player = player,
        playerAspectLandscape = playerAspectLandscape,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        localVideoWidth = localVideoWidth,
        localVideoHeight = localVideoHeight,
        activity = activity,
        context = context
    )

    // 轮询播放进度与总时长，用于底部进度条（拖动时暂停轮询，避免抖动）。
    LaunchedEffect(player) {
        while (true) {
            val d = player.duration
            if (d > 0) durationMs = d
            if (!isSeeking) {
                positionMs = player.safePositionMs()
            }
            // 顺带把"画幅是否横向"同步到状态 —— 全屏「智能识别」方向以它兜底
            //   （`onVideoSizeChanged` 在部分设备上不回调，见上方的兜底注释）。
            //   只在值真的变化时写，不会制造额外重组。
            val videoSize = player.videoSize
            val isLandscape = isLandscapeOrNull(videoSize.width, videoSize.height)
            if (isLandscape != playerAspectLandscape) playerAspectLandscape = isLandscape
            // 同步"是否在缓冲"（拖动进度后重新定位/取数据时会进入 BUFFERING）。
            val buffering = player.playbackState == androidx.media3.common.Player.STATE_BUFFERING
            if (buffering != isBuffering) isBuffering = buffering
            delay(400)
        }
    }

    // 观看历史：播放中每 5 秒把进度落一笔（fire-and-forget 进仓库私有 IO 作用域）。
    //   暂停/自然播完在 onIsPlayingChanged 那一拍收尾，退出页面在下方 onDispose 收尾 ——
    //   这里只负责"一直在播"的长会话。isPlaying 翻 false 时协程被取消，不会白转。
    LaunchedEffect(watchKey, isPlaying) {
        if (watchKey == null || !isPlaying) return@LaunchedEffect
        while (isActive) {
            delay(5_000)
            // 落笔样板收口到 saveWatchProgress（与暂停时同款）。
            if (currentPlayer.isPlaying) srRepo.saveWatchProgress(watchKey, currentPlayer)
        }
    }

    // 观看历史：退出播放页的最后一笔。读的是 positionMs/durationMs 的 State 委托
    //   （拿到 dispose 那一刻的值，不是捕获时的 0）；本 effect 声明在播放器 release 之前，
    //   dispose 顺序更早，读状态总是安全的。
    DisposableEffect(watchKey) {
        onDispose {
            val key = watchKey ?: return@onDispose
            if (positionMs > 0) srRepo.updateWatchProgress(key, positionMs, durationMs)
        }
    }

    // 播放中且未拖动时，达到 AUTO_HIDE_MS 后自动隐藏控制条。
    // 自动隐藏计时用**常驻协程 + snapshotFlow 消费**——
    //   若把 interactionTick 当 LaunchedEffect 的 key，左右拖动 seek 时 pokeControls() 每帧
    //   interactionTick++ ⇒ **每帧取消并重启一个协程**（重组级开销）。常驻一条协程后，
    //   tick 只在协程层重启计时，完全不碰重组。snapshotFlow 的 key 里带上三个布尔，
    //   保持与原 LaunchedEffect(controlsVisible, isPlaying, isSeeking, interactionTick) 一致的语义。
    LaunchedEffect(player) {
        snapshotFlow {
            listOf(controlsVisible, isPlaying, isSeeking, interactionTick)
        }.collectLatest {
            if (controlsVisible && isPlaying && !isSeeking) {
                delay(AUTO_HIDE_MS)
                if (controlsVisible && isPlaying && !isSeeking) {
                    controlsVisible = false
                }
            }
        }
    }

    // 全屏时：返回仅退出全屏。
    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    // 非全屏时：预测式返回，拖动跟手右滑；越过阈值则滑出并返回主页，否则回弹。
    // 被上层盖住（backEnabled=false）时不抢返回键 —— 让上层页自己处理。
    // 块未按回调作 key 重启，宿主下发的 lambda 经 rememberUpdatedState 读最新。
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnBackProgress by rememberUpdatedState(onBackProgress)
    val currentOnBackReleased by rememberUpdatedState(onBackReleased)
    PredictiveBackHandler(enabled = !isFullscreen && backEnabled) { flow: Flow<BackEventCompat> ->
        // 每次手势一个新实例：对 progress 事件做时间差分，估算松手瞬间的跟手速度。
        val velocity = BackVelocityTracker()
        try {
            flow.collect { ev ->
                backProgress = ev.progress.coerceIn(0f, 1f)
                velocity.onEvent(backProgress, System.nanoTime())
                currentOnBackProgress(backProgress)
            }
            // 手势完成：先上报动量（冻结进度 + 松手速度），再交给外层退场动画（见下）。
            // 不在此处加 animateTo，以避免该挂起点被打断导致 onBack 不执行、状态停留在播放页；
            // 也不复位 backProgress（冻结位移留在原地，从松手位置继续滑出）。
            currentOnBackProgress(null)
            currentOnBackReleased(backProgress, velocity.velocity())
            currentOnBack()
        } catch (e: CancellationException) {
            // 取消返回：同步复位（普通状态写入，无协程/锁），杜绝与下一次手势争抢造成错乱。
            backProgress = 0f
            currentOnBackProgress(null)
        }
    }

    // 换片/装片空窗的统一加载提示（本地/在线一致，条件在此算好、UI 只管画）：
    //   转圈覆盖三种情况 —— 地址解析中（在线）、地址已到但还没装进当前实例（换代空窗）、
    //   缓冲中（含播放中网络卡顿，老行为）。拖动 seek 时由居中时间提示负责，不叠加；
    //   错误面板（playError/resolveError）在屏时也不叠加。
    val showSwitchLoading = playError == null && resolveError == null && !isSeeking &&
        (mediaUrl.isBlank() || preparedUrl != mediaUrl || isBuffering)
    // 黑场垫底只跟"换片空窗"走：解析中 / 换代空窗 / 已装片但还没出过首帧。
    //   播放中的网络卡顿只转圈、不压黑画面（画面保持，老行为不变）。
    val switchBlackout = showSwitchLoading &&
        (mediaUrl.isBlank() || preparedUrl != mediaUrl || !firstFrameSeen)
    val switchLoadingText = if (mediaUrl.isBlank()) {
        tr("正在获取播放地址…", "Fetching playback URL…")
    } else {
        tr("正在加载…", "Loading…")
    }

    PlayerContent(
        player = player,
        currentPlayer = { currentPlayer },
        video = video,
        online = online,
        related = related,
        commentsTargetId = commentsTargetId,
        commentsPane = commentsPane,
        onAddToPlaylist = onAddToPlaylist,
        onSelectAuthor = onSelectAuthor,
        onSelectTag = onSelectTag,
        onSelectFolder = onSelectFolder,
        onOpenRelated = onOpenRelated,
        isFullscreen = isFullscreen,
        controlsVisible = controlsVisible,
        isPlaying = isPlaying,
        isSeeking = isSeeking,
        showSwitchLoading = showSwitchLoading,
        switchBlackout = switchBlackout,
        switchLoadingText = switchLoadingText,
        durationMs = durationMs,
        positionMs = { positionMs },
        scrubTargetMs = { scrubTargetMs },
        screenWidthPx = screenWidthPx,
        screenHeightDp = screenHeightDp,
        backProgress = { backProgress },
        coverAnim = { coverAnim.value },
        coverShiftPx = coverShiftPx,
        pageShape = pageShape,
        pageExposed = pageExposed,
        isLocal = isLocal,
        qualityLabel = qualityLabel,
        srEnabled = srEnabled,
        srModel = srModel,
        showDiagnostics = showDiagnostics,
        playError = playError,
        resolveError = resolveError,
        debugDetails = debugDetails,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        localVideoWidth = localVideoWidth,
        localVideoHeight = localVideoHeight,
        audioOnly = hasVideoTrack == false,
        onVideoTap = { if (controlsVisible) controlsVisible = false else pokeControls() },
        pokeControls = { pokeControls() },
        onTogglePlay = {
            currentPlayer.playWhenReady = !currentPlayer.playWhenReady
            pokeControls()
        },
        onVideoDragStart = { ms ->
            isSeeking = true
            scrubTargetMs = ms
        },
        onVideoDrag = { ms -> dispatchScrub(ms) },
        onVideoDragEnd = { isSeeking = false },
        onTopBarBack = {
            if (isFullscreen) isFullscreen = false
            else {
                currentPlayer.pause()
                onBack()
            }
        },
        onBack = onBack,
        onRetryResolve = onRetryResolve,
        onOpenDiagnostics = { showDiagnostics = true },
        onDismissDiagnostics = { showDiagnostics = false },
        onToggleFullscreen = { isFullscreen = !isFullscreen },
        onSelectSr = { model ->
            if (model == null) {
                srEnabled = false
            } else {
                srEnabled = true
                srModel = model
            }
        },
        onSelectQuality = { q -> online?.let { it.onSelectQuality(q) } },
        localSources = localSources,
        onDeleteLocalSources = onDeleteLocalSources,
        onRetryPlay = {
            if (online != null) online.onRetry() else {
                playError = null
                currentPlayer.prepare()
                currentPlayer.playWhenReady = true
            }
        },
        onSeekStart = { isSeeking = true },
        onSeek = { ms -> dispatchScrub(ms) },
        onSeekEnd = {
            isSeeking = false
            pokeControls()
        }
    )
}

/** 播放页诊断日志 tag（切 SR 黑屏 / 播放报错都靠这条链定位）。 */
private const val TAG = "PlayerScreen"
