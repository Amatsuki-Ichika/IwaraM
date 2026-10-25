package io.github.amatsukiichika.iwaram.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import io.github.amatsukiichika.iwaram.ui.components.GlassTopBar
import io.github.amatsukiichika.iwaram.ui.components.glassViewBackdrop
import io.github.amatsukiichika.iwaram.ui.components.rememberGlassViewBackdrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.amatsukiichika.iwaram.data.IwaraClient
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.ui.components.AvatarImage
import io.github.amatsukiichika.iwaram.ui.components.PillSegment
import io.github.amatsukiichika.iwaram.ui.components.PillSelector
import io.github.amatsukiichika.iwaram.ui.components.R18Pill
import io.github.amatsukiichika.iwaram.ui.components.horizontalSlideSpec
import io.github.amatsukiichika.iwaram.ui.browse.LocalShowR18Tag
import io.github.amatsukiichika.iwaram.ui.browse.formatCount
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 详情区两个 tab：固定头药丸条的目标态（ordinal 定横滑方向）。 */
private enum class DetailsTab { INTRO, COMMENTS }

/**
 * 详情区：药丸条固定在顶部（视频正下方），标题/统计移进简介 tab ——
 * 点「评论」后整页只有评论（药丸条仍固定可切回）。
 * - 简介 tab（单 [LazyColumn]）：标题 → 统计（含 R18 药丸）→ 作者行 → 简介 → 操作区 → 标签
 *   → 路径 → 相关推荐；
 * - 评论 tab = [io.github.amatsukiichika.iwaram.ui.browse.CommentsPanel] 全页（宿主经 [commentsPane] 装配，
 *   状态机与独立评论页共用）。
 * 评论入口只有药丸条。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailsSection(
    video: VideoEntity,
    modifier: Modifier = Modifier,
    onSelectAuthor: (String) -> Unit = {},
    onSelectTag: (String) -> Unit = {},
    onSelectFolder: (String) -> Unit = {},
    online: OnlineVideoExtras? = null,
    /** 相关推荐（在线 = related 接口；本地 = 同作者/同标签）。 */
    related: List<RelatedVideo> = emptyList(),
    onOpenRelated: (RelatedVideo) -> Unit = {},
    /** 评论 tab 的目标 id（在线 = 视频 id / 本地 = 匹配到的 iwaraId）；null = 无评论目标。 */
    commentsTargetId: String? = null,
    /** 评论 tab 内容（宿主装配 [io.github.amatsukiichika.iwaram.ui.browse.CommentsPanel]）。 */
    commentsPane: @Composable () -> Unit = {},
    /**
     * 「收藏」按钮（网站"添加到"）→ 打开加入播放列表面板；
     * null = 不显示（本地视频未匹配 iwaraId，无目标）。
     */
    onAddToPlaylist: (() -> Unit)? = null
) {
    var tab by rememberSaveable { mutableStateOf(DetailsTab.INTRO) }

    // 药丸条是悬浮的玻璃条，简介列表从它底下穿过。
    // 结构与本地页 / Iwara 页一致（见 GlassBackdrop.GlassTopBar）：底 = 内容（挂背景源），
    // 上层 = 条（是源的兄弟节点，防自采样）；条高实测后喂回内容的 contentPadding(top)。
    Box(modifier.fillMaxWidth()) {
        val tabBackdrop = rememberGlassViewBackdrop()
        var barPx by remember { mutableIntStateOf(0) }
        val barDp = with(LocalDensity.current) { barPx.toDp() }

        Box(Modifier.fillMaxSize().glassViewBackdrop(tabBackdrop)) {
        // 内容区：两 tab 横滑切换（320ms，与全仓转场一致），各自独立滚动容器。
        AnimatedContent(
            targetState = tab,
            transitionSpec = horizontalSlideSpec { target, initial -> if (target.ordinal >= initial.ordinal) 1 else -1 },
            modifier = Modifier.fillMaxSize(),
            label = "detailsTab"
        ) { current ->
            when (current) {
                DetailsTab.INTRO -> IntroColumn(
                    video = video,
                    online = online,
                    related = related,
                    onSelectAuthor = onSelectAuthor,
                    onSelectTag = onSelectTag,
                    onSelectFolder = onSelectFolder,
                    onOpenRelated = onOpenRelated,
                    onAddToPlaylist = onAddToPlaylist,
                    // 让出悬浮药丸条的高度（走 contentPadding，内容仍从条底下穿过）。
                    topPadding = barDp
                )
                // 评论面板是宿主装配的（自带滚动），这里给它整块让出条高（padding 够用，
                // 且不用侵入外部组件去改它的 contentPadding）。
                DetailsTab.COMMENTS -> Box(Modifier.fillMaxSize().padding(top = barDp)) {
                    CommentsTabPane(commentsTargetId, commentsPane)
                }
            }
        }
        }   // Box(内容区 + 背景源)

        // 悬浮药丸条（标题/统计移进简介 tab；评论 tab 整页只有评论）。
        GlassTopBar(
            backdrop = tabBackdrop,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .onSizeChanged { barPx = it.height }
        ) {
        PillSelector(
            segments = listOf(
                PillSegment(DetailsTab.INTRO, "简介"),
                PillSegment(DetailsTab.COMMENTS, commentTabLabel(video.commentCount))
            ),
            selected = tab,
            onSelect = { tab = it },
            evenWeight = true,
            rowModifier = Modifier.fillMaxWidth(),
            modifier = Modifier
                .fillMaxWidth()
                // 上下间隙对称：上下各 8dp，列表顶部 padding 归零（见 IntroColumn）。
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
        )
        }
    }
}

