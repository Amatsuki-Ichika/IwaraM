package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.ui.components.PillSegment
import io.github.amatsukiichika.iwaram.ui.components.PillSelector

/**
 * 分段切换器（主页滑块同款机制，药丸形滑块）：options 顺序 = 从左到右，
 * 主色 16% 滑块以 animateDpAsState(220ms) 滑到选中段背后，各段均分撑满宽度。
 * 实现统一委托给 [PillSelector]（全仓库唯一实现），本函数只保留原公共签名。
 */
@Composable
fun PillSegmentSelector(
    options: List<String>,
    current: Int,
    /**
     * 左右内缩。默认 12dp（二级页直接放在 Column 里时的观感）。
     * 放进 **LazyGrid 通栏 item** 时必须传 0.dp —— 网格自己有
     *   `contentPadding = 12.dp`，再叠 12dp 会变成 24dp 双倍内缩（作者页分段栏入滚）。
     * 注意：必须排在 [onSelect] **前面**，否则 `PillSegmentSelector(a, b) { ... }`
     * 这种尾随 lambda 写法会把 lambda 绑到本参数上。
     */
    horizontalPadding: Dp = 12.dp,
    onSelect: (Int) -> Unit
) {
    PillSelector(
        segments = options.mapIndexed { i, label -> PillSegment(i, label) },
        selected = current,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 4.dp),
        evenWeight = true
    )
}

/* ---------- 排序 / 分级选择器 ---------- */

data class SortOption(val value: String, val label: String)

val VIDEO_SORTS = listOf(
    SortOption("trending", "趋势"),
    SortOption("date", "最新"),
    SortOption("popularity", "热门"),
    SortOption("views", "最多播放"),
    SortOption("likes", "最多点赞")
)

val RATING_OPTIONS = listOf(
    SortOption("all", "全部"),
    SortOption("general", "一般"),
    SortOption("ecchi", "R18")
)

/** 与管理器滑块同源的 chip 配色：选中 = 主色 16% 浅底 + 主色文字/图标，全局统一强调色。 */
@Composable
fun accentChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
    selectedLabelColor = MaterialTheme.colorScheme.primary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.primary
)

/** InputChip 版本（管理器与在线页的「过滤」标签用）。 */
@Composable
fun accentInputChipColors() = InputChipDefaults.inputChipColors(
    containerColor = Color.Transparent,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
    selectedLabelColor = MaterialTheme.colorScheme.primary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.primary
)

/**
 * 已选标签筛选行 —— 本地页与在线 Iwara 页**共用这一份**。
 *
 * 逐个 chip 入场（淡入 + 自上落下 + 轻微过冲），且只有**新增的那一枚**自己入场，已有 chip
 * 纹丝不动、行高不补间（key(tag) 保证首组合只播一次）。
 * 用 `AnimatedContent(targetState = 整个列表)` 会让已存在的 chip 也重播一遍（"点第二枚时
 * 第一枚先缩回去再一起出来"），所以改为每枚 chip 各自入场。
 */
@Composable
fun TagFilterRow(
    tags: List<String>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    labelOf: (String) -> String = { "#$it" }
) {
    // **整行的高度做补间**，形成"tag 出现 → 下面的视频向下移一小段"的动画：行高从 0 长到 34dp
    //   的过程中，悬浮顶栏的高度逐帧变大 → 内容区的 contentPadding(top) 逐帧跟着变大 →
    //   下面的内容**平滑下移**（而不是"啪"地闪一下）。参数与全仓其它"行显隐"同一组
    //   （220 进 / 240 长 / 180 出 / 200 收）。删掉最后一枚时也能把收起动画播完
    //   （`if (tags.isEmpty()) return` 的写法会硬切）。
    AnimatedVisibility(
        visible = tags.isNotEmpty(),
        enter = fadeIn(tween(220)) + expandVertically(tween(240)),
        exit = fadeOut(tween(180)) + shrinkVertically(tween(200))
    ) {
        Row(
            // 调用方的 padding 留在**行内**：否则行收起到 0 时仍会残留那几 dp，下方内容位置会偏低。
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tags.forEach { tag ->
                key(tag) {
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { shown = true }
                    AnimatedVisibility(
                        visible = shown,
                        // **一条曲线**：淡入 + 自上落下（带轻微过冲）。
                        //   不用"淡入 160 + 下落 180 + 缩放 180"三条各跑各的曲线（会显得效果过多）。
                        //   过冲靠 spring(dampingRatio < 1) 给：落下时略冲过头再弹回一点点，有"落到位"的手感。
                        //   横滑方向不是重点：**层级**由调用方的 zIndex 保证（搜索行压在它之上），
                        //   所以药丸表现为"从搜索栏底下钻出来"。
                        enter = fadeIn(tween(200)) +
                            slideInVertically(
                                animationSpec = spring(dampingRatio = 0.70f, stiffness = 600f)
                            ) { -it },
                        // 出场镜像：淡出 + 向上收回去（同样是单条曲线）。
                        exit = fadeOut(tween(160)) +
                            slideOutVertically(tween(160)) { -it / 2 }
                    ) {
                        InputChip(
                            selected = true,
                            onClick = { onRemove(tag) },
                            label = { Text(labelOf(tag), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "取消该标签",
                                    modifier = Modifier.heightIn(max = 18.dp)
                                )
                            },
                            shape = RoundedCornerShape(50),
                            colors = accentInputChipColors()
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一排「已选标签」chip，每枚自带关闭按钮 —— **全仓库唯一实现**。
 *
 * 多个标签**分体**显示（一枚标签一枚 chip），不拼成一个"MMD + 3DCG"，并且模块化共用。
 * 三处共用：全局筛选面板 / 主页搜索栏的过滤行 / 本地库的过滤行。
 *
 * @param labelOf chip 上的文字（默认 `#标签`；本地库是标签名，直接原样显示）
 */
@Composable
fun SelectedTagChips(
    tags: List<String>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    labelOf: (String) -> String = { "#$it" }
) {
    if (tags.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tags.forEach { tag ->
            InputChip(
                selected = true,
                onClick = { onRemove(tag) },
                label = { Text(labelOf(tag), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "取消该标签",
                        modifier = Modifier.heightIn(max = 18.dp)
                    )
                },
                shape = RoundedCornerShape(50),
                colors = accentInputChipColors()
            )
        }
    }
}

@Composable
fun SelectorChip(
    current: String,
    options: List<SortOption>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = (options.firstOrNull { it.value == current }?.label ?: current)
    Box {
        // 与管理器页排序按钮同款：FilterChip + 下拉勾选。
        FilterChip(
            selected = true,
            onClick = { expanded = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            colors = accentChipColors(),
            shape = RoundedCornerShape(50)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label + if (opt.value == current) " ✓" else "") },
                    onClick = {
                        expanded = false
                        onSelect(opt.value)
                    }
                )
            }
        }
    }
}
