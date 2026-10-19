package io.github.amatsukiichika.iwaramanager.ui

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Effect
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.PassthroughShaderProgram
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.effect.BaseGlShaderProgram
import com.google.gson.JsonParser
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

/** 读当前绑定的帧缓冲 id —— 输出帧缓冲即调用方绑定的那个（两个程序同一取法）。 */
private fun currentFramebufferId(): Int {
    val buf = IntArray(1)
    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, buf, 0)
    return buf[0]
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
        A4K_BAL -> "Anime4K 修复 S + 放大 S"
        A4K_HQ -> "Anime4K 修复 M + 放大 M"
        A4K_UP_S -> "Anime4K 仅放大 S"
        A4K_RST -> "Anime4K 仅修复 S"
        A4K_SOFT -> "Anime4K 仅修复 S · 柔和"
        DN_MEAN -> "降噪 · 轻度"
        DN_MEDIAN -> "降噪 · 均衡"
        DN_MODE -> "降噪 · 强力"
        LANCZOS -> "Lanczos"
        TEST_RED -> "测试 · 红"
        TEST_GREEN -> "测试 · 绿"
        else -> "Anime4K"
    }

    /** 各模型的适用场景说明：只在设置页的 ⓘ 弹窗里展示，不占菜单宽度。 */
    fun desc(model: String): String = when (model) {
        A4K_3D -> "MMD / 3D CG 专用：3D 画面放大 + 抗锯齿。模型最小、最省电，边缘干净、不动线条。" +
            "已按官方要求前置高光钳制，不会过冲出白边。"
        A4K_BAL -> "先做轻度线稿修复（Restore CNN S）再 2× 放大，并前置高光钳制。" +
            "2D 番剧的均衡档。"
        A4K_HQ -> "更强的线稿修复（Restore CNN M）+ 2× 放大，并前置高光钳制。" +
            "最吃 GPU，低端机可能掉帧。"
        A4K_UP_S -> "只做 2× CNN 放大、不做线条修复。Anime4K 最轻的一档。"
        A4K_RST -> "只修复、不放大，在原分辨率上运行。1080p 源最实用的一档：" +
            "放大受屏幕宽度限制几乎没有空间，画质提升主要来自这里。"
        A4K_SOFT -> "同上但不放大，改用官方柔和变体（Restore CNN Soft）：" +
            "最不容易出现白边/过冲，适合已经很干净或噪点较多的源。"
        DN_MEAN -> "双边滤波（加权均值）：最快、最轻的去噪，适合噪点不明显的源。"
        DN_MEDIAN -> "双边滤波（中值）：保边去噪，专门对付压缩块、脉冲噪点。MMD 1080p 推荐这一档。"
        DN_MODE -> "双边滤波（众数）：最强去噪，能压色带与压缩纹，但细节损失也最大。"
        LANCZOS -> "media3 自带 Lanczos 重采样（非 AI）。比双线性清晰很多、几乎不吃 GPU；源分辨率已超过屏幕时没有效果。"
        TEST_RED, TEST_GREEN ->
            "开发者测试特效：走与超分完全相同的特效管线，直接输出纯色帧。" +
                "切到该档画面变色 = 特效管线与「切模型重建」路径正常；它不做任何画质处理。"
        else -> ""
    }

    /** chip 上的短标识（芯片宽度有限，只放 2~3 个字符）。 */
    fun shortTag(model: String): String = when (model) {
        A4K_3D -> "3D"
        A4K_BAL, A4K_HQ, A4K_UP_S -> "A4K"
        A4K_RST -> "修复"
        A4K_SOFT -> "柔修"
        DN_MEAN, DN_MEDIAN -> "降噪"
        DN_MODE -> "强降"
        LANCZOS -> "LZ"
        TEST_RED -> "测红"
        TEST_GREEN -> "测绿"
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
            SuperResStatus.engine = "测试特效"
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
                    SuperResStatus.engine = "Lanczos(降级)"
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

/**
 * 超分运行状态：GL 线程写、UI 线程读，供播放器 SR chip 展示真实倍率与降级提示。
 * - [activeScale] 实际输出倍率（相对源分辨率）；null = 尚未生效/已关闭；
 * - [degraded]   构建或分配失败、已降级（画面照常播放，但超分未按预期生效）；
 * - [engine]     实际生效的引擎名。
 */
object SuperResStatus {
    var activeScale by mutableStateOf<Float?>(null)
    var degraded by mutableStateOf(false)
    var engine by mutableStateOf<String?>(null)

    /**
     * 失败原因（一行人类可读文本，来自异常 message）。
     * 直接显示在播放器 SR 下拉菜单里，无需抓 logcat。
     */
    var reason by mutableStateOf<String?>(null)

    /**
     * 特效管线在播放中发生 7001、已由播放器自动关闭 SR 并恢复播放。
     * 与 [degraded] 的区别：这是管线级失败（播放已被打断过一次），chip 显示
     * "SR 自动停用"提醒；换视频或重新打开 SR 时清零。
     */
    var pipelineFailed by mutableStateOf(false)

    /**
     * 真实源分辨率（GPU 实际拿到的帧尺寸）。
     *
     * 由 `Anime4kProgram.configure()` 记录 —— media3 把解码后的帧纹理交给我们时喂进来的
     * width/height 就是它，这是唯一权威的"源分辨率"。
     *
     * 不能改用播放器上报的 `onVideoSizeChanged`：装了特效管线后那个值可能是特效的输出尺寸
     * （显示成 1272×2262 这种看不懂的数），也可能因上游 issue #2505 根本不更新。
     */
    var sourceWidth by mutableStateOf<Int?>(null)
    var sourceHeight by mutableStateOf<Int?>(null)

    /** 特效实际输出分辨率（只修复 / 只降噪的档 = 源分辨率）。 */
    var outputWidth by mutableStateOf<Int?>(null)
    var outputHeight by mutableStateOf<Int?>(null)

    /** `1920x1080` 形式；任一维取不到就返回 null。 */
    private fun sizeText(w: Int?, h: Int?): String? =
        if (w != null && h != null && w > 0 && h > 0) "${w}x$h" else null

    /** 实测源分辨率文案。 */
    val sourceText: String? get() = sizeText(sourceWidth, sourceHeight)

    /** 实测输出分辨率文案。 */
    val outputText: String? get() = sizeText(outputWidth, outputHeight)

    /**
     * SR 下拉菜单顶部那条只读说明：「源 1080x1920 · 输出 1080x1920 · 不放大」。
     * 只显示 configure() 实测到的尺寸，不出现看似识别错误的分辨率。
     */
    fun resolutionInfo(isUpscalingModel: Boolean, scale: Float?): String? {
        val src = sourceText ?: return null
        val out = outputText ?: src
        val ratio = if (isUpscalingModel && scale != null && scale > 1.01f) {
            " · ${formatScale(scale)}"
        } else {
            " · 不放大"
        }
        return "源 $src · 输出 $out$ratio"
    }

    fun reset() {
        activeScale = null
        degraded = false
        engine = null
        reason = null
        pipelineFailed = false
        sourceWidth = null
        sourceHeight = null
        outputWidth = null
        outputHeight = null
    }

    /**
     * 降级状态赋值入口：degraded=true + 清倍率 + 记原因，避免多处重复。
     */
    fun markDegraded(reason: String?) {
        degraded = true
        activeScale = null
        this.reason = reason
    }

    /**
     * 底部条 SR chip 文案：
     * - 关闭 → `SR`；管线级失败自动停用 → `SR 自动停用`；降级 → `SR 未生效`；
     *   生效 → `SR·3D 2×`（倍率实际 >1 才展示）。
     * 恒为单行短串，避免把右侧清晰度 chip 挤出屏幕。
     */
    fun chipLabel(enabled: Boolean, model: String, scale: Float?, degraded: Boolean): String = when {
        !enabled && pipelineFailed -> "SR 自动停用"
        !enabled -> "SR"
        degraded -> "SR 未生效"
        scale != null && scale > 1.01f -> "SR·${SuperRes.shortTag(model)} ${formatScale(scale)}"
        else -> "SR·${SuperRes.shortTag(model)}"
    }

    /** 倍率文案：整数倍显示 `2×`，非整数保留一位小数（如 `1.5×`）。 */
    fun formatScale(scale: Float): String {
        val tenths = (scale * 10f).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}×" else "${tenths / 10f}×"
    }
}

