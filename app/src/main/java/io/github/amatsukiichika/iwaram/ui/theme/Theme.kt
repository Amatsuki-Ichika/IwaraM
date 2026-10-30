package io.github.amatsukiichika.iwaram.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    secondary = Color(0xFFB7C5FF),
    background = Color(0xFF121317),
    surface = Color(0xFF1B1D22),
    surfaceVariant = Color(0xFF2A2D34),
    onPrimary = Color(0xFF002D6B),
    onBackground = Color(0xFFE3E3E8),
    onSurface = Color(0xFFE3E3E8),
    onSurfaceVariant = Color(0xFFA8ACB6)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5BD8),
    secondary = Color(0xFF5A66C2),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEEF4),
    onPrimary = Color.White,
    onBackground = Color(0xFF191C20),
    onSurface = Color(0xFF191C20),
    onSurfaceVariant = Color(0xFF4A4E57)
)

// 统一的 Material 形状：卡片/面板用更柔和的圆角，控件保持一致。
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

// 统一的排版：标题加粗收紧行高，正文沿用 Material 默认。
private val AppTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp)
)

/**
 * 主题模式："system" 跟随系统，"light" 强制浅色，"dark" 强制深色。
 */
fun resolveDarkTheme(mode: String, systemDark: Boolean): Boolean = when (mode) {
    "light" -> false
    "dark" -> true
    else -> systemDark
}

/** 内置主题色板：value ↔ 浅色/深色两套 primary+secondary（blue 即历史默认色）。 */
data class ThemeColorOption(
    val value: String,
    val label: String,
    val labelEn: String,
    val light: Color,
    val lightSecondary: Color,
    val dark: Color,
    val darkSecondary: Color
)

val THEME_COLOR_OPTIONS = listOf(
    ThemeColorOption("blue", "蓝", "Blue", Color(0xFF2F5BD8), Color(0xFF5A66C2), Color(0xFF8AB4FF), Color(0xFFB7C5FF)),
    ThemeColorOption("green", "绿", "Green", Color(0xFF2E7D46), Color(0xFF4F9A6B), Color(0xFF93D6A6), Color(0xFFA8D8B8)),
    ThemeColorOption("purple", "紫", "Purple", Color(0xFF7B4FD0), Color(0xFF9568E0), Color(0xFFC9B2FF), Color(0xFFD6C6FF)),
    ThemeColorOption("red", "红", "Red", Color(0xFFC2185B), Color(0xFFD05680), Color(0xFFFFAFC7), Color(0xFFFFC4D6)),
    ThemeColorOption("orange", "橙", "Orange", Color(0xFFC2661F), Color(0xFFD98A4A), Color(0xFFFFB77E), Color(0xFFFFCCA4)),
    ThemeColorOption("teal", "青", "Teal", Color(0xFF00838F), Color(0xFF26A0AC), Color(0xFF7FD8DD), Color(0xFF9FE4E8))
)

/**
 * 卡片内 tag chip 的底色：由卡片底色再深一档派生。
 *
 * M3 的 colorScheme 里没有"比卡片更深一档"的语义槽位（`surfaceVariant` 是固定基线色、不带主题色，
 * 和收敛后的卡片底色色相不一致），所以在这里单独派生并由 [IwaraTheme] 下发。
 * 默认值只在未套 [IwaraTheme] 时兜底。
 */
val LocalTagContainer = staticCompositionLocalOf { Color(0xFFE4E6EC) }

@Composable
fun IwaraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColor: String = "blue",
    content: @Composable () -> Unit
) {
    val palette = THEME_COLOR_OPTIONS.firstOrNull { it.value == themeColor } ?: THEME_COLOR_OPTIONS.first()
    // surfaceTint（表面海拔叠加色）与 container 系槽位默认是 Material 基线粉紫色，且 surfaceTint
    // 默认取 primary 原色（红/粉主题下整页泛粉），这里全部改为"主题色向底色收敛 65%"的淡色：
    // 跟随主题色、又明显更淡。
    val base = if (darkTheme) DarkColors else LightColors
    val accent = if (darkTheme) palette.dark else palette.light
    val accentSecondary = if (darkTheme) palette.darkSecondary else palette.lightSecondary
    val containerBase = if (darkTheme) base.surface else Color.White
    val containerOnBase = if (darkTheme) base.onSurface else Color(0xFF191C20)
    val surfaceTint = lerp(base.surface, accent, 0.35f)
    // surfaceContainer* / surfaceDim/Bright / outline 系是静态基线槽位（粉紫系），
    // Card/菜单/底栏表面/分隔线用的正是它们，同样按主题色向底色收敛派生。
    // 容器色阶梯取值 0.03/0.045/0.06/0.075（深色同比 +0.015）：M3 Card 默认取
    // surfaceContainerHighest，阶梯偏高会让卡片底色"太浓"；卡片/菜单/底栏/字段共用同一条阶梯，
    // 避免"卡片淡了底栏还浓"的割裂。
    val ladderShift = if (darkTheme) 0.015f else 0f
    val surfaceContainerLowest = lerp(containerBase, accent, if (darkTheme) 0.03f else 0f)
    val surfaceContainerLow = lerp(containerBase, accent, 0.03f + ladderShift)
    val surfaceContainer = lerp(containerBase, accent, 0.045f + ladderShift)
    val surfaceContainerHigh = lerp(containerBase, accent, 0.06f + ladderShift)
    val surfaceContainerHighest = lerp(containerBase, accent, 0.075f + ladderShift)
    val surfaceDim = lerp(containerBase, accent, if (darkTheme) 0f else 0.10f)
    val surfaceBright = lerp(containerBase, accent, if (darkTheme) 0.10f else 0f)
    val outline = lerp(containerOnBase, accent, 0.30f)
    val outlineVariant = lerp(containerOnBase, accent, 0.12f)
    val colorScheme = base.copy(
        primary = accent,
        secondary = accentSecondary,
        surfaceTint = surfaceTint,
        primaryContainer = lerp(containerBase, accent, 0.14f),
        onPrimaryContainer = lerp(containerOnBase, accent, 0.55f),
        secondaryContainer = lerp(containerBase, accentSecondary, 0.14f),
        onSecondaryContainer = lerp(containerOnBase, accentSecondary, 0.55f),
        tertiaryContainer = lerp(containerBase, accent, 0.14f),
        onTertiaryContainer = lerp(containerOnBase, accent, 0.55f),
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceDim = surfaceDim,
        surfaceBright = surfaceBright,
        outline = outline,
        outlineVariant = outlineVariant
    )
    // 卡片内 tag 底色：卡片底（M3 Card 默认取 surfaceContainerHighest）再深一档。
    // 浅色往黑压 5%；深色往白提 6%（深色下"更亮一层"才是视觉上的凸起）。
    val tagContainer = lerp(
        surfaceContainerHighest,
        if (darkTheme) Color.White else Color.Black,
        if (darkTheme) 0.06f else 0.05f
    )

    // MaterialTheme 不提供 LocalContentColor，而 MainActivity 的
    // Scaffold(containerColor = Transparent) 也拿不到 contentColorFor(Transparent)，
    // 未显式取色的文字（登录页标题最典型）会落回 Compose 默认的黑色：浅色下正常，深色下不跟随。
    // 这里下发主题的 onBackground 兜底：浅色下约 #191C20（与原来的黑几乎无差），
    // 深色下才是正确的浅色字（Card / Surface 等仍由各自的 contentColor 覆盖，不受影响）。
    CompositionLocalProvider(
        LocalTagContainer provides tagContainer,
        LocalContentColor provides colorScheme.onBackground
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
