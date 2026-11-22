package io.github.amatsukiichika.iwaram.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 播放页玻璃背景源的封面居中裁切（纯函数）。 */
class PlayerPosterBackdropTest {

    @Test
    fun `宽封面进正方形按钮 裁成中间竖条`() {
        // 1920x1080 进 100x100：目标 1:1，源 16:9 → 取中间 1080x1080，左偏移 420。
        assertArrayEquals(intArrayOf(420, 0, 1080, 1080), posterCropRect(1920, 1080, 100f, 100f))
    }

    @Test
    fun `竖封面进正方形按钮 裁成中间横条`() {
        // 1080x1920 进 100x100：取中间 1080x1080，上偏移 420。
        assertArrayEquals(intArrayOf(0, 420, 1080, 1080), posterCropRect(1080, 1920, 100f, 100f))
    }

    @Test
    fun `同比例不裁切`() {
        assertArrayEquals(intArrayOf(0, 0, 1600, 900), posterCropRect(1600, 900, 320f, 180f))
    }

    @Test
    fun `非法参数返回 null`() {
        assertNull(posterCropRect(0, 1080, 100f, 100f))
        assertNull(posterCropRect(1920, 1080, 0f, 100f))
        assertNull(posterCropRect(-1, 1080, 100f, 100f))
    }

    @Test
    fun `极端宽高比至少保留一像素`() {
        // 10000x1 进正方形：目标 1:1，只能从中间取 1x1（宽高都夹到下限 1），左偏移 (10000-1)/2=4999。
        val rect = posterCropRect(10_000, 1, 100f, 100f)!!
        assertArrayEquals(intArrayOf(4999, 0, 1, 1), rect)
    }
}
