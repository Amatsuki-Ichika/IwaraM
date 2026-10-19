package io.github.amatsukiichika.iwaramanager.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.amatsukiichika.iwaramanager.IwaraApp
import kotlinx.coroutines.delay

/**
 * 播放页的自包含效果集合（自 PlayerScreen() 抽出）。
 *
 * 抽出的都是只读写自身、不与其他 effect 共享时序的效果。需要读写页面局部状态、
 * 或与其他 effect 存在先后顺序依赖的（装片 effect、拖动取帧泵、播放器监听器、
 * 自动隐藏、观看进度落笔等）仍留在 PlayerScreen() 原地。
 */

private const val TAG = "PlayerScreen"

/** 画面停滞检测的轮询间隔。 */
private const val STALL_CHECK_INTERVAL_MS = 250L

/** 多久没有新帧就判定"画面定住"（正常播放时每帧都在刷新，不会命中）。 */
private const val STALL_THRESHOLD_MS = 700L

/** 自动微调时回拉多少毫秒（模拟手动轻拉进度，数值越小越无感）。 */
private const val STALL_NUDGE_MS = 80L

/**
 * 画面停滞检测 + 自动微调：不改变任何拖动/跳转行为，只在画面真的不动时出手。
 *   - 心跳：用播放器的逐帧回调记录"最近一次有帧要渲染"的时刻（正常播放时每帧都在刷新）；
 *   - 判定：开超分 + 正在播放 + 已连续 700ms 没有新帧，即为"声音在走、画面定住"；
 *   - 出手：朝当前进度回拉 80ms 再跳一次（80ms 肉眼看不出），并把心跳重置，避免连续触发。
 *   正常播放时这个分支不会命中，对正常使用无影响。
 *
 * 逐帧心跳 + 停滞判定合成一个单元，共享同一个时间戳（[lastFrameAtMs]）。
 * 心跳时间戳用普通 AtomicLong：只有停滞检测协程读它，无需 Compose 状态的快照写入。
 */
@Composable
internal fun PlayerFrameStallGuardEffect(player: ExoPlayer) {
    val lastFrameAtMs = androidx.compose.runtime.remember {
        java.util.concurrent.atomic.AtomicLong(0L)
    }
    DisposableEffect(player) {
        // 逐帧回调：只更新时间戳，不做别的（每帧一次，必须极轻）。
        player.setVideoFrameMetadataListener { _, _, _, _ ->
            lastFrameAtMs.set(android.os.SystemClock.elapsedRealtime())
        }
        // onDispose 里摘除监听器（参数非空，换空实现）。
        onDispose { player.setVideoFrameMetadataListener { _, _, _, _ -> } }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(STALL_CHECK_INTERVAL_MS)
            val now = android.os.SystemClock.elapsedRealtime()
            val lastFrame = lastFrameAtMs.get()
            val stalled = lastFrame > 0 && now - lastFrame > STALL_THRESHOLD_MS
            if (stalled && SuperResStatus.engine != null && player.playWhenReady &&
                player.playbackState == Player.STATE_READY
            ) {
                val rewindTargetMs = (player.currentPosition - STALL_NUDGE_MS).coerceAtLeast(0L)
                runCatching { player.seekTo(rewindTargetMs) }
                lastFrameAtMs.set(now)
                DiagLog.log(TAG, "画面停滞 ${STALL_THRESHOLD_MS}ms → 自动微调 seek（-${STALL_NUDGE_MS}ms）")
            }
        }
    }
}

/**
 * 切出页面（ON_PAUSE 早于 ON_STOP，减少退出自动暂停的延迟）时立即暂停，避免后台继续出声。
 */
@Composable
internal fun PlayerPauseOnLeaveEffect(player: Player) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * 全屏时按设置项切换方向并沉浸（隐藏状态栏/导航栏）。
 *
 * 智能模式不得回退到"跟随手机姿态"：尺寸未知时什么都不设（保持当前方向），
 * 不启用姿态感应；尺寸一到（playerAspectLandscape / videoWidth 变化）就重算并锁定方向。
 * 画幅来源的可靠度排序见下方注释，最可靠的是 `SuperResStatus.sourceWidth/Height`
 * （超分 GL 管线 configure() 拿到的解码帧真实尺寸）。
 */
