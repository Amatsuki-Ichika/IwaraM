package io.github.amatsukiichika.iwaram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaram.i18n.LanguageMode
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.browse.accentChipColors
import io.github.amatsukiichika.iwaram.ui.components.AppEntryCard
import io.github.amatsukiichika.iwaram.ui.components.AppSlider
import io.github.amatsukiichika.iwaram.ui.components.GlassMode
import io.github.amatsukiichika.iwaram.ui.components.PillSegment
import io.github.amatsukiichika.iwaram.ui.components.PillSelector
import io.github.amatsukiichika.iwaram.ui.theme.THEME_COLOR_OPTIONS
import kotlin.math.roundToInt

/* ---------- 各分类页内容（原 SettingsScreen.kt 内容区块，一设置项一张 AppEntryCard） ---------- */
private enum class ThemeOption(
    private val labelZh: String,
    private val labelEn: String,
    val value: String
) {
    SYSTEM("跟随系统", "System", "system"),
    LIGHT("浅色", "Light", "light"),
    DARK("深色", "Dark", "dark");

    /** 当前界面语言下的选项名（组合期求值，切语言立即生效）。 */
    val label: String get() = tr(labelZh, labelEn)
}

private enum class OrientationOption(
    private val labelZh: String,
    private val labelEn: String,
    val value: String
) {
    AUTO("自动", "Auto", "auto"),
    SMART("智能识别", "Smart", "smart"),
    LANDSCAPE("横向", "Landscape", "landscape"),
    PORTRAIT("竖向", "Portrait", "portrait");

    /** 当前界面语言下的选项名（组合期求值，切语言立即生效）。 */
    val label: String get() = tr(labelZh, labelEn)
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
    // 区块标题与卡片组之间 8dp（原 12dp，随设置页整体收紧）。
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    language: LanguageMode,
    onLanguageChange: (LanguageMode) -> Unit,
    onInfo: (String, String) -> Unit
) {
    SettingsSection(tr("语言", "Language")) {
        AppEntryCard(title = tr("界面语言", "Interface language")) {
            PillSelector(
                segments = languageSegments(),
                selected = language,
                onSelect = onLanguageChange,
                modifier = Modifier.fillMaxWidth(),
                evenWeight = true,
                segmentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            )
        }
    }
    SettingsSection(tr("外观", "Appearance")) {
        AppearanceContent(
            vm = vm,
            themeMode = themeMode,
            themeColor = themeColor,
            showTags = showTags,
            onInfo = onInfo
        )
    }
    SettingsSection(tr("本地库", "Local library")) {
        AppEntryCard(
            title = tr("隐藏未匹配的视频", "Hide unmatched videos"),
            subtitle = tr("本地视频尚未匹配 Iwara id 时不显示", "Hide local videos not yet matched to an Iwara ID"),
            trailing = { Switch(checked = hideUnmatched, onCheckedChange = vm::setHideUnmatched) }
        )
    }
    SettingsSection(tr("内容分级", "Content rating")) {
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
    AppEntryCard(title = tr("主题模式", "Theme mode")) {
        ChoiceChips(
            options = ThemeOption.entries.map { it.label to it.value },
            current = themeMode,
            onSelect = vm::setThemeMode
        )
    }
    AppEntryCard(
        title = tr("卡片显示标签", "Show tags on cards"),
        subtitle = tr("在视频卡片底部显示标签", "Show tags at the bottom of video cards"),
        trailing = { Switch(checked = showTags, onCheckedChange = vm::setShowTags) }
    )
    // 强调色块本身自明，去掉"应用强调色，全局生效"这类复述。
    AppEntryCard(title = tr("主题色", "Theme color")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            THEME_COLOR_OPTIONS.forEach { opt ->
                val selected = themeColor == opt.value
                val colorName = tr(opt.label, opt.labelEn)
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
                        .clickable { vm.setThemeColor(opt.value) }
                        .semantics { contentDescription = colorName },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = tr("已选中", "Selected"),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
    // ---- 半透明材质（原在开发者页，已移入外观页）----
    // 先选「材质」档位、再调「材质不透明度」：档位决定材质系统开不开，不透明度只在开着时有意义，
    // 从上往下正好是"先定性、后定量"。底栏玻璃 / 所有角标胶囊 / 回顶按钮统一生效
    //（实现在 ui/components/GlassMaterial.kt，一处定义）。
    val glassMode = GlassMode.of(vm.glassModeFlow.collectAsStateWithLifecycle().value)
    // 当前档位由下方 chips 高亮表达，副标题不再复述档位名。
    AppEntryCard(
        title = tr("材质", "Material"),
        info = tr(
            "「关」= 实心不透明，材质系统整体关闭；「透明」= 半透明色罩、无模糊（零成本）；" +
                "「磨砂」= 真实背景模糊 + 边缘折射（AndroidLiquidGlass，需要 Android 12+，折射部分 13+，默认）。",
            "'Off' = solid and opaque, the material system is disabled entirely; " +
                "'Translucent' = translucent tint without blur (zero cost); " +
                "'Frosted' = real background blur + edge refraction (AndroidLiquidGlass, " +
                "requires Android 12+, refraction part 13+, default)."
        ),
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
    // 滑条右侧有实时百分比显示，副标题不再解释"0% = 完全透明"；作用范围保留列举。
    BarTuningCard(
        title = tr("材质不透明度", "Material opacity"),
        subtitle = tr("底栏 · 顶栏 · 悬浮条 · 回顶按钮", "Bottom bar · top bar · floating bar · scroll-to-top button"),
        value = vm.glassOpacityFlow.collectAsStateWithLifecycle().value,
        onValueChange = vm::setGlassOpacity
    )
}

@Composable
private fun ContentRatingContent(
    vm: VideoViewModel,
    defaultR18: Boolean,
    showR18Tag: Boolean,
    onInfo: (String, String) -> Unit
) {
    AppEntryCard(
        title = tr("默认 R18", "Default R18"),
        // 开关的唯一效果就是"只显示 R18"，副标题原是标题的同义复述 → 删；
        // info 只留开关之外的行为变化（隐藏筛选/恢复手动筛选）。
        info = tr(
            "开启后只显示 R18 内容（在线与本地），并隐藏分级筛选项；关闭后恢复手动分级筛选。",
            "Only R18 content is shown (online and local) and the rating filter is hidden; " +
                "switch it off to restore manual rating filtering."
        ),
        onInfo = onInfo,
        trailing = { Switch(checked = defaultR18, onCheckedChange = vm::setDefaultR18) }
    )
    AppEntryCard(
        title = tr("显示 R18 标签", "Show R18 badge"),
        subtitle = tr("在卡片右上角显示 R18 角标", "Show the R18 badge in the top-right corner of cards"),
        info = tr(
            "只给确实为 R18 的内容打角标；未匹配到 iwara 的本地视频不打标。",
            "Only content confirmed as R18 gets the badge; local videos not matched to iwara are not badged."
        ),
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
    SettingsSection(tr("默认", "Defaults")) {
        AppEntryCard(
            title = tr("全屏方向", "Fullscreen orientation"),
            subtitle = tr("智能识别=按视频画面宽高比自动选横/竖屏", "Smart = pick landscape/portrait from the video's aspect ratio")
        ) {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ChoiceChips(
                    options = OrientationOption.entries.map { it.label to it.value },
                    current = fsOrientation,
                    onSelect = vm::setFsOrientation
                )
            }
        }
        AppEntryCard(
            title = tr("默认分辨率", "Default resolution"),
            subtitle = tr("在线视频优先使用的清晰度", "Preferred quality for online videos"),
            info = tr("该档位不可用时自动回退到最高可用档。", "Falls back to the highest available quality when this one is unavailable."),
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
            title = tr("下载分辨率", "Download resolution"),
            subtitle = tr("下载在线视频优先使用的清晰度", "Preferred quality when downloading online videos"),
            // 回退规则与「默认分辨率」一致，不再逐字重复；两者各管播放/下载自明。
            info = tr("该档位不可用时自动回退到最高可用档。", "Falls back to the highest available quality when this one is unavailable."),
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
    SettingsSection(tr("超分", "Super resolution")) {
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
        title = tr("默认启用超分", "Enable super resolution by default"),
        subtitle = tr("本地 / 在线通用", "Applies to local and online videos"),
        // 副标题与 info 原有一半逐字重复（"播放时默认应用"≈"决定播放页打开时的默认状态"），
        //   info 只留开关之外的行为说明。
        info = tr(
            "播放页打开时的默认状态；播放中可随时在超分菜单临时切换，不影响此默认值。",
            "Initial state when the player opens; you can switch it at any time in the super resolution menu without changing this default."
        ),
        onInfo = onInfo,
        trailing = { Switch(checked = srEnabled, onCheckedChange = vm::setSrEnabled) }
    )
    // 把每个方案的适用场景收进弹窗，菜单/chip 上只放模型名。
    //   ⓘ 是弹窗唯一入口，info 保留作引导；"按模型名选择"与 chip 自明 → 删。
    AppEntryCard(
        title = tr("超分方案", "Super resolution model"),
        info = tr("查看各方案的适用场景", "See when each model works best"),
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
    AppEntryCard(
        title = tr("视频根目录", "Video root folder"),
        subtitle = tr("选择后自动递归导入其中所有视频", "Imports every video inside automatically, recursively")
    ) {
        Text(
            text = rootName.ifBlank { tr("未设置", "Not set") },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onPickFolder) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(4.dp))
            Text(tr("重新选择文件夹", "Choose folder again"))
        }
    }
    AppEntryCard(
        title = tr("下载目录", "Download folder"),
        subtitle = tr("在线视频的保存位置", "Where online videos are saved")
    ) {
        Text(
            text = downloadRootName.ifBlank { tr("默认（管理器根目录）", "Default (manager root folder)") },
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
                Text(tr("选择文件夹", "Choose folder"))
            }
            if (downloadRootName.isNotBlank()) {
                TextButton(onClick = { vm.setDownloadRoot(null) }) {
                    Text(tr("恢复默认", "Reset to default"))
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
        title = tr("开发者模式", "Developer mode"),
        trailing = { Switch(checked = debugDetails, onCheckedChange = vm::setDebugDetails) }
    )
    if (!debugDetails) return
    // 诊断入口：诊断日志常开记录，让任何页面复现的问题都能就地回看。
    AppEntryCard(
        title = tr("运行状态 / 日志", "Status / logs"),
        subtitle = tr("网络、超分与错误状态；日志可一键复制", "Network, super resolution and error status; logs can be copied in one tap"),
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
internal fun AboutContent(onShowOnboarding: () -> Unit = {}) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    AppEntryCard(
        title = tr("版本", "Version"),
        trailing = {
            Text(version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    )
    AppEntryCard(
        title = tr("再次查看首次引导", "Show the first-run guide again"),
        subtitle = tr("重看语言、文件夹与偏好设置的介绍", "Replay the language, folder and preference tour"),
        onClick = onShowOnboarding
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

/** 界面语言分段：语言名按当前界面语言显示。 */
private fun languageSegments(): List<PillSegment<LanguageMode>> = listOf(
    PillSegment(LanguageMode.SYSTEM, tr("跟随系统", "System")),
    PillSegment(LanguageMode.ZH, tr("简体中文", "Chinese")),
    PillSegment(LanguageMode.EN, tr("English", "English"))
)
