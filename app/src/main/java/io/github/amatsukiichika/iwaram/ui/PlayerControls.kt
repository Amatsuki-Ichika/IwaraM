package io.github.amatsukiichika.iwaram.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import io.github.amatsukiichika.iwaram.ui.components.AnchorMenu
import io.github.amatsukiichika.iwaram.ui.components.AnchorMenuGeometry
import io.github.amatsukiichika.iwaram.ui.components.AnchorMenuInfo
import io.github.amatsukiichika.iwaram.ui.components.AnchorMenuItem
import io.github.amatsukiichika.iwaram.ui.components.LocalDebugDetails
import kotlin.math.roundToInt

/** 底部控制条：暂停/播放 · 进度条 · 时间 · 清晰度 · 全屏，带淡入淡出与上滑动画。 */
@Composable
internal fun PlayerBottomBar(
    visible: Boolean,
    isPlaying: Boolean,
    /** 收 lambda：进度每 400ms 更新，读的本该只有这条底栏里的进度条与时间文字。 */
    positionMs: () -> Long,
    durationMs: Long,
    isFullscreen: Boolean,
    qualityLabel: String?,
    showLocalBadge: Boolean,
    qualityOptions: List<QualityOption>,
    currentQuality: String?,
    srEnabled: Boolean,
    srModel: String,
    onSelectSr: (String?) -> Unit,
    onInteract: () -> Unit,
    onSelectQuality: (QualityOption) -> Unit,
    /** 本地多源时的管理入口；null = 只有一份源，菜单里不出现。 */
    onManageSources: (() -> Unit)? = null,
    onTogglePlay: () -> Unit,
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    // SR chip 文案在这里读：SuperResStatus 由 GL 线程回填、读状态即订阅，
    // 订阅范围收在本组件内 —— 装片/切模型的状态写入只重组底栏，不再带上整页播放页。
    val srLabel = SuperResStatus.chipLabel(
        enabled = srEnabled,
        model = srModel,
        scale = SuperResStatus.activeScale,
        degraded = SuperResStatus.degraded
    )
    // AnimatedVisibility 在"稳定显示"时会为底栏保留一个合成图层；该图层压在视频 SurfaceView
    // 之上时，图层边界处可能出现 1px 半透明接缝（底栏渐变末端一条细透明线）。
    // 改成**只在淡入/淡出期间建图层**，稳态（alpha=1）不建层，淡入淡出时长与观感完全不变。
    val barAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(160),
        label = "playerBottomBarAlpha"
    )
    if (barAlpha > 0.01f) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (barAlpha < 0.999f) Modifier.graphicsLayer { alpha = barAlpha } else Modifier
                )
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                // 渐变背景铺满整条（被屏幕圆角/手势条遮挡是正常观感），只把可交互内容内缩出安全边距
                .padding(
                    start = if (isFullscreen) 10.dp else 2.dp,
                    end = if (isFullscreen) 10.dp else 2.dp,
                    top = 2.dp,
                    bottom = if (isFullscreen) 12.dp else 2.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "暂停/播放",
                    tint = Color.White
                )
            }
            // 把「进度条 + 时间」一起装进弹性组，让**只有进度区会收缩**。
            // 时间文字若作 Row 的直接子项（无 weight），Compose 的 Row 会按**整行宽度**测量
            // 所有非 weight 子项 —— "播放键 + 时间 + SR chip + 清晰度 chip + 全屏键"的固定宽度
            // 之和一旦超过屏宽，末尾的清晰度 chip 就被直接推出屏幕（窄屏 / 大字号时必现）。
            // 收进来后，SR chip 与清晰度 chip（含右下角勾选徽标）的宽度成为硬保证，永不互相挤占。
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SeekBar(
                    currentMs = positionMs,
                    durationMs = durationMs,
                    onSeekStart = onSeekStart,
                    onSeek = onSeek,
                    onSeekEnd = onSeekEnd,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    // 去掉 ` / ` 两侧空格，窄屏 / 大字体下给右侧两个 chip 再多让一点宽度。
                    text = "${formatTime(positionMs())}/${formatTime(durationMs)}",
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(start = 3.dp, end = 1.dp)
                )
            }
            // 清晰度：在线可点选档位（当前档 ✓），本地纯展示智能映射标签。
            // 清晰度 chip 排在 SR chip 前面（清晰度优先级更高、永远靠内），两 chip 都加宽度上限。
            if (qualityLabel != null) {
                MenuChip(
                    text = qualityLabel,
                    enabled = qualityOptions.isNotEmpty(),
                    badge = showLocalBadge,
                    onInteract = onInteract
                ) { closeMenu ->
                    qualityOptions.forEach { q ->
                        // 紧凑菜单条目：以勾选图标替代原先的文本后缀。
                        AnchorMenuItem(
                            text = q.name,
                            selected = q.name == currentQuality,
                            onClick = {
                                closeMenu()
                                onSelectQuality(q)
                                onInteract()
                            }
                        )
                    }
                    // 本地多源才有管理入口：与选档同一张菜单，但排在最后一行，
                    // 日常播放时看不见它（这是删除流程的入口，低频且不可逆）。
                    if (onManageSources != null) {
                        AnchorMenuItem(
                            text = "管理源文件…",
                            selected = false,
                            onClick = {
                                closeMenu()
                                onManageSources.invoke()
                                onInteract()
                            }
                        )
                    }
                }
            }
            // 超分开关（位于清晰度选项右侧，同款样式）。
            // chip 文案恒为单行短串（`SR` / `SR 未生效` / `SR·3D 2×`），倍率细节收进下拉菜单，
            // 保证不与右侧清晰度 chip 互相挤占。
            MenuChip(text = srLabel, onInteract = onInteract) { closeMenu ->
                // 按**使用场景**命名的平铺列表，不用 A/B/C 这种不直观的字母。
                // 顺序即常用程度：MMD/3D 最对口也最省电，高质最吃 GPU。
                AnchorMenuItem(
                    text = "关闭",
                    selected = !srEnabled,
                    onClick = {
                        closeMenu()
                        onSelectSr(null)
                        onInteract()
                    }
                )
                // 源 / 输出分辨率**实测值**（configure() 收到的帧尺寸），只读一行。
                // 这里只显示 GPU 真正拿到/产出的尺寸，播放器上报值挪到诊断面板的「播放器上报」
                // 一行，两者对不上时一眼就能看出。
                SuperResStatus.resolutionInfo(
                    isUpscalingModel = srEnabled && SuperRes.isUpscaling(srModel),
                    scale = SuperResStatus.activeScale
                )?.let { info ->
                    AnchorMenuInfo(text = info)
                }
                // 失败原因直接显示在菜单里（可截屏反馈），不用抓 logcat。
                SuperResStatus.reason?.let { reason ->
                    AnchorMenuInfo(text = "原因：$reason", maxLines = 2)
                }
                // Debug 开关打开时，模型列表末尾附加「测试·红/绿」管线探针
                SuperRes.modelsFor(LocalDebugDetails.current).forEach { m ->
                    val isCurrent = srEnabled && srModel == m
                    AnchorMenuItem(
                        text = SuperRes.label(m),
                        selected = isCurrent,
                        onClick = {
                            closeMenu()
                            onSelectSr(m)
                            onInteract()
                        }
                    )
                }
            }
            IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = "全屏",
                    tint = Color.White
                )
            }
        }
    }
}

