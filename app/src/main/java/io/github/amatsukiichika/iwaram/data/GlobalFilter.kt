package io.github.amatsukiichika.iwaram.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 全局筛选器 —— **最后一道过滤关卡**：
 * 无论其它设置如何，在线各页与本地库的列表最终都要过这一关。
 *
 * 规则：
 * - **总开关**：关掉 = 完全不筛（默认关闭，且**不持久化**，重启即回默认）；
 * - **标题** 与 **标签** 各自独立，各有黑/白名单（黑 = 隐藏命中的 / 白 = 只留命中的）；
 * - **两行的"模糊 / 精确"都没有开关，由规则自己决定**：
 *   * 标题：**一律模糊**（标题里含有输入的字就算命中；黑/白名单只是反过来）；
 *   * 标签：**点选**到的已存在标签 ⇒ **精确**（只命中它自己）、**手打**的自由文字 ⇒ **模糊**（含子串即命中）。
 * - 多标签：检索侧（在线搜索 / 本地库）是**全都含**（与 iwara 网站一致），
 *   筛选器侧**黑名单 = 命中任一即隐藏**、**白名单 = 全都含才保留**。
 * - 两条规则之间是 **AND**（都通过才留下）；pattern 为空 = 该条规则**不生效**。
 *
 * 纯函数 + 单测（见 GlobalFilterTest）：规则组合多，写成纯函数才不会在接线时被改歪。
 */
enum class ListMode { BLACK, WHITE }

/** 单条匹配的粗细（内部使用；标题由名单模式决定，标签由"点选/手打"决定）。 */
enum class MatchMode { FUZZY, EXACT }

/**
 * 标题（名字）规则：单串 + 名单模式。
 *
 * **标题一律按"模糊"判**（标题里**含有**输入的字就算命中），黑名单与白名单只是"反过来"：
 *   黑名单：标题含有 ⇒ 隐藏；白名单：标题含有 ⇒ 保留。
 */
data class NameRule(
    val pattern: String = "",
    val list: ListMode = ListMode.BLACK
) {
    val active: Boolean get() = pattern.isNotBlank()

    /** 恒为模糊（含子串即命中），不按名单模式分档。 */
    val match: MatchMode get() = MatchMode.FUZZY
}

/**
 * 一条已确认的标签条件。
 *
 * @param exact true = 只命中**完全相等**的标签（从联想里点选到的，必然是真实存在的标签）；
 *              false = **包含**即命中（手打的自由文字）。
 */
data class TagTerm(val text: String, val exact: Boolean = false)

/**
 * 标签规则：一组标签条件 + 一个"正在打的字"。
 *
 * [draft] 是输入框里的实时文本（按**模糊**算、立刻生效），回车或点选联想后它会变成
 * [terms] 里的一条 chip（点选 ⇒ exact）。
 */
data class TagRule(
    val terms: List<TagTerm> = emptyList(),
    val draft: String = "",
    val list: ListMode = ListMode.BLACK
) {
    val active: Boolean get() = terms.isNotEmpty() || draft.isNotBlank()

    /** 生效的条件 = 已确认的 chip + 正在打的那个字（模糊）。 */
    fun effectiveTerms(): List<TagTerm> =
        if (draft.isBlank()) terms else terms + TagTerm(draft.trim(), exact = false)
}

/** 全局筛选器的整体状态（不持久化：进程活着一份，重启即回默认）。 */
data class GlobalFilterState(
    val enabled: Boolean = false,
    val name: NameRule = NameRule(),
    val tag: TagRule = TagRule()
) {
    /** 是否"真的在过滤"——总开关开着、且至少有一条规则生效。 */
    val effective: Boolean
        get() = enabled && (name.active || tag.active)
}

/**
 * 这条内容该不该留下。
 *
 * @param name 用于标题筛选的文本：本地取 `title`（空则回退 `fileName`），在线取视频标题。
 * @param tags 用于标签筛选的标签文字：本地取 `tagsCsv` 切分结果，在线取 tag 列表的 id。
 *   **传 null = 这个列表根本没有标签字段**（在线图片 / 帖子）⇒ 标签规则**豁免**它，
 *   而不是"当作没命中"（后者会让"只留命中"的白名单把整页清空，用户只会当成 bug）。
 */
fun GlobalFilterState.keep(name: String, tags: List<String>?): Boolean {
    if (!enabled) return true
    if (this.name.active && !nameRulePasses(this.name, name)) return false
    if (tags != null && tag.active && !tagRulePasses(this.tag, tags)) return false
    return true
}

