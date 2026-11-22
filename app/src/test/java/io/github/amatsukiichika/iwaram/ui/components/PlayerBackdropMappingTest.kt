package io.github.amatsukiichika.iwaram.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放页圆钮的实时玻璃映射：把 SurfaceView 的一帧画面按按钮位置裁进录制层。
 * 纯函数单测（不碰 Android 运行时）。
 */
class PlayerBackdropMappingTest {

    /** 画面完全盖住按钮：整个按钮方块都取帧，源矩形按帧缩放比换算。 */
    @Test
    fun fullCoverMapsWholeButton() {
        val plan = playerBackdropDrawPlan(
            consumerInWindow = IntRect(100, 40, 144, 84),
            surfaceInWindow = IntRect(0, 0, 400, 200),
            frameWidth = 200,
            frameHeight = 100,
            layerWidth = 44,
            layerHeight = 44
        )!!
        assertEquals(IntOffset(50, 20), plan.srcOffset)
        assertEquals(IntSize(22, 22), plan.srcSize)
        assertEquals(IntOffset(0, 0), plan.dstOffset)
        assertEquals(IntSize(44, 44), plan.dstSize)
    }

    /** 库给录制层加了 padding 时：层比按钮大，目标矩形要整体偏移 padding。 */
    @Test
    fun paddedLayerOffsetsDestination() {
        val plan = playerBackdropDrawPlan(
            consumerInWindow = IntRect(100, 40, 144, 84),
            surfaceInWindow = IntRect(0, 0, 400, 200),
            frameWidth = 200,
            frameHeight = 100,
            layerWidth = 48,
            layerHeight = 48
        )!!
        // 层的 padding 也属于录制范围：源取 98,38 起 48x48，目标铺满整层。
        assertEquals(IntOffset(49, 19), plan.srcOffset)
        assertEquals(IntSize(24, 24), plan.srcSize)
        assertEquals(IntOffset(0, 0), plan.dstOffset)
        assertEquals(IntSize(48, 48), plan.dstSize)
    }

    /** 按钮压在画面下边缘外：只画相交的那一条，不把画面外的部分采进来。 */
    @Test
    fun clippedAtBottomEdge() {
        val plan = playerBackdropDrawPlan(
            consumerInWindow = IntRect(100, 40, 144, 84),
            surfaceInWindow = IntRect(0, 0, 400, 60),
            frameWidth = 200,
            frameHeight = 100,
            layerWidth = 44,
            layerHeight = 44
        )!!
        assertEquals(IntOffset(50, 67), plan.srcOffset)
        assertEquals(IntSize(22, 33), plan.srcSize)
        assertEquals(IntOffset(0, 0), plan.dstOffset)
        assertEquals(IntSize(44, 20), plan.dstSize)
    }

    /** 按钮一半在画面上方（顶栏贴边）：目标矩形从层内 y=20 开始画。 */
    @Test
    fun clippedAtTopEdge() {
        val plan = playerBackdropDrawPlan(
            consumerInWindow = IntRect(100, -20, 144, 24),
            surfaceInWindow = IntRect(0, 0, 400, 200),
            frameWidth = 200,
            frameHeight = 100,
            layerWidth = 44,
            layerHeight = 44
        )!!
        assertEquals(IntOffset(50, 0), plan.srcOffset)
        assertEquals(IntSize(22, 12), plan.srcSize)
        assertEquals(IntOffset(0, 20), plan.dstOffset)
        assertEquals(IntSize(44, 24), plan.dstSize)
    }

    /** SurfaceView 自身有内缩（窗口坐标不贴 0）：源矩形要扣掉画面原点。 */
    @Test
    fun surfaceOffsetIsSubtracted() {
        val plan = playerBackdropDrawPlan(
            consumerInWindow = IntRect(100, 40, 144, 84),
            surfaceInWindow = IntRect(0, 20, 400, 180),
            frameWidth = 200,
            frameHeight = 80,
            layerWidth = 44,
            layerHeight = 44
        )!!
        assertEquals(IntOffset(50, 10), plan.srcOffset)
        assertEquals(IntSize(22, 22), plan.srcSize)
        assertEquals(IntOffset(0, 0), plan.dstOffset)
        assertEquals(IntSize(44, 44), plan.dstSize)
    }

    /** 按钮整个在画面外（黑边/被裁掉）：不画。 */
    @Test
    fun noIntersectionReturnsNull() {
        assertNull(
            playerBackdropDrawPlan(
                consumerInWindow = IntRect(0, -100, 44, -56),
                surfaceInWindow = IntRect(0, 0, 400, 200),
                frameWidth = 200,
                frameHeight = 100,
                layerWidth = 44,
                layerHeight = 44
            )
        )
    }

    /** 非法尺寸（尚未测量 / 无帧）一律不画，调用方退回半透明底色。 */
    @Test
    fun invalidSizesReturnNull() {
        assertNull(
            playerBackdropDrawPlan(
                consumerInWindow = IntRect(0, 0, 0, 44),
                surfaceInWindow = IntRect(0, 0, 400, 200),
                frameWidth = 200,
                frameHeight = 100,
                layerWidth = 44,
                layerHeight = 44
            )
        )
        assertNull(
            playerBackdropDrawPlan(
                consumerInWindow = IntRect(0, 0, 44, 44),
                surfaceInWindow = IntRect(0, 0, 400, 200),
                frameWidth = 0,
                frameHeight = 100,
                layerWidth = 44,
                layerHeight = 44
            )
        )
        assertNull(
            playerBackdropDrawPlan(
                consumerInWindow = IntRect(0, 0, 44, 44),
                surfaceInWindow = IntRect(10, 10, 10, 10),
                frameWidth = 200,
                frameHeight = 100,
                layerWidth = 44,
                layerHeight = 44
            )
        )
    }

    /** 按钮只有 1px 落在画面里：源矩形至少 1px，不能取成 0（drawImage 会崩）。 */
    @Test
    fun onePixelIntersectionKeepsAtLeastOneSourcePixel() {
        val plan = playerBackdropDrawPlan(
            consumerInWindow = IntRect(1079, 300, 1123, 344),
            surfaceInWindow = IntRect(0, 0, 1080, 608),
            frameWidth = 360,
            frameHeight = 203,
            layerWidth = 44,
            layerHeight = 44
        )!!
        assertEquals(359, plan.srcOffset.x)
        assertEquals(1, plan.srcSize.width)
        assertEquals(100, plan.srcOffset.y)
        assertEquals(15, plan.srcSize.height)
        assertEquals(IntOffset(0, 0), plan.dstOffset)
        assertEquals(IntSize(1, 44), plan.dstSize)
    }
}