/**
 * 简介 tab 内容：首两项 = 标题 + 统计（含 R18 药丸），
 * 其后 = 作者行 → 简介 → 操作区 → 标签 → 路径 → 相关推荐；单 [LazyColumn]。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntroColumn(
    video: VideoEntity,
    online: OnlineVideoExtras?,
    related: List<RelatedVideo>,
    onSelectAuthor: (String) -> Unit,
    onSelectTag: (String) -> Unit,
    onSelectFolder: (String) -> Unit,
    onOpenRelated: (RelatedVideo) -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    /** 悬浮药丸条的高度 → 顶部 contentPadding（内容从条底下穿过）。 */
    topPadding: Dp = 0.dp
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val tags = remember(video) {
        video.tagsCsv?.split(",")?.filter { it.isNotBlank() }.orEmpty()
    }
    // remember 必须在 LazyColumn 外（LazyListScope 非组合上下文，组合函数不允许在里面调用）。
    // 统计行只保留 日期/播放 + R18 药丸：评论数已由评论 tab 标签带数量、「赞」已由点赞按钮带数量。
    val stats = remember(video) {
        buildList {
            video.createdAt?.let { add(Icons.Filled.Schedule to dateStr(it)) }
            video.viewCount?.let { add(Icons.Filled.Visibility to "${formatCount(it)} 播放") }
        }
    }
    // 换片（点相关推荐）时滚回顶部：remember 的滚动位置跨视频保留，不复位的话
    //   新片的详情区会停在旧片滚到的位置，看起来像"换了个壳还是旧内容"。
    //   用**动画**滚回（而不是瞬移）：换片瞬间黑场盖着视频区，详情区这段上滚
    //   正是"切到新片"的可见反馈之一。详情列表很短（十来个 item），
    //   animateScrollToItem 不会有长列表飞越的合成压力（长列表见 ScrollToTopButton 的混合策略）。
    val listState = rememberLazyListState()
    LaunchedEffect(video.iwaraId, video.localUri) {
        if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) {
            listState.animateScrollToItem(0)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 顶部 padding 归零：药丸条自己已经留了 8dp，再叠加会造成上下间隙不对称。
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPadding, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item("title") {
            Text(
                text = video.title,
                style = MaterialTheme.typography.titleLarge,
                color = cs.onBackground,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 统计信息 + 分级药丸（R18 取色同卡片角标、尊重「显示 R18 标签」开关；
        // 评论数不进统计行，由评论 tab 标签带数量；stats 在上方 remember）。
        if (stats.isNotEmpty() || !video.rating.isNullOrBlank()) {
            item("stats") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    stats.forEach { (icon, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text(label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                    RatingBadge(video.rating)
                }
            }
        }
        if (video.author != null) {
            item("author") {
                DetailsAuthorRow(
                    author = video.author,
                    authorId = video.authorId,
                    avatarUrl = online?.avatarUrl,
                    canFollow = online?.canFollow == true,
                    following = online?.following == true,
                    onToggleFollow = { online?.onToggleFollow?.invoke() },
                    onSelectAuthor = onSelectAuthor
                )
            }
        }
        // 视频简介：默认折叠 2 行。仅在线视频显示（本地无简介数据）。
        online?.description?.takeIf { it.isNotBlank() }?.let { body ->
            item("desc") { DescriptionBlock(body) }
        }
        // 操作区（评论入口 = 药丸条 tab）。本地视频（含未匹配 iwaraId 的）也显示这一行
        // （行内「下载」= 已下载完成态；有 iwaraId 时再带「原视频」）。
        if (online != null || !video.iwaraId.isNullOrBlank() || video.localUri.isNotBlank()) {
            item("actions") { ActionButtonsRow(video, online, onAddToPlaylist) }
        }
        if (tags.isNotEmpty()) {
            item("tags") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tags.forEach { tag -> TagChip(text = tag, onClick = { onSelectTag(tag) }) }
                }
            }
        }
        // 本地路径（点击切入对应文件夹）。
        video.relPath?.takeIf { it.isNotBlank() }?.let { path ->
            item("path") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectFolder(path.substringBefore('/')) }
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // 相关推荐：在线走 related 接口、本地走同作者/同标签；静默加载。
        if (related.isNotEmpty()) {
            item("related") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = cs.surfaceVariant)
                    Text("相关推荐", style = MaterialTheme.typography.titleSmall, color = cs.onBackground)
                    related.forEach { item ->
                        RelatedRow(item = item, onClick = { onOpenRelated(item) })
                    }
                }
            }
        }
    }
}

