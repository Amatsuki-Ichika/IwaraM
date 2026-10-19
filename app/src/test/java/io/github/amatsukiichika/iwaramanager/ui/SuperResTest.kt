package io.github.amatsukiichika.iwaramanager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超分方案选择的纯函数回归用例。
 *
 * 档位基于 Anime4K 官方 v4 GLSL（MIT）移植与 media3 自带 Lanczos，按场景命名。
 * 这里回归三件事：① 旧偏好值（"A"/"B"/"C"、空、乱值）能安全迁移；
 * ② 放大倍率收敛规则（不缩小、不超过屏幕、总像素封顶）；
 * ③ chip 文案恒为短串、倍率只在 >1 时展示。
 */
class SuperResTest {

    // ---------- 旧偏好值迁移 ----------

    @Test
    fun `legacy A B C values migrate to the new default`() {
        assertEquals(SuperRes.DEFAULT, SuperRes.normalize("A"))
        assertEquals(SuperRes.DEFAULT, SuperRes.normalize("B"))
        assertEquals(SuperRes.DEFAULT, SuperRes.normalize("C"))
    }

    @Test
    fun `null blank and garbage migrate to the new default`() {
        assertEquals(SuperRes.DEFAULT, SuperRes.normalize(null))
        assertEquals(SuperRes.DEFAULT, SuperRes.normalize(""))
        assertEquals(SuperRes.DEFAULT, SuperRes.normalize("whatever"))
    }

    @Test
    fun `known new values survive normalize`() {
        SuperRes.models.forEach { assertEquals(it, SuperRes.normalize(it)) }
        assertEquals(SuperRes.A4K_3D, SuperRes.normalize(SuperRes.A4K_3D))
        assertEquals(SuperRes.LANCZOS, SuperRes.normalize(SuperRes.LANCZOS))
    }

    // ---------- 放大倍率收敛（倍率吸附到 2× / 1.5× / 1.25× / 1×）----------

    /** 倍率必须落在允许的档位上（不再出现"屏幕宽÷源宽"那种任意小数）。 */
    private fun assertSnappedStep(inputW: Int, inputH: Int, outW: Int, screenW: Int, screenH: Int) {
        assertTrue("宽度不是放大：$outW < $inputW", outW >= inputW)
        assertTrue("超出屏宽 $screenW：$outW", outW <= screenW)
        val ratio = outW.toFloat() / inputW
        assertTrue(
            "倍率 $ratio 不在 {1, 1.25, 1.5, 2} 里（输入 ${inputW}x$inputH 屏 ${screenW}x$screenH）",
            listOf(1f, 1.25f, 1.5f, 2f).any { kotlin.math.abs(it - ratio) < 3e-3f }
        )
    }

    @Test
    fun `upscale is capped at 2x`() {
        // 320x180 的小源，屏幕足够大 → 应该给满 2×
        val s = SuperRes.scaledSize(320, 180, 1080, 2400)
        assertEquals(640, s.width)
        assertEquals(360, s.height)
    }

    @Test
    fun `upscale snaps down to the largest step that fits the screen`() {
        // 854x480 在 1080 宽的屏上：2× = 1708、1.5× = 1281 都超出屏宽 → 吸附到 1.25×
        val s = SuperRes.scaledSize(854, 480, 1080, 2400)
        assertTrue("width ${s.width} > 1080", s.width <= 1080)
        assertEquals(1068, s.width) // 854 * 1.25 = 1067.5 → 1068
        assertSnappedStep(854, 480, s.width, 1080, 2400)
    }

    /**
     * 竖屏 1080p（MMD 主流分辨率）在 1272 宽的屏上不能再算出 1272x2262。
     * 若取 min(2, 1272/1080=1.178, …) → 输出 1272×2262（1272 是屏幕物理宽），
     * 既不是真实分辨率也不是整倍数。此处必须吸附到 1×。
     */
    @Test
    fun `portrait 1080p snaps to 1x instead of a screen width derived oddity`() {
        val s = SuperRes.scaledSize(1080, 1920, 1272, 2772)
        assertEquals(1080, s.width)
        assertEquals(1920, s.height)
    }

