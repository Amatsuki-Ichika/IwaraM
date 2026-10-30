package io.github.amatsukiichika.iwaram.ui

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.github.amatsukiichika.iwaram.data.MediaFormats
import io.github.amatsukiichika.iwaram.data.VideoEntity

/**
 * 播放页的 UI 树（从 PlayerScreen() 的绘制段抽出）。
 *
 * 只负责把父级已有的值与回调摆到正确位置，不新增/删除渲染节点、不改变条件与文案。
 *
 * 相位敏感参数：在 `graphicsLayer{}` / `drawWithContent{}` 等布局/绘制相位里读取的状态
 * （[backProgress]、[coverAnim]）一律收 lambda 而非取值再传，否则会退化成组合相位读取，
 * 使每次跟手/让位动画都重组整页。
 */
@OptIn(UnstableApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlayerContent(
    player: Player,
    /** 未按 player 作 key 的手势 lambda 经它读最新实例（换代会重建 player）。 */
    currentPlayer: () -> Player,
    video: VideoEntity,
    online: OnlineVideoExtras?,
    related: List<RelatedVideo>,
    commentsTargetId: String?,
    commentsPane: @Composable () -> Unit,
    onAddToPlaylist: (() -> Unit)?,
    onSelectAuthor: (String) -> Unit,
    onSelectTag: (String) -> Unit,
    onSelectFolder: (String) -> Unit,
    onOpenRelated: (RelatedVideo) -> Unit,
    isFullscreen: Boolean,
    controlsVisible: Boolean,
    isPlaying: Boolean,
    isSeeking: Boolean,
    /** 换片/装片空窗的加载提示（转圈）开关与文案 —— 条件在 PlayerScreen 算好。 */
    showSwitchLoading: Boolean,
    /** 黑场垫底开关：仅换片空窗压黑画面；播放中网络卡顿只转圈。 */
    switchBlackout: Boolean,
    switchLoadingText: String,
    durationMs: Long,
    positionMs: () -> Long,
    scrubTargetMs: () -> Long?,
    screenWidthPx: Float,
    screenHeightDp: Dp,
    /** 绘制相位读取 → 收 lambda。 */
    backProgress: () -> Float,
    coverAnim: () -> Float,
    coverShiftPx: Float,
    pageShape: Shape,
    /**
     * 页面是否处于"需要变换/裁切"的状态（进出场动画中 / 预测返回跟手中 / 被上层盖住让位中）。
     * false = 静止铺满 —— 位移恒为 0、形状恒为直角矩形，此时整页不建合成图层，
     * 去掉一层压在视频 SurfaceView 上的图层，避免其边界 1px 半透明接缝。
     */
    pageExposed: Boolean,
    isLocal: Boolean,
    qualityLabel: String?,
    srEnabled: Boolean,
    srModel: String,
    showDiagnostics: Boolean,
    playError: PlayerErrorUi?,
    resolveError: String?,
    debugDetails: Boolean,
    videoWidth: Int?,
    videoHeight: Int?,
    localVideoWidth: Int?,
    localVideoHeight: Int?,
    /** 轨道表确认当前片源没有视频轨（纯音频）：视频区显示音频占位而不是整块黑。 */
    audioOnly: Boolean,
    onVideoTap: () -> Unit,
    pokeControls: () -> Unit,
    onTogglePlay: () -> Unit,
    /** 视频区横向拖动（左右滑视频）：开始 / 目标 / 结束。 */
    onVideoDragStart: (Long) -> Unit,
    onVideoDrag: (Long) -> Unit,
    onVideoDragEnd: () -> Unit,
    /** 顶栏返回键（全屏时内部退全屏，否则暂停并退出）—— 逻辑在父级，这里只转发。 */
    onTopBarBack: () -> Unit,
    /** 错误面板的「退出」。 */
    onBack: () -> Unit,
    /** 解析失败面板的「重试」。 */
    onRetryResolve: (() -> Unit)?,
    onOpenDiagnostics: () -> Unit,
    onDismissDiagnostics: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onSelectSr: (String?) -> Unit,
    onSelectQuality: (QualityOption) -> Unit,
    /** 播放错误面板的「重试」。 */
    onRetryPlay: () -> Unit,
    /** 底栏进度条的拖动开始 / 目标 / 结束（与视频区横拖是两条入口）。 */
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: () -> Unit,
    /** 同一支视频的全部本地源文件（已按推荐顺序排好）；只有一份时为空也不显示管理入口。 */
    localSources: List<VideoEntity> = emptyList(),
    /** 删除选中的源；deleteFiles 默认由面板控制（默认 false）。 */
    onDeleteLocalSources: (List<VideoEntity>, Boolean) -> Unit = { _, _ -> }
) {
    // 源文件管理面板的开关：只在用户点菜单里的「管理源文件…」时打开。
    var showSources by remember { mutableStateOf(false) }
    // 错误面板的「用其他应用打开」：本地取 content:// 地址，在线取当前解析出的播放地址；
    //   都拿不到时（地址解析中/已清空）不显示该按钮。MIME 优先用入库文件名，其次用在线
    //   播放地址的最后一段（短时效地址带查询串，先截掉）。
    val externalMedia: Pair<Uri, String?>? = remember(video.localUri, video.fileName, online?.mediaUrl) {
        val uri = video.localUri.ifBlank { online?.mediaUrl.orEmpty() }
        val onlineName = online?.mediaUrl.orEmpty()
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
        val mimeType = listOf(video.fileName, onlineName)
            .firstNotNullOfOrNull { MediaFormats.mimeTypeFor(it) }
        if (uri.isBlank()) null else Uri.parse(uri) to mimeType
    }
    // 根容器透明，背景放在内层 Column 上并随内容一起右滑，
    // 预测式返回时露出下方主页内容，避免左半边出现空白背景。
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 只有需要变换/裁切时才建图层；静止态（pageExposed=false）位移恒为 0、
                // 形状恒为直角矩形，图层纯属空操作 —— 去掉它即去掉压在视频 SurfaceView
                // 上的那一层，避免其边界 1px 半透明接缝。
                .then(
                    if (pageExposed) {
                        Modifier
                            .graphicsLayer {
                                // 右滑（跟手/退场）为正，被上层盖住的让位为负（左移）。
                                translationX = backProgress() * screenWidthPx - coverAnim() * coverShiftPx
                            }
                            // `.clip(shape)` 必须写在 `.background(...)` 之前 —— Modifier 链里
                            // "靠前的在外层"，背景画在裁切之外时不会被裁，整页底色会变成
                            // 一块直角矩形，四角本该露出的下层深色被填满。
                            .clip(pageShape)
                    } else {
                        Modifier
                    }
                )
                .background(MaterialTheme.colorScheme.background)
                // 被盖住时压暗（写在 clip 之内 → 跟着圆角一起裁；读 coverAnim 在绘制期 →
                // 每帧只重绘不重组）。栈内被盖页的压暗层也是这个公式。
                .drawWithContent {
                    drawContent()
                    if (coverAnim() > 0.01f) {
                        drawRect(Color.Black.copy(alpha = COVER_DIM_ALPHA * coverAnim()))
                    }
                }
        ) {
            // 非全屏时给状态栏留出空间，视频下移不顶到通知栏；通知栏背景固定黑色（深浅色一致）。
            // 瞬间插入（不做补间）—— 与视频区尺寸一起"一步到位"，避免动画期间出现
            // "先上移再下移"的中间态。
            if (!isFullscreen) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .statusBarsPadding()
                )
            }

            // 换片加载提示的两条 alpha（转圈层 / 黑场垫底层，180ms 淡入淡出）。
            val loadingAlpha by animateFloatAsState(
                targetValue = if (showSwitchLoading) 1f else 0f,
                animationSpec = tween(180),
                label = "switchLoadingAlpha"
            )
            val blackoutAlpha by animateFloatAsState(
                targetValue = if (switchBlackout) 1f else 0f,
                animationSpec = tween(180),
                label = "switchBlackoutAlpha"
            )

            VideoSurfaceBox(
                player = player,
                currentPlayer = currentPlayer,
                isFullscreen = isFullscreen,
                screenHeightDp = screenHeightDp,
                durationMs = durationMs,
                onVideoTap = onVideoTap,
                pokeControls = pokeControls,
                onVideoDragStart = onVideoDragStart,
                onVideoDrag = onVideoDrag,
                onVideoDragEnd = onVideoDragEnd
            ) {
                // 纯音频片源（轨道表确认没有视频轨）：视频区本来只有黑底，居中给一个
                //   音符图标 + 标题，避免用户看到整块黑色以为卡死；错误面板在屏时不叠加。
                if (audioOnly && playError == null && resolveError == null) {
                    AudioOnlyPlaceholder(
                        title = video.title,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                // 换片/装片空窗：黑场 + 中央加载提示（本地/在线统一，条件在 PlayerScreen 算好）。
                //   黑底整块淡入淡出 —— 淡出与新片首帧衔接成"渐显"，本地换片不再冻在旧片最后一帧；
                //   解析失败（resolveError）与播放错误（playError）面板在屏时不叠加。
                //   黑场垫底（switchBlackout）单独一层：播放中网络卡顿只转圈、不压黑画面。
                //   alpha 动画读在绘制相位（graphicsLayer），逐帧只重绘不重组。
                //   不用 AnimatedVisibility：overlay 槽位处于 BoxScope 嵌外层 ColumnScope 的
                //   双接收者环境，扩展重载会被误解析（编译器报"implicit receiver"）。
                if (loadingAlpha > 0.01f) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = loadingAlpha }) {
                        if (blackoutAlpha > 0.01f) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = blackoutAlpha }
                                    .background(Color.Black)
                            )
                        }
                        CenterLoadingOverlay(
                            switchLoadingText,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
                // 在线地址解析失败（断连 / 私密 403 / 外嵌）→ 在页内给出说明 + 「重试 / 退出」，
                // 绝不自动关页。私密与外嵌这两类大概率打不开，文案里直接点明，
                // 详情页仍提供「打开原视频」。
                resolveError?.let { msg ->
                    ResolveErrorOverlay(
                        message = msg,
                        debugDetails = debugDetails,
                        onRetryResolve = onRetryResolve,
                        onBack = onBack,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                // 播放出错：中央错误面板，带诊断信息与重试/退出。
                // 普通模式显示人话主行（friendly），debug 模式显示完整技术串（detail）。
                playError?.let { errorUi ->
                    PlayErrorOverlay(
                        error = errorUi,
                        debugDetails = debugDetails,
                        mediaUri = externalMedia?.first,
                        mediaMimeType = externalMedia?.second,
                        onRetry = onRetryPlay,
                        onBack = onBack,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // 拖动（左右滑动视频 / 拖动底部进度条）时的居中时间提示。
                // 画面本身由播放器实时跳到目标帧，这里只负责显示时间。
                // 两个参数都收 lambda —— 拖动时 scrubTargetMs 每帧都在变，
                // 在本页顶层读它（无论 visible 还是 positionMs）都会每帧重组整页播放页。
                SeekTimeOverlay(
                    visible = { isSeeking && scrubTargetMs() != null },
                    positionMs = { scrubTargetMs() ?: positionMs() },
                    durationMs = durationMs,
                    modifier = Modifier.align(Alignment.Center)
                )

                // 播放诊断面板（右上角 ⋮ 打开）：状态 / 日志 双 tab + 一键复制。
                if (showDiagnostics) {
                    DiagnosticsSheet(
                        onDismiss = onDismissDiagnostics,
                        isLocal = isLocal,
                        qualityLabel = qualityLabel,
                        videoWidth = videoWidth,
                        videoHeight = videoHeight,
                        localWidth = localVideoWidth,
                        localHeight = localVideoHeight,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        // 诊断面板里始终看完整技术串（detail 轨）
                        playError = playError?.detail
                    )
                }

                // 源文件管理（本地多源）：与诊断面板同层，盖在播放页之上。
                if (showSources && localSources.size > 1) {
                    PlayerSourcesSheet(
                        sources = localSources,
                        currentUri = video.localUri,
                        onDismiss = { showSources = false },
                        onDelete = { chosen, deleteFiles ->
                            showSources = false
                            onDeleteLocalSources(chosen, deleteFiles)
                        }
                    )
                }

                // 顶部短条渐变遮罩：返回 + 标题（淡入淡出 + 上滑动画）。
                PlayerTopBar(
                    visible = controlsVisible,
                    title = video.title,
                    onBack = onTopBarBack,
                    isFullscreen = isFullscreen,
                    onOpenDiagnostics = onOpenDiagnostics,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(1f)
                        .fillMaxWidth()
                    // 背景铺满不动（被圆角遮住是正常的），安全边距在 PlayerTopBar 内容层加
                )

                // 底部细条控制栏：暂停/播放 · 进度条 · 时间 · 清晰度 · 全屏（淡入淡出 + 上滑动画）。
                PlayerBottomBar(
                    visible = controlsVisible,
                    isPlaying = isPlaying,
                    // 传 lambda 而不是值 —— 进度每 400ms 变一次，读它的是底栏内部的
                    // 进度条与时间文字，不该把整页播放页带上一起重组。
                    positionMs = positionMs,
                    durationMs = durationMs,
                    isFullscreen = isFullscreen,
                    qualityLabel = qualityLabel,
                    showLocalBadge = isLocal,
                    qualityOptions = online?.qualities.orEmpty(),
                    currentQuality = online?.currentQuality,
                    srEnabled = srEnabled,
                    srModel = srModel,
                    onSelectSr = onSelectSr,
                    onInteract = pokeControls,
                    onSelectQuality = onSelectQuality,
                    // 只有多源时才给菜单加「管理源文件…」：单源没有可管理的对象。
                    onManageSources = if (localSources.size > 1) ({ showSources = true }) else null,
                    onTogglePlay = onTogglePlay,
                    onSeekStart = onSeekStart,
                    onSeek = onSeek,
                    onSeekEnd = onSeekEnd,
                    onToggleFullscreen = onToggleFullscreen,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(1f)
                        .fillMaxWidth()
                    // 背景铺满不动（被圆角/手势条遮住是正常的），安全边距在 PlayerBottomBar 内容层加
                )
            }

            // 详情区（视频下方，B 站式二级信息；全屏时隐藏）
            if (!isFullscreen) {
                DetailsSection(
                    video = video,
                    modifier = Modifier.weight(1f),
                    onSelectAuthor = onSelectAuthor,
                    onSelectTag = onSelectTag,
                    onSelectFolder = onSelectFolder,
                    online = online,
                    // 简介/头像在 extras 里；相关推荐独立传（本地视频也有）。
                    related = related,
                    onOpenRelated = onOpenRelated,
                    // 评论收进药丸条 tab（宿主装配面板；本地未匹配 iwaraId = 占位提示）。
                    commentsTargetId = commentsTargetId,
                    commentsPane = commentsPane,
                    // 「收藏」= 网站"添加到"（宿主打开加入播放列表面板）。
                    onAddToPlaylist = onAddToPlaylist
                )
            }
        }
    }
}

/**
 * 纯音频片源的画面占位：黑底上居中一个音符图标 + 标题。
 *
 * 只在轨道表确认没有视频轨时显示（判据见 PlayerScreen 的 onTracksChanged）；
 * 本地音频文件没有视频帧，不占位的话视频区是一整块纯黑，看起来像卡死。
 */
@Composable
private fun AudioOnlyPlaceholder(title: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null, // 装饰图标：标题文字已说明是音频
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 视频画面区：黑底 Box + 尺寸策略 + 三种手势，内容槽 [overlay] 放覆盖在画面上的
 * 加载/错误/时间提示与顶/底控制栏。
 *
 * 尺寸策略：全屏铺满；非全屏整宽 16:9 且限高半屏（横屏下 16:9 比屏幕还高，
 * 不限的话一退出全屏就把下面的详情页整块往下挤）。
 */
@Composable
private fun VideoSurfaceBox(
    player: Player,
    currentPlayer: () -> Player,
    isFullscreen: Boolean,
    screenHeightDp: Dp,
    durationMs: Long,
    onVideoTap: () -> Unit,
    pokeControls: () -> Unit,
    onVideoDragStart: (Long) -> Unit,
    onVideoDrag: (Long) -> Unit,
    onVideoDragEnd: () -> Unit,
    overlay: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isFullscreen) {
                    Modifier.fillMaxHeight()
                } else {
                    // 非全屏：整宽 16:9，但**限高半屏** —— 横屏下 16:9 比屏幕还高，
                    // 不限的话一退出全屏就把下面的详情页整块往下挤（见上方屏幕高度处的说明）。
                    Modifier.aspectRatio(16f / 9f).heightIn(max = screenHeightDp * 0.5f)
                }
            )
            // 不再对视频区做尺寸补间 —— 补间期间视频画面每帧都要重新适配（黑边/缩放比例
            // 逐帧重算），会看到"画面放大 / 画面上移"，且中间尺寸会被喂进超分管线，
            // 退出后画面停在放大态。尺寸瞬间切换则一步到位，没有中间态。
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onVideoTap() },
                    onDoubleTap = {
                        val p = currentPlayer()
                        p.playWhenReady = !p.playWhenReady
                        pokeControls()
                    }
                )
            }
            .pointerInput(durationMs) {
                var dragStartMs = 0L
                var dragAccumPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragStartMs = currentPlayer().currentPosition
                        dragAccumPx = 0f
                        onVideoDragStart(dragStartMs)
                        pokeControls()
                    },
                    onDragEnd = { onVideoDragEnd() },
                    onDragCancel = { onVideoDragEnd() },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        if (durationMs <= 0 || size.width <= 0) return@detectHorizontalDragGestures
                        dragAccumPx += dragAmount
                        val deltaMs = (dragAccumPx / size.width.toFloat()) * durationMs
                        val target = (dragStartMs + deltaMs.toLong()).coerceIn(0L, durationMs)
                        onVideoDrag(target)
                        pokeControls()
                    }
                )
            }
    ) {
        AndroidView(
            // 走复用池（省一次整棵 PlayerView inflate）；边界见 [PlayerViewPool]。
            factory = { ctx -> PlayerViewPool.acquire(ctx) },
            update = { it.player = player },
            onRelease = { PlayerViewPool.release(it) },
            modifier = Modifier.fillMaxSize()
        )
        overlay()
    }
}

/**
 * PlayerView 复用池（进程内最多留一个闲置实例）。
 *
 * 每次打开播放页都要 inflate 一整棵 PlayerView（SurfaceView + 字幕视图 + 控制器视图），
 * 这笔开销正好落在入场滑入动画的几帧里，缓存复用即可省掉。
 *
 * 安全边界：只交出 `parent == null`（确实已脱离视图树）的实例；仍挂在树上的
 * （快速退出后立刻再进、上一份组合尚未销毁）直接丢弃并新建，
 * 绝不把一个 View 同时挂到两个父节点上。
 */
@OptIn(UnstableApi::class)
private object PlayerViewPool {
    private var idle: PlayerView? = null

    fun acquire(context: Context): PlayerView {
        val cached = idle
        idle = null
        if (cached != null && cached.parent == null) {
            cached.player = null
            return cached
        }
        return PlayerView(context).apply { useController = false }
    }

    fun release(view: PlayerView) {
        // 先解绑播放器（别让闲置实例握着一个已释放的 player）。
        view.player = null
        idle = view.takeIf { it.parent == null }
    }
}
