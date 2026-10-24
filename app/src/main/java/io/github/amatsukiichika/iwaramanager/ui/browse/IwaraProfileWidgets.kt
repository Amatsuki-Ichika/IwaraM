package io.github.amatsukiichika.iwaramanager.ui.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.amatsukiichika.iwaramanager.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaramanager.data.IwaraAccount
import io.github.amatsukiichika.iwaramanager.data.IwaraClient
import io.github.amatsukiichika.iwaramanager.data.IwaraImageItem
import io.github.amatsukiichika.iwaramanager.data.IwaraPostItem
import io.github.amatsukiichika.iwaramanager.data.IwaraProfileData
import io.github.amatsukiichika.iwaramanager.data.IwaraVideoItem
import io.github.amatsukiichika.iwaramanager.data.VideoEntity
import io.github.amatsukiichika.iwaramanager.data.filterBy
import io.github.amatsukiichika.iwaramanager.data.localVideoKeys
import io.github.amatsukiichika.iwaramanager.data.videoKeys
import io.github.amatsukiichika.iwaramanager.ui.DiagLog
import io.github.amatsukiichika.iwaramanager.ui.components.debugAwareErrorText
import kotlin.math.roundToInt
import io.github.amatsukiichika.iwaramanager.ui.VideoCard
import io.github.amatsukiichika.iwaramanager.ui.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ---------- 作者页小组件（原 IwaraProfilePage.kt 头部/Bio/计数区块） ---------- */
/**
 * 非 READY（加载中 / 失败 / 离线）时的内容区：**头部照旧在顶部**，下面挂一个居中的提示。
 *
 * 分段栏在吸顶块里恒在，不随内容区一起被换掉。左右内缩补 12dp（网格外没有 contentPadding），
 * 顶部 12dp 由传进来的 [header]（measuredHeader）自己带。
 */
