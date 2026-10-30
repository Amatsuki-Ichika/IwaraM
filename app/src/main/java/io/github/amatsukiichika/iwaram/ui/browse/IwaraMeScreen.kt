package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import io.github.amatsukiichika.iwaram.ui.components.TOP_BAR_CONTENT_HEIGHT
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.FastScrollbarBasic
import io.github.amatsukiichika.iwaram.ui.components.AppEntryCard
import io.github.amatsukiichika.iwaram.ui.components.AvatarImage
import io.github.amatsukiichika.iwaram.ui.components.debugAwareErrorText

/** 底栏「我的」tab：登录 / 个人信息 / 播放列表 / 关注列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IwaraMeScreen(
    cvm: IwaraClientViewModel,
    actions: IwaraNavActions,
    /** 悬浮玻璃底栏高度 → 列表底部 contentPadding（内容穿到栏后，不露白块）。 */
    bottomPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)
) {
    val session by cvm.session.collectAsStateWithLifecycle()
    // 顶栏已提升到 MainActivity（固定不随页面滑动），这里只负责内容。
    // App 顶栏现在是**覆盖层**、内容从它底下穿过 ⇒ 本页整块往下让出它的高度。
    Box(Modifier.fillMaxSize().padding(top = TOP_BAR_CONTENT_HEIGHT)) {
        when {
            // 有登录态（含离线缓存会话）直接渲染面板：不再被 checking 转圈挡住，
            // 关注等数据由卡片内/二级页懒加载（离线可看）。
            session.loggedIn -> LoggedInPanel(cvm, actions, session, bottomPadding)
            session.checking -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> LoginForm(cvm, actions)
        }
    }
}

@Composable
private fun LoginForm(cvm: IwaraClientViewModel, actions: IwaraNavActions) {
    val session by cvm.session.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // imePadding：adjustNothing 下窗口不再随键盘压缩，靠 inset 让输入框浮在键盘上方。
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(tr("登录 Iwara 账号", "Sign in to Iwara"), style = MaterialTheme.typography.titleLarge)
        Text(
            tr(
                "账号密码只用于向 iwara.tv 官方接口登录，本机保存的是会话令牌",
                "Credentials are sent only to the official iwara.tv API; only a session token is stored on this device"
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(tr("邮箱或用户名", "Email or username")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.size(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(tr("密码", "Password")) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { cvm.login(email, password) }),
            modifier = Modifier.fillMaxWidth()
        )
        session.message?.let {
            Text(
                // 普通模式显示人话（登录失败的技术细节按 debug 开关取轨）
                debugAwareErrorText(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Spacer(Modifier.size(16.dp))
        Button(
            onClick = { cvm.login(email, password) },
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (session.busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            }
            Text(if (session.busy) tr("登录中…", "Signing in…") else tr("登录", "Sign in"))
        }
        Text(
            tr(
                "登录后可用：订阅流、收藏、点赞、评论、关注、播放列表",
                "After signing in: subscriptions, favorites, likes, comments, following, playlists"
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp)
        )
        Spacer(Modifier.size(20.dp))
        SettingsEntryRow(actions)
    }
}

/**
 * 账号卡片内的分隔线：**左右对称**内缩的细线。
 *
 * 只给 `padding(start = 16.dp)` 会让左边缩进、右边顶到卡片边缘，观感左右不对称；
 * 且 `outlineVariant` 是 `lerp(onSurface, accent, 0.12)` 的偏深色，再乘高透明度叠在白卡上会是一条明显的灰线。
 * 这里左右各内缩 16dp、0.5dp 细、透明度 0.22 —— 作为"分组提示"存在感刚好，不抢视线。
 */
@Composable
private fun MeCardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
    )
}

/**
 * 「设置」入口行：与收藏/播放列表入口同款卡片样式。
 * 卡片本体为公共组件 [AppEntryCard]（设置页一、三级同款）。
 */
@Composable
private fun SettingsEntryRow(actions: IwaraNavActions) {
    AppEntryCard(title = tr("设置", "Settings"), icon = Icons.Filled.Settings, onClick = { actions.openSettings() })
}

@Composable
private fun LoggedInPanel(
    cvm: IwaraClientViewModel,
    actions: IwaraNavActions,
    session: IwaraClientViewModel.SessionState,
    bottomPadding: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f)
) {
    LaunchedEffect(Unit) {
        // 每次进入都尝试刷新资料（头像 URL 持久化在 prefs、启动已恢复；
        // 这里再拉一次 /user 保证头像/昵称与站点同步 —— 失败静默，离线沿用上次缓存）。
        cvm.refreshMe()
    }

    Box(Modifier.fillMaxSize()) {
    val meListState = rememberLazyListState()
    LazyColumn(
        state = meListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + bottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 个人信息卡片：只剩身份信息与「退出登录」（破坏性操作收在账号信息卡里，避免误触）
        item {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 头像统一走 [AvatarImage] —— 有头像显示真实头像，
                        // 无头像/离线显示打包的 iwara 默认头像。
                        AvatarImage(
                            url = session.avatarUrl,
                            size = 56.dp,
                            contentDescription = session.username
                        )
                        Spacer(Modifier.size(14.dp))
                        Column {
                            Text(session.username.ifBlank { tr("已登录", "Signed in") }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                tr("已登录 Iwara 账号", "Signed in to Iwara"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    MeCardDivider()
                    // 退出登录
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { cvm.logout() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            tr("退出登录", "Sign out"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        // 账号操作入口：独立卡片，与「设置」同款；观看历史提到最前。
        item { AppEntryCard(title = tr("观看历史", "Watch history"), icon = Icons.Filled.History, onClick = { actions.openWatchHistory() }) }
        item { AppEntryCard(title = tr("我的关注", "Following"), icon = Icons.Filled.Group, onClick = { actions.openFollowing() }) }
        item { AppEntryCard(title = tr("我的收藏", "Favorites"), icon = Icons.Filled.Star, onClick = { actions.openFavorites() }) }
        item { AppEntryCard(title = tr("我的播放列表", "My playlists"), icon = Icons.Filled.VideoLibrary, onClick = { actions.openPlaylists() }) }
        item { AppEntryCard(title = tr("下载列表", "Downloads"), icon = Icons.Filled.Download, onClick = { actions.openDownloads() }) }

        // 设置入口（从顶栏齿轮移到这里，置于页面最底部）
        item {
            SettingsEntryRow(actions)
        }
    }
    FastScrollbarBasic(state = meListState, modifier = Modifier.fillMaxHeight())
    }
}
