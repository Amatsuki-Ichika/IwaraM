package io.github.amatsukiichika.iwaram.ui

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.PassthroughShaderProgram
import io.github.amatsukiichika.iwaram.i18n.tr
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "SuperRes"

/**
 * 异常 → 一行人类可读的原因：message + 前 3 帧调用栈。
 *
 * 只放 message 时会出现"getClass() on null"这种无法定位的句子，
 * 加上栈帧后能看清具体是哪一类的哪一行。
 */
internal fun describeThrowable(t: Throwable): String {
    val msg = t.message?.replace("\n", " ")?.trim().orEmpty()
    val frames = t.stackTrace.take(3)
        .joinToString(" ← ") { it.className.substringAfterLast('.') + "." + it.methodName }
    val text = if (msg.isBlank()) frames else "$msg @ $frames"
    return if (text.length > 240) text.take(240) + "…" else text
}

/**
 * 超分方案（prefs `sr_model` 存的就是这些 value）。
 *
 * 算法来自 Anime4K 官方 v4 GLSL（MIT）移植 + media3 自带的 Lanczos 重采样。
 * 默认 [A4K_3D]：MMD / 3D CG 画面用 Anime4K 的 3D 专用放大（带抗锯齿）最对口 ——
 * MMD 是平滑着色的 3D 渲染、边缘本身已抗锯齿，几乎没有 2D 番剧那种线稿，
 * 套 2D 向的 `Restore_CNN`（线稿重建）反而容易涂抹、过冲。
 */
@OptIn(UnstableApi::class)
object SuperRes {

    /** MMD / 3D 画面：Anime4K_3DGraphics_AA_Upscale_x2_US */
    const val A4K_3D = "A4K_3D"

    /** 番剧 / 2D · 均衡：Anime4K_Restore_CNN_S + Upscale_CNN_x2_S */
    const val A4K_BAL = "A4K_BAL"

    /** 番剧 / 2D · 高质：Anime4K_Restore_CNN_M + Upscale_CNN_x2_M */
    const val A4K_HQ = "A4K_HQ"

    /** 仅放大 · 轻量：Anime4K_Upscale_CNN_x2_S（不做线稿修复） */
    const val A4K_UP_S = "A4K_UP_S"

    /**
     * 只修复、不放大（在原分辨率上运行）。
     *
     * 屏幕物理宽度只有 1272px，1080p（1920 宽）源能放大的空间约 1.18×，
     * 放大类模型在 1080p 上看不出效果是物理上限而非缺陷。1080p 上真正有观感的提升
     * 来自原生分辨率的画质修复 / 降噪，这也是同类客户端（mpv + Anime4K）lite 组的思路
     * （`restore_s_only` / `denoise_bilateral_fast`）。
     */
    const val A4K_RST = "A4K_RST"

    /** 只修复 · 柔和：Anime4K_Restore_CNN_Soft_S（官方柔和变体，最不容易过冲/发白） */
    const val A4K_SOFT = "A4K_SOFT"

    /** 只降噪 · 轻度：Denoise_Bilateral_Mean */
    const val DN_MEAN = "DN_MEAN"

    /** 只降噪 · 均衡：Denoise_Bilateral_Median（保边，对付压缩块） */
    const val DN_MEDIAN = "DN_MEDIAN"

    /** 只降噪 · 强力：Denoise_Bilateral_Mode（最强，细节损失也最大） */
    const val DN_MODE = "DN_MODE"

    /** 传统放大：media3 自带 Lanczos 重采样（非 AI，但比双线性好很多） */
    const val LANCZOS = "LANCZOS"

    /**
     * 开发者测试特效：走正常超分管线输出纯色帧。
     * 画面变红/绿表示特效管线与"切模型重建"路径都在工作（超分效果细微难辨，
     * 用一眼可见的颜色当管线探针）；仅在 Debug 开关下的模型列表里出现，不是 UI 滤镜。
     */
    const val TEST_RED = "TEST_RED"
    const val TEST_GREEN = "TEST_GREEN"