    /** 720p 竖屏在 1272 宽的屏上吸附到 1.5× → 1080x1920，正是能一眼看懂的真实分辨率。 */
    @Test
    fun `portrait 720p snaps to 1_5x and lands on a real resolution`() {
        val s = SuperRes.scaledSize(720, 1280, 1272, 2772)
        assertEquals(1080, s.width)
        assertEquals(1920, s.height)
    }

    @Test
    fun `upscale never shrinks a large source`() {
        // 3840x2160 的 4K 源，屏幕才 1080 → 若允许缩小会反过来丢分辨率，必须收敛到 1×
        val s = SuperRes.scaledSize(3840, 2160, 1080, 2400)
        assertTrue("width ${s.width} < 3840", s.width >= 3840)
        assertTrue("height ${s.height} < 2160", s.height >= 2160)
    }

    /** 1440p 源（面积已超 2K 上限）也绝不能被缩小，且倍率仍落在档位上。 */
    @Test
    fun `1440p source is never shrunk`() {
        val s = SuperRes.scaledSize(2560, 1440, 1272, 2772)
        assertTrue("width ${s.width} < 2560", s.width >= 2560)
        assertTrue("height ${s.height} < 1440", s.height >= 1440)
    }

    @Test
    fun `total pixels are capped so low end devices do not stall`() {
        // 1280x720 的 2× = 2560x1440 恰好在 2560x1440 上限内
        val s = SuperRes.scaledSize(1280, 720, 3840, 2160)
        assertEquals(2560, s.width)
        assertEquals(1440, s.height)
        // 更大源被面积封顶：输出面积不得超过上限
        val big = SuperRes.scaledSize(1440, 1080, 3840, 2160)
        assertTrue(big.width.toLong() * big.height <= 2560L * 1440L)
    }

    /**
     * 一组常见源分辨率在常见屏幕上的输出都必须满足：**绝不缩小**；
     * 一旦真的放大，就必须同时满足不超屏、倍率吸附到档位、面积不超 2K 上限。
     *
     * 注意"不缩小"优先于"不超屏"：1280×720 的源放在 1272 宽的屏上时，放大 1.25× 会超屏、
     * 所以只能是 1×（原样输出、宽度略大于屏幕，交给 SurfaceView 缩放）——这正是
     * `upscale never shrinks a large source` 立下的规矩。
     */
    @Test
    fun `common sources always produce a snapped in budget size`() {
        val screens = listOf(1080 to 2400, 1272 to 2772, 1440 to 3200, 1080 to 1920)
        val sources = listOf(
            320 to 180, 640 to 360, 854 to 480, 960 to 540, 1280 to 720,
            720 to 1280, 1080 to 1920, 1920 to 1080, 1440 to 1080, 2560 to 1440
        )
        for ((sw, sh) in screens) {
            for ((iw, ih) in sources) {
                val s = SuperRes.scaledSize(iw, ih, sw, sh)
                assertTrue("[${iw}x$ih @ ${sw}x$sh] 缩小了", s.width >= iw && s.height >= ih)
                val upscaled = s.width > iw || s.height > ih
                if (!upscaled) continue
                assertTrue("[${iw}x$ih @ ${sw}x$sh] 放大后超屏宽 ${s.width} > $sw", s.width <= sw)
                assertTrue("[${iw}x$ih @ ${sw}x$sh] 放大后超屏高 ${s.height} > $sh", s.height <= sh)
                assertTrue(
                    "[${iw}x$ih @ ${sw}x$sh] 放大后面积超限",
                    s.width.toLong() * s.height <= 2560L * 1440L
                )
                assertSnappedStep(iw, ih, s.width, sw, sh)
            }
        }
    }

