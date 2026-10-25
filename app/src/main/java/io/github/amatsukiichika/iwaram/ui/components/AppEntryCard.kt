package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 全仓库**唯一**的"独立条目卡"（「我的」页 MeEntryCard 规格升为公共组件）。
 *
 * 视觉规格：M3 填充 Card 默认（12dp 圆角 + surfaceContainerHighest）+ 行内边距 16×10 +
 * 图标 22dp 主色 + 右箭头 onSurfaceVariant。设置页一、三级全部用它（一设置项一卡，取代大卡+分隔线）。
 *
 * 用法：
 * - **入口行**：传 [onClick]（不传 [trailing] 时自动带右箭头）——「我的」页入口、设置分类入口、诊断入口；
 * - **选择项**：不传 onClick；[trailing] 放开关/值文本，[content] 放标题区下方的附加内容（chips、色块、路径+按钮）；
 * - **ⓘ 长说明**：[info] 非空时标题旁渲染 ⓘ，点击经 [onInfo] 报出「标题, 全文」（页面级 AppDialog 显示）。
 */
@Composable
fun AppEntryCard(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    subtitle: String? = null,
    info: String? = null,
    onInfo: (String, String) -> Unit = { _, _ -> },
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        // 卡内上下 10dp（原 14dp）：设置页反馈"上下留白太多"后整体收紧；左右触控留白不动。
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.size(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.bodyLarge)
                        if (info != null) {
                            Spacer(Modifier.size(4.dp))
                            IconButton(
                                onClick = { onInfo(title, info) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = "$title 说明",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                when {
                    trailing != null -> {
                        Spacer(Modifier.size(12.dp))
                        trailing()
                    }
                    onClick != null -> Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (content != null) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}