    /** 开发者测试特效清单：由 [modelsFor] 在 Debug 开关下追加到正常列表之后。 */
    val testModels: List<String> = listOf(TEST_RED, TEST_GREEN)

    const val DEFAULT = A4K_3D

    /** 不放大（在原分辨率上跑）的模型：`configure()` 必须原样返回输入尺寸。 */
    private val NO_UPSCALE = setOf(A4K_RST, A4K_SOFT, DN_MEAN, DN_MEDIAN, DN_MODE, TEST_RED, TEST_GREEN)

    /** 该模型是否会放大输出（false = 只修复/降噪，输出尺寸＝源尺寸）。 */
    internal fun isUpscaling(model: String): Boolean = model !in NO_UPSCALE

    /** 播放器下拉菜单 / 设置页的展示顺序（"关闭"由调用方另外补在最前）。 */
    val models: List<String> =
        listOf(A4K_3D, A4K_BAL, A4K_HQ, A4K_UP_S, A4K_RST, A4K_SOFT, DN_MEDIAN, DN_MEAN, DN_MODE, LANCZOS)

    /** 模型列表；[debug]=true 时末尾附加测试特效（两处 UI 共用此口径）。 */
    fun modelsFor(debug: Boolean): List<String> = if (debug) models + testModels else models

    /** 模型名 —— 播放器下拉菜单与设置页 chip 直接显示的就是它（不使用场景描述当选项名）。 */
    fun label(model: String): String = when (model) {
        A4K_3D -> "Anime4K 3D AA"
        A4K_BAL -> tr("Anime4K 修复 S + 放大 S", "Anime4K Restore S + Upscale S")
        A4K_HQ -> tr("Anime4K 修复 M + 放大 M", "Anime4K Restore M + Upscale M")
        A4K_UP_S -> tr("Anime4K 仅放大 S", "Anime4K Upscale only S")
        A4K_RST -> tr("Anime4K 仅修复 S", "Anime4K Restore only S")
        A4K_SOFT -> tr("Anime4K 仅修复 S · 柔和", "Anime4K Restore only S · Soft")
        DN_MEAN -> tr("降噪 · 轻度", "Denoise · Light")
        DN_MEDIAN -> tr("降噪 · 均衡", "Denoise · Balanced")
        DN_MODE -> tr("降噪 · 强力", "Denoise · Strong")
        LANCZOS -> "Lanczos"
        TEST_RED -> tr("测试 · 红", "Test · Red")
        TEST_GREEN -> tr("测试 · 绿", "Test · Green")
        else -> "Anime4K"
    }

