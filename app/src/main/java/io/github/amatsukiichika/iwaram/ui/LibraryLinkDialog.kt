package io.github.amatsukiichika.iwaram.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.browse.CompactSearchSlot
import io.github.amatsukiichika.iwaram.ui.browse.RATING_OPTIONS
import io.github.amatsukiichika.iwaram.ui.browse.SEARCH_ROW_HEIGHT
import io.github.amatsukiichika.iwaram.ui.browse.ScrollToTopButton
import io.github.amatsukiichika.iwaram.ui.browse.SelectorChip
import io.github.amatsukiichika.iwaram.ui.browse.TagFilterRow
import io.github.amatsukiichika.iwaram.ui.components.TagSuggestionRow
import io.github.amatsukiichika.iwaram.ui.browse.accentChipColors
import io.github.amatsukiichika.iwaram.ui.browse.accentInputChipColors
import io.github.amatsukiichika.iwaram.ui.browse.GRID_CARD_GAP
import io.github.amatsukiichika.iwaram.ui.browse.animateScrollToTop
import io.github.amatsukiichika.iwaram.ui.components.PillSegment
import io.github.amatsukiichika.iwaram.ui.components.horizontalSlideSpec
import io.github.amatsukiichika.iwaram.ui.components.PillSelector
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.amatsukiichika.iwaram.ui.components.TOP_BAR_CONTENT_HEIGHT
import io.github.amatsukiichika.iwaram.ui.components.glassViewBackdrop
import io.github.amatsukiichika.iwaram.ui.components.rememberGlassViewBackdrop
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaram.data.VideoEntity
import kotlinx.coroutines.launch

/* ---------- 手动关联 iwara ID 对话框（原 LibraryScreen.kt 尾部区块） ---------- */
@Composable
internal fun ManualLinkDialog(
    video: VideoEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onRemove: () -> Unit
) {
    var input by remember { mutableStateOf(video.iwaraId ?: "") }
    // 两步式：默认显示手动关联表单；点「从库中移除」后切换为移除确认，防误触。
    var confirmingRemove by remember { mutableStateOf(false) }
    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(tr("从库中移除？", "Remove from library?")) },
            text = {
                Column {
                    Text(
                        text = video.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = tr(
                            "仅从本地库移除该条目，不删除视频文件；重新扫描可找回。",
                            "Removes this entry from the local library only; the video file is kept and rescanning restores it."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove()
                    onDismiss()
                }) {
                    Text(tr("确认移除", "Remove"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
            }
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("手动指定 iwara ID", "Set iwara ID manually")) },
        text = {
            Column {
                Text(
                    text = video.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(tr("视频 ID 或链接", "Video ID or link")) },
                    placeholder = { Text(tr("例如 1ywe1sbkqwumpdxz5", "e.g. 1ywe1sbkqwumpdxz5")) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                TextButton(
                    onClick = { confirmingRemove = true },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) { Text(tr("从库中移除…", "Remove from library…"), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val id = sanitizeIwaraId(input)
                if (id.isNotBlank()) onConfirm(id)
            }) { Text(tr("确定", "OK")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        }
    )
}

private fun sanitizeIwaraId(raw: String): String =
    io.github.amatsukiichika.iwaram.data.IwaraIdParser.parse(raw) ?: ""
