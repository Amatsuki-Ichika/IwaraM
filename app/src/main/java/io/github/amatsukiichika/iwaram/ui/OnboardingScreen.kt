package io.github.amatsukiichika.iwaram.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.R
import io.github.amatsukiichika.iwaram.i18n.LanguageMode
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.components.AppEntryCard
import io.github.amatsukiichika.iwaram.ui.components.PillSegment
import io.github.amatsukiichika.iwaram.ui.components.PillSelector

/** 引导页总页数：欢迎/语言、视频文件夹、下载文件夹、偏好、完成。 */
private const val ONBOARDING_PAGE_COUNT = 5

/**
 * 首次启动引导页：语言、本地文件夹、下载文件夹、内容偏好与完成确认。
 *
 * 本组件只渲染调用方传入的状态，不访问仓库、服务或 Context，也不自行启动系统文件夹选择器：
 * [onPickLibrary] / [onPickDownload] 由调用方用 ActivityResultContracts.OpenDocumentTree 启动，
 * 选择结果再经 [libraryName] / [downloadName] 回传；[onClearDownload] 表示"跟随视频文件夹"。
 * 所有参数都有明确默认语义，因此可直接用固定值 @Preview，无需 ViewModel 或真机。
 *
 * @param language 当前界面语言；语言切换由调用方写入 AppLanguage 并回传新值。
 * @param libraryName 已选视频文件夹的显示名，null 表示尚未选择（此时不允许进入下一步）。
 * @param downloadName 已选下载文件夹的显示名，null 表示跟随视频文件夹。
 * @param defaultR18 默认 R18 过滤开关的当前值。
 * @param showTags 卡片标签开关的当前值。
 * @param onLanguageChange 选择语言时回调，调用方负责 AppLanguage.set 并持久化。
 * @param onPickLibrary 请求调用方打开系统文件夹选择器（视频文件夹）。
 * @param onPickDownload 请求调用方打开系统文件夹选择器（下载文件夹）。
 * @param onClearDownload 请求清除下载文件夹，改为跟随视频文件夹。
 * @param onDefaultR18Change 默认 R18 开关变化回调。
 * @param onShowTagsChange 卡片标签开关变化回调。
 * @param onFinish 点击"开始使用"：调用方负责持久化 onboarding_done 并进入主界面。
 * @param onSkip 点击"跳过"：调用方负责同样收尾，跳过后仍可稍后在设置里重看引导。
 */
@Composable
fun OnboardingScreen(
    language: LanguageMode,
    libraryName: String?,
    downloadName: String?,
    defaultR18: Boolean,
    showTags: Boolean,
    onLanguageChange: (LanguageMode) -> Unit,
    onPickLibrary: () -> Unit,
    onPickDownload: () -> Unit,
    onClearDownload: () -> Unit,
    onDefaultR18Change: (Boolean) -> Unit,
    onShowTagsChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
    onSkip: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(0) }
    val canAdvance = page != 1 || libraryName != null

    // 系统返回键在引导页内退回上一步，而不是直接离开应用。
    BackHandler(enabled = page > 0) { page -= 1 }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp),
        ) {
            OnboardingHeader(
                showSkip = page < ONBOARDING_PAGE_COUNT - 1,
                onSkip = onSkip,
            )
            AnimatedContent(
                targetState = page,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "onboardingPage",
            ) { index ->
                when (index) {
                    0 -> LanguagePage(language = language, onLanguageChange = onLanguageChange)
                    1 -> LibraryPage(libraryName = libraryName, onPickLibrary = onPickLibrary)
                    2 -> DownloadPage(
                        downloadName = downloadName,
                        onPickDownload = onPickDownload,
                        onClearDownload = onClearDownload,
                    )
                    3 -> PreferencePage(
                        defaultR18 = defaultR18,
                        showTags = showTags,
                        onDefaultR18Change = onDefaultR18Change,
                        onShowTagsChange = onShowTagsChange,
                    )
                    else -> DonePage(
                        language = language,
                        libraryName = libraryName,
                        downloadName = downloadName,
                        defaultR18 = defaultR18,
                        showTags = showTags,
                    )
                }
            }
            PageDots(page = page, modifier = Modifier.align(Alignment.CenterHorizontally))
            OnboardingBottomBar(
                page = page,
                canAdvance = canAdvance,
                canFinish = libraryName != null,
                onBack = { page -= 1 },
                onNext = { page += 1 },
                onFinish = onFinish,
            )
        }
    }
}

