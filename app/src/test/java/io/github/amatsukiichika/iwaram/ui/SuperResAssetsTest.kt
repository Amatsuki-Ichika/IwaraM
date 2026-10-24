package io.github.amatsukiichika.iwaram.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 超分生成物（models.json + *.glsl）的契约回归用例。
 *
 * 生成物与运行时之间是跨文件契约：GLSL 按一套约定预先编译好，运行时却可能喂入另一套
 * 缓冲约定（转译器把只读 MAIN 的趟标成不拆包，而实际 `HOOKED` 绑的是原始视频帧却被拆包，
 * 于是 `2c-1` 被 clamp 进 [0,1]，画面整体变深、暗部压死）。编译期与纯函数单测都看不出
 * 这类失配，只能把 models.json 与 .glsl 文本对起来验：
 *
 * ① 每一趟的 `mainRaw` == "此刻 MAIN 是否还是原始视频帧"（与运行时同一套规则）；
 * ② 生成物里 `MAIN_tex` / `HOOKED_tex` 的取值方式必须与 ① 一致；
 * ③ 链结构：唯一 final 且在链尾、只有链尾能放大、读的纹理都产生过、钳制趟位置正确。
 */
class SuperResAssetsTest {

    /** 生成物目录。单测工作目录通常是模块目录，容错多试几个候选，找不到就报出 cwd。 */
    private val generated: File by lazy {
        val candidates = listOf(
            "src/main/assets/anime4k/generated",
            "app/src/main/assets/anime4k/generated"
        )
        candidates.map { File(it) }.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "未找到超分生成物目录（cwd=${File("").absolutePath}），候选：$candidates"
            )
    }

    private val models: JsonObject by lazy {
        JsonParser.parseString(File(generated, "models.json").readText()).asJsonObject
    }

    private fun stagesOf(model: String): JsonArray = models.getAsJsonObject(model).getAsJsonArray("stages")

    private fun JsonObject.str(key: String) = get(key).asString

    private fun JsonObject.bool(key: String) = get(key).asBoolean

    private fun JsonObject.arr(key: String) = getAsJsonArray(key).map { it.asString }

    /**
     * 模型清单和生成物必须一一对应。
     * 少了条目 = 选了那一档会直接抛"未知超分模型"（降级 Lanczos）；多了条目 = 有档位漏进菜单。
     */
    @Test
    fun `every model except lanczos has generated stages`() {
        val expected = (SuperRes.models - SuperRes.LANCZOS).toSet()
        assertEquals(expected, models.keySet())
    }

    /**
     * `mainRaw` 必须逐趟等于"此刻 MAIN 是否还是原始视频帧"。
     *
     * 规则与运行时 `Anime4kProgram.init` 里那句
     * `if (!d.isFinal && d.save == "MAIN") currentMain = out` 完全一致 —— 两边任何一侧改动
     * 而忘了另一侧，这条用例就会红。
     */
    @Test
    fun `mainRaw matches whether main is still the raw frame`() {
        models.keySet().forEach { model ->
            var mainRaw = true
            stagesOf(model).forEachIndexed { i, el ->
                val st = el.asJsonObject
                assertEquals(
                    "$model 第 $i 趟（${st.str("file")}）的 mainRaw",
                    mainRaw, st.bool("mainRaw")
                )
                if (!st.bool("final") && st.str("save") == "MAIN") mainRaw = false
            }
        }
    }

    /**
     * 把 `mainRaw` 和生成物源码对起来 —— 抓的是"算对了但没写进 shader"这类偏差。
     * 原始帧不拆包（`texture2D` 直接取），打包过的中间图要拆包（`* 2.0 - 1.0`）。
     */
    @Test
    fun `generated shaders read MAIN and HOOKED with the declared convention`() {
        models.keySet().forEach { model ->
            stagesOf(model).forEach { el ->
                val st = el.asJsonObject
                val file = st.str("file")
                val text = File(generated, file).readText()
                val expectedRaw = st.bool("mainRaw")
                listOf("MAIN", "HOOKED").forEach { name ->
                    val def = Regex("""#define ${name}_tex\(p\) (.*)""").find(text)
                        ?: return@forEach
                    val actualRaw = !def.groupValues[1].contains("* 2.0 - 1.0")
                    assertEquals(
                        "$model/$file 里 $name 取的是" + (if (actualRaw) "原始值" else "拆包值") +
                            "，但此处绑定的缓冲要求" + (if (expectedRaw) "原始值" else "拆包值"),
                        expectedRaw, actualRaw
                    )
                }
            }
        }
    }

    /** 每一趟声明的纹理（`textures` / `rawTextures`）都必须与生成物里的宏声明一致。 */
    @Test
    fun `declared textures match the generated macros`() {
        models.keySet().forEach { model ->
            stagesOf(model).forEach { el ->
                val st = el.asJsonObject
                val text = File(generated, st.str("file")).readText()
                st.arr("textures").forEach { name ->
                    assertTrue(
                        "$model/${st.str("file")}: 声明了 $name 但生成物没有 ${name}_tex 宏",
                        Regex("""#define ${name}_tex\(p\)""").containsMatchIn(text)
                    )
                }
                // rawTextures 是 mainRaw 的展开，两者必须自洽（MAIN 是否原始值）
                assertEquals(
                    "$model/${st.str("file")}: rawTextures 与 mainRaw 不一致",
                    st.bool("mainRaw"), st.arr("rawTextures").contains("MAIN")
                )
            }
        }
    }

    /** 链结构：唯一 final、必须在链尾、必须是写真实输出的那趟；放大只允许出现在链尾。 */
    @Test
    fun `chain structure is sound`() {
        models.keySet().forEach { model ->
            val stages = stagesOf(model)
            assertTrue("$model 没有渲染趟", stages.size() > 0)
            val finals = stages.mapIndexedNotNull { i, el ->
                if (el.asJsonObject.bool("final")) i else null
            }
            assertEquals("$model 的 final 趟数", 1, finals.size)
            assertEquals("$model 的 final 必须是链尾", stages.size() - 1, finals[0])
            assertEquals(
                "$model 的 final 趟必须写 MAIN（否则输出缓冲拿不到画面）",
                "MAIN", stages[finals[0]].asJsonObject.str("save")
            )
            stages.forEachIndexed { i, el ->
                val st = el.asJsonObject
                if (st.get("scale").asInt != 1) {
                    assertEquals("$model/${st.str("file")} 非链尾却放大", stages.size() - 1, i)
                }
                if (i < stages.size() - 1) {
                    assertTrue(
                        "$model/${st.str("file")} 非链尾却标记 final",
                        !st.bool("final")
                    )
                }
            }
        }
    }

    /** 每趟读的纹理都必须在此前被写过（MAIN / HOOKED 是输入帧特例）。 */
    @Test
    fun `no stage reads a texture that has not been produced yet`() {
        models.keySet().forEach { model ->
            val available = mutableSetOf("MAIN", "HOOKED")
            stagesOf(model).forEach { el ->
                val st = el.asJsonObject
                st.arr("textures").forEach { t ->
                    assertTrue(
                        "$model/${st.str("file")}: 读取了尚未产生的纹理 $t",
                        t in available
                    )
                }
                val save = st.str("save")
                if (save != "MAIN") available.add(save)
                // 自写回会多开一张缓冲（运行时把 STATSMAX 改名成 STATSMAX_v2）
                available.add(save + "_v2")
            }
        }
    }

    /**
     * 高光钳制的摆放：统计两趟必须在链首（统计图必须来自原始画面），钳制一趟有且只有一次。
     * 纯放大（A4K_UP_S）与纯降噪（DN_*）**不带**钳制 —— 它们不做线稿重建，不存在 CNN 过冲。
     */
    @Test
    fun `highlight clamp is placed as designed`() {
        val clamped = setOf(
            SuperRes.A4K_3D, SuperRes.A4K_BAL, SuperRes.A4K_HQ,
            SuperRes.A4K_RST, SuperRes.A4K_SOFT
        )
        models.keySet().forEach { model ->
            val stages = stagesOf(model)
            val clamps = stages.mapIndexedNotNull { i, el ->
                if (el.asJsonObject.str("src") == "Anime4K_Clamp_Highlights.glsl") i else null
            }
            if (model !in clamped) {
                assertEquals("$model 不该带高光钳制", emptyList<Int>(), clamps)
                return@forEach
            }
            // 前两趟 = 统计（save=STATSMAX），最后一趟 = 钳制（save=MAIN）
            assertEquals("$model 的钳制相关趟数", 3, clamps.size)
            assertEquals(listOf(0, 1), clamps.take(2))
            stages.take(2).forEach {
                assertEquals("$model 统计趟必须 save=STATSMAX", "STATSMAX", it.asJsonObject.str("save"))
                assertTrue("$model 统计趟读的是原始画面", it.asJsonObject.bool("mainRaw"))
            }
            val apply = stages[clamps[2]].asJsonObject
            assertEquals("$model 钳制趟必须 save=MAIN", "MAIN", apply.str("save"))
        }
    }

    /**
     * "只修复 / 只降噪"这五档的链尾都在直接读 `HOOKED`
     * （不是"读 MAIN、只把 MAIN 标对"就不出错的那种结构），所以它们的打包约定必须钉死：
     *
     * - 纯降噪三档（DN_*）：整条链没有任何一趟 `SAVE MAIN`，链尾读到的还是原始视频帧
     *   → `mainRaw = true`（若把它当成打包值 `2c-1` 解码，画面会整体变深）；
     * - 仅修复两档（A4K_RST / A4K_SOFT）：钳制趟排在 Restore 之后，此前已有一趟 `SAVE MAIN`，
     *   链尾读到的是打包过的修复结果 → `mainRaw = false`。
     *
     * 这两个期望值故意写死：挪动钳制趟的位置时，这条用例会先提醒重算约定。
     */
    @Test
    fun `tail stage of the fix only and denoise models is pinned`() {
        listOf(SuperRes.DN_MEAN, SuperRes.DN_MEDIAN, SuperRes.DN_MODE).forEach { model ->
            val stages = stagesOf(model)
            val last = stages[stages.size() - 1].asJsonObject
            assertTrue("$model 链尾必须是 final", last.bool("final"))
            assertTrue("$model 链尾必须声明 HOOKED", last.arr("textures").contains("HOOKED"))
            assertTrue("$model 链尾直接处理原始帧 → 不拆包", last.bool("mainRaw"))
        }
        listOf(SuperRes.A4K_RST, SuperRes.A4K_SOFT).forEach { model ->
            val stages = stagesOf(model)
            val last = stages[stages.size() - 1].asJsonObject
            assertTrue("$model 链尾必须是 final 钳制趟", last.bool("final"))
            assertEquals(
                "$model 链尾应当是钳制趟",
                "Anime4K_Clamp_Highlights.glsl", last.str("src")
            )
            assertTrue("$model 链尾必须声明 HOOKED", last.arr("textures").contains("HOOKED"))
            assertTrue("$model 链尾处理的是修复后的中间图 → 要拆包", !last.bool("mainRaw"))
        }
        // A4K_3D 的卷积趟不写 MAIN，所以中段那趟钳制读的仍是原始帧（这是它没变深的唯一原因）
        val clamp3d = stagesOf(SuperRes.A4K_3D).first {
            val o = it.asJsonObject
            o.str("src") == "Anime4K_Clamp_Highlights.glsl" && o.str("save") == "MAIN"
        }.asJsonObject
        assertTrue("A4K_3D 的中段钳制趟读的是原始帧", clamp3d.bool("mainRaw"))
        assertTrue("A4K_3D 的钳制趟不是链尾", !clamp3d.bool("final"))
    }
}