@Composable
internal fun FullscreenOrientationEffect(
    isFullscreen: Boolean,
    player: Player,
    playerAspectLandscape: Boolean?,
    videoWidth: Int?,
    videoHeight: Int?,
    localVideoWidth: Int?,
    localVideoHeight: Int?,
    activity: Activity?,
    context: Context
) {
    LaunchedEffect(
        isFullscreen, playerAspectLandscape, videoWidth, videoHeight,
        // 超分实测帧尺寸也是画幅来源，一变就要重算方向。
        SuperResStatus.sourceWidth, SuperResStatus.sourceHeight
    ) {
        if (isFullscreen) {
            val pref = (context.applicationContext as IwaraApp).repository.getFullscreenOrientation()
            // ---- 画幅来源（按可靠度排序）----
            // 装了特效管线时播放器不报画面尺寸（`onVideoSizeChanged` 不触发，见上游 #2505 家族）：
            //   `playerSize=0x0 回调=nullxnull 轮询=null landscape=null 请求=null`
            //   ⇒ 画幅算不出来 ⇒ 不请求方向 ⇒ 一直竖屏。
            // 可靠的来源是 `SuperResStatus.sourceWidth/Height`：超分 GL 管线在 `configure()`
            // 里被 media3 喂进来的解码帧真实尺寸，在线/本地都有效、不依赖回调，本就是 Compose 状态。
            // 语义与 `player.videoSize` 一致（都是未应用旋转元数据的解码尺寸）。
            val srW = SuperResStatus.sourceWidth
            val srH = SuperResStatus.sourceHeight
            val bySuperRes = isLandscapeOrNull(srW, srH)
            val videoSize = player.videoSize
            val byPlayerDirect = isLandscapeOrNull(videoSize.width, videoSize.height)
            val byLocal = isLandscapeOrNull(localVideoWidth, localVideoHeight)
            val byCallback = isLandscapeOrNull(videoWidth, videoHeight)
            val landscape = bySuperRes ?: byPlayerDirect ?: playerAspectLandscape ?: byLocal ?: byCallback
            val target = resolveOrientation(pref, landscape)
            // 诊断：把判定的全部输入和将要请求的值都写进诊断日志，便于复现后复制日志定位。
            DiagLog.log(
                "Rotate",
                "进全屏 pref=$pref activity=${activity != null} 超分=${srW}x$srH " +
                    "playerSize=${videoSize.width}x${videoSize.height} 回调=${videoWidth}x$videoHeight " +
                    "本地=${localVideoWidth}x$localVideoHeight 轮询=$playerAspectLandscape " +
                    "landscape=$landscape 请求=$target 当前方向=${activity?.resources?.configuration?.orientation}"
            )
            if (target != null) activity?.requestedOrientation = target
            setSystemBarsHidden(activity, true)
            // 800ms 后回读：系统/ROM 是否接受了方向请求（1=竖 2=横 0=未定义）。
            // 直接在本 effect 里 delay（它就是挂起块）；key 变化会自动取消这次回读。
            delay(800)
            DiagLog.log(
                "Rotate",
                "800ms 后 实际方向=${activity?.resources?.configuration?.orientation} " +
                    "requested=${activity?.requestedOrientation}"
            )
        } else {
            // 诊断：退出全屏前先记下当时的实际方向，用于判断详情页的方向变化是否由旋转动画引起。
            DiagLog.log("Rotate", "退出全屏 退出前实际方向=${activity?.resources?.configuration?.orientation}")
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            setSystemBarsHidden(activity, false)
        }
    }
}

/** 沉浸式系统栏的显示/隐藏（全屏方向效果与系统栏配色效果共用）。 */
internal fun setSystemBarsHidden(activity: Activity?, hidden: Boolean) {
    if (activity == null) return
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (hidden) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
