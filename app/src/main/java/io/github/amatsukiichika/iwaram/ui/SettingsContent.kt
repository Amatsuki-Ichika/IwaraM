package io.github.amatsukiichika.iwaram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaram.ui.browse.accentChipColors
import io.github.amatsukiichika.iwaram.ui.components.AppEntryCard
import io.github.amatsukiichika.iwaram.ui.components.AppSlider
import io.github.amatsukiichika.iwaram.ui.components.GlassMode
import io.github.amatsukiichika.iwaram.ui.theme.THEME_COLOR_OPTIONS
import kotlin.math.roundToInt

/* ---------- 各分类页内容（原 SettingsScreen.kt 内容区块，一设置项一张 AppEntryCard） ---------- */
private enum class ThemeOption(val label: String, val value: String) {
    SYSTEM("跟随系统", "system"),
    LIGHT("浅色", "light"),
    DARK("深色", "dark")
}

private enum class OrientationOption(val label: String, val value: String) {
    AUTO("自动", "auto"),
    SMART("智能识别", "smart"),
    LANDSCAPE("横向", "landscape"),
    PORTRAIT("竖向", "portrait")
}

/** 默认分辨率选项：值为 API 档位名（Source 精确匹配；数字档缺失时回退最高可用档）。 */
private val QUALITY_OPTIONS = listOf(
    "Source" to "Source",
    "1080p" to "1080",
    "720p" to "720",
    "540p" to "540",
    "480p" to "480",
    "360p" to "360"
)

/**
 * 设置页的集合区块标题（唯一实现）。
 *
 * 用于"同一页里把设置分成几组"（通用页的外观 / 内容分级；播放页的默认 / 超分）——
 * 不占一层页面，只是一个小标题 + 其下卡片。
 */
@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
        content()
    }
}

/* ---------- 各分类页内容（一设置项一张 AppEntryCard） ---------- */

/** 「通用」页 = 原「外观」+「内容分级」合并，页内两个集合区块。 */
@Composable
internal fun GeneralContent(
    vm: VideoViewModel,
    themeMode: String,
    themeColor: String,
    showTags: Boolean,
    defaultR18: Boolean,
    showR18Tag: Boolean,
    hideUnmatched: Boolean,
    onInfo: (String, String) -> Unit
) {
    SettingsSection("外观") {
        AppearanceContent(
            vm = vm,
            themeMode = themeMode,
            themeColor = themeColor,
            showTags = showTags,
            onInfo = onInfo
        )
    }
    SettingsSection("本地库") {
        AppEntryCard(
            title = "隐藏未匹配的视频",
            subtitle = "本地视频尚未匹配 Iwara id 时不显示",
            trailing = { Switch(checked = hideUnmatched, onCheckedChange = vm::setHideUnmatched) }
        )
    }
    SettingsSection("内容分级") {
        ContentRatingContent(
            vm = vm,
            defaultR18 = defaultR18,
            showR18Tag = showR18Tag,
            onInfo = onInfo
        )
    }
}

