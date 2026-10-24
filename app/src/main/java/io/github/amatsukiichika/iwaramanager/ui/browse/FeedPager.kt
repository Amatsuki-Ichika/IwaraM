package io.github.amatsukiichika.iwaramanager.ui.browse

import androidx.compose.runtime.Immutable
import io.github.amatsukiichika.iwaramanager.data.IwaraImageItem
import io.github.amatsukiichika.iwaramanager.data.IwaraPostItem
import io.github.amatsukiichika.iwaramanager.data.IwaraVideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 分页信息流的通用状态：items + 当前页 + 加载/结束/错误标记。
 *
 * 标 `@Immutable` 是人工承诺而非编译器推断：该类被 [InfiniteGrid] 及信息流页面作为参数接收，
 * `items: List<T>`（接口类型）会被 Compose 视为 unstable，导致整棵子树无法跳过重组。
 * 该承诺成立的前提是 `items` 始终整体替换（[FeedPager.load] 以 `+` 拼出新列表），从不原地修改。
 */
@Immutable
data class FeedState<T>(
    val items: List<T> = emptyList(),
    val page: Int = -1,
    val loading: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null
)

/**
 * 三种在线条目的身份判据，与各网格调用点传给 [InfiniteGrid] 的 `itemKey` 逐字一致。
 *
 * iwara 采用偏移式分页（按 `page × limit` 取数），列表在翻页期间持续变化
 * （有新投稿、榜单变动），页边界必然重叠，同一支视频会同时出现在第 N 页与第 N+1 页。
 * Lazy 容器层的 key 唯一化（[uniqueLazyKeys]）只用于防崩溃，不做条目去重，
 * 去重必须落在页拼接这一步，因为只有这里知道哪些条目已经收过。
 */
internal object OnlineIdentity {
    fun video(v: IwaraVideoItem): String = v.id.orEmpty().ifEmpty { v.hashCode().toString() }
    fun image(i: IwaraImageItem): String = i.id.orEmpty().ifEmpty { i.hashCode().toString() }
    fun post(p: IwaraPostItem): String = p.id.orEmpty().ifEmpty { p.hashCode().toString() }
}

/**
 * 追加去重（纯函数，可单测）：保留 [previous] 全部并保序，再按序补入 [incoming] 中
 * 身份尚未出现过的条目。
 *
 * @return 合并后的列表 to 本次真正新增的条数；新增 0 条也是"到底了"的判据之一
 *   （见 [FeedPager.load]）：整页都是重复项时，若仍按"本页 = 满页"判定，
 *   列表会对空页持续请求（滑到底一直转圈）。
 */
internal fun <T> appendDistinct(
    previous: List<T>,
    incoming: List<T>,
    identity: (T) -> String
): Pair<List<T>, Int> {
    if (incoming.isEmpty()) return previous to 0
    val seen = HashSet<String>(previous.size + incoming.size)
    for (item in previous) seen.add(identity(item).ifBlank { "item" })
    val out = ArrayList<T>(previous.size + incoming.size)
    out.addAll(previous)
    var added = 0
    for (item in incoming) {
        if (seen.add(identity(item).ifBlank { "item" })) {
            out.add(item)
            added++
        }
    }
    return out to added
}

/**
 * 通用分页信息流加载器（视频/订阅/搜索/图片/帖子/收藏共用）。
 * - load(false)：顺序翻页，loading 中或已到底时忽略；
 * - load(true)：最高优先级强制刷新，先取消进行中的加载任务再从第 0 页重拉；
 *   以代数标记丢弃被取代任务的结果，旧任务晚到也不会覆盖新状态。
 *
 * 追加时按 [identity] 去重（上游偏移分页会跨页重叠，不去重就会重复显示），
 * 并把"本页去重后新增 0 条"也当作"到底了"。`page` 记录服务器页码（翻页语义不变），
 * `items` 记录去重后的展示列表，两者语义分离。
 */
class FeedPager<T>(
    private val scope: CoroutineScope,
    /** 单页条数上限，用于判定 endReached。 */
    private val pageSize: Int,
    /**
     * 条目身份（必填，与各调用点给 [InfiniteGrid] 的 `itemKey` 同一份判据）。
     * 显式必填的理由与 [InfiniteGrid.itemKey] 相同：留默认值只会在某个调用点悄悄退化成
     * "整页去重失败"，而那种错误既不报错也不崩，只会显示重复条目。
     */
    private val identity: (T) -> String,
    private val describeError: (Throwable) -> String
) {
    private val _state = MutableStateFlow(FeedState<T>())
    val state: StateFlow<FeedState<T>> = _state.asStateFlow()

    private var job: Job? = null
    private var generation = 0

    fun load(force: Boolean = false, fetch: suspend (page: Int) -> List<T>) {
        val current = _state.value
        if (!force) {
            if (current.loading || current.endReached) return
        } else {
            job?.cancel()
        }
        val page = if (force) 0 else current.page + 1
        _state.value = current.copy(loading = true, error = if (force) null else current.error)
        val gen = ++generation
        job = scope.launch {
            try {
                val fetched = fetch(page)
                if (generation != gen) return@launch // 已被更新的刷新取代，丢弃结果
                // 第 0 页整体替换（全新一批），后续页追加去重（接着上一批）。
                val previous = if (page == 0) emptyList() else _state.value.items
                val (merged, added) = appendDistinct(previous, fetched, identity)
                _state.value = _state.value.copy(
                    items = merged,
                    page = page,
                    loading = false,
                    // 到底的两种情形：① 服务器这一页没给满；② 给了满页但**一条新的都没有**
                    //（整页都是前面见过的重复项 ⇒ 再往下翻也只会重复，停在这里）。
                    endReached = fetched.size < pageSize || (page > 0 && added == 0)
                )
            } catch (t: Throwable) {
                // 取消是正常控制流，必须原样上抛：写进 error 状态会把「用户离开 / 刷新」当成
                // 「加载失败」，吞掉取消还会让协程假装正常结束、破坏结构化并发。
                if (t is CancellationException) throw t
                if (generation != gen) return@launch // 同上（含被 cancel 的旧任务）
                _state.value = _state.value.copy(loading = false, error = describeError(t))
            }
        }
    }

    /** 清空回初始态（清搜索/作者筛选等场景），进行中的加载一并取消。 */
    fun reset() {
        job?.cancel()
        generation++
        _state.value = FeedState()
    }
}
