package io.github.amatsukiichika.iwaram.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.components.CardDateLabel
import io.github.amatsukiichika.iwaram.ui.components.R18Pill
import io.github.amatsukiichika.iwaram.ui.theme.LocalTagContainer

/*
 * 卡片统一：本地与在线共用**一份渲染实现** [VideoCardContent]。
 *
 * - 数据差异全部挡在映射层（本地 [VideoEntity] / 在线 `IwaraVideoItem` 各自映射成 [VideoCardModel]）；
 * - 三行（标题 / 作者 / 标签）的间距与最小高度全部来自 [VideoCardSpec]，本地与在线共用同一组常量
 *   ⇒ 同排卡片严格等高（缩略图仍是 16:9）；
 * - 作者行统一为作者名字本身可点（不再是整行大色块）；文字自带 clickable 即可命中，无需撑大触摸区；
 * - 「本地」标志只有在线卡片会显示（本地页整页都是本地的，标了没意义）。
 */

/** 卡片底部最多展示的 tag 数量，其余折叠成 +N。 */
private const val MAX_VISIBLE_TAGS = 3

/**
 * 卡片渲染规格：本地 / 在线**共用同一组常量**。
 * 想要"三行再矮一点"就只改这里，两页同时生效，不会再出现两边跑偏。
 */
object VideoCardSpec {
    /** 三行区域的内边距。 */
    val contentPadding = 6.dp

    /** 作者行最小高度。 */
    val authorRowMinHeight = 16.dp

    /** 标签行最小高度。 */
    val tagRowMinHeight = 15.dp

    /** 标题 → 作者、作者 → 标签的间距。 */
    val gapTitleAuthor = 1.dp
    val gapAuthorTags = 2.dp

    /** 缩略图上两个角标的内缩。 */
    val overlayPadding = 6.dp
}

/**
 * 卡片的**展示数据** —— 本地与在线各自映射到它，渲染层只认这个。
 *
 * 本地与在线的差别只剩 [showLocalBadge] 与缩略图来源，其余字段语义完全一致。
 *
 * 该类标 `@Immutable`、`thumbnail` 用 `String?` 而非 `Any?`：
 * 否则 `thumbnail`、`tags: List<String>` 会被 Compose 判为不稳定，
 * `VideoCardContent` 无法跳过重组，上层任何重组都会把可见卡片全部重跑。
 * 约定：两个映射点都即时构造新 `tags` 列表、从不原地改动。
 */
@Immutable
data class VideoCardModel(
    /** AsyncImage 的 model：本地抽帧路径 / iwara 封面 URL；null 时显示占位底色。 */
    val thumbnail: String?,
    val title: String,
    val author: String,
    val tags: List<String>,
    val rating: String?,
    /** 右上角 R18 角标是否可显示（还要 [rating] 本身是 ecchi 才真的画出来）。 */
    val showR18: Boolean = true,
    /**
     * 左上角「本地」标志（"这支片子在本地已有一份"）。
     * **只有在线卡片显示**，本地页 / 作者页的本地条目一律不显示。
     */
    val showLocalBadge: Boolean = false,
    /**
     * 发布日期（毫秒）。非空时在封面**左下角**画一枚日期胶囊
     * （<1h 刚刚 / <24h n 小时前 / <30d n 天前 / 其余 yyyy-MM-dd，见 [cardDateText]）。
     */
    val createdAtMs: Long? = null
)