/** 标题规则：`hit` = 标题命中；黑名单要求"不命中"，白名单要求"命中"。 */
private fun nameRulePasses(rule: NameRule, name: String): Boolean {
    val hit = when (rule.match) {
        MatchMode.FUZZY -> name.contains(rule.pattern, ignoreCase = true)
        MatchMode.EXACT -> name.equals(rule.pattern, ignoreCase = true)
    }
    return hit == (rule.list == ListMode.WHITE)
}

/**
 * 标签规则。每个条件各自按 精确/模糊 判定"命中"，再按名单模式汇总：
 * - **黑名单**：命中**任一**条件即隐藏（`!hits.any`）；
 * - **白名单**：必须**全都含**才保留（`hits.all`）。
 */
private fun tagRulePasses(rule: TagRule, tags: List<String>): Boolean {
    val terms = rule.effectiveTerms()
    if (terms.isEmpty()) return true
    val hits = terms.map { term ->
        tags.any { tag ->
            when {
                term.exact -> tag.equals(term.text, ignoreCase = true)
                else -> tag.contains(term.text, ignoreCase = true)
            }
        }
    }
    return when (rule.list) {
        ListMode.BLACK -> !hits.any { it }
        ListMode.WHITE -> hits.all { it }
    }
}

/* ---------- 过滤落地 ---------- */

/** 本地条目 → (名字, 标签) 适配。 */
fun localKeysOf(title: String?, fileName: String?, tagsCsv: String?): Pair<String, List<String>> =
    (title?.takeIf { it.isNotBlank() } ?: fileName.orEmpty()) to
        tagsCsv.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** 在线条目 → (名字, 标签) 适配（标签取 id，即卡片上显示的文字）。 */
fun onlineKeysOf(title: String?, tagIds: List<String?>): Pair<String, List<String>> =
    title.orEmpty() to tagIds.filterNotNull().filter { it.isNotBlank() }

/* ---------- 各列表的**统一取键**（调用处只写 `globalFilter = ::videoKeys` 这种） ---------- */

/** 在线视频：名字 = 标题，标签 = `tag.id`（卡片上显示的文字）。 */
fun videoKeys(v: IwaraVideoItem): Pair<String, List<String>?> =
    onlineKeysOf(v.title, v.tags.orEmpty().map { it.id })

/** 在线图片：**标签返回 null** —— 图片列表的 DTO 里没有 tags 字段 ⇒ 标签规则豁免（只受标题规则影响）。 */
fun imageKeys(i: IwaraImageItem): Pair<String, List<String>?> = i.title.orEmpty() to null

/** 在线帖子：同图片，无标签字段。 */
fun postKeys(p: IwaraPostItem): Pair<String, List<String>?> = p.title.orEmpty() to null

/** 本地条目（本地库 / 作者页本地行 / 下载条目）：名字 = 标题（空则文件名），标签 = `tagsCsv`。 */
fun localVideoKeys(v: VideoEntity): Pair<String, List<String>?> =
    localKeysOf(v.title, v.fileName, v.tagsCsv)

/**
 * 列表级过滤（**最后一道**）：不生效时**原样返回同一个列表实例**（一次遍历都不做，
 * 也不会让上层 Remember 的缓存失效）。
 *
 * `keysOf` 的 tags 传 **null** 表示"这个列表没有标签字段" ⇒ 标签规则豁免（见 [keep] 的 @param）。
 */
fun <T> List<T>.filterBy(
    state: GlobalFilterState,
    keysOf: (T) -> Pair<String, List<String>?>
): List<T> {
    if (!state.effective) return this
    return filter { item ->
        val (name, tags) = keysOf(item)
        state.keep(name, tags)
    }
}

/* ---------- 标签的"多值"匹配（检索侧用：**全都含**） ---------- */

/**
 * 一组标签是否**全都命中**（在线搜索 / 本地库 tag 筛选共用；与 iwara 网站行为一致）。
 *
 * @param selected 已选标签（大小写不敏感、去重）
 * @param tags 该条目自身的标签
 */
fun containsAllTags(selected: List<String>, tags: List<String>): Boolean =
    selected.isEmpty() || selected.all { wanted -> tags.any { it.equals(wanted, ignoreCase = true) } }

/**
 * 全局筛选器的**进程内状态容器**（不持久化：重启即回默认）。
 *
 * 用 `StateFlow` 而非 Compose 的 `mutableStateOf`：
 * - 本地库的过滤发生在 `VideoViewModel` 的**后台重算**里，它需要"值变了就重算"这条通路
 *   （[VideoViewModel] 在 init 里订阅本流），Compose 的 state 没有订阅回调；
 * - Compose 侧照样能读：`GlobalFilterHolder.state.collectAsStateWithLifecycle()`。
 *
 * `MutableStateFlow` 对**相等值不重复发射** ⇒ 界面上点同一个档位不会白白触发一次全库重算。
 */
