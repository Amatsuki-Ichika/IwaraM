package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop

/**
 * 顶栏内容区高度。
 *
 * Material3 小顶栏的内建内容高度就是 64dp（material3 1.3.0
 * `tokens.TopAppBarSmallTokens` 的 `ContainerHeight` = 64.0d）。在 `TopAppBar` 外面挂
 * `.height(60.dp)` 会与 M3 抢高度：容器被压到 60dp，内部仍按 64dp 摆放标题行，
 * 标题（titleLarge 22sp / 行高 28sp）下半截因此被裁掉。
 *
 * 自绘（高度 = 状态栏 inset + 内容高度，两者互不挤占）后，这个常量就是内容高度本身，
 * 调整高度只改这一个数字，不会再出现"改小了但字被裁"的坑。
 */
val TOP_BAR_CONTENT_HEIGHT = 64.dp

/**
 * 全仓库唯一的顶栏实现（自绘，替掉 M3 `TopAppBar`）。
 *
 * M3 的小顶栏把内容高度写死 64dp，并把 windowInsets 一起算进那个固定高度里；
 * 外部再挂 `.height()` 只会把内容压扁、不会把栏变矮。
 *
 * 结构：`Column { 状态栏让位（inset 自己吃） + 内容行（高度 = [TOP_BAR_CONTENT_HEIGHT]） }`
 * —— inset 与内容高度各算各的，所以内容永远拿得到完整的 64dp。
 *
 * 文案样式与 M3 对齐：标题槽位下发 `titleLarge`（+ `onSurface` 内容色，动作区的图标 tint
 * 走 `LocalContentColor`），使 Crossfade 标题与图标观感与原来一致。
 *
 * @param title 左侧标题（调用方用 Crossfade 切换各 tab 的标题；「本地」那一版自带数量文字）
 * @param actions 右侧动作区（筛选入口 + 刷新按钮）
 */
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 玻璃背景源（主内容那一层）。非 null 且选用液态玻璃时走真模糊，
     * 其余情况走色罩 —— 与底栏 / 悬浮顶栏同一条降级链。
     * null = 本帧不画玻璃（例如主内容被二级页盖住、或非液态玻璃档）。
     */
    backdrop: Backdrop? = null,
    /**
     * 本条是否自己画材质。
     *
     * 本地页 / Iwara 页在内容区里还有一条悬浮条，紧贴本条的底边
     * （见这两个页面里 `GlassTopBar(..., extendTop = …)` 的说明）。为了让两条成为
     * 一整块连续材质，约定由下面那条向上外扩、吃下本条这一格（只有一次 `drawBackdrop`，
     * 模糊与罩色都不会有缝）。此时本条传 `false` ⇒ 只画标题/动作内容，材质交给下面那条。
     *
     * `true`（默认）= 本条自己画材质 —— 没有下邻条的场景（如「我的」tab、二级页）。
     */
    drawOwnMaterial: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val cs = MaterialTheme.colorScheme
    // 整条顶栏（含状态栏那一段）走 [GlassTopBar] —— 与底栏 / 三个悬浮顶栏同角色、同配方、同降级链。
    // 配合 MainActivity 把内容铺满整屏（不再给顶栏留槽位），卡片就会从顶栏底下滚过去。
    // 关掉本条的底边渐隐：本地页 / Iwara 页在本条下面紧贴着自己的悬浮条（同为 BAR 角色、
    // 同色同透明度），若本条再画一段 16dp 渐隐，它会叠在下面那条的实底上，交界处出现更深的色带。
    // 两条紧贴的玻璃直接拼成一块连续材质才是对的（渐隐只在"下方是普通内容"时才需要）。
    // `drawOwnMaterial = false` 时只输出内容（透明底）—— 材质由下面那条悬浮条向上外扩吃下
    // （见函数头），两块因此是一整块材质。这里复用同一个内容 lambda，避免两处走样。
    val content: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxWidth()
                // 状态栏 + 挖孔/横屏的水平 inset：与 M3 顶栏的 windowInsets 口径一致（只吃 Top + Horizontal）。
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
                // 吃住标题行空白里的"点" —— 与 [GlassTopBar] 那条缝隙穿透同源：
                // 滚起来后点"本地"标题右边的空白会打开一支被盖住的视频。子节点（标题/筛选/刷新）
                // 比本条先派发，因此它们的点击不受影响；这里只吞 down/up、放行拖动，滚动照旧。
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TOP_BAR_CONTENT_HEIGHT)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.titleLarge,
                    LocalContentColor provides cs.onSurface
                ) {
                    Box(Modifier.weight(1f)) { title() }
                    actions()
                }
            }
        }
    }

    if (drawOwnMaterial) {
        GlassTopBar(
            backdrop = backdrop,
            modifier = modifier.fillMaxWidth(),
            fadeBottom = false,
            // 本条自己画材质时（「我的」tab），状态栏 + 标题栏这一格做成实心；
            // `fadeBottom = false` ⇒ 不扩材质、不做底边过渡，整条实心。
            solidTop = with(LocalDensity.current) {
                WindowInsets.systemBars.getTop(this).toDp() + TOP_BAR_CONTENT_HEIGHT
            }
        ) {
            content()
        }
    } else {
        // 纯内容覆盖层：不画任何底色 / 材质 ⇒ 从下面那条的材质里"长出来"，交界处没有第二条材质面。
        Box(modifier.fillMaxWidth()) { content() }
    }
}
