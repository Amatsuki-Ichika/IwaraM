package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.amatsukiichika.iwaram.i18n.tr

/**
 * 胶囊搜索框 —— 全仓库唯一实现。
 *
 * 主页搜索栏的展开态与筛选面板的两个输入框共用这一份；调样式只改这里，两处不会再走形。
 *
 * 视觉规格（照抄主页既有实现，勿再分叉）：全圆角 + 主题色淡底 + 左侧放大镜 + 14sp 输入 +
 * 右侧 ✕（仅在展开/可清空时出现）。宽度动画不在这里：主页的"点击展开"由调用方
 * （`CompactSearchSlot` 的 BoxWithConstraints + 比例补间）负责，筛选面板则恒为满宽。
 *
 * @param collapsed 收起态（主页那颗放大镜小球）：输入框归零、不可交互，也不显示 ✕。
 */
@Composable
fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
    onClear: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    /** 获得焦点时回调 —— 调用方拿它把正在输入的那一块滚进可视区。 */
    onFocused: (() -> Unit)? = null,
    /** 底色透明度：主页展开态 0.10、收起态 0.06（保持一致观感）。 */
    containerAlpha: Float = 0.10f
) {
    Row(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = containerAlpha)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = if (collapsed) tr("展开搜索", "Expand search") else null,
            tint = if (collapsed) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 13.dp).size(18.dp)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            // 收起态直接禁用：输入框只要还能被点到/拿到焦点，它就会自己聚焦弹键盘。
            enabled = !collapsed,
            modifier = Modifier.then(
                if (collapsed) {
                    // 收起态：同一个输入框，尺寸归 0（不重建、不丢状态/焦点，且完全不可命中）
                    Modifier.size(0.dp)
                } else {
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .then(
                            if (onFocused != null) {
                                Modifier.onFocusChanged { if (it.isFocused) onFocused() }
                            } else {
                                Modifier
                            }
                        )
                }
            ),
            textStyle = TextStyle(
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            ),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            }
        )
        if (!collapsed && onClear != null) {
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = tr("清空", "Clear"),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * tag 联想行 —— 全仓库唯一实现（主页搜索栏与筛选面板共用）。
 *
 * 候选静态摆在那，不做"展开/收起"动效：不使用 `AnimatedContent + expandVertically/shrinkVertically`
 * （那正是"弹出/向上收"的来源），候选出现与消失都是就地替换，标签位置不会动。
 */
@Composable
fun TagSuggestionRow(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 外层调用方给固定高度（筛选面板）或自然高度（主页），这里只负责把 chips 摆出来。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        suggestions.forEach { tag ->
            SuggestionChip(
                onClick = { onPick(tag) },
                label = { Text("#$tag", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
}
