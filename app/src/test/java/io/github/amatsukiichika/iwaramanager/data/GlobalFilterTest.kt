package io.github.amatsukiichika.iwaramanager.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局筛选器规则的单测。
 *
 * 规则语义：
 * - 标题行的"模糊/精确"没有开关：黑名单即精确、白名单即模糊；
 * - 标签行的"模糊/精确"也没有开关：点选到的已存在标签即精确、手打的自由文字即模糊；
 * - 多标签：检索侧（[containsAllTags]）要求全都含；筛选器黑名单命中任一即隐藏、白名单要求全都含。
 *
 * 这些组合最容易在接线时被写歪，所以规则本体是纯函数、并在这里逐条钉住。
 */
class GlobalFilterTest {

    private fun state(
        enabled: Boolean = true,
        name: NameRule = NameRule(),
        tag: TagRule = TagRule()
    ) = GlobalFilterState(enabled, name, tag)

    /* ---------- 总开关 ---------- */

    @Test
    fun `总开关关闭时一律放行`() {
        val s = state(
            enabled = false,
            name = NameRule("SP", ListMode.BLACK),
            tag = TagRule(listOf(TagTerm("SP", exact = true)), list = ListMode.WHITE)
        )
        assertTrue(s.keep("SP 特辑", listOf("SP")))
    }

    @Test
    fun `规则为空时该条不生效`() {
        val s = state(name = NameRule("   "), tag = TagRule(terms = emptyList(), draft = ""))
        assertTrue(s.keep("随便什么标题", listOf("随便什么标签")))
        assertFalse("两条都空 ⇒ 不算在过滤", s.effective)
    }

    /* ---------- 标题：一律模糊（黑=隐藏含的 / 白=只留含的） ---------- */

    @Test
    fun `标题黑名单_模糊_含有就隐藏`() {
        val s = state(name = NameRule("SP", ListMode.BLACK))
        assertTrue("黑名单也是模糊", s.name.match == MatchMode.FUZZY)
        assertFalse("标题含 SP ⇒ 隐藏", s.keep("【SP】限定", emptyList()))
        assertFalse(s.keep("SPEED 测试", emptyList()))
        assertFalse(s.keep("SP", emptyList()))
        assertTrue(s.keep("普通视频", emptyList()))
    }

    @Test
    fun `标题白名单_模糊_含有就留`() {
        val s = state(name = NameRule("SP", ListMode.WHITE))
        assertTrue(s.name.match == MatchMode.FUZZY)
        assertTrue(s.keep("【SP】限定", emptyList()))
        assertTrue(s.keep("SPEED 测试", emptyList()))
        assertFalse(s.keep("普通视频", emptyList()))
    }

    /* ---------- 标签：点选=精确 / 手打=模糊 ---------- */

    @Test
    fun `标签黑名单_手打是模糊_含子串也算命中`() {
        val s = state(tag = TagRule(terms = emptyList(), draft = "SP", list = ListMode.BLACK))
        assertFalse("SP 命中", s.keep("甲", listOf("SP")))
        assertFalse("SPEED 也含 SP ⇒ 算命中", s.keep("乙", listOf("SPEED")))
        assertTrue(s.keep("丙", listOf("MMD")))
    }

    @Test
    fun `标签黑名单_点选是精确_只挡同名标签`() {
        val s = state(tag = TagRule(listOf(TagTerm("SP", exact = true)), list = ListMode.BLACK))
        assertFalse("完全相等的 SP 被挡", s.keep("甲", listOf("SP")))
        assertTrue("SPEED 不是精确匹配 ⇒ 留着", s.keep("乙", listOf("SPEED")))
        assertTrue(s.keep("丙", listOf("mmd", "3d")))
    }

    @Test
    fun `标签黑名单_多条_命中任一即隐藏`() {
        val s = state(
            tag = TagRule(
                listOf(TagTerm("AI", exact = true), TagTerm("FUTA", exact = true)),
                list = ListMode.BLACK
            )
        )
        assertFalse("只挂 AI 也隐藏", s.keep("甲", listOf("AI")))
        assertFalse("只挂 FUTA 也隐藏", s.keep("乙", listOf("FUTA")))
        assertFalse("两个都挂更要隐藏", s.keep("丙", listOf("AI", "FUTA")))
        assertTrue("都不挂 ⇒ 留", s.keep("丁", listOf("MMD")))
    }