/**
 * 信息行末尾的分级药丸：R18 与卡片角标同一实现 [R18Pill]
 * （尊重「显示 R18 标签」开关）；general/未知分级用同规格灰药丸。
 */
@Composable
private fun RatingBadge(rating: String?) {
    if (rating.isNullOrBlank()) return
    if (rating.trim().equals("ecchi", ignoreCase = true)) {
        R18Pill(rating = rating, enabled = LocalShowR18Tag.current)
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(50)
        ) {
            Text(
                text = ratingLabelOf(rating),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * 操作区：3~4 个独立圆角矩形按钮（图标上/文字下、8dp 间隙）。
 * 顺序固定：点赞 / 收藏 / 下载 / 原视频（下载恒为从左往右第三个）。
 *
 * 「下载」按钮恒显示，只按"这条视频在不在本机"换形态：
 * - 在线未下载 → 可点（连接中/未连接置灰）；
 * - 已下载（本地播放）→ 「已下载」完成态，不可点。
 * 判定口径 = `video.localUri.isNotBlank()`（本地播放 = 已下载；在线播放恒为空串）。
 */
@Composable
private fun ActionButtonsRow(
    video: VideoEntity,
    online: OnlineVideoExtras?,
    /** 「收藏」（网站"添加到"）回调；null = 不显示该按钮。 */
    onAddToPlaylist: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val isActionable = online == null || online.netState == OnlineNetState.AVAILABLE
    /** 这条视频已在本机（本地播放），下载按钮显示为「已下载」完成态。 */
    val downloaded = video.localUri.isNotBlank()
    // 连接态不再有专门的指示器（按钮文字与信息行圆点都不是），只由"按钮能不能点"表达。
    // 点赞弹跳反馈：点赞状态变化时缩放弹一下再回弹（只弹图标不弹整钮）。
    val likeScale = remember { Animatable(1f) }
    LaunchedEffect(online?.liked) {
        likeScale.snapTo(0.72f)
        likeScale.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (online != null) {
            DetailActionButton(
                icon = if (online.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                // 常态文案（不显示「连接中 / 未连接」）。
                label = buildString {
                    append(if (online.liked) "已赞" else "点赞")
                    video.likeCount?.let { append(" ").append(formatCount(it)) }
                },
                onClick = online.onToggleLike,
                modifier = Modifier.weight(1f),
                enabled = isActionable,
                iconTint = if (online.liked) cs.primary else null,
                iconModifier = Modifier.graphicsLayer {
                    scaleX = likeScale.value
                    scaleY = likeScale.value
                }
            )
            // 「收藏」= 网站"添加到"（打开加入播放列表面板）。
            if (onAddToPlaylist != null) {
                DetailActionButton(
                    icon = Icons.Filled.Add,
                    label = "收藏",
                    onClick = onAddToPlaylist,
                    modifier = Modifier.weight(1f),
                    enabled = isActionable
                )
            }
        }
        // 下载按钮恒显示，且恒在第三个位置（点赞 → 收藏 → 下载 → 原视频）。
        // 已下载 = 完成态（淡底灰字，不高亮）且不可点；未下载 = 可点（受网络门控）。
        // 放在 `online != null` 之外：纯本地未匹配 iwaraId 的视频（online == null）
        // 也应看到「已下载」这一枚。
        if (downloaded || online != null) {
            DetailActionButton(
                icon = if (downloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                label = if (downloaded) "已下载" else "下载",
                onClick = { if (!downloaded) online?.onDownload?.invoke() },
                modifier = Modifier.weight(1f),
                enabled = !downloaded && isActionable,
                tone = if (downloaded) ActionTone.DONE else ActionTone.NORMAL
            )
        }
        video.iwaraId?.takeIf { it.isNotBlank() }?.let { id ->
            DetailActionButton(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                label = "原视频",
                onClick = { openInBrowser(context, videoPageUrl(id)) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 操作按钮：独立圆角矩形（12dp 圆角、图标 20dp 上 / 文字 11sp 下，
 * 按钮之间 8dp 间隙由调用方的 Row spacedBy 给出）。
 * 配色为主色系：底 = primary 14%、图标/文字 = primary；
 * [enabled]=false 时整钮置灰；[iconModifier] 给点赞弹跳动画用（只弹图标不弹整钮）。
 * [tone] 为完成态（已下载）：淡底 + 灰字 + 不可点，不特别高亮，但保留完成图标与文字。
 */
private enum class ActionTone {
    /** 常规可点/三态置灰。 */
    NORMAL,

    /** 完成态（已下载）：同款淡底 + 灰字 + 不可点。 */
    DONE
}

@Composable
private fun DetailActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconTint: Color? = null,
    tone: ActionTone = ActionTone.NORMAL,
    iconModifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val fg = when {
        // 完成态用 onSurfaceVariant（不高亮、不抢眼，但仍可读）。
        tone == ActionTone.DONE -> cs.onSurfaceVariant
        enabled -> cs.primary
        else -> cs.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = cs.primary.copy(alpha = 0.14f),
        contentColor = fg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = iconModifier.size(20.dp),
                tint = iconTint ?: fg
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 评论 tab 内容：宿主装配的 [io.github.amatsukiichika.iwaram.ui.browse.CommentsPanel]；没有可评论目标（本地未匹配 iwaraId）给占位提示。 */
@Composable
private fun CommentsTabPane(targetId: String?, pane: @Composable () -> Unit) {
    if (targetId == null) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "未匹配 Iwara 视频，暂无评论",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        pane()
    }
}

/** 评论 tab 文案：有计数显示「评论 1.2万」，无计数只显示「评论」。 */
internal fun commentTabLabel(count: Int?): String =
    if (count == null) "评论" else "评论 ${formatCount(count)}"

/**
 * 视频简介块：默认折叠 2 行省略，点整块或「展开 ▾ / 收起 ▴」切换
 * （animateContentSize 平滑过渡，无跳变）。仅在线视频显示（本地无简介数据）。
 */
@Composable
private fun DescriptionBlock(body: String) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember(body) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(tween(200))
            .clickable { expanded = !expanded }
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (expanded) "收起 ▴" else "展开 ▾",
            style = MaterialTheme.typography.labelMedium,
            color = cs.primary,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 「相关推荐」行的展示字段（两种来源映射成同一套 UI 数据，行渲染不分支）。 */
private data class RelatedRowUi(
    val title: String,
    val author: String?,
    val meta: String?,
    val thumbnail: String?
)

/** 「相关推荐」横排行：左 16:9 缩略图 + 右标题/作者·播放数。 */
@Composable
private fun RelatedRow(item: RelatedVideo, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ui = when (item) {
        is RelatedVideo.Online -> RelatedRowUi(
            title = item.item.title.orEmpty(),
            author = item.item.user?.name,
            meta = item.item.numViews?.let { "${formatCount(it)} 次播放" },
            thumbnail = IwaraClient.thumbnailUrl(item.item.file?.id)
        )
        is RelatedVideo.Local -> RelatedRowUi(
            title = item.video.title.ifBlank { item.video.fileName },
            author = item.video.author,
            meta = item.video.viewCount?.let { "${formatCount(it)} 次播放" },
            thumbnail = item.video.localThumbnailPath ?: item.video.thumbnailUrl
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.small)
                .background(cs.surfaceVariant)
        ) {
            if (ui.thumbnail != null) {
                AsyncImage(
                    model = ui.thumbnail,
                    contentDescription = ui.title,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = ui.title,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (ui.meta != null || ui.author != null) {
                Text(
                    text = listOfNotNull(ui.author, ui.meta).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * iwara 分级原始值 → 界面文案（与筛选器、卡片角标口径一致）：
 * general = 一般，ecchi = R18；其它未知值原样显示。
 */
private fun ratingLabelOf(raw: String): String = when (raw.trim().lowercase()) {
    "general" -> "一般"
    "ecchi" -> "R18"
    else -> raw
}

/**
 * 时间戳 → "yyyy-MM-dd"（本地时区）。
 * 用常量 [DateTimeFormatter.ISO_LOCAL_DATE]：不可变、线程安全、零分配，
 * 避免每次调用都新建 `SimpleDateFormat`。
 */
private fun dateStr(epochMs: Long): String =
    DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** iwara 网页地址：视频详情页与作者主页。 */
private const val IWARA_WEB = "https://www.iwara.tv"

private fun videoPageUrl(id: String): String = "$IWARA_WEB/video/$id"

/** 用系统浏览器打开指定链接，失败则静默忽略。 */
private fun openInBrowser(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * 作者行的最小高度 = 40dp。
 *
 * M3 的 `Surface(onClick=…)` 会强制 48dp 最小触控尺寸（由 `minimumInteractiveComponentSize`
 * 保证），而头像只有 36dp，若不做约束，「关注」按钮一出现整行就会从 36dp 跳到 48dp。
 * 因此「关注」按钮槽位常驻，且用 `LocalMinimumInteractiveComponentSize = Unspecified`
 * 有意退出 M3 的 48dp 最小触控（与 TagChip 同一做法），换取这 8dp 的紧凑，
 * 代价是那枚按钮触控面积变小。
 *
 * `internal` 是为了让单测能钉住这条不变量（见 `GlassBarGeometryTest`）。
 */
internal val AUTHOR_ROW_MIN_HEIGHT = 40.dp

/**
 * 详情区作者行：头像/图标 + 名字（可点进作者页）+ 在线视频的关注 / 已关注按钮。
 * 在线视频用真实头像（[avatarUrl]），本地保持图标；头像统一走 [AvatarImage]
 * （无 URL = 打包的 iwara 默认头像）。
 * 行高恒定：根 Row 钉死 [AUTHOR_ROW_MIN_HEIGHT]，且「关注」按钮槽位常驻
 * （不可关注时同一按钮整体透明 + 不可点）。这样任何会话/网络状态翻转都不改这一行高度，
 * 作者名的可用宽度也恒定。
 */
@Composable
private fun DetailsAuthorRow(
    author: String,
    authorId: String?,
    avatarUrl: String?,
    canFollow: Boolean,
    following: Boolean,
    onToggleFollow: () -> Unit,
    onSelectAuthor: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AUTHOR_ROW_MIN_HEIGHT)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (authorId != null) {
                        Modifier.clickable { onSelectAuthor(authorId) }
                    } else {
                        Modifier
                    }
                )
        ) {
            AvatarImage(
                url = avatarUrl,
                size = 36.dp,
                contentDescription = author
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = author,
                style = MaterialTheme.typography.bodyLarge,
                color = if (authorId != null) cs.primary else cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 「关注 / 已关注」按钮（需已登录 + 有作者 UUID）。
        // 槽位常驻：不可关注时不隐藏，而是渲染同一个按钮但 alpha=0 且不可点
        // （既不改高度、也不改右侧宽度）。
        // 该按钮退出 M3 的 48dp 最小触控高度（`LocalMinimumInteractiveComponentSize = Unspecified`，
        // 与 TagChip 同一做法），行高因此可以从 48dp 收到 40dp，且不会因"关注"出现而跳行。
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(
            onClick = { if (canFollow) onToggleFollow() },
            enabled = canFollow,
            shape = RoundedCornerShape(50),
            color = if (following) cs.surfaceVariant else cs.primary,
            contentColor = if (following) cs.onSurfaceVariant else cs.onPrimary,
            modifier = Modifier.alpha(if (canFollow) 1f else 0f)
        ) {
            Text(
                text = if (following) "已关注" else "关注",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        }
    }
}
