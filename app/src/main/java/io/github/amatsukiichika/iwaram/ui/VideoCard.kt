package io.github.amatsukiichika.iwaram.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
 * 拖动滚动条期间的卡片降级开关。
 *
 * 拖动时列表每帧按目标位置落位，目标处那一屏卡片必须在**当前帧内**组合/测量完成；
 * 封面图请求与解码、标签行 [TagRow] 的多次 subcompose 正是这一屏的主要成本，
 * 上千条的大库快拖时叠加成能感觉到的微卡顿。拖动中只画底色骨架 + 标题（高度与真实
 * 卡片逐像素一致，排布不跳），松手立刻切回真实卡片，把拖动帧留轻。
 */
internal val LocalScrubbingCards = staticCompositionLocalOf { false }

/**
 * 视频卡片的**唯一渲染实现**（本地 / 在线共用）。
 *
 * 三行恒渲染（标题 / 作者 / 标签）—— 没有作者名、没有 tag 时也保留最小高度，
 * 保证同一排卡片严格等高（这是"整齐统一"的硬保证）。
 *
 * [LocalScrubbingCards] 为 true 时只画骨架：封面图、角标、作者名与标签行全部跳过，
 * 但封面框、标题行、作者行与标签行的占位高度都保留 ⇒ 卡片高度、网格排布与真实卡片完全一致。
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val scrubbing = LocalScrubbingCards.current
    Card(modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                // 拖动滚动条期间只留底色：不建 AsyncImage（省掉封面请求/解码）、不画角标。
                if (!scrubbing && model.thumbnail != null) {
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
                if (!scrubbing && model.showLocalBadge) {
                    LocalPill(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(VideoCardSpec.overlayPadding)
                    )
                }
                // 右上角：R18 药丸。只认显式 ecchi —— 未匹配到 iwara（rating 为空）的本地视频
                // 不打角标（未知 ≠ 确认 R18），但它们仍按 R18 参与「分级」筛选。
                // 该药丸用红底实色，不消费卡片背景层。
                if (!scrubbing) {
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
                    // 拖动滚动条期间跳过作者名（整行占位高度不变）。
                    if (!scrubbing) {
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
                }
                Spacer(Modifier.height(VideoCardSpec.gapAuthorTags))

                // 第 3 行：标签。永远是**一行**：按卡片实际可用宽度量出每枚药丸的宽度，
                //   放不下的收进 "+N"；最后一枚自己太长就用省略号截断（"…" 不算一个标签，
                //   "+N" 只数它后面没显示出来的那些）。规则见 [layoutTagRow]（纯逻辑，带单测）。
                //   无标签也保留同一最小高度，左右卡片等高对齐。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = VideoCardSpec.tagRowMinHeight),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // 拖动滚动条期间跳过标签行（SubcomposeLayout 的多次 subcompose 是拖动帧的主要成本之一）。
                    if (!scrubbing && model.tags.isNotEmpty()) {
                        TagRow(tags = model.tags, onTagClick = onTagClick)
                    }
                }
            }
        }
    }
}

/** 标签药丸之间的水平间距。 */
private val TagGap = 4.dp

/** 单张卡片最多真实组合并测量多少枚标签；再往后的标签一定放不下，直接计入 "+N"。 */
private const val TAG_MEASURE_LIMIT = 12

/** "+N" 药丸宽度预留的安全余量（px）：别把最后一点空间用到亚像素边界。 */
private const val TAG_PLUS_SAFETY_PX = 1

/**
 * 标签行：组合出**真实药丸**逐个测量，再交给 [layoutTagRow] 决定"整枚显示哪几枚、
 * 最后一枚是否截断、后面还有几枚收进 +N"，最后单行摆放。
 *
 * 为什么用 [SubcomposeLayout] 而不是 TextMeasurer 估算：估算的宽度与真实排版有 1-2px 偏差，
 * 累积后最后几枚会被 Row 按剩余宽度裁掉，出现"只有省略号"的药丸（用户截图里的第三个 …）。
 * 这里直接量真实药丸宽度，并且**复用**这些测量结果去摆放（不再二次排版），
 * 既精确又不比原来多组合几次 —— 顺序摆放到"再放一枚必然放不下"就停止组合后面的标签。
 */