/** 拖动进度时的居中时间提示（左右滑动视频与拖动底部进度条共用）。
 *  `visible` / `positionMs` 收 **lambda**（拖动目标与进度每帧都在变）：
 *  在叶子节点里读，拖动期间只重排这个小提示，不再每帧重组整页播放页。 */
@Composable
internal fun SeekTimeOverlay(
    visible: () -> Boolean,
    positionMs: () -> Long,
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible(),
        modifier = modifier,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120))
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "${formatTime(positionMs())} / ${formatTime(durationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}


internal fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 细线条进度条 + 圆形滑块，支持点击定位与拖动。
 *  `currentMs` 收 lambda：进度每 400ms 变一次、拖动时每帧变，
 *  在**本组件内**读才只让这条进度条重组，而不是整页播放页。 */
@Composable
private fun SeekBar(
    currentMs: () -> Long,
    durationMs: Long,
    onSeekStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val frac = if (durationMs > 0) (currentMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0 && size.width > 0) {
                        val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek((f * durationMs).toLong())
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { onSeekStart() },
                    onDragEnd = { onSeekEnd() },
                    onDragCancel = { onSeekEnd() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (durationMs <= 0 || size.width <= 0) return@detectHorizontalDragGestures
                        val f = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek((f * durationMs).toLong())
                    }
                )
            }
    ) {
        // 底部灰线
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color.White.copy(alpha = 0.3f))
        )
        // 已播放线段
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(frac)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color.White)
        )
        // 圆形滑块：用 layout 修饰符按比例定位，避免 BoxWithConstraints 的子组合 ——
        // 进度每 400ms/拖动时每帧变化都要重组本组件，子组合的二次测量是白付的开销。
        Box(
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(
                            ((constraints.maxWidth - placeable.width) * frac).roundToInt(),
                            (constraints.maxHeight - placeable.height) / 2
                        )
                    }
                }
                .size(12.dp)
                .background(Color.White, CircleShape)
        )
    }
}


