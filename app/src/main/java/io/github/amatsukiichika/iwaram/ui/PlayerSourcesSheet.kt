package io.github.amatsukiichika.iwaram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.qualityLabel

/**
 * 源文件管理面板：选择要移除的源，明确告知会删什么。
 *
 * 删除是**不可逆**的，所以这里刻意做成两步（勾选 → 就地二次确认），并且：
 *  - 「同时删除磁盘文件」默认关闭，只移除库条目；
 *  - 每行都写明档位与文件名，不让用户在盲盒里点确定；
 *  - 选满全部源时红字提示条目会从库里消失。
 */
@Composable
internal fun PlayerSourcesSheet(
    sources: List<VideoEntity>,
    currentUri: String,
    onDismiss: () -> Unit,
    onDelete: (List<VideoEntity>, Boolean) -> Unit
) {
    var selected by remember(sources) { mutableStateOf(emptySet<String>()) }
    var deleteFiles by remember(sources) { mutableStateOf(false) }
    var confirming by remember(sources) { mutableStateOf(false) }
    val chosen = sources.filter { it.localUri in selected }
    val allSelected = sources.isNotEmpty() && chosen.size == sources.size

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            // 面板本身吃掉点击，避免点内容时穿透到遮罩把面板关掉。
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("源文件", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "${sources.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
                Text(
                    "勾选要移除的源。默认只从本地库移除，文件留在你的文件夹里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                Column(
                    Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sources.forEach { source ->
                        val checked = source.localUri in selected
                        val playing = source.localUri == currentUri
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    confirming = false
                                    selected = if (checked) selected - source.localUri else selected + source.localUri
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        source.qualityLabel() ?: "本地",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    if (playing) {
                                        Spacer(Modifier.size(6.dp))
                                        Text(
                                            "当前播放",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    source.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("同时删除磁盘上的文件", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "默认关闭。关闭时文件仍在文件夹里，下次扫描会把它重新导入。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                }

                if (allSelected) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "选了全部源文件：删除后这个视频会从本地库消失。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (confirming) {
                        Text(
                            "确认删除 ${chosen.size} 项？",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { confirming = false }) { Text("取消") }
                        TextButton(onClick = { onDelete(chosen, deleteFiles) }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { confirming = true }, enabled = chosen.isNotEmpty()) {
                            Text("删除选中项${if (chosen.isEmpty()) "" else " (${chosen.size})"}")
                        }
                    }
                }
            }
        }
    }
}
