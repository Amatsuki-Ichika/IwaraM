package io.github.amatsukiichika.iwaramanager.ui

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import com.google.gson.JsonParser

private const val TAG = "SuperRes"
/** 读当前绑定的帧缓冲 id —— 输出帧缓冲即调用方绑定的那个（两个程序同一取法）。 */
private fun currentFramebufferId(): Int {
    val buf = IntArray(1)
    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, buf, 0)
    return buf[0]
}

/**
 * 测试特效程序。与 [Anime4kProgram] 同基类（同一条 BaseGlShaderProgram 通道、同一个 GL 上下文），
 * 但不做任何采样/处理：`drawFrame` 直接把输出帧缓冲清成 [color]。
 * 用 `glClear` 而不是着色器，因为管线探针要的是零处理、零歧义：帧从这条管线出来即为纯色。
 */
@OptIn(UnstableApi::class)
internal class TestColorProgram(
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

    override fun release() {
        // 基类 release 带 @CallSuper（负责回收纹理池），不能省略调用
        super.release()
    }
}

@OptIn(UnstableApi::class)
internal class Anime4kProgram(
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