@Composable
internal fun HeaderWithHint(header: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        header()
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * 吸顶块：药丸分段栏 + 两个小按钮（来源筛选 / 排序）。
 *
 * **全页唯一的一份渲染**：覆盖在内容视口顶端，由调用方给的 [Modifier] 位移驱动下沉 / 锁定。
 * 不透明底，内容从它下面滑过不会透视。两个小按钮那一行**钉死高度** ——
 * 「来源筛选」只在视频栏出现，不钉死的话切分段会让整块高度变化、吸顶时抖动。
 *
 * 底边不放 1dp 分隔线（静止状态下像"视频上面多出一条横线"）。块高由 `onSizeChanged` 实测。
 */
@Composable
internal fun ProfileStickyBar(
    segment: ProfileSegment,
    sourceFilter: String,
    sort: String,
    onSegment: (Int) -> Unit,
    onSourceFilter: (String) -> Unit,
    onSort: (String) -> Unit,
    onHeight: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            // 底色必须与二级页背景一致（overlay 宿主用的是 colorScheme.background，
            // 不是 surface —— 浅色下两者是 #F7F8FA / #FFFFFF，用错会露出一条色带）。
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { onHeight(it.height) }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        PillSegmentSelector(
            options = ProfileSegment.entries.map { it.label },
            current = segment.ordinal,
            onSelect = onSegment,
            horizontalPadding = 0.dp
        )
        Row(
            Modifier.fillMaxWidth().height(34.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 来源筛选：排序 chip（趋势等）左边，同款样式；仅「视频」栏有意义。
            if (segment == ProfileSegment.VIDEOS) {
                SelectorChip(sourceFilter, SOURCE_FILTERS) { onSourceFilter(it) }
                Spacer(Modifier.size(8.dp))
            }
            SelectorChip(sort, VIDEO_SORTS) { onSort(it) }
        }
    }
}

/** 折叠态简介固定显示的行数；超出才出现「展开」。 */
private const val BIO_COLLAPSED_LINES = 3

/** 「展开」/「收起」—— 都是两个汉字，宽度相同（右侧预留宽按它算）。 */
private const val BIO_EXPAND_LABEL = "展开"
private const val BIO_COLLAPSE_LABEL = "收起"

/**
 * 可展开的作者简介。
 *
 * 「展开」两个字放在省略号『…』的**右边** —— 不是另起一行、也不是右下角浮动。
 * 用 `Box` + `offset` 精确落位（不用 FlowRow：只有这样才能正好贴在「…」正右方）：
 * 1. 先 `rememberTextMeasurer` 把按钮宽**量出来**，连同 4dp 间隙一起作为文字**右侧永久预留**
 *    （折叠态、展开态都留）—— 省略号因此必然落在预留区之前，按钮永远放得下；
 *    预留宽不依赖 "有没有溢出"，所以不会出现「预留 ↔ 溢出」互相触发的抖动。
 * 2. `onTextLayout` 给锚点：折叠态取**被省略号截断的那一行**（第 [BIO_COLLAPSED_LINES] 行）的
 *    右边缘 + 行顶；展开态取最后一行 ⇒ 折叠时紧跟「…」，展开时跟在文字末尾（「收起」也在原位置）。
 *    锚点只被 `Modifier.offset {}` 在 layout 相位读取，不触发重组。
 * 3. 只有 `hasVisualOverflow` 才显示按钮（短简介不留白）。**注意 `overflow` 只在折叠态更新** ——
 *    否则展开后不再溢出会把按钮自己弄没，就收不回去了。
 */
@Composable
internal fun ExpandableBio(text: String) {
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val buttonWidthPx = remember(measurer, style) {
        measurer.measure(AnnotatedString(BIO_EXPAND_LABEL), style = style).size.width
    }
    val gapPx = with(density) { 4.dp.roundToPx() }
    val reserveDp = with(density) { (buttonWidthPx + gapPx).toDp() }

    var expanded by remember(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    var anchorX by remember(text) { mutableFloatStateOf(0f) }
    var anchorY by remember(text) { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(
            text = text,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else BIO_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(end = reserveDp),
            onTextLayout = { r ->
                if (!expanded) overflow = r.hasVisualOverflow
                val last = (r.lineCount - 1).coerceAtLeast(0)
                val line = if (expanded) last else (BIO_COLLAPSED_LINES - 1).coerceAtMost(last)
                anchorX = r.getLineRight(line)
                anchorY = r.getLineTop(line)
            }
        )
        if (overflow) {
            Text(
                text = if (expanded) BIO_COLLAPSE_LABEL else BIO_EXPAND_LABEL,
                style = style,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .offset { IntOffset(anchorX.roundToInt() + gapPx, anchorY.roundToInt()) }
                    .clickable { expanded = !expanded }
            )
        }
    }
}

/**
 * 「关注 / 粉丝 / 视频」计数行。
 *
 * - **有数字**的关注 / 粉丝 → 可点，进关系列表二级页；数字那项高亮成主色（暗示可点）。
 * - **拿不到数字**（离线 / 未登录 / 接口没给）→ 显示 `—`，且**不是可点组件**（点了没有任何反馈）。
 * - 「视频」数只作信息展示，不可点（当前页就是视频列表）。
 */
@Composable
internal fun CountRow(
    counts: IwaraRelationCounts,
    onOpenFollowing: () -> Unit,
    onOpenFollowers: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CountItem("关注", counts.follows, onOpenFollowing)
        Spacer(Modifier.size(14.dp))
        CountItem("粉丝", counts.followers, onOpenFollowers)
        Spacer(Modifier.size(14.dp))
        CountItem("视频", counts.videos, null)
    }
}

@Composable
internal fun CountItem(label: String, value: Int?, onClick: (() -> Unit)?) {
    val clickable = value != null && onClick != null
    val text = "$label ${value?.toString() ?: "—"}"
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (clickable) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .then(if (clickable) Modifier.clickable { onClick?.invoke() } else Modifier)
            .padding(vertical = 2.dp)
    )
}

/**
 * 作者页「视频」栏。
 *
 * **不论从本地还是在线进入都是同一个状态**：
 * 1. 首帧渲染本地视频（零网络）；
 * 2. 在线数据返回后按 [AuthorRows.mergeAll] 重排行序：主干恒等于在线顺序，命中的位置**就地把
 *    在线卡换成本地版本**（点击即本地播放、卡片带「本地」标签），placement 动画滑过去；
 * 3. 未匹配的本地按自己留存的发布日期插进主干，**时间未知的排到最后**（旧实现回落到"导入时间"
 *    ⇒ 本地全堆在上面）；
 * 4. 所有行共用 `Modifier.animateItem()` —— 插入/移位/收尾全程动画。
 *
 * 头部（[header]）是**通栏首 item**：里面是"作者信息 + 等高的吸顶块占位"，所以整页只有一条滚动链；
 * 药丸栏由页面层的吸顶块负责，网格里看不到它，也就不会重影。
 */

/** 头像（有则显示，无则占位），尺寸与关注列表保持一致。 */
@Composable
internal fun AccountAvatar(account: IwaraAccount?, size: Int) {
    val url = IwaraClient.avatarUrl(account?.avatar)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(Modifier.size(size.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size((size * 0.6f).dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