/** 「外观」区块 = 主题与显示 + 半透明材质（材质项已从开发者页移入）。 */
@Composable
private fun AppearanceContent(
    vm: VideoViewModel,
    themeMode: String,
    themeColor: String,
    showTags: Boolean,
    onInfo: (String, String) -> Unit
) {
    // 选项 chips 已自明（浅色/深色/跟随系统），不再用副标题复述一遍。
    AppEntryCard(title = "主题模式") {
        ChoiceChips(
            options = ThemeOption.entries.map { it.label to it.value },
            current = themeMode,
            onSelect = vm::setThemeMode
        )
    }
    AppEntryCard(
        title = "卡片显示标签",
        subtitle = "在视频卡片底部显示标签",
        trailing = { Switch(checked = showTags, onCheckedChange = vm::setShowTags) }
    )
    // 强调色块本身自明，去掉"应用强调色，全局生效"这类复述。
    AppEntryCard(title = "主题色") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            THEME_COLOR_OPTIONS.forEach { opt ->
                val selected = themeColor == opt.value
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (isSystemInDarkTheme()) opt.dark else opt.light)
                        .then(
                            if (selected) Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            ) else Modifier
                        )
                        .clickable { vm.setThemeColor(opt.value) },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "已选中",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
    // ---- 半透明材质（原在开发者页，已移入外观页）----
    // 一根不透明度条 + 一个磨砂方案：底栏玻璃 / 所有角标胶囊 / 回顶按钮统一生效
    //（实现在 ui/components/GlassMaterial.kt，一处定义）。
    // 滑条右侧有实时百分比显示，副标题不再解释"0% = 完全透明"；作用范围保留列举。
    BarTuningCard(
        title = "材质不透明度",
        subtitle = "底栏 · 顶栏 · 悬浮条 · 回顶按钮",
        value = vm.glassOpacityFlow.collectAsStateWithLifecycle().value,
        onValueChange = vm::setGlassOpacity
    )
    val glassMode = GlassMode.of(vm.glassModeFlow.collectAsStateWithLifecycle().value)
    // 当前档位由下方 chips 高亮表达，副标题不再复述档位名。
    AppEntryCard(
        title = "材质",
        info = "「关」= 实心不透明，材质系统整体关闭；「透明」= 半透明色罩、无模糊（零成本，默认）；" +
            "「磨砂」= 真实背景模糊 + 边缘折射（AndroidLiquidGlass，需要 Android 12+，折射部分 13+）。",
        onInfo = onInfo
    ) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            ChoiceChips(
                options = GlassMode.options,
                current = glassMode.ordinal.toString(),
                onSelect = { vm.setGlassMode(it.toIntOrNull() ?: 1) }
            )
        }
    }
}

@Composable
private fun ContentRatingContent(
    vm: VideoViewModel,
    defaultR18: Boolean,
    showR18Tag: Boolean,
    onInfo: (String, String) -> Unit
) {
    AppEntryCard(
        title = "默认 R18",
        // 开关的唯一效果就是"只显示 R18"，副标题原是标题的同义复述 → 删；
        // info 只留开关之外的行为变化（隐藏筛选/恢复手动筛选）。
        info = "开启后只显示 R18 内容（在线与本地），并隐藏分级筛选项；关闭后恢复手动分级筛选。",
        onInfo = onInfo,
        trailing = { Switch(checked = defaultR18, onCheckedChange = vm::setDefaultR18) }
    )
    AppEntryCard(
        title = "显示 R18 标签",
        subtitle = "在卡片右上角显示 R18 角标",
        info = "只给确实为 R18 的内容打角标；未匹配到 iwara 的本地视频不打标。",
        onInfo = onInfo,
        trailing = { Switch(checked = showR18Tag, onCheckedChange = vm::setShowR18Tag) }
    )
}

/** 播放页 = 两个集合区块：「默认」（播放页三项）与「超分」。 */
@Composable
internal fun PlaybackContent(
    vm: VideoViewModel,
    fsOrientation: String,
    qualityPref: String,
    downloadQualityPref: String,
    srEnabled: Boolean,
    srModel: String,
    debugDetails: Boolean,
    onInfo: (String, String) -> Unit,
    onShowModels: () -> Unit
) {
    SettingsSection("默认") {
        AppEntryCard(title = "全屏方向", subtitle = "智能识别=按视频画面宽高比自动选横/竖屏") {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ChoiceChips(
                    options = OrientationOption.entries.map { it.label to it.value },
                    current = fsOrientation,
                    onSelect = vm::setFsOrientation
                )
            }
        }
        AppEntryCard(
            title = "默认分辨率",
            subtitle = "在线视频优先使用的清晰度",
            info = "该档位不可用时自动回退到最高可用档。",
            onInfo = onInfo
        ) {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ChoiceChips(
                    options = QUALITY_OPTIONS,
                    current = qualityPref,
                    onSelect = vm::setQualityPref
                )
            }
        }
        AppEntryCard(
            title = "下载分辨率",
            subtitle = "下载在线视频优先使用的清晰度",
            // 回退规则与「默认分辨率」一致，不再逐字重复；两者各管播放/下载自明。
            info = "该档位不可用时自动回退到最高可用档。",
            onInfo = onInfo
        ) {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ChoiceChips(
                    options = QUALITY_OPTIONS,
                    current = downloadQualityPref,
                    onSelect = vm::setDownloadQualityPref
                )
            }
        }
    }
    // 原「超分」分类整体移入（不再单独占主页一格）。
    SettingsSection("超分") {
        SuperResContent(
            vm = vm,
            srEnabled = srEnabled,
            srModel = srModel,
            debugDetails = debugDetails,
            onInfo = onInfo,
            onShowModels = onShowModels
        )
    }
}

