package io.github.amatsukiichika.iwaram.ui.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.kyant.backdrop.Backdrop
import kotlin.math.roundToInt

/**
 * 播放页圆钮的液态玻璃背景源：用**视频封面**当底。
 *
 * 为什么不用视图级录制：视频画面是 `PlayerView` 里的 SurfaceView，Compose/RenderNode 录制不到
 * 它的像素（录出来是黑洞），而播放页又要让返回/更多按钮跟底栏、回顶按钮走同一份磨砂材质。
 * 封面是这支视频的画面缩影：把它缩到按钮大小、交给库做 blur + lens，观感就是"按钮后面是视频画面"。
 * 加载失败 / 非磨砂档 / API < 31 时返回 null，[GlassRoundButton] 自动退回半透明路径。
 *
 * 只在绘制相位用 [PosterBackdrop.drawBackdrop] 把封面铺进库给的本层，不参与布局，不录制任何子树。
 */
internal class PosterBackdrop(private val image: ImageBitmap) : Backdrop {

    /** 源就是本按钮自己的封面铺底，不依赖与其它节点的坐标映射。 */
    override val isCoordinatesDependent: Boolean get() = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val rect = posterCropRect(image.width, image.height, size.width, size.height) ?: return
        drawImage(
            image = image,
            srcOffset = IntOffset(rect[0], rect[1]),
            srcSize = IntSize(rect[2], rect[3]),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(
                size.width.roundToInt().coerceAtLeast(1),
                size.height.roundToInt().coerceAtLeast(1)
            )
        )
    }
}

/**
 * 封面「居中裁切铺满」的源矩形（左、上、宽、高，单位像素）；参数非法时返回 null。
 *
 * 纯函数（单测见 `PlayerPosterBackdropTest`）：按钮是正方形、封面是各种比例，
 * 若直接拉伸，磨砂边缘的折射会把变形看出来；居中裁切只取中间那块。
 */
internal fun posterCropRect(
    imageWidth: Int,
    imageHeight: Int,
    dstWidth: Float,
    dstHeight: Float
): IntArray? {
    if (imageWidth <= 0 || imageHeight <= 0 || dstWidth <= 0f || dstHeight <= 0f) return null
    val dstAspect = dstWidth / dstHeight
    val srcAspect = imageWidth.toFloat() / imageHeight
    var w = imageWidth
    var h = imageHeight
    if (srcAspect > dstAspect) {
        w = (imageHeight * dstAspect).roundToInt()
    } else {
        h = (imageWidth / dstAspect).roundToInt()
    }
    w = w.coerceIn(1, imageWidth)
    h = h.coerceIn(1, imageHeight)
    return intArrayOf((imageWidth - w) / 2, (imageHeight - h) / 2, w, h)
}

/**
 * 取封面 bitmap 并包成播放页专用的玻璃背景源。
 *
 * 只允许磨砂档加载：半透明/关档不产生任何网络与解码开销（[GlassRoundButton] 那时也走不到这里）。
 * 封面已在 Coil 缓存里时这一步只命中内存/磁盘；没有封面（部分本地视频）返回 null。
 *
 * @param model 与入场封面同一个 model（`localThumbnailPath ?: thumbnailUrl`）。
 */
@Composable
internal fun rememberPosterBackdrop(model: Any?): Backdrop? {
    val settings = LocalGlassSettings.current
    val liquid = settings.mode.isLiquid && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    var image by remember(model) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(model, liquid) {
        image = null
        if (!liquid || model == null) return@LaunchedEffect
        image = runCatching {
            val request = ImageRequest.Builder(context)
                .data(model)
                .allowHardware(false)
                .size(360)
                .build()
            context.imageLoader.execute(request).drawable?.toBitmap()?.asImageBitmap()
        }.getOrNull()
    }
    val bitmap = image ?: return null
    return remember(bitmap) { PosterBackdrop(bitmap) }
}