    @Test
    fun `degenerate input is tolerated`() {
        val s = SuperRes.scaledSize(0, 0, 1080, 2400)
        assertTrue(s.width >= 2)
        assertTrue(s.height >= 2)
    }

    // ---------- 诊断 / 菜单里的分辨率文案 ----------

    /**
     * 源/输出分辨率只认 configure() 的实测值，菜单里的只读说明采用
     * 「源 WxH · 输出 WxH · 倍率/不放大」这种能自证的格式。
     */
    @Test
    fun `resolution info reports measured source and output`() {
        SuperResStatus.reset()
        SuperResStatus.sourceWidth = 1080
        SuperResStatus.sourceHeight = 1920
        SuperResStatus.outputWidth = 1080
        SuperResStatus.outputHeight = 1920
        assertEquals("1080x1920", SuperResStatus.sourceText)
        assertEquals("1080x1920", SuperResStatus.outputText)
        // 只修复档（不放大）
        assertEquals(
            "源 1080x1920 · 输出 1080x1920 · 不放大",
            SuperResStatus.resolutionInfo(isUpscalingModel = false, scale = 1f)
        )
        // 放大档
        SuperResStatus.outputWidth = 2160
        SuperResStatus.outputHeight = 3840
        assertEquals(
            "源 1080x1920 · 输出 2160x3840 · 2×",
            SuperResStatus.resolutionInfo(isUpscalingModel = true, scale = 2f)
        )
        // 尺寸未知时整条不显示（宁可没有，也不要显示错的）
        SuperResStatus.reset()
        assertEquals(null, SuperResStatus.sourceText)
        assertEquals(null, SuperResStatus.resolutionInfo(isUpscalingModel = true, scale = 2f))
    }

    /** 只修复 / 只降噪的档不放大（`isUpscaling` 的语义必须稳定 —— 它同时决定 configure 的输出尺寸）。 */
    @Test
    fun `only fix and denoise models do not upscale`() {
        listOf(
            SuperRes.A4K_RST, SuperRes.A4K_SOFT,
            SuperRes.DN_MEAN, SuperRes.DN_MEDIAN, SuperRes.DN_MODE
        ).forEach { assertTrue("$it 不应放大", !SuperRes.isUpscaling(it)) }
        listOf(
            SuperRes.A4K_3D, SuperRes.A4K_BAL, SuperRes.A4K_HQ,
            SuperRes.A4K_UP_S, SuperRes.LANCZOS
        ).forEach { assertTrue("$it 应放大", SuperRes.isUpscaling(it)) }
    }

    // ---------- chip 文案 ----------

    @Test
    fun `chip shows a short label when enabled`() {
        assertEquals("SR·3D", SuperResStatus.chipLabel(true, SuperRes.A4K_3D, null, false))
        // 倍率只在确实 >1 时展示
        assertEquals("SR·3D 2×", SuperResStatus.chipLabel(true, SuperRes.A4K_3D, 2.0f, false))
        assertEquals("SR·A4K 1.3×", SuperResStatus.chipLabel(true, SuperRes.A4K_BAL, 1.3f, false))
        // 收敛到 1×（源已够大）时不再展示"1×"，避免误导
        assertEquals("SR·LZ", SuperResStatus.chipLabel(true, SuperRes.LANCZOS, 1.0f, false))
    }

    @Test
    fun `chip shows off and degraded states`() {
        assertEquals("SR", SuperResStatus.chipLabel(false, SuperRes.A4K_3D, 2.0f, false))
        assertEquals("SR 未生效", SuperResStatus.chipLabel(true, SuperRes.A4K_3D, null, true))
    }

    @Test
    fun `format scale keeps one decimal only when needed`() {
        assertEquals("2×", SuperResStatus.formatScale(2.0f))
        assertEquals("1.3×", SuperResStatus.formatScale(1.3333f))
        assertEquals("1.7×", SuperResStatus.formatScale(1.69f))
    }
}