@Composable
private fun SuperResContent(
    vm: VideoViewModel,
    srEnabled: Boolean,
    srModel: String,
    debugDetails: Boolean,
    onInfo: (String, String) -> Unit,
    onShowModels: () -> Unit
) {
    AppEntryCard(
        title = "默认启用超分",
        subtitle = "本地 / 在线通用",
        // 副标题与 info 原有一半逐字重复（"播放时默认应用"≈"决定播放页打开时的默认状态"），
        //   info 只留开关之外的行为说明。
        info = "播放页打开时的默认状态；播放中可随时在超分菜单临时切换，不影响此默认值。",
        onInfo = onInfo,
        trailing = { Switch(checked = srEnabled, onCheckedChange = vm::setSrEnabled) }
    )
    // 把每个方案的适用场景收进弹窗，菜单/chip 上只放模型名。
    //   ⓘ 是弹窗唯一入口，info 保留作引导；"按模型名选择"与 chip 自明 → 删。
    AppEntryCard(
        title = "超分方案",
        info = "查看各方案的适用场景",
        onInfo = { _, _ -> onShowModels() }
    ) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            ChoiceChips(
                options = SuperRes.modelsFor(debugDetails).map { SuperRes.label(it) to it },
                current = srModel,
                onSelect = vm::setSrModel
            )
        }
    }
}

@Composable
internal fun StorageContent(
    vm: VideoViewModel,
    rootName: String,
    downloadRootName: String,
    onPickFolder: () -> Unit,
    onPickDownloadFolder: () -> Unit
) {
    AppEntryCard(title = "视频根目录", subtitle = "选择后自动递归导入其中所有视频") {
        Text(
            text = rootName.ifBlank { "未设置" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onPickFolder) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text("重新选择文件夹")
        }
    }
    AppEntryCard(title = "下载目录", subtitle = "在线视频的保存位置") {
        Text(
            text = downloadRootName.ifBlank { "默认（管理器根目录）" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onPickDownloadFolder) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
                Text("选择文件夹")
            }
            if (downloadRootName.isNotBlank()) {
                TextButton(onClick = { vm.setDownloadRoot(null) }) {
                    Text("恢复默认")
                }
            }
        }
    }
}

@Composable
internal fun DeveloperContent(
    vm: VideoViewModel,
    debugDetails: Boolean,
    onOpenDiagnostics: () -> Unit
) {
    // 它是总门控：页里除它以外的所有内容都只在开启后渲染（见下面的 if）。
    AppEntryCard(
        title = "开发者模式",
        trailing = { Switch(checked = debugDetails, onCheckedChange = vm::setDebugDetails) }
    )
    if (!debugDetails) return
    // 诊断入口：诊断日志常开记录，让任何页面复现的问题都能就地回看。
    AppEntryCard(
        title = "运行状态 / 日志",
        subtitle = "网络、超分与错误状态；日志可一键复制",
        onClick = onOpenDiagnostics
    )

}


/** 材质/调参滑条行（语义 = 0 完全透明 / 100 完全不透明），拖动即生效。
 *  [display] 让显示文案可自定义 —— 有些量的单位不是百分比（如 dp）。 */
@Composable
private fun BarTuningCard(
    title: String,
    subtitle: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    display: (Float) -> String = { "${(it * 100).roundToInt()}%" }
) {
    AppEntryCard(
        title = title,
        subtitle = subtitle
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppSlider(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f)
            )
            Text(
                display(value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(52.dp)
            )
        }
    }
}

@Composable
internal fun AboutContent() {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    AppEntryCard(
        title = "版本",
        trailing = {
            Text(version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    )
}

/* ---------- 行组件 ---------- */

@Composable
private fun ChoiceChips(
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = current == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                colors = accentChipColors()
            )
        }
    }
}