    @Test
    fun `标签白名单_多条_必须全都含`() {
        val s = state(
            tag = TagRule(
                listOf(TagTerm("MMD", exact = true), TagTerm("3DCG", exact = true)),
                list = ListMode.WHITE
            )
        )
        assertTrue("两个都有 ⇒ 留", s.keep("甲", listOf("MMD", "3DCG", "舞蹈")))
        assertFalse("只有一个 ⇒ 筛掉", s.keep("乙", listOf("MMD")))
        assertFalse(s.keep("丙", listOf("3DCG")))
    }

    @Test
    fun `标签白名单_精确条件只认同名标签`() {
        val s = state(tag = TagRule(listOf(TagTerm("SP", exact = true)), list = ListMode.WHITE))
        assertTrue(s.keep("甲", listOf("SP")))
        assertTrue("多标签里有一个精确命中就留", s.keep("乙", listOf("MMD", "SP")))
        assertFalse(s.keep("丙", listOf("SPEED")))
    }

    @Test
    fun `没有标签的内容在白名单下会被筛掉`() {
        val s = state(tag = TagRule(terms = emptyList(), draft = "SP", list = ListMode.WHITE))
        assertFalse(s.keep("无标签视频", emptyList()))
    }

    /* ---------- 两条规则是 AND ---------- */

    @Test
    fun `标题与标签必须同时通过`() {
        val s = state(
            name = NameRule("MMD", ListMode.WHITE),
            tag = TagRule(terms = emptyList(), draft = "SP", list = ListMode.BLACK)
        )
        assertTrue(s.keep("【MMD】作品", listOf("舞蹈")))
        assertFalse("标题通过但标签命中黑名单 ⇒ 仍要被挡", s.keep("【MMD】作品", listOf("SP")))
        assertFalse("标签没问题但标题不在白名单 ⇒ 被挡", s.keep("普通视频", listOf("舞蹈")))
    }

    /* ---------- 标签字段缺失的列表（在线图片 / 帖子） ---------- */

    @Test
    fun `标签传 null 时标签规则整条豁免`() {
        val white = state(tag = TagRule(terms = emptyList(), draft = "SP", list = ListMode.WHITE))
        assertTrue("拿不到标签 ⇒ 白名单也不能把它清掉", white.keep("图集", null))
        val black = state(tag = TagRule(terms = emptyList(), draft = "SP", list = ListMode.BLACK))
        assertTrue(black.keep("图集", null))
        assertFalse(
            "标题规则照旧生效（模糊 ⇒ 标题含 SP 就挡）",
            state(name = NameRule("SP", ListMode.BLACK)).keep("SP", null)
        )
        assertFalse(
            "同样，含 SP 的标题也挡",
            state(name = NameRule("SP", ListMode.BLACK)).keep("SP 图集", null)
        )
        assertFalse(
            "对照：标签『已知但为空』是另一回事 —— 白名单下会被筛掉",
            white.keep("图集", emptyList())
        )
    }

    /* ---------- 适配层与统一取键 ---------- */

    @Test
    fun `本地适配_标题为空时回退文件名_标签按逗号切分去空`() {
        val (a, ta) = localKeysOf(null, "file.mp4", "SP, MMD ,,3D")
        assertTrue(a == "file.mp4")
        assertTrue(ta == listOf("SP", "MMD", "3D"))
        val (b, tb) = localKeysOf("真实标题", "file.mp4", null)
        assertTrue(b == "真实标题")
        assertTrue(tb.isEmpty())
    }

    @Test
    fun `在线适配_过滤空标签`() {
        val (n, t) = onlineKeysOf("标题", listOf("SP", null, "", "MMD"))
        assertTrue(n == "标题")
        assertTrue(t == listOf("SP", "MMD"))
    }

    @Test
    fun `取键_在线视频取标题与 tag_id`() {
        val (n, t) = videoKeys(
            IwaraVideoItem(title = "标题", tags = listOf(IwaraTagRef("SP"), IwaraTagRef(null)))
        )
        assertTrue(n == "标题")
        assertTrue(t == listOf("SP"))
    }

    @Test
    fun `取键_图片与帖子没有标签字段_返回 null`() {
        assertTrue(imageKeys(IwaraImageItem(title = "图集")).second == null)
        assertTrue(postKeys(IwaraPostItem(title = "帖子")).second == null)
        assertTrue(imageKeys(IwaraImageItem(title = "图集")).first == "图集")
    }

    @Test
    fun `取键_本地条目_标题空则回退文件名`() {
        val (n, t) = localVideoKeys(
            VideoEntity(
                localUri = "u", fileName = "f.mp4", iwaraId = null, title = "",
                author = null, authorId = null, createdAt = null, viewCount = null,
                likeCount = null, commentCount = null, rating = null, thumbnailUrl = null,
                localThumbnailPath = null, relPath = null, tagsCsv = "MMD, SP", metaFetched = false,
                addedAt = 0L
            )
        )
        assertTrue(n == "f.mp4")
        assertTrue(t == listOf("MMD", "SP"))
    }