object GlobalFilterHolder {

    private val _state = MutableStateFlow(GlobalFilterState())

    /** 供 Compose / 协程订阅。输入框等需要「立刻回显」的地方用这一条。 */
    val state: StateFlow<GlobalFilterState> = _state.asStateFlow()

    /**
     * **列表消费端**专用的防抖视图：标题 pattern / 标签 draft 这类逐键文本输入防抖
     * [TEXT_DEBOUNCE_MS] 后再生效；总开关 / chip / 黑白名单等结构变化立即生效。
     * 输入框本身仍订阅 [state]（立刻回显，不能滞后）；列表重算（InfiniteGrid / 本地库 /
     * 作者页 / 观看历史）一律订阅这一条 —— 打字期间不再每键把所有可见列表全量重算一遍。
     */
    private val _listState = MutableStateFlow(GlobalFilterState())
    val listState: StateFlow<GlobalFilterState> = _listState.asStateFlow()

    /** 纯文本输入的防抖窗口（毫秒）：与搜索框既有防抖一致的手感。 */
    private const val TEXT_DEBOUNCE_MS = 250L

    private val debounceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var textDebounceJob: Job? = null

    /** 文本字段清零后的副本：用于比较「非文本部分」有没有变化。 */
    private fun GlobalFilterState.stripText(): GlobalFilterState = copy(
        name = name.copy(pattern = ""),
        tag = tag.copy(draft = "")
    )

    init {
        debounceScope.launch {
            _state.collect { st ->
                val current = _listState.value
                if (st == current) return@collect
                textDebounceJob?.cancel()
                if (st.stripText() != current.stripText()) {
                    // 结构变化（总开关 / chip / 黑白名单）：立即生效。
                    _listState.value = st
                } else {
                    // 纯文本输入：防抖后取最新值（连打只算最后一次）。
                    textDebounceJob = launch {
                        delay(TEXT_DEBOUNCE_MS)
                        _listState.value = _state.value
                    }
                }
            }
        }
    }

    /** 同步取当前值（非 Compose 的调用点用）。 */
    val value: GlobalFilterState get() = _state.value

    fun setEnabled(v: Boolean) = set { it.copy(enabled = v) }

    /* ---- 标题行（无档位开关，标题恒为模糊） ---- */

    fun setNamePattern(p: String) = set { it.copy(name = it.name.copy(pattern = p)) }
    fun setNameList(m: ListMode) = set { it.copy(name = it.name.copy(list = m)) }

    /* ---- 标签行（多值；点选=精确、手打=模糊，同样没有档位开关） ---- */

    /** 输入框里的实时文本（模糊、立刻生效）。 */
    fun setTagDraft(p: String) = set { it.copy(tag = it.tag.copy(draft = p)) }

    /** 把 [text] 确认为一条 chip；[exact] = 是否"从联想点选到的真实标签"。 */
    fun addTagTerm(text: String, exact: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        set { st ->
            val exists = st.tag.terms.any { it.text.equals(t, ignoreCase = true) }
            val terms = if (exists) st.tag.terms else st.tag.terms + TagTerm(t, exact)
            st.copy(tag = st.tag.copy(terms = terms, draft = ""))
        }
    }

    /** 取消一条 chip（卡片上再点一次同一个 tag 也走这里）。 */
    fun removeTagTerm(text: String) = set { st ->
        st.copy(tag = st.tag.copy(terms = st.tag.terms.filterNot { it.text.equals(text, true) }))
    }

    /** 卡片 tag 点击的"追加 / 再点取消"：返回操作后它是否还在选中集合里。 */
    fun toggleTagTerm(text: String, exact: Boolean = true) {
        val t = text.trim()
        if (t.isEmpty()) return
        val has = value.tag.terms.any { it.text.equals(t, ignoreCase = true) }
        if (has) removeTagTerm(t) else addTagTerm(t, exact)
    }

    fun clearTagTerms() = set { it.copy(tag = it.tag.copy(terms = emptyList(), draft = "")) }

    fun setTagList(m: ListMode) = set { it.copy(tag = it.tag.copy(list = m)) }

    /** 清空回默认（总开关也关掉）—— 面板上的「清空」。 */
    fun reset() = set { GlobalFilterState() }

    private fun set(f: (GlobalFilterState) -> GlobalFilterState) {
        _state.value = f(_state.value)
    }
}
