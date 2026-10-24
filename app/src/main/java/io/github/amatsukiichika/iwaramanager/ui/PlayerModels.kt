package io.github.amatsukiichika.iwaramanager.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.amatsukiichika.iwaramanager.LocalAppRepository
import io.github.amatsukiichika.iwaramanager.data.IwaraClient
import io.github.amatsukiichika.iwaramanager.data.IwaraRepository
import io.github.amatsukiichika.iwaramanager.data.IwaraVideoItem
import io.github.amatsukiichika.iwaramanager.data.OkHttpDataSource
import io.github.amatsukiichika.iwaramanager.data.friendlyHttpError
import io.github.amatsukiichika.iwaramanager.data.VideoEntity
import io.github.amatsukiichika.iwaramanager.data.watchHistoryKey
import io.github.amatsukiichika.iwaramanager.ui.components.LocalDebugDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/* ---------- 播放页在线状态模型（原 PlayerScreen.kt 模型区块） ---------- */
/** 在线视频可选清晰度：name 为 API 档位名（如 Source/540/360），url 为对应 src.view。 */
data class QualityOption(val name: String, val url: String)

/**
 * 在线附加操作的网络门控。
 * 本地视频只有匹配到 iwara（带 iwaraId）才可能有在线功能，且必须等真正连上服务器才可用。
 */
enum class OnlineNetState {
    /** 还在连服务器 / 拉取在线状态 —— 操作区灰色显示「连接中」（不再整行隐藏）。 */
    LOADING,
    /** 在线可用。 */
    AVAILABLE,
    /** 连不上 / 拉取失败 —— 操作区**置灰不可点**。 */
    UNAVAILABLE
}

data class OnlineVideoExtras(
    val liked: Boolean,
    val mediaUrl: String? = null,
    val qualities: List<QualityOption> = emptyList(),
    val currentQuality: String? = null,
    val onToggleLike: () -> Unit,
    // 「评论」入口收在详情区药丸条 tab，不单独提供按钮/二级页。
    /** 在线视频的「下载」入口；本地视频（已下载）用 [showDownload]=false 隐藏该按钮。 */
    val onDownload: () -> Unit = {},
    /**
     * 「收藏」按钮（网站"添加到"）—— 打开加入播放列表面板
     * （复用动作面板 PICK_PLAYLIST 模式）；null = 不显示按钮（无 iwara id 的本地视频）。
     */
    val onAddToPlaylist: (() -> Unit)? = null,
    val onRetry: () -> Unit = {},
    val onSelectQuality: (QualityOption) -> Unit = {},
    /** 网络门控：LOADING 灰「连接中」、UNAVAILABLE 灰「未连接」；在线视频恒 AVAILABLE。 */
    val netState: OnlineNetState = OnlineNetState.AVAILABLE,
    /** 在线视频简介（网站 body 字段）；本地视频为 null（无简介数据）。 */
    val description: String? = null,
    /** 在线作者头像 URL（作者行展示；本地视频为 null 走图标）。 */
    val avatarUrl: String? = null,
    /** 关注作者：为 true 时在作者行显示关注按钮（需已登录且有作者 UUID）。 */
    val canFollow: Boolean = false,
    val following: Boolean = false,
    val onToggleFollow: () -> Unit = {}
)

/**
 * 「相关推荐」条目：在线（related 接口）与本地（同作者/同标签）两种来源，
 * 类型安全分流、不做 Any 载荷；展示字段由 UI 各自映射。
 */
sealed interface RelatedVideo {
    /** 在线推荐：点击后走 [io.github.amatsukiichika.iwaramanager.MainActivity] 的既有 resolveAndPlay 通路（本地已下载则直接播本地）。 */
    data class Online(val item: IwaraVideoItem) : RelatedVideo

    /** 本地推荐：点击后与本地库点卡片同通路。 */
    data class Local(val video: VideoEntity) : RelatedVideo
}

// PlayerView 复用池（PlayerViewPool）已随播放页 UI 树迁至 PlayerScreenContent.kt。
