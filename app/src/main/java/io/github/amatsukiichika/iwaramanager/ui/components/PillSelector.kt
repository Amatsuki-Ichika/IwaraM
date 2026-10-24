package io.github.amatsukiichika.iwaramanager.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 单个分段：[key] 是唯一标识（Int 下标 / 枚举均可），[label] 仅作默认文本内容。 */
data class PillSegment<T>(val key: T, val label: String)

/** 分段在某帧的位置与尺寸（dp）——全仓库仅此一份。 */
private data class PillSegmentBounds(val x: Dp, val y: Dp, val w: Dp, val h: Dp)

/**
 * 分段药丸滑块的**唯一**实现。内部机制：onGloballyPositioned 记录每段 bounds +
 * animateDpAsState(tween(220)) + 主色 16% 滑块滑到选中段背后；各段点击即回调 [onSelect]。
 *
 * 各调用点的差异都由下面的参数表达，调用处须原样传各自现值，**不得**在本组件外再手写滑块：
 * - [cornerRadius]：50.dp（药丸形）或 16.dp（圆角矩形）；
 * - [evenWeight]：true = 各段 weight(1f) 均分撑满；false = 按 [rowArrangement] 排布、内容自适应宽；
 * - [rowModifier]/[rowArrangement]/[rowVerticalAlignment]：内层 Row（SpaceEvenly / spacedBy 等）；
 * - [segmentMinWidth]/[segmentPadding]：段的尺寸约束；
 * - [segmentContent]：段内容，默认 labelLarge 文本（选中 primary / 未选中 onSurfaceVariant）。
 */
@Composable
fun <T> PillSelector(
    segments: List<PillSegment<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier.fillMaxWidth(),
    rowArrangement: Arrangement.Horizontal = Arrangement.Start,
    rowVerticalAlignment: Alignment.Vertical = Alignment.Top,
    cornerRadius: Dp = 50.dp,
    evenWeight: Boolean = false,
    segmentMinWidth: Dp? = null,
    segmentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    segmentContent: @Composable (PillSegment<T>, Boolean) -> Unit = { seg, isSelected ->
        Text(
            seg.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
) {
    val density = LocalDensity.current
    val bounds = remember { mutableStateMapOf<T, PillSegmentBounds>() }
    val target = bounds[selected]

    // 滑块**首次落位不动画**。
    // bounds 来自 onGloballyPositioned（布局之后才有）⇒ 首帧 map 必空、目标必为 null，
    // 四个动画值从 0dp 起步；第二帧量到真实 bounds 后若照常 tween(220)，滑块就会
    // "从 0 放大到正确位置"。修法：第一次拿到目标位时用 snap 直接落位，之后的切换/吸附才播 tween。
    var pillPlaced by remember { mutableStateOf(false) }
    SideEffect { if (target != null) pillPlaced = true }
    val pillSpec: FiniteAnimationSpec<Dp> = if (pillPlaced) tween(220) else snap()

    val animX by animateDpAsState(target?.x ?: 0.dp, pillSpec, label = "pillX")
    val animY by animateDpAsState(target?.y ?: 0.dp, pillSpec, label = "pillY")
    val animW by animateDpAsState(target?.w ?: 0.dp, pillSpec, label = "pillW")
    val animH by animateDpAsState(target?.h ?: 0.dp, pillSpec, label = "pillH")

    Box(modifier) {
        if (target != null) {
            Box(
                modifier = Modifier
                    .offset(x = animX, y = animY)
                    .width(animW)
                    .height(animH)
                    .clip(RoundedCornerShape(cornerRadius))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            )
        }
        Row(
            modifier = rowModifier,
            horizontalArrangement = rowArrangement,
            verticalAlignment = rowVerticalAlignment
        ) {
            segments.forEach { seg ->
                val isSelected = seg.key == selected
                // 修饰符链序：weight（可选）→ minWidth（可选）→ 测量 → clip → clickable → padding。
                var segModifier: Modifier = if (evenWeight) Modifier.weight(1f) else Modifier
                if (segmentMinWidth != null) {
                    segModifier = segModifier.defaultMinSize(minWidth = segmentMinWidth)
                }
                segModifier = segModifier
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInParent()
                        bounds[seg.key] = PillSegmentBounds(
                            (pos.x / density.density).dp,
                            (pos.y / density.density).dp,
                            (coords.size.width / density.density).dp,
                            (coords.size.height / density.density).dp
                        )
                    }
                    .clip(RoundedCornerShape(cornerRadius))
                    .clickable { onSelect(seg.key) }
                    .padding(segmentPadding)
                Box(segModifier, contentAlignment = Alignment.Center) {
                    segmentContent(seg, isSelected)
                }
            }
        }
    }
}