// ---------------------------------------------------------------------------
// 测试特效：走正常管线输出纯色帧（管线探针）
// ---------------------------------------------------------------------------

/**
 * 测试特效程序。与 [Anime4kProgram] 同基类（同一条 BaseGlShaderProgram 通道、同一个 GL 上下文），
 * 但不做任何采样/处理：`drawFrame` 直接把输出帧缓冲清成 [color]。
 * 用 `glClear` 而不是着色器，因为管线探针要的是零处理、零歧义：帧从这条管线出来即为纯色。
 */
@OptIn(UnstableApi::class)
private class TestColorProgram(
    private val color: FloatArray
) : BaseGlShaderProgram(/* useHdr= */ false, /* texturePoolCapacity= */ 1) {

    private var widthPx = 2
    private var heightPx = 2

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        widthPx = inputWidth.coerceAtLeast(2)
        heightPx = inputHeight.coerceAtLeast(2)
        // 与"只修复/降噪"类一致：不放大，输出 = 输入；源分辨率照常进诊断面板
        SuperResStatus.sourceWidth = inputWidth
        SuperResStatus.sourceHeight = inputHeight
        SuperResStatus.outputWidth = widthPx
        SuperResStatus.outputHeight = heightPx
        SuperResStatus.activeScale = null
        DiagLog.log(TAG, "TestColor configure in=${inputWidth}x${inputHeight}")
        return Size(widthPx, heightPx)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        // 输出帧缓冲 = 调用方当前绑定的 FBO（与 Anime4kProgram 末趟的取法一致）
        GlUtil.focusFramebufferUsingCurrentContext(currentFramebufferId(), widthPx, heightPx)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, widthPx, heightPx)
        GLES20.glClearColor(color[0], color[1], color[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    override fun release() = Unit
}

// ---------------------------------------------------------------------------
// Anime4K：一趟 = 一个 GLSL ES 1.00 片元着色器，多趟按顺序渲染到各自的 FBO
// ---------------------------------------------------------------------------

@OptIn(UnstableApi::class)
private class Anime4kProgram(
    context: Context,
    useHdr: Boolean,
    private val model: String,
    private val maxOutW: Int,
    private val maxOutH: Int
) : BaseGlShaderProgram(/* useHdr= */ useHdr, /* texturePoolCapacity= */ OUTPUT_POOL_CAPACITY) {

    private class StageDef(
        val file: String,
        val textures: List<String>,
        val scale: Int,
        val isFinal: Boolean,
        val save: String,
        /**
         * 本趟读到的 MAIN/HOOKED 是否还是原始视频帧（转译器逐趟推导后写进 models.json）。
         * GLSL 按这个约定预先编译（原始帧不拆包 / 打包中间图要拆包），
         * 运行时必须核对实际绑定的缓冲，不符就降级（见 [Anime4kProgram.init] 里的 check）。
         */
        val mainRaw: Boolean
    )

    /**
     * 一趟的渲染计划。
     * - [outTarget]：写到哪张缓冲（`__out__` = 真正的输出）；
     * - [inputs]：本趟每个纹理名 → 实际缓冲名（`__video__` = 播放器喂进来的输入帧）。
     *   存"逻辑名→缓冲名"而不是纹理 id：中间缓冲要等 `configure()` 拿到输入尺寸才分配。
     */
    private class Planned(val def: StageDef, val outTarget: String, val inputs: Map<String, String>)

    private class Compiled(val program: GlProgram, val planned: Planned)

    private class Target(val texId: Int, val fboId: Int, val w: Int, val h: Int)

    private val compiled: List<Compiled>
    /** 除媒体输出外的所有中间缓冲（名字 → 纹理/FBO，configure 时按输入尺寸分配）。 */
    private val intermediateNames: List<String>
    private var targets: Map<String, Target> = emptyMap()
    private var inW = 0
    private var inH = 0
    private var outW = 0
    private var outH = 0
    private var isAllocFailed = false
    /** 渲染期出错后置位：后续帧全部走直通，不再重复报错。 */
    private var renderBroken = false
    /** 兜底：分配失败时把输入原样拷到输出，保证不出黑屏。 */
    private val passthrough = GlProgram(VERTEX_SHADER, PASSTHROUGH_FRAGMENT)

    init {
        val json = context.assets.open(ASSETS_MODELS).use { it.readBytes().decodeToString() }
        val root = JsonParser.parseString(json).asJsonObject
        val entry = root.getAsJsonObject(model) ?: throw IllegalStateException("未知超分模型: $model")
        val defs = entry.getAsJsonArray("stages").map { el ->
            val obj = el.asJsonObject
            StageDef(
                file = obj.get("file").asString,
                textures = obj.getAsJsonArray("textures").map { it.asString },
                scale = obj.get("scale").asInt,
                isFinal = obj.get("final").asBoolean,
                save = obj.get("save").asString,
                // 缺字段＝产物与代码不配套（会静默出错误颜色）→ 直接抛，上层降级为 Lanczos
                mainRaw = obj.get("mainRaw")?.asBoolean
                    ?: throw IllegalStateException("models.json 缺少 mainRaw 字段（${obj.get("file")?.asString}），请重跑转译器")
            )
        }
        if (defs.isEmpty()) throw IllegalStateException("模型没有渲染趟: $model")

        // 规划每趟的输出缓冲。两处必须换新缓冲 —— 原地读写同一张纹理是 GPU 反馈回路，结果未定义：
        // ① 链中部 `save == MAIN`；
        // ② 自写回：一趟既 BIND 又 SAVE 同一张纹理。Anime4K_Clamp_Highlights 的第 2 趟
        //    （纵向取局部最大亮度）正是 `BIND STATSMAX` + `SAVE STATSMAX` —— mpv 自己会
        //    ping-pong，media3 不会。这里换一张缓冲，并让后续所有引用指向新缓冲；
        //    着色器源码不用改（它只认 `STATSMAX_tex` 这个逻辑名，绑到哪张由这里决定）。
        val planned = ArrayList<Planned>(defs.size)
        var mainBufSerial = 0
        var currentMain = VIDEO
        // 逻辑纹理名 → 当前实际缓冲名（自写回后会指向新缓冲）
        val alias = HashMap<String, String>()
        defs.forEach { def ->
            // 核对打包约定。GLSL 是按"这一趟读到的 MAIN/HOOKED 是原始视频帧还是已打包的中间图"
            // 预先编译好的；一旦和运行时实际绑定的缓冲不符，读出来就是 `2c-1` 这种错值，
            // 表现为画面变深/发黑而不是报错，所以必须在这里拦住
            // （不符就抛 → anime4kEffect 自动降级为 Lanczos，宁可不放大也不出错误颜色）。
            val mainIsVideo = currentMain == VIDEO
            check(def.mainRaw == mainIsVideo) {
                "打包约定不符: ${def.file} 声明 mainRaw=${def.mainRaw}，" +
                    "但此处 MAIN=$currentMain（$model）—— 请重跑 transpile-anime4k.py"
            }
            // 输入必须在"本趟写入之前"解析。
            // `HOOKED` 与 `MAIN` 是同一张缓冲 —— mpv 的 HOOKED 语义就是"本 hook 的输入帧"，
            // 而本管线里 hook 的对象始终是 MAIN。若把 HOOKED 硬绑到播放器喂进来的原始帧：
            //   * 链首的降噪（DN_*）会拿原始帧按 `2c-1` 解码 → 整体变深；
            //   * 链尾的钳制（A4K_RST/A4K_SOFT）会把修复后的画面整个换成原始帧再钳制，
            //     等于丢弃前面几趟的修复结果（A4K_3D/BAL/HQ 的中段钳制同理）。
            val inputs = def.textures.associateWith { name ->
                when (name) {
                    "MAIN", "HOOKED" -> currentMain
                    else -> alias[name] ?: name
                }
            }
            val out = resolveOutputTarget(def, alias, mainBufSerial)
            if (def.save == "MAIN") mainBufSerial++
            planned += Planned(def, out, inputs)
            if (!def.isFinal && def.save == "MAIN") currentMain = out
        }
        intermediateNames = planned.map { it.outTarget }.filter { it != OUT }.distinct()

        compiled = planned.map { plan ->
            val src = context.assets.open(ASSETS_PREFIX + plan.def.file).use { it.readBytes().decodeToString() }
            // 必须用双参 GlProgram(vertexSrc, fragmentSrc) 直编源码；三参重载会把字符串
            // 当成 assets/ 下的文件名去读，会触发 7001。
            Compiled(GlProgram(VERTEX_SHADER, src), plan)
        }
        compiled.forEach { bindStatic(it.program) }
        bindStatic(passthrough)
        if (intermediateNames.size > MAX_INTERMEDIATES) {
            throw IllegalStateException("中间缓冲过多(${intermediateNames.size})，GPU 纹理单元/显存不够")
        }
        Log.i(TAG, "Anime4K $model 就绪: ${compiled.size} 趟, ${intermediateNames.size} 张中间缓冲")
    }

    /**
     * 规划一趟的输出缓冲。
     * - 末趟 → `__out__`；
     * - `save == "MAIN"` → 换一张新 main 缓冲（原地读写同一张纹理是 GPU 反馈回路，结果未定义）；
     * - 自写回（BIND+SAVE 同一张）→ 换 `_v2` 新缓冲并更新 [alias]，
     *   后续所有引用自动指向新缓冲（着色器只认逻辑名，绑到哪张由这里决定）。
     *
     * @param mainBufSerial 当前 main 缓冲序号；`save=="MAIN"` 时由调用方在返回后 +1。
     */
    private fun resolveOutputTarget(
        def: StageDef,
        alias: MutableMap<String, String>,
        mainBufSerial: Int
    ): String = when {
        def.isFinal -> OUT
        def.save == "MAIN" -> "__main${mainBufSerial}__"
        else -> {
            val physical = alias[def.save] ?: def.save
            if (def.save in def.textures) {
                val fresh = physical + "_v2"
                alias[def.save] = fresh
                fresh
            } else {
                physical
            }
        }
    }

    private fun bindStatic(p: GlProgram) {
        p.setBufferAttribute("aFramePosition", QUAD_NDC, /* sizePerComponent= */ 4)
        p.setFloatsUniformIfPresent(
            "uFrameRotationTransformMatrix",
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inW = inputWidth
        inH = inputHeight
        // 只修复 / 只降噪的模型链里没有放大趟，输出必须是源尺寸 ——
        // 若按屏幕分辨率放大，末段（钳制/降噪那趟）会把结果拉伸成一张糊的放大图。
        val isUpscalingModel = SuperRes.isUpscaling(model)
        val out = if (isUpscalingModel) {
            SuperRes.scaledSize(inputWidth, inputHeight, maxOutW, maxOutH)
        } else {
            Size(inputWidth, inputHeight)
        }
        outW = out.width
        outH = out.height
        releaseTargets()
        isAllocFailed = false
        if (inputWidth <= 0 || inputHeight <= 0) {
            SuperResStatus.reset()
            DiagLog.log(TAG, "configure in=${inputWidth}x${inputHeight} 无效尺寸 → 直通")
            return Size(inputWidth, inputHeight)
        }
        // 源 / 输出分辨率在这里定案：这是 media3 真正交给 GPU 的帧尺寸，
        // 之后诊断面板与 SR 菜单只认这两个数（见 SuperResStatus.sourceText 的说明）。
        SuperResStatus.sourceWidth = inputWidth
        SuperResStatus.sourceHeight = inputHeight
        SuperResStatus.outputWidth = outW
        SuperResStatus.outputHeight = outH
        // 只有一条统一规则：输出总像素不超过 MAX_OUTPUT_PIXELS（2K 档，也就是 scaledSize 的上限）。
        // 按模型分别设源分辨率上限会让绝大多数 1080p 内容静默落到直通降级。放大档的输出
        // 已被 scaledSize 收敛、恒满足；只有当源本身就超过 2K（1440p 以上）时才直通。
        // 真跑不动的设备由 7001 自动保护兜底。
        val outPixels = outW.toLong() * outH.toLong()
        if (outPixels > SuperRes.MAX_OUTPUT_PIXELS) {
            releaseTargets()
            isAllocFailed = true
            val megapixels = String.format(java.util.Locale.US, "%.1f", SuperRes.MAX_OUTPUT_PIXELS / 1_000_000.0)
            SuperResStatus.markDegraded(
                "源分辨率 ${inputWidth}x${inputHeight} 超过 ${megapixels}MP 处理上限（1440p 以上），" +
                    "本模型自动停用（直通输出）"
            )
            DiagLog.log(TAG, "configure in=${inputWidth}x${inputHeight} 超 ${megapixels}MP 上限 → 直通降级")
            return Size(inputWidth, inputHeight)
        }
        return try {
            targets = intermediateNames.associateWith { newTarget(inputWidth, inputHeight) }
            // 不放大时倍率记 1：chip 只在 >1.01 才显示 "N×"（"SR·修复" 比 "SR·修复 1×" 干净）
            SuperResStatus.activeScale = if (isUpscalingModel) outW.toFloat() / inputWidth else 1f
            SuperResStatus.degraded = false
            SuperResStatus.engine = "Anime4K"
            Log.i(TAG, "configure in=${inputWidth}x$inputHeight out=${outW}x$outH")
            DiagLog.log(
                TAG,
                "configure 源=${inputWidth}x$inputHeight 输出=${outW}x$outH" +
                    if (isUpscalingModel) "" else "（只修复/降噪，不放大）"
            )
            out
        } catch (t: Throwable) {
            // 显存/纹理分配失败：本模型退回"直通"，画面照常，chip 显示"SR 未生效"。
            Log.e(TAG, "中间缓冲分配失败，本模型退回直通", t)
            DiagLog.log(TAG, "中间缓冲分配失败，本模型退回直通: ${t.message}")
            releaseTargets()
            isAllocFailed = true
            runCatching {
                SuperResStatus.markDegraded(describeThrowable(t))
            }
            Size(inputWidth, inputHeight)
        }
    }

    /**
     * 渲染入口绝不往外抛。
     * `BaseGlShaderProgram.queueInputFrame` 只捕获 `VideoFrameProcessingException`/`GlException`
     * 并转成 ExoPlayer 7001（表现为整片"播放出错"）。这里把任何异常都拦下来：
     * 本次播放退回直通（画面照常），把原因写进 [SuperResStatus.reason]，随后在播放器下拉菜单显示。
     */
    /*
     * 绕开 media3 issue #821。
     * `SingleFrameGlShaderProgram` 把输出纹理池容量写死成 1 —— 上一帧还没被下游释放、
     * 下一帧就 `useTexture()`，直接抛 "Textures are all in use" → 7001（慢特效必踩，
     * 见 issue #1139）。本类因此直接继承 `BaseGlShaderProgram(useHdr, texturePoolCapacity = 3)`，
     * 基类的 `queueInputFrame` 已实现"尺寸变了→configure→ensureConfigured→useTexture
     * →focusFramebuffer→drawFrame→回调"整套流程，无需覆写。
     * 注意：`TexturePool` 在 media3 里是 package-private，子类不能自己手写 queueInputFrame
     * 直接调 `outputTexturePool.useTexture()`（Kotlin 报 "Cannot access TexturePool:
     * it is package-private"），容量只能经构造参数注入。
     */

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        // 输出帧缓冲 id 的取法收口到 currentFramebufferId()（与 TestColorProgram 同款）。
        val prevFbo = currentFramebufferId()
        if (renderBroken || isAllocFailed) {
            // 这段在下面的 try/catch 之外 —— 直通绘制一旦抛异常会直接穿透成 7001。
            // 防御性兜一层，不让降级路径反过来中断播放。
            try {
                renderPassthroughInto(prevFbo, inputTexId)
            } catch (t: Throwable) {
                Log.e(TAG, "直通绘制失败（放弃本帧输出）", t)
                DiagLog.log(TAG, "直通绘制失败: ${t.message}")
            }
            return
        }
        try {
            compiled.forEach { pass -> renderPass(pass, inputTexId, prevFbo) }
        } catch (t: Throwable) {
            renderBroken = true
            Log.e(TAG, "Anime4K($model) 渲染失败，本次播放退回直通（画面不受影响）", t)
            DiagLog.log(TAG, "Anime4K($model) 渲染失败，退回直通: ${t.message}")
            // 写状态本身也可能抛（Compose 快照跨线程写），包一层保证异常不再穿透。
            runCatching { SuperResStatus.markDegraded(describeThrowable(t)) }
            runCatching { renderPassthroughInto(prevFbo, inputTexId) }
        }
    }

    /**
     * 画一趟（自 [drawFrame] 抽出，降低嵌套）——
     * 解析本趟采样绑定 → 聚焦目标帧缓冲（末趟 = 调用方的 [prevFbo]，中间趟 = 计划缓冲）→ 绘制。
     * 抛出的异常由 [drawFrame] 统一接住转直通。
     */
    private fun renderPass(pass: Compiled, inputTexId: Int, prevFbo: Int) {
        val samplers = HashMap<String, Int>()
        pass.planned.def.textures.forEach { name ->
            val physical = pass.planned.inputs[name]
            samplers[name] = if (physical == null || physical == VIDEO) {
                inputTexId
            } else {
                // 计划里指向的缓冲必须真的分配过。静默退回"原始帧"只会得到内容与颜色都不对的
                // 画面，所以这里抛出去 → 本帧起退回直通，原因显示在 SR 菜单里。
                targets[physical]?.texId
                    ?: throw IllegalStateException("缓冲未分配: $physical（${pass.planned.def.file}）")
            }
        }
        if (pass.planned.def.isFinal) {
            GlUtil.focusFramebufferUsingCurrentContext(prevFbo, outW, outH)
        } else {
            val target = targets[pass.planned.outTarget]
                ?: throw IllegalStateException("缺少缓冲 ${pass.planned.outTarget}")
            GlUtil.focusFramebufferUsingCurrentContext(target.fboId, target.w, target.h)
        }
        renderSampling(pass.program, pass.planned.def.textures, samplers)
    }

    /** 把输入原样画进 [fbo]：降级路径的最后一道保险，保证出一帧、不黑屏。 */
    private fun renderPassthroughInto(fbo: Int, inputTexId: Int) {
        GlUtil.focusFramebufferUsingCurrentContext(fbo, inW, inH)
        renderSampling(passthrough, listOf("MAIN"), mapOf("MAIN" to inputTexId))
    }

    /**
     * 画一趟。顺序必须是：use() → 设 sampler/size uniform → bindAttributesAndUniforms() → draw。
     * media3 的 GlProgram 是延迟 uniform 模型：setXxx 只把值存进表里，真正 glUniform* 发生在
     * bindAttributesAndUniforms() 里（必须在 program 已 use 且 sampler 已 glActiveTexture 之后）。
     */
    private fun renderSampling(program: GlProgram, textures: List<String>, samplerTex: Map<String, Int>) {
        program.use()
        textures.forEachIndexed { unit, name ->
            // GL 编译器会把"声明了但没被使用"的 uniform 剔除（inactive），media3 的 uniforms
            // 表里就没有它，setXxxUniform 会对 checkNotNull 抛 NPE（降级直通 shader 只有
            // uTex_MAIN、没有 uSize_MAIN）。未激活的 uniform 在 GL 里本来就是 no-op，
            // 跳过与设置等价：
            // - float 用 setFloatsUniformIfPresent（表里没有即直接 return）；
            // - sampler 没有 IfPresent 变体，用 getUniformLocation >= 0 守卫（-1 = 不存在）。
            if (program.getUniformLocation("uTex_$name") >= 0) {
                program.setSamplerTexIdUniform("uTex_$name", samplerTex[name] ?: 0, unit)
            }
            program.setFloatsUniformIfPresent(
                "uSize_$name",
                floatArrayOf(inW.toFloat(), inH.toFloat())
            )
        }
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun newTarget(w: Int, h: Int): Target {
        val texId = GlUtil.createTexture(w, h, /* useHighPrecision= */ false)
        val fboId = GlUtil.createFboForTexture(texId)
        return Target(texId, fboId, w, h)
    }

    private fun releaseTargets() {
        targets.values.forEach { t ->
            runCatching { GlUtil.deleteFbo(t.fboId) }
            runCatching { GlUtil.deleteTexture(t.texId) }
        }
        targets = emptyMap()
    }

    override fun release() {
        runCatching { releaseTargets() }
        compiled.forEach { runCatching { it.program.delete() } }
        runCatching { passthrough.delete() }
        super.release()
    }

    private companion object {
        const val VIDEO = "__video__"
        const val OUT = "__out__"
        const val ASSETS_PREFIX = "anime4k/generated/"
        const val ASSETS_MODELS = "anime4k/generated/models.json"
        /**
         * 中间缓冲张数上限。
         *
         * 这不是纹理单元限制（GLES2 至少 8 个单元，而单趟最多同时绑 8 张：Restore M 的
         * 1x1x56 卷积），而是显存限制：每张 = W×H×4 字节，1080p（2.07MP）一张约 8.3MB。
         * 最重的 A4K_HQ 有 12 张（含高光钳制的 STATSMAX 与自写回用的 STATSMAX_v2，
         * 它有 540p 上限，实际约 2.1MB/张 → 25MB 左右）。超出就抛异常让上层降级。
         */
        const val MAX_INTERMEDIATES = 12

        /** 输出纹理池容量：3 帧缓冲，慢特效不至于立刻把池子用光（media3 默认 1）。 */
        const val OUTPUT_POOL_CAPACITY = 3

        val QUAD_NDC = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 0f, 1f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f
        )

        /** 与生成器输出一致：varying 名为 vTexCoord。 */
        val VERTEX_SHADER = """
            uniform mat3 uFrameRotationTransformMatrix;
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = vec4(aFramePosition.xy, 0.0, 1.0);
              vTexCoord = (uFrameRotationTransformMatrix * vec3(aFramePosition.xy, 1.0)).xy * 0.5 + 0.5;
            }
        """.trimIndent() + "\n"

        val PASSTHROUGH_FRAGMENT = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTex_MAIN;
            void main() { gl_FragColor = texture2D(uTex_MAIN, vTexCoord); }
        """.trimIndent() + "\n"
    }
}
