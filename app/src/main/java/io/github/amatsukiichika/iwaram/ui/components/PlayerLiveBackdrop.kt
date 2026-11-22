package io.github.amatsukiichika.iwaram.ui.components

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** 抓帧位图的最长边（像素）：磨砂只需要低频色块，再大只是白烧一次 GPU 回读。 */
private const val CAPTURE_MAX_DIMENSION = 360

/** 抓帧间隔：约 10 帧/秒 —— 视频整帧变化在模糊后是连续的，而回读频率压到很低。 */
private const val CAPTURE_INTERVAL_MS = 100L

/** 播放器还没就绪（SurfaceView 未 attach / 尺寸为 0）时的重试间隔。 */
private const val CAPTURE_RETRY_MS = 160L

/** 一帧视频画面 + 它的 SurfaceView 在窗口里的矩形（px）。 */
internal data class LiveVideoFrame(
    val image: ImageBitmap,
    val surfaceInWindow: IntRect
)

/**
 * 播放页圆钮的液态玻璃背景源：**实时**视频画面（不再用封面近似）。
 *
 * 为什么不能录制：视频画在 `PlayerView` 的 SurfaceView 上，由系统合成器直接上屏，
 * Compose 的 RenderNode 录制与 `drawBackdrop` 都拿不到它的像素（录出来是黑洞）。
 * 这里改用 `PixelCopy` 从 SurfaceView 回读一帧降采样位图，再按按钮的窗口坐标把它画进
 * 玻璃库给按钮录的那一层 —— 之后 blur / 边缘受光都发生在这层真实画面上，
 * 材质与底栏、回顶按钮完全同源（同一 `GlassMaterial` 与 `glassBarEffects` 配方）。
 *
 * 开销边界：只在按钮可见（`active`）时抓帧、约 10 fps、最长边 360px（约 0.5 MB/帧），
 * 且只在液态玻璃档 + API 31+ 才有对象；其余情况返回 null，按钮自动退回色罩路径。
 */
internal class PlayerLiveBackdrop : Backdrop {

    // neverEqualPolicy：帧位图是**同一块 Bitmap 反复重写**，新旧帧对象可能相等；
    //   默认的结构相等策略会因此跳过通知，圆钮就会冻在第一帧。这里强制每次发布都失效重画。
    private val frameState = mutableStateOf<LiveVideoFrame?>(null, neverEqualPolicy())

    /** 每帧画面的位置都不一样，需要库把消费节点的 LayoutCoordinates 传进来。 */
    override val isCoordinatesDependent: Boolean get() = true

    fun publishFrame(frame: LiveVideoFrame?) {
        frameState.value = frame
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val frame = frameState.value ?: return
        if (coordinates == null || !coordinates.isAttached) return
        // 消费节点（圆钮）在窗口里的矩形：库录制层与按钮同心，差值即 padding。
        val bounds = coordinates.boundsInWindow()
        val plan = playerBackdropDrawPlan(
            consumerInWindow = IntRect(
                bounds.left.roundToInt(),
                bounds.top.roundToInt(),
                bounds.right.roundToInt(),
                bounds.bottom.roundToInt()
            ),
            surfaceInWindow = frame.surfaceInWindow,
            frameWidth = frame.image.width,
            frameHeight = frame.image.height,
            layerWidth = size.width.roundToInt(),
            layerHeight = size.height.roundToInt()
        ) ?: return
        drawImage(
            image = frame.image,
            srcOffset = plan.srcOffset,
            srcSize = plan.srcSize,
            dstOffset = plan.dstOffset,
            dstSize = plan.dstSize
        )
    }
}

/**
 * 取播放页圆钮的实时玻璃背景源；非液态玻璃档 / API < 31 返回 null。
 *
 * @param playerView 读当前 PlayerView（组合期传入，随时可能从无到有）。
 * @param active 圆钮是否在屏（控制条可见）；false 时停止抓帧并清掉旧帧，不再有一条定时回读。
 */
@OptIn(UnstableApi::class)
@Suppress("UnsafeOptInUsageError") // lint 追踪不到 @Composable 函数体内的 @OptIn，兜底抑制
@Composable
internal fun rememberPlayerLiveBackdrop(
    playerView: () -> PlayerView?,
    active: Boolean
): Backdrop? {
    if (!glassBackdropAvailable()) return null
    val backdrop = remember { PlayerLiveBackdrop() }
    val currentPlayerView by rememberUpdatedState(playerView)
    LaunchedEffect(active, backdrop) {
        if (!active) return@LaunchedEffect
        val handler = Handler(Looper.getMainLooper())
        var bitmap: Bitmap? = null
        var image: ImageBitmap? = null
        try {
            while (isActive) {
                val view = currentPlayerView()?.videoSurfaceView as? SurfaceView
                if (view == null || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
                    delay(CAPTURE_RETRY_MS)
                    continue
                }
                val longest = max(view.width, view.height)
                val scale = CAPTURE_MAX_DIMENSION.toFloat() / longest
                val frameWidth = (view.width * scale).roundToInt().coerceIn(1, CAPTURE_MAX_DIMENSION)
                val frameHeight = (view.height * scale).roundToInt().coerceIn(1, CAPTURE_MAX_DIMENSION)
                val current = bitmap
                if (current == null || current.width != frameWidth || current.height != frameHeight) {
                    bitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
                    image = bitmap.asImageBitmap()
                }
                val dest = bitmap ?: continue
                val frameImage = image ?: continue
                // SurfaceView 的 PixelCopy 会把源画面缩放到目标位图尺寸（官方文档明示），
                // 因此降采样在拷贝阶段完成，不用再自己缩一次。
                val captured = runCatching {
                    suspendCancellableCoroutine { continuation ->
                        PixelCopy.request(view, dest, { result ->
                            if (continuation.isActive) {
                                continuation.resume(result == PixelCopy.SUCCESS)
                            }
                        }, handler)
                    }
                }.getOrDefault(false)
                if (captured) {
                    val location = IntArray(2)
                    view.getLocationInWindow(location)
                    backdrop.publishFrame(
                        LiveVideoFrame(
                            image = frameImage,
                            surfaceInWindow = IntRect(
                                location[0],
                                location[1],
                                location[0] + view.width,
                                location[1] + view.height
                            )
                        )
                    )
                }
                delay(CAPTURE_INTERVAL_MS)
            }
        } finally {
            // 离开组合/停止抓帧时立刻松掉位图引用（按钮随后可能被绘制一帧，不能再画旧帧）。
            backdrop.publishFrame(null)
        }
    }
    return backdrop
}