    /** 各模型的适用场景说明：只在设置页的 ⓘ 弹窗里展示，不占菜单宽度。 */
    fun desc(model: String): String = when (model) {
        A4K_3D -> tr(
            "MMD / 3D CG 专用：3D 画面放大 + 抗锯齿。模型最小、最省电，边缘干净、不动线条。" +
                "已按官方要求前置高光钳制，不会过冲出白边。",
            "For MMD / 3D CG: upscales 3D footage and antialiases edges. The smallest, most power-efficient " +
                "model — clean edges, line art left untouched. Highlight clamping runs up front as officially " +
                "required, so there are no overshoot halos."
        )
        A4K_BAL -> tr(
            "先做轻度线稿修复（Restore CNN S）再 2× 放大，并前置高光钳制。" +
                "2D 番剧的均衡档。",
            "Light line-art restore (Restore CNN S), then 2× upscale, with highlight clamping up front. " +
                "The balanced pick for 2D anime."
        )
        A4K_HQ -> tr(
            "更强的线稿修复（Restore CNN M）+ 2× 放大，并前置高光钳制。" +
                "最吃 GPU，低端机可能掉帧。",
            "Stronger line-art restore (Restore CNN M) plus 2× upscale, with highlight clamping up front. " +
                "Heaviest on the GPU; low-end devices may drop frames."
        )
        A4K_UP_S -> tr(
            "只做 2× CNN 放大、不做线条修复。Anime4K 最轻的一档。",
            "2× CNN upscale only, no line-art restore. The lightest Anime4K tier."
        )
        A4K_RST -> tr(
            "只修复、不放大，在原分辨率上运行。1080p 源最实用的一档：" +
                "放大受屏幕宽度限制几乎没有空间，画质提升主要来自这里。",
            "Restore only, no upscale — runs at source resolution. The most practical tier for 1080p sources: " +
                "screen width leaves little room to upscale, so most of the quality gain comes from here."
        )
        A4K_SOFT -> tr(
            "同上但不放大，改用官方柔和变体（Restore CNN Soft）：" +
                "最不容易出现白边/过冲，适合已经很干净或噪点较多的源。",
            "Same as above without upscaling, using the official soft variant (Restore CNN Soft): " +
                "least prone to halos/overshoot, best for already-clean or noisy sources."
        )
        DN_MEAN -> tr(
            "双边滤波（加权均值）：最快、最轻的去噪，适合噪点不明显的源。",
            "Bilateral filter (weighted mean): the fastest, lightest denoise; suits sources with little noise."
        )
        DN_MEDIAN -> tr(
            "双边滤波（中值）：保边去噪，专门对付压缩块、脉冲噪点。MMD 1080p 推荐这一档。",
            "Bilateral filter (median): edge-preserving denoise aimed at compression blocks and impulse noise. " +
                "Recommended for 1080p MMD."
        )
        DN_MODE -> tr(
            "双边滤波（众数）：最强去噪，能压色带与压缩纹，但细节损失也最大。",
            "Bilateral filter (mode): the strongest denoise; crushes banding and compression artifacts, " +
                "but loses the most detail."
        )
        LANCZOS -> tr(
            "media3 自带 Lanczos 重采样（非 AI）。比双线性清晰很多、几乎不吃 GPU；源分辨率已超过屏幕时没有效果。",
            "media3's built-in Lanczos resampling (not AI). Much sharper than bilinear and nearly free on the " +
                "GPU; no effect once the source already exceeds the screen resolution."
        )
        TEST_RED, TEST_GREEN ->
            tr(
                "开发者测试特效：走与超分完全相同的特效管线，直接输出纯色帧。" +
                    "切到该档画面变色 = 特效管线与「切模型重建」路径正常；它不做任何画质处理。",
                "Developer test effect: runs through the exact same effect pipeline as super resolution and " +
                    "outputs a solid-color frame. If the picture changes color on this tier, the pipeline and " +
                    "the \u2018rebuild on model switch\u2019 path are working; it does no image processing."
            )
        else -> ""
    }

    /** chip 上的短标识（芯片宽度有限，只放 2~3 个字符）。 */
    fun shortTag(model: String): String = when (model) {
        A4K_3D -> "3D"
        A4K_BAL, A4K_HQ, A4K_UP_S -> "A4K"
        A4K_RST -> tr("修复", "RST")
        A4K_SOFT -> tr("柔修", "SFT")
        DN_MEAN, DN_MEDIAN -> tr("降噪", "DN")
        DN_MODE -> tr("强降", "SDN")
        LANCZOS -> "LZ"
        TEST_RED -> tr("测红", "TR")
        TEST_GREEN -> tr("测绿", "TG")
        else -> "SR"
    }

    /**
     * 兼容旧值：历史版本可能存 "A"/"B"/"C"（旧的自写锐化三档），
     * 统一迁移到 [DEFAULT]，避免出现标签变了但档位没变的情况。
     */
    fun normalize(value: String?): String = when (value) {
        A4K_3D, A4K_BAL, A4K_HQ, A4K_UP_S, A4K_RST, A4K_SOFT,
        DN_MEAN, DN_MEDIAN, DN_MODE, LANCZOS, TEST_RED, TEST_GREEN -> value
        else -> DEFAULT
    }

