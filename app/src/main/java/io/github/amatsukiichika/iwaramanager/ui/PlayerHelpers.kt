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

private const val TAG = "PlayerScreen"
/**
 * 组装播放错误面板的两轨文案（自 onPlayerError 抽出，错误回调只留分支骨架）。
 * [chain] = 错误原因链，[chainMsg] = 已拼好的一行链串，[httpCode] = 解包出的 HTTP 状态码（可空）。
 */
internal fun buildPlayerErrorUi(
    error: PlaybackException,
    chain: List<Throwable>,
    chainMsg: String,
    httpCode: Int?
): PlayerErrorUi {
    val httpMsg = httpCode?.let { "，服务器返回 HTTP $it" } ?: ""
    // 超分失败的原因 + 最深一帧调用栈直接拼进错误面板，无需额外开菜单即可看到
    val srReason = SuperResStatus.reason?.let { "；超分原因：$it" } ?: ""
    val stackMsg = chain.lastOrNull()?.stackTrace?.firstOrNull()
        ?.let { "（${it.className}.${it.methodName}:${it.lineNumber}）" } ?: ""
    // detail 轨 = 原样全串（debug 模式上屏；DiagLog 侧照旧记录）；
    // friendly 轨 = 按 HTTP 码 / media3 错误域给一句人话（普通模式）。
    val detail = "播放出错（${error.errorCodeName}$httpMsg，代码 ${error.errorCode}）$srReason$stackMsg\n原因链：$chainMsg"
    val friendly = when {
        httpCode == 403 -> "播放地址被拒绝访问（可能已失效），请重试"
        httpCode != null -> friendlyHttpError(httpCode)
        // 2001/2002 IO 连接域 + 1003 超时（media3 1.4.1 无 ERROR_CODE_IO_NETWORK_READ_FAILED）
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> "网络不稳定，请重试"
        // 3000-3999 = media3 解码错误域
        error.errorCode in 3000..3999 -> "该视频编码无法播放"
        else -> "播放出错，请重试"
    }
    return PlayerErrorUi(friendly, detail)
}

/**
 * 全屏方向解析（自全屏 effect 抽出的纯函数，单测见 PlayerOrientationTest）。
 *
 * 设置项四值：`auto`(自动) / `smart`(智能识别) / `landscape` / `portrait`；
 * - 「智能识别」用**强制**方向常量（**不带 `USER_` 前缀**）—— 带 `USER_` 的版本
 *   会**尊重系统"自动旋转"开关**，关掉自动旋转时不生效，表现为"一直竖屏"。
 *   既然是"按视频画幅锁定"，就必须强制；
 * - 「自动」保持 `SCREEN_ORIENTATION_SENSOR`（跟随系统/姿态）；
 * - 「横向 / 竖向」保持 `USER_*`（尊重系统开关）；
 * - 画幅未知（[isLandscape] = null）返回 null = 什么都不设，等画面尺寸到了再重算。
 */
internal fun resolveOrientation(pref: String, isLandscape: Boolean?): Int? = when (pref) {
    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    "smart" -> when (isLandscape) {
        true -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        false -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        null -> null
    }
    else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
}

/**
 * 7001/1003/1000 自动恢复共用的"从当前进度重装片"。
 * 只用于管线级故障后**保住旧实例**的场景；切 SR/模型一律走装片 effect 的 epoch 重建 ——
 * 两条恢复通路对同一实例混用，会造成重复装片（在线源连缓冲两次）。
 */
internal fun recoverAtCurrentPosition(player: ExoPlayer, url: String) {
    runCatching {
        val pos = player.currentPosition
        val item = MediaItem.fromUri(Uri.parse(url))
        if (pos > 0) player.setMediaItem(item, pos) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }.onFailure { DiagLog.log(TAG, "恢复播放失败: ${it.message}") }
}

/**
 * 观看进度落笔的公共收口（暂停那一拍与 5 秒心跳同款样板）。
 * key 推不出（无 iwaraId 且无 localUri）直接跳过；fire-and-forget 由仓库私有 IO 作用域承担。
 */
internal fun IwaraRepository.saveWatchProgress(key: String?, player: Player) {
    if (key == null) return
    updateWatchProgress(key, player.safePositionMs(), player.safeDurationMs())
}


internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 宽高都有效时判断"是否横向"；任一无效（null/≤0）返回 null = 画幅未知。
 * （internal：全屏方向 effect 已抽到 PlayerScreenEffects.kt。）
 */
internal fun isLandscapeOrNull(w: Int?, h: Int?): Boolean? =
    if (w != null && h != null && w > 0 && h > 0) w >= h else null

/** 进度取值防护：播放器偶发负值钳 0。 */
internal fun Player.safePositionMs(): Long = currentPosition.coerceAtLeast(0L)

/** 时长取值防护：时长未知（C.TIME_UNSET/≤0）归 0。 */
internal fun Player.safeDurationMs(): Long = duration.takeIf { it > 0 } ?: 0L
