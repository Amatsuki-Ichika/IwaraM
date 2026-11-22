package io.github.amatsukiichika.iwaram.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop

/**
 * 全 app 唯一的「玻璃圆钮」：44dp 圆形、底色走半透明材质模块（PILL 角色），
 * 关档/透明档是半透明实底，磨砂档走 AndroidLiquidGlass 的真模糊 + 边缘折射。
 *
 * 抽出来的原因：回顶按钮与播放页的返回 / 更多按钮必须**同一份材质**。
 * 以前这类圆钮散在各自页面里，各写一套 fill / 模糊 / 图标色 —— 材质一改（磨砂档、
 * 不透明度滑条）就有一处漏改。现在：[ScrollToTopButton] 与 [io.github.amatsukiichika.iwaram.ui.PlayerTopBar]
 * 都只是这个组件的排列差异，视觉参数只有这一处。
 *
 * **自绘**（不用 M3 Surface / IconButton）：M3 的 shadow + 最小触控机制与半透明底色组合
 * 会在圆角处画出"八边形边框"；这里与 [GlassPill] 同一套画法（底色 + 可选玻璃效果，无 M3 机制），
 * 涟漪关闭 —— 玻璃上"扩散噪点"与底栏同判。
 *
 * @param backdrop 视图级液态玻璃背景层；null = 半透明路径（二级页未接，播放页压在视频上时由
 *   实时抓帧的 [PlayerLiveBackdrop] 提供）。
 * @param darkSurface 覆盖「深/浅」判定：null = 跟随主题（回顶按钮、播放页圆钮都在用它）；
 *   true = 恒用深色雾面 + 白图标（留给压在亮画面上的少数位置，目前没有调用方）。
 * @param tint 图标色覆盖（默认按深/浅自动：浅色 onSurface 0.9 / 深色白 0.92）。
 * @param barRecipe true = 与底栏同一套配方（BAR 角色 + 只模糊、无折射）：播放页顶栏的两枚
 *   按钮用它，材质与底栏/全局不透明度实时联动；默认 false 走小圆钮配方（模糊 + 边缘折射）。
 */
@Composable
fun GlassRoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    darkSurface: Boolean? = null,
    tint: Color? = null,
    barRecipe: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val settings = LocalGlassSettings.current
    val glassActive = backdrop != null &&
        settings.mode.isLiquid &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val isLight = when (darkSurface) {
        true -> false
        false -> true
        null -> cs.surface.luminance() > 0.5f
    }
    val material = glassMaterialFor(
        role = if (barRecipe) GlassRole.BAR else GlassRole.PILL,
        settings = settings,
        surface = cs.surface,
        dark = !isLight,
        backdropActive = glassActive
    )
    val iconTint = tint ?: if (isLight) cs.onSurface.copy(alpha = 0.9f)
    else Color.White.copy(alpha = 0.92f)
    val shape = CircleShape
    val circle = Modifier
        .size(size)
        .then(
            if (glassActive && backdrop != null) {
                // highlight=null（库默认边缘光 = 灰线，同底栏）；shadow=null（44dp 小圆不挂
                // 24dp 大投影）；效果配方收敛在 GlassBackdrop 模块（同一份小圆钮配方）。
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    highlight = null,
                    shadow = null,
                    // 播放页顶栏 = 底栏同款配方（只模糊，半径同样取自 GlassMaterial）；
                    // 其余圆钮仍是小半径模糊 + 轻折射。
                    effects = {
                        if (barRecipe) glassBarEffects(material.blurRadius.toPx())
                        else glassPillEffects()
                    },
                    onDrawSurface = { drawRect(color = material.fill) }
                )
            } else {
                Modifier.clip(shape).background(material.fill)
            }
        )
    Box(
        modifier = modifier
            .then(circle)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = contentDescription
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(iconSize)
        )
    }
}