@Composable
private fun TagRow(tags: List<String>, onTagClick: (String) -> Unit) {
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        val bounded = constraints.hasBoundedWidth
        val availPx = if (bounded) constraints.maxWidth else Int.MAX_VALUE / 4
        val gapPx = TagGap.roundToPx()
        val maxHeight = Constraints.Infinity

        // "+N" 药丸按隐藏数量分别组合测量（N 的位数影响宽度）；缓存避免重复组合。
        val plusPlaceables = HashMap<Int, Placeable>()
        fun plusPlaceable(hidden: Int): Placeable = plusPlaceables.getOrPut(hidden) {
            subcompose("plus:$hidden") { TagChip(text = "+$hidden", onClick = {}) }
                .map { it.measure(Constraints(maxWidth = availPx, maxHeight = maxHeight)) }
                .first()
        }

        // 按显示顺序组合并测量真实药丸；一旦"再放一枚必然放不下"就停 —— 后面的标签不用组合。
        val widths = ArrayList<Int>()
        val shown = ArrayList<Placeable>()
        val limit = minOf(tags.size, TAG_MEASURE_LIMIT)
        for (index in 0 until limit) {
            if (index > 0) {
                val used = widths.sum() + gapPx * (index - 1)
                // 已用宽度 + 一个间距 + 最窄的 "+1" + 余量都超了 ⇒ 后面的全都显示不了。
                if (used + gapPx + plusPlaceable(1).width + TAG_PLUS_SAFETY_PX > availPx) break
            }
            val tag = tags[index]
            val placeable = subcompose("tag:$index") { TagChip(text = tag, onClick = { onTagClick(tag) }) }
                .map { it.measure(Constraints(maxWidth = availPx, maxHeight = maxHeight)) }
                .first()
            widths += placeable.width
            shown += placeable
        }

        // 截断药丸至少要能显示"省略号 + 一个字"，否则宁可把这枚收进 +N ——
        //   绝不允许出现"只有省略号"的药丸（用户截图里的第三枚 … 就是这么来的）。
        val truncatedMinPx = subcompose("trunc-min") { TagChip(text = "…0", onClick = {}) }
            .map { it.measure(Constraints(maxWidth = availPx, maxHeight = maxHeight)) }
            .first().width

        val layout = layoutTagRow(
            chipWidthsPx = widths,
            totalTagCount = tags.size,
            gapPx = gapPx,
            truncatedMinPx = truncatedMinPx,
            plusWidthPx = { hidden -> plusPlaceable(hidden).width },
            availablePx = availPx
        )

        // 要真正摆放的药丸：整枚的复用上面的测量结果；截断那枚按允许宽度重新组合；
        //   "+N" 永远整枚显示（它是"还有多少"的唯一出口，不能被挤成省略号）。
        val placed = ArrayList<Placeable>()
        for (i in 0 until layout.visibleCount) {
            if (layout.ellipsizeLast && i == layout.visibleCount - 1) {
                val tag = tags[i]
                val maxPx = layout.lastChipMaxPx
                placed += subcompose("trunc") {
                    TagChip(text = tag, onClick = { onTagClick(tag) }, maxWidth = maxPx.toDp())
                }.map { it.measure(Constraints(maxWidth = maxPx, maxHeight = maxHeight)) }.first()
            } else {
                placed += shown[i]
            }
        }
        if (layout.hiddenCount > 0) placed += plusPlaceable(layout.hiddenCount)

        val rowHeight = placed.maxOfOrNull { it.height } ?: 0
        val usedWidth = placed.sumOf { it.width } + gapPx * (placed.size - 1).coerceAtLeast(0)
        val rowWidth = if (bounded) constraints.maxWidth else usedWidth
        layout(rowWidth, rowHeight) {
            var x = 0
            for (placeable in placed) {
                val y = (rowHeight - placeable.height) / 2
                placeable.place(x, y)
                x += placeable.width + gapPx
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

/**
 * 标签药丸。
 *
 * @param maxWidth 非 null = 这枚药丸最多这么宽（标签行收尾被压缩时用，文字自动省略号）。
 */
@Composable
fun TagChip(text: String, onClick: () -> Unit, maxWidth: Dp? = null) {
    // 取消 Material3 默认的 48dp 最小点击区域，避免 tag 之间过空。
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            // "比卡片底色稍深一档"的派生色（本地卡 / 在线卡 / 播放页详情三处共用本实现）。
            color = LocalTagContainer.current,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier
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
