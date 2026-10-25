package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全 app 唯一的「半透明材质」模块。
 *
 * 本模块只负责材质参数（颜色 / 透明度 / 模糊半径 / 方案）；玻璃背景源（录制 + 补页面底色）与效果配方
 * 在同目录的 `GlassBackdrop.kt`（同样是全 app 唯一）。两者合起来才是"全 app 玻璃的唯一实现"，
 * 改材质看这两处即可，不要在调用点就地写颜色 / 模糊 / lens。
 *
 * 分工：
 * - [GlassRole] 区分角色（底栏会真模糊，角标不模糊 —— 一屏十几枚角标，模糊不划算）；
 * - [GlassMode] 是可选的磨砂方案（外观页）；
 * - [glassMaterialFor] 是纯函数：设置 + 深浅色 → 一份材质参数（可单测，见 `GlassMaterialTest`）；
 * - 调用方只做两件事：`LocalGlassSettings.current` 读设置 → `glassMaterialFor(...)` → 按材质画。
 *
 * 磨砂的"高级感"来自四件事，而不是模糊半径本身（参考 Haze / iOS / Telegram）：
 * ① 噪点 —— 没有噪点的模糊像"糊了的水泥"；② 多层色相叠加 —— 单层色罩显得"脏"，两三层才通透；
 * ③ 上缘受光 —— 玻璃的"厚度"感来自边缘那一道高光；④ 深浅色分别调，而非同一套数字反色。
 */
enum class GlassRole {
    /** 底栏：大块，会真模糊。 */
    BAR,

    /** 角标胶囊（R18 / 日期 / 本地）：小块，不模糊（一屏十几枚，模糊不划算），也不画噪点。 */
    PILL
}

/**
 * 材质（外观页可选）：关 / 透明 / 磨砂三档。
 *
 * - [OFF] 关：材质系统整体关闭 —— **实心不透明**底（栏 / 药丸 / 回顶按钮都是实底），零玻璃感；
 * - [TRANSLUCENT] 透明：半透明色罩、无模糊（零录制成本，**默认档**）；
 * - [LIQUID_GLASS] 磨砂：Kyant0/AndroidLiquidGlass —— 真背景模糊 + 边缘折射（lens）+ vibrancy，
 *   由各消费点的 `drawBackdrop` 施加（API 31+；lens 部分 API 33+，之下自动退化成纯模糊）。
 */
enum class GlassMode(val label: String) {
    OFF("关"),
    TRANSLUCENT("透明"),
    LIQUID_GLASS("磨砂");

    /** 是否属于"液态玻璃"档（需要背景录制 + 库的 drawBackdrop 管线）。 */
    val isLiquid: Boolean get() = this == LIQUID_GLASS

    companion object {
        /**
         * 由持久化 Int 还原档位。历史值已在仓库读取处一次性迁移（见 IwaraRepository.getGlassMode
         * 的 glass_mode_v2 逻辑），这里只处理新值域：越界一律回退 [TRANSLUCENT]（默认档）。
         */
        fun of(index: Int): GlassMode = entries.getOrElse(index) { TRANSLUCENT }

        /** 供设置页 `ChoiceChips` 用的 (文案, 值) 列表。 */
        val options: List<Pair<String, String>> = entries.map { it.label to it.ordinal.toString() }
    }
}

/** 可调的"半透明材质"设置（全局一份，见 [LocalGlassSettings]）。 */
@Immutable
data class GlassSettings(
    /** 材质不透明度 0f..1f（外观页滑条；越大越实、越小越透）。 */
    val opacity: Float = DEFAULT_GLASS_OPACITY,
    /** 材质方案（外观页）。默认 = 半透明（历史默认观感，液态玻璃不作默认）。 */
    val mode: GlassMode = GlassMode.TRANSLUCENT
)

/** 不透明度是 0%..100% 的线性值，默认 78%。取 78 而非整数 55，是因为它对应的实际透明度等于旧 55 档的
 *  观感（旧公式浅色 ≈ lerp(0.55, 0.96, 0.55) = 0.7755），不动滑条时观感不变。
 *  落在 SharedPreferences 里的旧值由 IwaraRepository 一次性迁移（键 `glass_opacity_v2`）。 */
const val DEFAULT_GLASS_OPACITY = 0.78f

/**
 * 把"半透明材质设置"下发到整棵树。
 *
 * 用 CompositionLocal 而不是逐层传参：底栏在 Scaffold 的 bottomBar 槽、角标在不同页面深处，
 * 逐层透传会把十几个调用点全污染一遍。默认值 = 历史观感，未提供时行为不变。
 */
val LocalGlassSettings = staticCompositionLocalOf { GlassSettings() }

/** 一份"材质"的全部视觉参数（由 [glassMaterialFor] 从设置解析出来）。 */
@Immutable
data class GlassMaterial(
    /** 底色（已含不透明度）。 */
    val fill: Color,
    /** 叠在模糊/底色之上的色相层（**多层**才有通透感，见类注释 ②）。 */
    val tints: List<Color>,
    /** 模糊半径（0.dp = 不模糊）。 */
    val blurRadius: Dp,
    /** 噪点强度 0f..1f（0 = 不画；磨砂质感的关键，见类注释 ①）。 */
    val noise: Float,
    /** 上缘受光强度 0f..1f（玻璃"厚度"感，见类注释 ③）。 */
    val edgeHighlight: Float
)

/**
 * 底栏玻璃的基准高度（原「底栏大小」滑条已去掉，固定用这一档）。
 * 0.45 对应栏高约 60.1dp，两侧各内缩约 5dp。
 */