    /**
     * 允许的放大倍率档位（由大到小）。倍率**吸附**到这些档，而不是取"屏幕宽 ÷ 源宽"这类任意小数。
     */
    private val SCALE_STEPS = floatArrayOf(2f, 1.5f, 1.25f, 1f)

    /**
     * 按源尺寸与屏幕物理分辨率算出真实放大后的输出尺寸（恒 ≥ 源尺寸）。
     * 收敛规则：不超过 2×；不超出屏幕宽/高；总像素不超过 [MAX_OUTPUT_PIXELS]；
     * 最后把倍率吸附到 [SCALE_STEPS] 里的整数档。
     *
     * 吸附的必要性：若直接取 `min(2, 屏宽/源宽, 屏高/源高, 像素上限)`，
     * 一个 1080×1920 的竖屏源会算出 1272×2262（1272 是屏幕物理宽），
     * 既不是真实分辨率也不是整倍数；而 Anime4K 的 `Upscale_CNN_x2` 按 2× 输出网格设计，
     * 被压到 1.18× 反而更糊。吸附后 720×1280 → 1080×1920、720p 横屏 → 1280×720（1×），
     * 输出都是能一眼看懂的真实分辨率。
     */
    internal fun scaledSize(inputW: Int, inputH: Int, maxW: Int, maxH: Int): Size {
        if (inputW <= 0 || inputH <= 0) {
            return Size(inputW.coerceAtLeast(2), inputH.coerceAtLeast(2))
        }
        var limit = MAX_SCALE
        if (maxW > 0) limit = minOf(limit, maxW.toFloat() / inputW)
        if (maxH > 0) limit = minOf(limit, maxH.toFloat() / inputH)
        val inputPixels = inputW.toLong() * inputH.toLong()
        limit = minOf(limit, sqrt(MAX_OUTPUT_PIXELS.toDouble() / inputPixels.toDouble()).toFloat())
        // 吸附（向下取最接近的档）；源本身已比屏幕大时一个档都取不到 → 1×（绝不缩小）。
        val scale = SCALE_STEPS.firstOrNull { it <= limit + 1e-3f } ?: 1f
        val w = (inputW * scale).roundToInt().coerceAtLeast(2)
        val h = (inputH * scale).roundToInt().coerceAtLeast(2)
        // 纯防御：吸附后仍越过总像素上限就退回原尺寸，且只在真的做了放大时才退，
        // 否则会把 4K 源缩小回 2K、反而丢分辨率。
        return if (w.toLong() * h.toLong() > MAX_OUTPUT_PIXELS && w > inputW && h > inputH) {
            Size(inputW, inputH)
        } else {
            Size(w, h)
        }
    }

    /**
     * 构建超分特效链；关闭时传空列表。
     *
     * 任何环节失败都降级而不是报错：Anime4K 编译/分配失败自动换成 media3 Lanczos；
     * 连 Lanczos 都失败则用 media3 直通（最坏情况是超分未生效，画面照常播放，不会触发 7001）。
     */
    fun buildEffects(context: Context, model: String): List<Effect> {
        val displayMetrics = context.resources.displayMetrics
        val maxW = displayMetrics.widthPixels
        val maxH = displayMetrics.heightPixels
        SuperResStatus.reset()
        val normalized = normalize(model)
        Log.i(TAG, "buildEffects model=$normalized screen=${maxW}x$maxH")
        DiagLog.log(TAG, "buildEffects model=$normalized screen=${maxW}x$maxH")
        return when (normalized) {
            LANCZOS -> listOf(lanczosEffect(context, maxW, maxH))
            TEST_RED, TEST_GREEN -> listOf(testColorEffect(normalized))
            else -> listOf(anime4kEffect(context, normalized, maxW, maxH))
        }
    }