/**
 * 视频卡片的**唯一渲染实现**（本地 / 在线共用）。
 *
 * 三行恒渲染（标题 / 作者 / 标签）—— 没有作者名、没有 tag 时也保留最小高度，
 * 保证同一排卡片严格等高（这是"整齐统一"的硬保证）。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun VideoCardContent(
    model: VideoCardModel,
    onClick: () -> Unit,
    onTagClick: (String) -> Unit,
    onLongClick: () -> Unit = {},
    /** 为 null = 作者不可点（在线卡片拿不到作者对象时就是这种情况）。 */
    onAuthorClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (model.thumbnail != null) {
                    AsyncImage(
                        // 网格卡片**显式关掉 crossfade** —— Coil 2 默认开 100ms 渐变，
                        //   双列网格快速滑动时几十个渐变同时在跑，是可见的掉帧来源；
                        //   详情页那类单张图仍走全局默认（保留淡入）。
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(model.thumbnail)
                            .crossfade(false)
                            .build(),
                        contentDescription = model.title,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                // 左上角：「本地」药丸（只有在线卡且本地库已有该 iwaraId 时为 true）。
                if (model.showLocalBadge) {
                    LocalPill(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(VideoCardSpec.overlayPadding)
                    )
                }
                // 右上角：R18 药丸。只认显式 ecchi —— 未匹配到 iwara（rating 为空）的本地视频
                // 不打角标（未知 ≠ 确认 R18），但它们仍按 R18 参与「分级」筛选。
                // 该药丸用红底实色，不消费卡片背景层。
                R18Pill(
                    model.rating,
                    model.showR18,
                    Modifier.align(Alignment.TopEnd).padding(VideoCardSpec.overlayPadding)
                )
                // 左下角发布日期：纯文字，只有一层很淡的黑色柔边保可读；更贴角、与下方文字左对齐
                //   （start 用内容区的内边距，bottom 收到 4dp）。
                CardDateLabel(
                    createdAtMs = model.createdAtMs,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = VideoCardSpec.contentPadding, bottom = 4.dp)
                )
            }

            Column(modifier = Modifier.padding(VideoCardSpec.contentPadding)) {
                // 第 1 行：标题。文本统一走 normalizeCardText（空白折叠 + 全角字母数字转半角）。
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.bodyMedium,
                    minLines = 1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(VideoCardSpec.gapTitleAuthor))

                // 第 2 行：作者。就是"作者名字本身可点"（与在线页统一）——
                //   点它进作者二级页；无作者对象时不是可点组件。整行高度恒定，无作者也占位。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = VideoCardSpec.authorRowMinHeight),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = model.author,
                        style = MaterialTheme.typography.bodySmall,
                        minLines = 1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (onAuthorClick != null) {
                            Modifier.clickable(onClick = onAuthorClick)
                        } else {
                            Modifier
                        }
                    )
                }
                Spacer(Modifier.height(VideoCardSpec.gapAuthorTags))

                // 第 3 行：标签。固定一行、最多 MAX_VISIBLE_TAGS 个，其余折叠成 +N；
                //   无标签也保留同一最小高度，左右卡片等高对齐。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = VideoCardSpec.tagRowMinHeight),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (model.tags.isNotEmpty()) {
                        val shown = model.tags.take(MAX_VISIBLE_TAGS)
                        val remaining = model.tags.size - shown.size
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.Center,
                            maxLines = 1
                        ) {
                            shown.forEach { tag -> TagChip(text = tag, onClick = { onTagClick(tag) }) }
                            if (remaining > 0) {
                                TagChip(text = "+$remaining", onClick = {})
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 本地库的视频卡片：把 [VideoEntity] 映射成 [VideoCardModel] 后交给 [VideoCardContent] 渲染。
 *
 * 与在线卡片的差别只有两处（都是**数据**层面的差异，不是外观）：
 * 1. 缩略图优先用本地抽帧（缺失回退 iwara 封面）；
 * 2. **不显示**「本地」标志（本地页整页都是本地的，标了没意义）。
 */
@Composable
fun VideoCard(
    video: VideoEntity,
    onClick: () -> Unit,
    onTagClick: (String) -> Unit,
    showTags: Boolean = true,
    /** 是否显示右上角 R18 药丸（设置里的独立开关；与在线卡片完全同款）。 */
    showR18Tag: Boolean = true,
    onLongClick: () -> Unit = {},
    /** 点作者名进作者二级页。为 null 时作者行不可点。 */
    onAuthorClick: (() -> Unit)? = null,
    /**
     * 左上角「本地」药丸。本地页默认关（整页都是本地的，标了没意义）；
     * 作者页「视频」栏本地/在线穿插混排时必须开（否则分不清哪个是本地）。
     */
    showLocalBadge: Boolean = false,
    modifier: Modifier = Modifier
) {
    VideoCardContent(
        model = VideoCardModel(
            thumbnail = video.localThumbnailPath ?: video.thumbnailUrl,
            title = normalizeCardText(video.title),
            author = normalizeCardText(video.author),
            tags = if (showTags) video.tagsCsv.splitTags() else emptyList(),
            rating = video.rating,
            showR18 = showR18Tag,
            showLocalBadge = showLocalBadge,
            // 封面左下角日期胶囊（本地条目用扫描/匹配写入的 createdAt）。
            createdAtMs = video.createdAt
        ),
        onClick = onClick,
        onTagClick = onTagClick,
        onLongClick = onLongClick,
        onAuthorClick = onAuthorClick,
        modifier = modifier
    )
}

/** 本地库的 tagsCsv（逗号分隔）→ 归一化后的 tag 列表。 */
fun String?.splitTags(): List<String> =
    this?.split(",")
        ?.map { normalizeCardText(it) }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()

@Composable
fun TagChip(text: String, onClick: () -> Unit) {
    // 取消 Material3 默认的 48dp 最小点击区域，避免 tag 之间过空。
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            // "比卡片底色稍深一档"的派生色（本地卡 / 在线卡 / 播放页详情三处共用本实现）。
            color = LocalTagContainer.current,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Text(
                text = text,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * 卡片**左上角**的「本地」药丸 —— 表示"这支片子在本地已有一份"。
 *
 * 与右上角的 R18 药丸同属一套视觉语言（同字号、同内边距、同形状），
 * 半透明底是为了不糊住封面。
 */
@Composable
fun LocalPill(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Text(
            text = tr("本地", "Local"),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}
