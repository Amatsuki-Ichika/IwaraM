package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * R18 内容角标：固定红底实色，不随材质档与不透明度变化。
 * 红底取 M3 errorContainer 深红 (#93000A) 的提亮版 #A03533：10sp 小字需达 WCAG AA 4.5:1，
 * 原中间色 #C25C5A 配淡粉文字只有 3.27:1。
 * 圆角、字号、内边距固定；仅在 [rating] 显式等于 `ecchi` 时显示（未知分级不打标）。
 */
@Composable
fun R18Pill(rating: String?, enabled: Boolean, modifier: Modifier = Modifier) {
    if (!enabled || !rating.equals("ecchi", ignoreCase = true)) return
    Surface(
        color = R18_RED,
        contentColor = R18_TEXT,
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Text(
            text = "R18",
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

/** R18 红底：M3 errorContainer 深红 #93000A 的提亮版，与 [R18_TEXT] 对比 5.33:1（AA 小字通过）。 */
internal val R18_RED = Color(0xFFA03533)

/** R18 文字色：M3 onErrorContainer 基线浅粉 #FFDAD6，作为红底上的固定文字色。 */
internal val R18_TEXT = Color(0xFFFFDAD6)

/**
 * 封面左下角的发布日期：纯文字、无胶囊底，外加一层很淡的黑色柔边
 * （`Shadow(blurRadius=16f)`），在亮色封面上也能看清。
 * `start` 使用卡片内容区的内边距，与下方文字左对齐。
 */
@Composable
fun CardDateLabel(createdAtMs: Long?, modifier: Modifier = Modifier) {
    if (createdAtMs == null || createdAtMs <= 0L) return
    val style = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        lineHeight = 13.sp,
        shadow = Shadow(color = Color.Black.copy(alpha = 0.62f), offset = Offset(0f, 0f), blurRadius = 16f)
    )
    Text(
        text = io.github.amatsukiichika.iwaram.ui.cardDateText(createdAtMs),
        style = style,
        maxLines = 1,
        color = Color.White.copy(alpha = 0.95f),
        modifier = modifier
    )
}