    /**
     * 直通特效（等价于"什么都不做"）。
     *
     * 关闭超分时仍把它交给播放器，而不是 `emptyList()`：media3 要求 `setVideoEffects`
     * 在首次 `prepare()` 之前至少被调用一次才会建立特效管线，之后才能可靠地动态更换特效；
     * 传空列表可能被当成"清除特效"而不建管线，导致之后打开 SR 不生效。
     * 代价是每帧多一次 GPU 拷贝。
     */
    fun passthroughEffect(): Effect = object : GlEffect {
        override fun toGlShaderProgram(context2: Context, useHdr: Boolean): GlShaderProgram =
            PassthroughShaderProgram()
    }

    /** media3 自带 Lanczos 重采样：按屏幕物理分辨率放大（不依赖输入尺寸，故可静态构造）。 */
    @OptIn(UnstableApi::class)
    private fun lanczosEffect(context: Context, maxW: Int, maxH: Int): Effect = object : GlEffect {
        override fun toGlShaderProgram(context2: Context, useHdr: Boolean): GlShaderProgram =
            try {
                SuperResStatus.engine = "Lanczos"
                LanczosResample.scaleToFit(maxW, maxH).toGlShaderProgram(context2, useHdr)
            } catch (t: Throwable) {
                Log.e(TAG, "Lanczos 构建失败，降级为直通", t)
                SuperResStatus.markDegraded(describeThrowable(t))
                PassthroughShaderProgram()
            }
    }

    /**
     * 开发者测试特效工厂：走与超分完全相同的特效管线（同一个 `setVideoEffects` 通道、
     * 同一个 GL 上下文、同样的"切模型重建"路径），但程序不做画质处理、直接输出纯色帧。
     * 画面变色即表示管线正常。
     */
    private fun testColorEffect(model: String): Effect = object : GlEffect {
        override fun toGlShaderProgram(context2: Context, useHdr: Boolean): GlShaderProgram {
            SuperResStatus.engine = tr("测试特效", "Test effect")
            return TestColorProgram(
                if (model == TEST_RED) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
            )
        }
    }

    /** Anime4K 多趟链；构建失败自动降到 Lanczos（而不是让整条特效链报错）。 */    @OptIn(UnstableApi::class)
    private fun anime4kEffect(
        context: Context,
        model: String,
        maxW: Int,
        maxH: Int
    ): Effect = object : GlEffect {
        override fun toGlShaderProgram(context2: Context, useHdr: Boolean): GlShaderProgram =
            try {
                Anime4kProgram(context2, useHdr, model, maxW, maxH)
            } catch (t: Throwable) {
                // 特效链失败不整片报错（否则会抛 VideoFrameProcessingException → ExoPlayer 7001）。
                // 这里退到 media3 自带的 Lanczos，至少能拿到比双线性更好的放大。
                Log.e(TAG, "Anime4K($model) 构建失败，降级为 Lanczos", t)
                DiagLog.log(TAG, "Anime4K($model) 构建失败，降级为 Lanczos: ${t.message}")
                SuperResStatus.markDegraded(describeThrowable(t))
                try {
                    SuperResStatus.engine = tr("Lanczos(降级)", "Lanczos (fallback)")
                    LanczosResample.scaleToFit(maxW, maxH).toGlShaderProgram(context2, useHdr)
                } catch (lanczosError: Throwable) {
                    Log.e(TAG, "Lanczos 也失败，降级为直通", lanczosError)
                    PassthroughShaderProgram()
                }
            }
    }

    /** 目标放大倍率上限。 */
    private const val MAX_SCALE = 2f

    /**
     * 输出总像素上限（2560×1440 ≈ 2K），防止低端机在 4K 屏上被 2× 拖垮。
     * 它也是唯一的处理量上限：`Anime4kProgram.configure()` 直接引用它，
     * 不再按模型分别设源分辨率上限。
     */
    internal const val MAX_OUTPUT_PIXELS: Long = 2560L * 1440L
}
