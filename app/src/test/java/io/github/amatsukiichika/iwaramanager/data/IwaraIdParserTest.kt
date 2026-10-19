package io.github.amatsukiichika.iwaramanager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 文件名 → iwara ID 解析的回归用例。
 *
 * 核心回归：iwara ID 是 base62 随机串，纯字母 ID 完全合法
 * （14 位全字母的概率约 8.5%），要求"必须同时含字母和数字"会漏掉它们。
 * 下面前三个用例是真实文件名，必须全部解析成功。
 */
class IwaraIdParserTest {

    /* ---------- 真实文件名（纯字母 ID） ---------- */

    @Test
    fun `纯字母 ID 样例 1`() {
        assertEquals(
            "IeGtwtMrjKeEUa",
            IwaraIdParser.parse("Iwara - (MMD) Hatsune Miku - Cynical Night Plan [IeGtwtMrjKeEUa] [Source].mp4")
        )
    }

    @Test
    fun `纯字母 ID 样例 2`() {
        assertEquals(
            "bjBZHdQifBkOBB",
            IwaraIdParser.parse("Iwara - Suisei _ Towa - LADY CRAZY [bjBZHdQifBkOBB] [Source].mp4")
        )
    }

    @Test
    fun `纯字母 ID 样例 3（含日文标题与全角感叹号）`() {
        assertEquals(
            "aePjwcgvfnKtVK",
            IwaraIdParser.parse("Iwara - 茜ちゃんと柄ビキニ！ [aePjwcgvfnKtVK] [Source].mp4")
        )
    }

    @Test
    fun `纯字母 ID 不再因缺少数字被拒掉（各类长度）`() {
        assertEquals("abcdefghijkl", IwaraIdParser.parse("x [abcdefghijkl].mp4"))
        assertEquals("ABCDEFGHIJKLMNOPQRST", IwaraIdParser.parse("x [ABCDEFGHIJKLMNOPQRST].mp4"))
        assertEquals("AbCdEfGhIjKl", IwaraIdParser.parse("x [AbCdEfGhIjKl].mp4"))
    }

    /* ---------- 括号形态 ---------- */

    @Test
    fun `方括号包裹的 ID`() {
        assertEquals(
            "WiDWwZjj59KQSq",
            IwaraIdParser.parse("Iwara - 【Keqing 刻晴】Conqueror [WiDWwZjj59KQSq] [Source].mp4")
        )
    }

    @Test
    fun `全角方括号`() {
        assertEquals("abcdefghijkl", IwaraIdParser.parse("标题【abcdefghijkl】.mp4"))
    }

    @Test
    fun `圆括号与花括号（半角全角）`() {
        assertEquals("abcdefghijkl", IwaraIdParser.parse("title (abcdefghijkl).mp4"))
        assertEquals("abcdefghijkl", IwaraIdParser.parse("title {abcdefghijkl}.mp4"))
        assertEquals("abcdefghijkl", IwaraIdParser.parse("title （abcdefghijkl）.mp4"))
    }

    @Test
    fun `ID 写在扩展名之后`() {
        assertEquals("IeGtwtMrjKeEUa", IwaraIdParser.parse("Iwara - title.mp4 [IeGtwtMrjKeEUa]"))
    }

    @Test
    fun `多个括号时取最靠后的合规候选（下载模板把 ID 放在末尾短标记之前）`() {
        assertEquals(
            "IeGtwtMrjKeEUa",
            IwaraIdParser.parse("SomeLongCategory17x [IeGtwtMrjKeEUa] [Source].mp4")
        )
        assertEquals(
            "IeGtwtMrjKeEUa",
            IwaraIdParser.parse("Iwara - title [IeGtwtMrjKeEUa][Source].mp4")
        )
    }

    @Test
    fun `括号里有空白`() {
        assertEquals("abcdefghijkl", IwaraIdParser.parse("title [ abcdefghijkl ].mp4"))
    }

    /* ---------- 其他形态 ---------- */

    @Test
    fun `结尾下划线加 ID`() {
        assertEquals(
            "ka2k2tjLvw8hGg",
            IwaraIdParser.parse("2023-10-15_Honkai-Star Rail Clara DONUT HOLE - 崩壊：スターレイル クラーラ ドーナツホール_ka2k2tjLvw8hGg.mp4")
        )
    }

    @Test
    fun `iwara 前缀下划线`() {
        assertEquals("IeGtwtMrjKeEUa", IwaraIdParser.parse("iwara_IeGtwtMrjKeEUa.mp4"))
    }

    @Test
    fun `纯 iwara 链接`() {
        assertEquals(
            "1ywe1sbkqwumpdxz5",
            IwaraIdParser.parse("https://www.iwara.tv/video/1ywe1sbkqwumpdxz5")
        )
        assertEquals(
            "k2ayoueezfkx6gvq",
            IwaraIdParser.parse("https://ecchi.iwara.tv/video/k2ayoueezfkx6gvq")
        )
    }

    @Test
    fun `文件名只有 ID 本身（含重复下载产生的 (1) 后缀）`() {
        assertEquals("blggmfno8ghl725bg", IwaraIdParser.parse("blggmfno8ghl725bg.mp4"))
        assertEquals("IeGtwtMrjKeEUa", IwaraIdParser.parse("IeGtwtMrjKeEUa (1).mp4"))
    }

    /* ---------- 不能误判（假阳性同样是"识别不到"：会拿一个错的 ID 去请求元数据） ---------- */

    @Test
    fun `排除类似标题词的普通单词`() {
        // 纯字母普通词作为**整名主干**时仍拒绝（但括号/尾部标记里的纯字母串必须当 ID，见开头三例）。
        assertNull(IwaraIdParser.parse("PlayStation Folder 高清收藏.mp4"))
        // 纯数字串（无字母）也应被拒绝。
        assertNull(IwaraIdParser.parse("20231015 20231016 20231017.mp4"))
        // 日期带连字符不会命中。
        assertNull(IwaraIdParser.parse("2023-10-15 some video.mp4"))
    }

    @Test
    fun `不再把标题里的长字母数字词误当 ID（旧实现的假阳性）`() {
        assertNull(IwaraIdParser.parse("Iwara - MMD2019Collection.mp4"))
        assertNull(IwaraIdParser.parse("Iwara - SomeLongTitleHere.mp4"))
    }

    @Test
    fun `分辨率与短标记不会被当成 ID`() {
        assertNull(IwaraIdParser.parse("Iwara - title [1080p60fps].mp4"))
        assertNull(IwaraIdParser.parse("Iwara - title [Source] [Online].mp4"))
    }

    @Test
    fun `主干是纯数字串时拒绝（那是日期不是 ID）`() {
        assertNull(IwaraIdParser.parse("20231015123045.mp4"))
    }

    @Test
    fun `空输入为 null`() {
        assertNull(IwaraIdParser.parse(null))
        assertNull(IwaraIdParser.parse(""))
        assertNull(IwaraIdParser.parse("   "))
    }
}