    /* ---------- 列表级过滤 ---------- */

    @Test
    fun `列表过滤_不生效时原样返回同一实例`() {
        val items = listOf("甲", "乙")
        val off = state(enabled = false, name = NameRule("甲", ListMode.BLACK))
        assertTrue("关着开关 ⇒ 连遍历都不做", items.filterBy(off) { it to null } === items)
        val empty = state(name = NameRule("  "))
        assertTrue(items.filterBy(empty) { it to null } === items)
        assertTrue(items.filterBy(state()) { it to emptyList() } === items)
    }

    @Test
    fun `列表过滤_标题与标签都过一遍`() {
        data class Row(val title: String, val tags: List<String>)
        val rows = listOf(
            Row("【MMD】作品", listOf("舞蹈")),
            Row("【MMD】作品", listOf("SP")),
            Row("普通视频", listOf("舞蹈"))
        )
        val s = state(
            name = NameRule("MMD", ListMode.WHITE),
            tag = TagRule(listOf(TagTerm("SP", exact = true)), list = ListMode.BLACK)
        )
        val kept = rows.filterBy(s) { it.title to it.tags }
        assertTrue(kept.size == 1)
        assertTrue(kept.first().title == "【MMD】作品" && kept.first().tags == listOf("舞蹈"))
    }

    /* ---------- 检索侧多标签：全都含 ---------- */

    @Test
    fun `多标签检索_必须全都含`() {
        val sel = listOf("MMD", "3DCG")
        assertTrue(containsAllTags(sel, listOf("MMD", "3DCG", "舞蹈")))
        assertTrue("大小写不敏感", containsAllTags(sel, listOf("mmd", "3dcg")))
        assertFalse(containsAllTags(sel, listOf("MMD")))
        assertFalse(containsAllTags(sel, listOf("3DCG")))
        assertTrue("空选择 = 不筛", containsAllTags(emptyList(), listOf("随便")))
    }

    /* ---------- 进程内状态容器 ---------- */

    @Test
    fun `状态容器_改动即生效_清空回默认`() {
        GlobalFilterHolder.reset()
        try {
            assertFalse(GlobalFilterHolder.value.effective)
            GlobalFilterHolder.setEnabled(true)
            GlobalFilterHolder.setTagDraft("SP")
            assertTrue(GlobalFilterHolder.value.effective)
            assertTrue(GlobalFilterHolder.value.tag.effectiveTerms() == listOf(TagTerm("SP", false)))

            GlobalFilterHolder.setTagList(ListMode.WHITE)
            assertTrue(GlobalFilterHolder.value.tag.list == ListMode.WHITE)

            GlobalFilterHolder.reset()
            assertTrue(GlobalFilterHolder.value == GlobalFilterState())
            assertFalse(GlobalFilterHolder.value.effective)
        } finally {
            GlobalFilterHolder.reset()
        }
    }

    @Test
    fun `状态容器_标签追加去重_切换取消`() {
        GlobalFilterHolder.reset()
        try {
            GlobalFilterHolder.addTagTerm("MMD", exact = true)
            GlobalFilterHolder.addTagTerm("mmd", exact = true)
            assertTrue("同一条不重复加（大小写不敏感）", GlobalFilterHolder.value.tag.terms.size == 1)
            assertTrue(GlobalFilterHolder.value.tag.terms.first().exact)

            GlobalFilterHolder.addTagTerm("3DCG", exact = true)
            assertTrue("草稿被清空", GlobalFilterHolder.value.tag.draft.isEmpty())
            assertTrue(GlobalFilterHolder.value.tag.terms.size == 2)

            // 卡片 tag 点击：追加 / 再点取消
            GlobalFilterHolder.toggleTagTerm("舞蹈")
            assertTrue(GlobalFilterHolder.value.tag.terms.size == 3)
            GlobalFilterHolder.toggleTagTerm("舞蹈")
            assertTrue(GlobalFilterHolder.value.tag.terms.size == 2)

            GlobalFilterHolder.removeTagTerm("3DCG")
            assertTrue(GlobalFilterHolder.value.tag.terms == listOf(TagTerm("MMD", true)))

            GlobalFilterHolder.clearTagTerms()
            assertTrue(GlobalFilterHolder.value.tag.terms.isEmpty())
        } finally {
            GlobalFilterHolder.reset()
        }
    }
}