internal val GLASS_BAR_SIZE = 0.45f

/** 跟手强度（原开发者滑条已去掉，固定这一档）。 */
internal val GLASS_BAR_TRACKING = 0.40f

/**
 * 形变强度（原开发者滑条已去掉）。取 0.60：0.4 档峰值仅 2%，在 24dp 图标上几乎不可见。
 */
internal val GLASS_BAR_DEFORM = 0.60f

/**
 * 线性插值（小工具）。当前材质解析已用不到它（不透明度改为线性直取），保留备用。
 */
@Suppress("unused")
private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t.coerceIn(0f, 1f)

/**
 * 设置 + 深浅色 + 角色 → 一份材质。纯函数（单测见 `GlassMaterialTest`）。
 *
 * @param surface 当前主题的 `colorScheme.surface`（底栏/实心底色由它派生）
 * @param dark 是否深色主题（深浅色**分别**调，见类注释 ④）
 * @param backdropActive 「磨砂」档此刻背景层是否可用（二级页在屏 / 拉伸冻结 / API<31 时都算不可用）；
 *   不可用时该档一律按「透明」（色罩）渲染，否则会露出没糊的底。
 *   真模糊由库的 drawBackdrop 施加，本参数只决定"走不走玻璃管线"。
 */
fun glassMaterialFor(
    role: GlassRole,
    settings: GlassSettings,
    surface: Color,
    dark: Boolean,
    backdropActive: Boolean = false
): GlassMaterial {
    // 不透明度为 0–100% 线性：0 = 完全透明、100 = 完全不透明，直接取滑条值。
    //   旧实现把滑条值插进一段区间（浅色 0.55~0.96 / 深色 0.45~0.92），两头都到不了端点。
    //   连带影响：同一滑条值下各表面完全一致，深浅色不再改变透明度，液态玻璃档也不再"更薄"。
    val t = settings.opacity.coerceIn(0f, 1f)
    // 液态玻璃在背景层不可用时整档坍缩成半透明（对 PILL/BAR 一致生效）。
    val mode =
        if (settings.mode == GlassMode.LIQUID_GLASS && !backdropActive) GlassMode.TRANSLUCENT
        else settings.mode
    return when (role) {
        // ---- 角标胶囊：小块、绝不自己录制 ----
        // R18 胶囊已脱离本模块（回归红底实色）；PILL 角色现仅回顶按钮使用。
        // 液态玻璃档由视图级背景层提供（见 ScrollToTopButton）：fill 作为 onDrawSurface 的罩色，
        // blurRadius=0 —— 折射/模糊半径在消费点写死小值，不从这里带。
        GlassRole.PILL -> when (mode) {
            GlassMode.OFF -> GlassMaterial(
                fill = surface, tints = emptyList(), blurRadius = 0.dp, noise = 0f, edgeHighlight = 0.12f
            )
            // 液态玻璃档主题感知：深色 = 黑雾面，浅色 = surface 亮雾面（iOS 式浅玻璃）。
            //   玻璃消费的是真实页面内容，浅色主题下再压一层黑罩会在亮内容上变成"脏黑块"。
            // 液态玻璃档与半透明档同值：不透明度是全球唯一口径（100% 就必须完全不透明），
            //   任何隐藏系数都会让"100% 还是能看见底"变成说不清的行为。两档的区别只剩有没有模糊。
            GlassMode.LIQUID_GLASS -> GlassMaterial(
                fill = if (dark) Color.Black.copy(alpha = t) else surface.copy(alpha = t),
                tints = emptyList(), blurRadius = 0.dp, noise = 0f, edgeHighlight = 0.18f
            )
            GlassMode.TRANSLUCENT -> GlassMaterial(
                // 主题感知（与液态玻璃档同口径）：深色 = 黑雾面（白图标），浅色 = surface 亮雾面
                //   （深色图标）。此前写死黑罩，浅色主题下回顶按钮是一颗黑圆 —— 已按反馈适配。
                fill = if (dark) Color.Black.copy(alpha = t) else surface.copy(alpha = t),
                tints = emptyList(), blurRadius = 0.dp, noise = 0f, edgeHighlight = 0.12f
            )
        }

        // ---- 底栏：大块、走全局背景层 ----
        GlassRole.BAR -> when (mode) {
            // 关 = 实心不透明（"关"就是材质系统整体关闭）。
            GlassMode.OFF -> GlassMaterial(
                fill = surface, tints = emptyList(), blurRadius = 0.dp, noise = 0f, edgeHighlight = 0f
            )
            // 液态玻璃：blurRadius 交给消费点的 drawBackdrop 施加（库的 blur 管线）。
            //   取 8dp：回顶按钮实测可用区间是 14px，36px（12dp@3x）与 60px（20dp@3x）都看不到模糊，
            //   所以把半径压到可用区间之上一点，避免半径过大被设备丢弃。
            //   罩色与半透明档同值，区别只剩模糊。
            GlassMode.LIQUID_GLASS -> GlassMaterial(
                fill = surface.copy(alpha = t),
                tints = emptyList(), blurRadius = 8.dp, noise = 0f, edgeHighlight = 0f
            )
            // 半透明 = 干净色罩、无模糊。
            GlassMode.TRANSLUCENT -> GlassMaterial(
                fill = surface.copy(alpha = t),
                tints = emptyList(), blurRadius = 0.dp, noise = 0f, edgeHighlight = 0f
            )
        }
    }
}