/** 顶部品牌区：应用图标、名称、一句话标语，右上角跳过。 */
@Composable
private fun OnboardingHeader(showSkip: Boolean, onSkip: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.app_icon),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = tr("iwara.tv 的非官方 Android 客户端", "An unofficial Android client for iwara.tv"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showSkip) {
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.TopEnd)) {
                Text(tr("跳过", "Skip"))
            }
        }
    }
}

/** 页内容容器：统一滚动，避免小屏被底部按钮裁掉。 */
@Composable
private fun OnboardingPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** 页标题与一行说明。 */
@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 第 0 页：欢迎与界面语言。 */
@Composable
private fun LanguagePage(language: LanguageMode, onLanguageChange: (LanguageMode) -> Unit) {
    OnboardingPage {
        PageHeading(
            title = tr("欢迎使用 IwaraM", "Welcome to IwaraM"),
            subtitle = tr("几步完成初始设置，之后随时可以修改。", "A few quick steps and you are ready; you can change this later."),
        )
        AppEntryCard(title = tr("界面语言", "Interface language")) {
            PillSelector(
                segments = languageSegments(),
                selected = language,
                onSelect = onLanguageChange,
                modifier = Modifier.fillMaxWidth(),
                evenWeight = true,
                segmentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        Text(
            text = tr("切换语言会立即生效。", "Switching the language applies immediately."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** 第 1 页：视频文件夹（必选）。 */
@Composable
private fun LibraryPage(libraryName: String?, onPickLibrary: () -> Unit) {
    OnboardingPage {
        PageHeading(
            title = tr("选择本地文件夹", "Choose your local folder"),
            subtitle = tr(
                "IwaraM 不会上传你的文件：选择一个存放视频或音频的文件夹，应用会在本机扫描并索引。",
                "IwaraM never uploads your files. Point it at a folder of your own videos or audio and it indexes it on this device.",
            ),
        )
        FolderCard(
            title = tr("视频文件夹", "Video folder"),
            subtitle = tr("本地库的根目录", "Root of your local library"),
            name = libraryName,
            emptyLabel = tr("尚未选择", "Not chosen yet"),
            buttonLabel = if (libraryName == null) tr("选择文件夹", "Choose folder") else tr("重新选择", "Change folder"),
            icon = Icons.Filled.FolderOpen,
            onPick = onPickLibrary,
        )
        if (libraryName == null) {
            Text(
                text = tr("必须选择视频文件夹后才能继续。", "A video folder is required before you can continue."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** 第 2 页：下载文件夹（可选，可恢复为跟随视频文件夹）。 */
@Composable
private fun DownloadPage(
    downloadName: String?,
    onPickDownload: () -> Unit,
    onClearDownload: () -> Unit,
) {
    OnboardingPage {
        PageHeading(
            title = tr("下载文件夹", "Download folder"),
            subtitle = tr(
                "在线下载的视频会保存到这里；不选择则跟随视频文件夹。",
                "Online downloads are saved here. Skip this and they follow your video folder.",
            ),
        )
        FolderCard(
            title = tr("下载位置", "Download location"),
            subtitle = tr("可选，之后可在设置中修改", "Optional; change it later in Settings"),
            name = downloadName,
            emptyLabel = tr("跟随视频文件夹", "Follows the video folder"),
            buttonLabel = if (downloadName == null) tr("选择文件夹", "Choose folder") else tr("更改", "Change"),
            icon = Icons.Filled.Download,
            onPick = onPickDownload,
        )
        if (downloadName != null) {
            TextButton(onClick = onClearDownload) {
                Text(tr("恢复跟随视频文件夹", "Use the video folder instead"))
            }
        }
    }
}

/** 第 3 页：内容偏好。 */
@Composable
private fun PreferencePage(
    defaultR18: Boolean,
    showTags: Boolean,
    onDefaultR18Change: (Boolean) -> Unit,
    onShowTagsChange: (Boolean) -> Unit,
) {
    OnboardingPage {
        PageHeading(
            title = tr("内容偏好", "Content preferences"),
            subtitle = tr("先用默认值即可，之后可在「设置」中修改。", "The defaults are fine to start; change them later in Settings."),
        )
        AppEntryCard(
            title = tr("默认 R18", "Default R18"),
            subtitle = tr("启动时只显示 R18 内容", "Start with only R18 content shown"),
            trailing = { Switch(checked = defaultR18, onCheckedChange = onDefaultR18Change) },
        )
        AppEntryCard(
            title = tr("卡片显示标签", "Show tags on cards"),
            subtitle = tr("在视频卡片底部显示标签", "Show tags below each video card"),
            trailing = { Switch(checked = showTags, onCheckedChange = onShowTagsChange) },
        )
    }
}

/** 第 4 页：完成，汇总已选设置。 */
@Composable
private fun DonePage(
    language: LanguageMode,
    libraryName: String?,
    downloadName: String?,
    defaultR18: Boolean,
    showTags: Boolean,
) {
    OnboardingPage {
        PageHeading(
            title = tr("准备就绪", "Ready to go"),
            subtitle = tr("确认一下这些选择，之后都可以在「设置」中修改。", "Here is what you picked; everything can be changed later in Settings."),
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                SummaryRow(tr("界面语言", "Language"), languageName(language))
                SummaryRow(tr("视频文件夹", "Video folder"), libraryName ?: tr("未选择", "Not set"))
                SummaryRow(tr("下载文件夹", "Download folder"), downloadName ?: tr("跟随视频文件夹", "Follows video folder"))
                SummaryRow(tr("默认 R18", "Default R18"), onOff(defaultR18))
                SummaryRow(tr("卡片显示标签", "Show tags"), onOff(showTags))
            }
        }
        Text(
            text = tr("设置保存在本机，不会上传到任何服务器。", "Settings stay on this device and are never uploaded."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** 文件夹卡片：显示已选名称与选择按钮，视觉沿用设置页条目卡。 */
@Composable
private fun FolderCard(
    title: String,
    subtitle: String,
    name: String?,
    emptyLabel: String,
    buttonLabel: String,
    icon: ImageVector,
    onPick: () -> Unit,
) {
    AppEntryCard(title = title, subtitle = subtitle) {
        Text(
            text = name ?: emptyLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = if (name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onPick) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(buttonLabel)
        }
    }
}

/** 摘要中的一行：左侧名称，右侧取值。 */
@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 底部页码指示：当前页为主色长条，其余为圆点。 */
@Composable
private fun PageDots(page: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(ONBOARDING_PAGE_COUNT) { index ->
            val selected = index == page
            val dotWidth by animateDpAsState(
                targetValue = if (selected) 20.dp else 8.dp,
                animationSpec = tween(220),
                label = "dotWidth",
            )
            Box(
                modifier = Modifier
                    .width(dotWidth)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
            )
        }
    }
}

/** 底部操作栏：上一步 / 下一步，末页为主按钮"开始使用"。 */
@Composable
private fun OnboardingBottomBar(
    page: Int,
    canAdvance: Boolean,
    canFinish: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (page > 0) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("上一步", "Back"))
            }
        } else {
            Spacer(Modifier.width(1.dp))
        }
        if (page < ONBOARDING_PAGE_COUNT - 1) {
            Button(onClick = onNext, enabled = canAdvance) {
                Text(tr("下一步", "Next"))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        } else {
            Button(onClick = onFinish, enabled = canFinish) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("开始使用", "Get started"))
            }
        }
    }
}

/** 语言分段：语言名按当前界面语言显示。 */
private fun languageSegments(): List<PillSegment<LanguageMode>> = listOf(
    PillSegment(LanguageMode.SYSTEM, tr("跟随系统", "System")),
    PillSegment(LanguageMode.ZH, tr("简体中文", "Chinese")),
    PillSegment(LanguageMode.EN, tr("English", "English")),
)

/** 语言在摘要中的显示名，与分段控制保持一致。 */
private fun languageName(mode: LanguageMode): String = when (mode) {
    LanguageMode.SYSTEM -> tr("跟随系统", "System")
    LanguageMode.ZH -> tr("简体中文", "Chinese")
    LanguageMode.EN -> tr("English", "English")
}

/** 开关值在摘要中的显示文本。 */
private fun onOff(value: Boolean): String = if (value) tr("开启", "On") else tr("关闭", "Off")
