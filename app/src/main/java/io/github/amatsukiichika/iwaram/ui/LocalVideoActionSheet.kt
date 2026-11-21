package io.github.amatsukiichika.iwaram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.components.AppBottomSheet

/**
 * 本地库卡片长按后的动作面板（视觉参照 .zcode/plans/ui-mockups.html 图 2）。
 *
 * 只负责"选哪件事"，不在这里做任何删除：确认弹窗与执行都在 [LibraryScreen]，
 * 避免面板自身持有会随关闭丢失的删除状态。
 *
 * 分组：关联/检查类操作在上，会动数据的操作在下（分隔线隔开），最后一组是红色不可恢复项 ——
 * 颜色是这一项唯一的危险提示，不能和其它行共用主色。
 */
@Composable
internal fun LocalVideoActionsSheet(
    video: VideoEntity,
    onManualLink: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onPermanentDelete: () -> Unit,
    /** 「检查文件完整性」；null = 不显示该行（未匹配 iwaraId 时也隐藏）。 */
    onCheckIntegrity: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        ActionSheetEntry(
            icon = Icons.Outlined.Link,
            title = tr("手动指定 iwara ID", "Set iwara ID manually"),
            subtitle = if (video.iwaraId.isNullOrBlank()) {
                tr("当前未关联 · 关联后可显示在线信息", "Not linked · linking enables online information")
            } else {
                tr("当前关联「${video.iwaraId}」· 可重新指定", "Linked to ${video.iwaraId} · you can change it")
            },
            onClick = onManualLink
        )
        // 只有匹配到 iwaraId（能定位到下载记录）才提供完整性检查。
        if (onCheckIntegrity != null && !video.iwaraId.isNullOrBlank()) {
            ActionSheetEntry(
                icon = Icons.Filled.CheckCircle,
                title = tr("检查文件完整性", "Check file integrity"),
                subtitle = tr("核对本地文件是否完整", "Verifies the local file is intact"),
                onClick = { onCheckIntegrity.invoke() }
            )
        }
        // 分组线：上面是"不删东西"的操作，下面是会改库/删文件的。
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
        ActionSheetEntry(
            icon = Icons.Outlined.RemoveCircleOutline,
            title = tr("从库移除…", "Remove from library…"),
            subtitle = tr("只删记录，文件保留，可重新扫描找回", "Record only; the file is kept and a rescan restores it"),
            onClick = onRemoveFromLibrary
        )
        ActionSheetEntry(
            icon = Icons.Filled.DeleteForever,
            title = tr("彻底删除…", "Delete permanently…"),
            subtitle = tr("同时删除本地文件和封面，不可恢复", "Deletes the local file and cover; irreversible"),
            danger = true,
            onClick = onPermanentDelete
        )
        Spacer(Modifier.height(12.dp))
        // 取消行：居中、浅底，和上面的操作行区分开（图 2 的最后一行）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onDismiss)
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = tr("取消", "Cancel"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 一行动作：40dp 圆形图标底 + 标题/说明两行；[danger] 时图标与标题都用错误色。 */
@Composable
private fun ActionSheetEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val iconBackground = if (danger) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconBackground),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