/** 顶部控制条：返回 + 标题，带淡入淡出与上滑动画。 */
@Composable
internal fun PlayerTopBar(
    visible: Boolean,
    title: String,
    onBack: () -> Unit,
    isFullscreen: Boolean = false,
    onOpenDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 同底栏：稳态不建合成图层，避免压在视频 SurfaceView 上的 1px 接缝。
    val barAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(160),
        label = "playerTopBarAlpha"
    )
    if (barAlpha > 0.01f) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (barAlpha < 0.999f) Modifier.graphicsLayer { alpha = barAlpha } else Modifier
                )
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)))
                // 渐变背景铺满整条（被屏幕圆角遮挡是正常观感），只把可交互内容内缩出安全边距
                .padding(
                    start = if (isFullscreen) 10.dp else 4.dp,
                    end = if (isFullscreen) 10.dp else 4.dp,
                    top = if (isFullscreen) 8.dp else 2.dp,
                    bottom = 2.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 右上角三个点 → 播放诊断面板（状态 / 日志 + 一键复制），排障不再依赖 logcat。
            // 诊断入口只在 Debug 开关打开时可见，日常界面不出现。
            if (LocalDebugDetails.current) {
                IconButton(onClick = onOpenDiagnostics, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "播放诊断",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/**
 * 底栏菜单 chip（清晰度 chip 与 SR chip 同构收口）：
 * 单行文本 + 点击弹下拉菜单，可选右下角「已下载」勾选徽标。
 * [menuContent] 收一个 `closeMenu` 参数 —— 菜单项点击后自己调用来收起菜单
 * （弹出的 onDismissRequest 只覆盖"点外部关闭"那条路径）。
 * 宽度上限 92.dp：保证与右侧相邻 chip 不互相挤占（弹性组结构的硬保证）。
 * 弹出体用 [AnchorMenu]（而非 M3 DropdownMenu）：落位交给 [AnchorMenuGeometry]（方向、贴边距、
 * 内部滚动，展开方向与"盖住视频下缘"的代价见 `docs/decisions/0007-player-menu-placement.md`），
 * 行高压到 32dp（字号不变）。锚点范围必须每次布局都量进 `anchorRect`：位置、高度上限、缩放原点全靠它。
 */
@Composable
private fun MenuChip(
    text: String,
    enabled: Boolean = true,
    badge: Boolean = false,
    onInteract: () -> Unit,
    menuContent: @Composable (closeMenu: () -> Unit) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 锚点（chip 整块）在窗口内的像素范围 → 菜单的展开方向、高度上限、缩放原点。
    var anchorRect by remember { mutableStateOf(IntRect.Zero) }
    Box(
        Modifier.onGloballyPositioned { coords ->
            val position = coords.positionInWindow()
            val left = position.x.roundToInt()
            val top = position.y.roundToInt()
            anchorRect = IntRect(left, top, left + coords.size.width, top + coords.size.height)
        }
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .widthIn(max = 92.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = enabled) {
                    menuOpen = true
                    onInteract()
                }
                .padding(horizontal = 6.dp, vertical = 6.dp)
        )
        if (badge) {
            // 勾选徽标：白色圆环 + 透明底 + 白勾，小一圈，叠在文字右下角
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .size(13.dp)
                    .border(1.2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已下载",
                    tint = Color.White,
                    modifier = Modifier.size(8.dp)
                )
            }
        }
        AnchorMenu(
            expanded = menuOpen,
            onDismissRequest = {
                menuOpen = false
                onInteract()
            },
            anchor = anchorRect
        ) {
            menuContent { menuOpen = false }
        }
    }
}
