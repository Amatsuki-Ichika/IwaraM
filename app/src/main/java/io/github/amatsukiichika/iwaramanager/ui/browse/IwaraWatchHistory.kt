package io.github.amatsukiichika.iwaramanager.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.amatsukiichika.iwaramanager.LocalAppRepository
import io.github.amatsukiichika.iwaramanager.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaramanager.data.WatchHistoryEntity
import io.github.amatsukiichika.iwaramanager.data.filterBy
import io.github.amatsukiichika.iwaramanager.data.historyBadge
import io.github.amatsukiichika.iwaramanager.data.historyGroupLabel
import io.github.amatsukiichika.iwaramanager.data.historyTimeText
import io.github.amatsukiichika.iwaramanager.ui.components.AppDialog
import kotlinx.coroutines.launch

/**
 * 观看历史二级页。
 *
 * - 单列列表行：左 16:9 缩略图（时长角标 + 进度条）+ 右标题/作者/时间；点行 = 播 + 续播。
 * - 编辑模式：顶栏 [铅笔][全删垃圾桶]（铅笔在垃圾桶左边）—— 点铅笔进入编辑模式，
 *   每行卡片右侧出现小垃圾桶（单删，直接删）；编辑模式下点行不播放（防误触）；再点铅笔退出
 *   （编辑态按钮高亮）。全删走顶栏垃圾桶（自绘确认弹窗）。
 * - 日期分组头：今天 / 昨天 / M月d日（纯函数 [historyGroupLabel]）。
 * - 全局筛选器参与（标题模糊；tags 传 null = 标签规则豁免）。
 */
@Composable
fun WatchHistoryOverlay(actions: IwaraNavActions, onClose: () -> Unit) {
    val context = LocalContext.current
    val repo = LocalAppRepository.current
    val scope = rememberCoroutineScope()
    val entries by repo.observeWatchHistory().collectAsStateWithLifecycle(initialValue = emptyList())
    // 列表消费端订阅防抖视图。
    val filter by GlobalFilterHolder.listState.collectAsStateWithLifecycle()
    var showClearConfirm by remember { mutableStateOf(false) }
    // 页面级编辑模式 —— 顶栏开关，行右侧出单删垃圾桶
    var editingMode by remember { mutableStateOf(false) }

    val filtered = remember(entries, filter) {
        entries.filterBy(filter) { e -> e.title to null }
    }
    val now = remember(filtered) { System.currentTimeMillis() }
    // groupBy 进 LinkedHashMap，保持 lastWatchAt DESC 的既有顺序 = 分组头按时间倒序出现
    val grouped = remember(filtered, now) {
        filtered.groupBy { historyGroupLabel(it.lastWatchAt, now) }
    }
    // 筛选后没有内容时编辑/全删都没意义（含"全被筛掉"的情况）
    val hasContent = filtered.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        OverlayTopBar(
            title = "观看历史",
            onBack = onClose,
            trailing = {
                if (hasContent) {
                    // 编辑模式开关（铅笔），在垃圾桶左边；编辑态高亮 + 图标变 X
                    IconButton(onClick = { editingMode = !editingMode }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (editingMode) Icons.Filled.Close else Icons.Filled.Edit,
                            contentDescription = if (editingMode) "退出编辑" else "编辑",
                            tint = if (editingMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showClearConfirm = true }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "清空观看历史",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (entries.isEmpty()) "还没有观看记录" else "没有符合筛选的记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (label, items) ->
                    item(key = "header_$label") {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(items, key = { it.videoKey }) { entry ->
                        HistoryRow(
                            entry = entry,
                            editing = editingMode,
                            onClick = {
                                // 编辑模式下点行不播放（防误触）
                                if (!editingMode) actions.playHistory(entry)
                            },
                            onDelete = { scope.launch { repo.deleteWatchHistory(entry.id) } }
                        )
                    }
                }
            }
        }
    }

    // 清空确认：公用自绘弹窗 AppDialog（M3 AlertDialog 在本项目非浮窗 dialog 主题下
    // 出现会"上跳"，一律不用）。
    AppDialog(
        visible = showClearConfirm,
        onDismiss = { showClearConfirm = false },
        title = "清空观看历史？",
        text = "将删除全部 ${entries.size} 条观看记录，此操作不可撤销。",
        confirmText = "清空",
        onConfirm = {
            showClearConfirm = false
            editingMode = false
            scope.launch { repo.clearWatchHistory() }
        },
        dismissText = "取消",
        confirmDestructive = true
    )
}

/**
 * 单列历史行：左缩略图（时长角标 + 进度条）+ 右标题/作者/时间。
 * [editing]=true 时卡片右侧出现小垃圾桶（单删，直接删）；
 * 点行是否播放由调用方按编辑态决定。
 */
@Composable
private fun HistoryRow(
    entry: WatchHistoryEntity,
    editing: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    // 已看比例；看完（≥95%，与 resumeStartMs 同一阈值）自然接近满条
    val fraction = if (entry.durationMs > 0) {
        (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
    } else 0f
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(140.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (entry.thumbnail != null) {
                AsyncImage(
                    // 与网格卡片同款：显式关 crossfade（列表快速滑动掉帧来源）
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(entry.thumbnail)
                        .crossfade(false)
                        .build(),
                    contentDescription = entry.title,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
            }
            // 时长角标（右下角）：看完显示「已看完」，否则「当前 / 总时长」
            Text(
                historyBadge(entry.positionMs, entry.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
            // 底部进度条（没看过不画）
            if (fraction > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                entry.author.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            // 来源小标志 —— 本地视频 = 文件夹、在线视频 = 地球。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (entry.isLocal) Icons.Filled.Folder else Icons.Filled.Public,
                    contentDescription = if (entry.isLocal) "本地视频" else "在线视频",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    historyTimeText(entry.lastWatchAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 编辑模式下卡片右侧的单删垃圾桶
        if (editing) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(36.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        CircleShape
                    )
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除这条记录",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}
