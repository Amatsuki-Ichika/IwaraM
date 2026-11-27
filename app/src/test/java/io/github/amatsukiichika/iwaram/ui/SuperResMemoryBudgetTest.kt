package io.github.amatsukiichika.iwaram.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超分中间缓冲的显存准入。
 *
 * 每张中间缓冲都按**输入尺寸**分配 RGBA8（4 字节/像素），所以只卡输出像素不够：
 * 1080p + A4K_HQ（12 张）约 95MiB 必须放行；2560×1440 + 同一模型约 169MiB 必须拒绝，
 * 否则低端机上分配失败会把整条特效管线打成 7001（整片报错）。
 */
class SuperResMemoryBudgetTest {

    @Test
    fun `1080p with the heaviest model stays inside the budget`() {
        val bytes = intermediateTextureBytes(count = 12, inputWidth = 1920, inputHeight = 1080)
        assertEquals(12L * 1920L * 1080L * 4L, bytes)
        assertTrue("1080p × 12 张约 95MiB，应放行", bytes <= MAX_INTERMEDIATE_TEXTURE_BYTES)
    }

    @Test
    fun `widescreen 1080p with the heaviest model still fits`() {
        // 2560×1080 = 2.76MP，12 张约 127MiB，是 1080p 档里最宽的合法输入。
        val bytes = intermediateTextureBytes(count = 12, inputWidth = 2560, inputHeight = 1080)
        assertTrue("2560×1080 × 12 张约 127MiB，应放行", bytes <= MAX_INTERMEDIATE_TEXTURE_BYTES)
    }

    @Test
    fun `2k source with twelve intermediates is rejected`() {
        val bytes = intermediateTextureBytes(count = 12, inputWidth = 2560, inputHeight = 1440)
        assertTrue("2560×1440 × 12 张约 169MiB，必须拒绝", bytes > MAX_INTERMEDIATE_TEXTURE_BYTES)
    }

    @Test
    fun `light models still fit a 2k source`() {
        // DN_MEDIAN/DN_MODE 只有 1 张，2K 源约 14MiB，不能被误杀。
        val bytes = intermediateTextureBytes(count = 1, inputWidth = 2560, inputHeight = 1440)
        assertTrue(bytes <= MAX_INTERMEDIATE_TEXTURE_BYTES)
    }

    @Test
    fun `the estimate cannot overflow 32 bit ints`() {
        val bytes = intermediateTextureBytes(count = 12, inputWidth = 100_000, inputHeight = 100_000)
        assertEquals(12L * 100_000L * 100_000L * 4L, bytes)
    }
}
