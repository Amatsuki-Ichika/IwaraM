package io.github.amatsukiichika.iwaramanager.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaramanager.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaramanager.data.ListMode
import io.github.amatsukiichika.iwaramanager.ui.VideoViewModel
import io.github.amatsukiichika.iwaramanager.ui.components.AppBottomSheet
import io.github.amatsukiichika.iwaramanager.ui.components.SearchPill
import io.github.amatsukiichika.iwaramanager.ui.components.TagSuggestionRow
import kotlinx.coroutines.delay

/** 联想候选区预留的固定高度：恒定占位，候选多少都不改布局。 */
private val SUGGESTION_AREA_HEIGHT = 40.dp

/**
 * 全局筛选器面板，由顶栏刷新键旁边的入口打开。
 *
 * 布局按自然顺序排列：从上到下依次是 标题栏 / 开关 / 标题规则 / 标签规则 / 已选 / 候选。
 *
 * 键盘弹起时靠抽屉本身保证体验（见 [AppBottomSheet]）：抽屉外框与把手不动、只有内容区被压缩；
 * 内容超出时整页可上滑，聚焦的输入框会被 Compose 自动滚进可视区。
 *
 * 候选区为固定高度占位 + 无过渡动画，打字/删字都不会引起跳动。
 */
@Composable
fun GlobalFilterSheet(
    vm: VideoViewModel,
    cvm: IwaraClientViewModel,
    onClose: () -> Unit
) {
    val state by GlobalFilterHolder.state.collectAsStateWithLifecycle()
    val localTags by vm.localTagNames.collectAsStateWithLifecycle()
    val onlineTags by cvm.filterTagSuggestions.collectAsStateWithLifecycle()

    val tagDraft = state.tag.draft.trim()
    // 联想：输入非空就防抖拉在线候选（与主页搜索栏同款节奏）；本地候选不发请求。
    LaunchedEffect(tagDraft) {
        delay(350)
        cvm.loadFilterTagSuggestions(tagDraft)
    }
    val candidates = remember(tagDraft, localTags, onlineTags, state.tag.terms) {
        if (tagDraft.isEmpty()) {
            emptyList()
        } else {
            (localTags + onlineTags)
                .filter { it.contains(tagDraft, ignoreCase = true) }
                // 已经打全了、或已经在已选里的，不再重复提示。
                .filterNot { it.equals(tagDraft, ignoreCase = true) }
                .filterNot { c -> state.tag.terms.any { it.text.equals(c, ignoreCase = true) } }
                .distinctBy { it.lowercase() }
                .take(12)
        }
    }

    /**
     * 焦点在哪个输入框，就把哪一块滚进可视区：
     * 点【标题】输入框 → 对齐到顶（标题整块可见）；
     * 点【标签】输入框 → 对齐到底（标签输入框 + 已选 + 候选一起可见）。
     * 用 [alignBottom] 记住现在该对齐哪一头，键盘晚一点弹出来（可滚范围变大）也会再对齐一次。
     */
    val scroll = rememberScrollState()
    var alignBottom by remember { mutableStateOf(false) }
    LaunchedEffect(scroll.maxValue, alignBottom) {
        scroll.animateScrollTo(if (alignBottom) scroll.maxValue else 0)
    }

    AppBottomSheet(onDismiss = onClose, scrollState = scroll) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // 标题行 —— 标题靠左；「总开关 + 垃圾桶」整组靠右。
            //   一个抽屉里只留一个开关，不会出现两个。
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "高级筛选",
                    style = MaterialTheme.typography.titleMedium,
                    // weight(1f) 把后面那一组整体顶到右边（= "标题靠左、其余整组靠右"）。
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "总开关",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                Switch(
                    checked = state.enabled,
                    onCheckedChange = { GlobalFilterHolder.setEnabled(it) }
                )
                Spacer(Modifier.size(2.dp))
                // 垃圾桶图标：一键清掉全部筛选条件。
                IconButton(onClick = { GlobalFilterHolder.reset() }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "清空筛选条件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )

            // ---- 标题行（一律模糊：含有即命中；黑=隐藏含的 / 白=只留含的）----
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("标题", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                ListToggle(state.name.list) { GlobalFilterHolder.setNameList(it) }
            }
            Spacer(Modifier.size(6.dp))
            SearchPill(
                value = state.name.pattern,
                onValueChange = { GlobalFilterHolder.setNamePattern(it) },
                placeholder = "输入标题关键词…",
                modifier = Modifier.fillMaxWidth().height(44.dp),
                onClear = { GlobalFilterHolder.setNamePattern("") },
                // 点这里 → 对齐到顶（标题整块可见）
                onFocused = { alignBottom = false }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )

            // ---- 标签行（多选；点选=精确 / 手打=模糊）----
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("标签", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                ListToggle(state.tag.list) { GlobalFilterHolder.setTagList(it) }
            }
            Spacer(Modifier.size(6.dp))
            SearchPill(
                value = state.tag.draft,
                onValueChange = { GlobalFilterHolder.setTagDraft(it) },
                placeholder = "输入标签…",
                modifier = Modifier.fillMaxWidth().height(44.dp),
                // ✕ = 清掉这一行的全部条件（草稿 + 已选 chip）
                onClear = { GlobalFilterHolder.clearTagTerms() },
                // 回车 = 把它确认为一条条件；手打的按"模糊"算
                onSearch = { GlobalFilterHolder.addTagTerm(state.tag.draft, exact = false) },
                // 点这里 → 对齐到底（标签输入框 + 已选 + 候选一起可见）
                onFocused = { alignBottom = true }
            )
            // 已选标签：公用组件逐一显示（分体，每枚 ✕ 单独取消）
            SelectedTagChips(
                tags = state.tag.terms.map { it.text },
                onRemove = { GlobalFilterHolder.removeTagTerm(it) },
                modifier = Modifier.padding(top = 6.dp)
            )
            // 候选区：高度恒定（永远占位）+ 无过渡动画，打字/删字都不跳
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(SUGGESTION_AREA_HEIGHT)
                    .padding(top = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                TagSuggestionRow(
                    suggestions = candidates,
                    onPick = { GlobalFilterHolder.addTagTerm(it, exact = true) }
                )
            }
        }
    }
}

/** 黑名单 / 白名单二选一（全项目 chip 同款配色）。 */
@Composable
private fun ListToggle(current: ListMode, onSelect: (ListMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("黑名单" to ListMode.BLACK, "白名单" to ListMode.WHITE).forEach { (label, mode) ->
            FilterChip(
                selected = current == mode,
                onClick = { onSelect(mode) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                colors = accentChipColors(),
                shape = RoundedCornerShape(50)
            )
        }
    }
}
