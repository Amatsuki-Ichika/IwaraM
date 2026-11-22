package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * 一次「把视频帧画进按钮录制层」的绘制计划（单位：像素）。
 *
 * - [srcOffset] / [srcSize]：帧位图（已按最长边降采样）里的源矩形；
 * - [dstOffset] / [dstSize]：玻璃库给按钮录的那一层里的目标矩形。
 */
internal data class BackdropDrawPlan(
    val srcOffset: IntOffset,
    val srcSize: IntSize,
    val dstOffset: IntOffset,
    val dstSize: IntSize
)

/**
 * 把 SurfaceView 里的一帧画面映射到玻璃按钮的录制层上。
 *
 * 坐标系：玻璃库录制的层 = 按钮节点 + 两侧 padding（[layerWidth] / [layerHeight]），
 * 层的原点在窗口坐标里是「按钮左上角 − padding」；[surfaceInWindow] 是 SurfaceView
 * 在窗口里的矩形（= 视频画面实际显示的区域）。
 *
 * 步骤：① 由层尺寸与按钮尺寸反推 padding，得到层在窗口里的矩形；
 * ② 取层与画面矩形的交集（按钮压在黑边上时只画内容区，不把黑边采样进去）；
 * ③ 交集按画面缩放比换算成帧位图里的源矩形，同时换算成层内目标矩形。
 *
 * 纯函数（单测见 `PlayerBackdropMappingTest`）：交集为空、按钮/画面/帧/层任一尺寸非正
 * 都返回 null —— 调用方直接不画，按钮退回自己的半透明底色，不会崩也不会画错位。
 */
internal fun playerBackdropDrawPlan(
    consumerInWindow: IntRect,
    surfaceInWindow: IntRect,
    frameWidth: Int,
    frameHeight: Int,
    layerWidth: Int,
    layerHeight: Int
): BackdropDrawPlan? {
    if (consumerInWindow.width <= 0 || consumerInWindow.height <= 0) return null
    if (surfaceInWindow.width <= 0 || surfaceInWindow.height <= 0) return null
    if (frameWidth <= 0 || frameHeight <= 0 || layerWidth <= 0 || layerHeight <= 0) return null

    val padX = ((layerWidth - consumerInWindow.width) / 2).coerceAtLeast(0)
    val padY = ((layerHeight - consumerInWindow.height) / 2).coerceAtLeast(0)
    val layer = IntRect(
        left = consumerInWindow.left - padX,
        top = consumerInWindow.top - padY,
        right = consumerInWindow.left - padX + layerWidth,
        bottom = consumerInWindow.top - padY + layerHeight
    )

    val left = maxOf(layer.left, surfaceInWindow.left)
    val top = maxOf(layer.top, surfaceInWindow.top)
    val right = minOf(layer.right, surfaceInWindow.right)
    val bottom = minOf(layer.bottom, surfaceInWindow.bottom)
    if (right <= left || bottom <= top) return null

    // 帧位图是整块 SurfaceView 按同比例缩放后的结果 —— 两个轴各自换算。
    val scaleX = frameWidth.toFloat() / surfaceInWindow.width
    val scaleY = frameHeight.toFloat() / surfaceInWindow.height
    val srcLeft = ((left - surfaceInWindow.left) * scaleX).roundToInt().coerceIn(0, frameWidth - 1)
    val srcTop = ((top - surfaceInWindow.top) * scaleY).roundToInt().coerceIn(0, frameHeight - 1)
    val srcRight = ((right - surfaceInWindow.left) * scaleX).roundToInt().coerceIn(srcLeft + 1, frameWidth)
    val srcBottom = ((bottom - surfaceInWindow.top) * scaleY).roundToInt().coerceIn(srcTop + 1, frameHeight)

    return BackdropDrawPlan(
        srcOffset = IntOffset(srcLeft, srcTop),
        srcSize = IntSize(srcRight - srcLeft, srcBottom - srcTop),
        dstOffset = IntOffset(left - layer.left, top - layer.top),
        dstSize = IntSize(right - left, bottom - top)
    )
}
