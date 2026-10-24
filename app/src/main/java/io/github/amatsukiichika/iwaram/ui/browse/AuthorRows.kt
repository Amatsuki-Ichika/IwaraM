package io.github.amatsukiichika.iwaram.ui.browse

import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.parseIwaraDate

/**
 * 作者页「视频」栏的一行：本地实体或在线条目。
 *
 * 放在这里（`internal`）是为了让行序逻辑能被单测直接覆盖。
 */
internal sealed class AuthorVideoRow {
    abstract val key: String

    data class LocalV(val video: VideoEntity) : AuthorVideoRow() {
        override val key get() = "L:${video.localUri}"
    }

    data class OnlineV(val item: IwaraVideoItem) : AuthorVideoRow() {
        override val key get() = "O:${item.id ?: item.hashCode()}"
    }
}

/**
 * 「全部」模式（本地 + 在线混排）的行序纯函数。
 *
 * 不能按 `createdAt ?: addedAt` 把"未匹配的本地"和"在线主干"一起排序：下载/扫描写入的本地记录
 * `createdAt` 是 null（发布时间要靠元数据回填才有），排序键会回落到 `addedAt` = 导入时间，
 * 这些本地条目就必然盖在所有在线条目前面，表现为"上面全是本地、下面全是在线"。换个排序会强制
 * 重拉在线列表，所以看着像"刷新一下就好了"。
 *
 * 规则（构造式，不依赖本地时间戳的可用性）：
 * 1. 主干恒等于在线顺序：命中的在线卡就换成对应的本地版本（点击即本地播放，卡片带「本地」标签）；
 *    匹配优先 `iwaraId`，缺失时用"标题 + 作者"兜底（文件名导入的老记录常常没解析出 id）。
 * 2. 未匹配的本地：按它自己留存的发布日期插进主干（与在线条目用同一条时间轴）；
 *    时间未知的排到主干之后（等元数据回填后它会自己飘到正确位置）。
 * 3. 排序不是时间序（趋势 / 热门 / 播放 / 点赞）时，主干本身不是按时间排的，往里插没有意义：
 *    未匹配的本地统一排到主干之后；在线一条时间都解析不出来时同理。
 */
internal object AuthorRows {

    /** 「标题 + 作者」归一化键（小写、去掉所有空白）。标题为空 → null（不能拿空标题乱配）。 */
    fun titleKey(title: String?, author: String?): String? {
        val t = title?.trim()?.lowercase()?.filterNot { it.isWhitespace() }
        if (t.isNullOrEmpty()) return null
        val a = author?.lowercase()?.filterNot { it.isWhitespace() }.orEmpty()
        return "$t|$a"
    }

    /** 在线条目的发布时间（毫秒），解析不出来返回 null。 */
    fun onlineMs(item: IwaraVideoItem): Long? = parseIwaraDate(item.createdAt)

    /** 主干某一行的排序基准时间：命中本地的用它自己的发布时间，否则用在线那条的。 */
    private fun rowMs(row: AuthorVideoRow): Long? = when (row) {
        is AuthorVideoRow.LocalV -> row.video.createdAt
        is AuthorVideoRow.OnlineV -> onlineMs(row.item)
    }

    /**
     * 「全部」模式的行序。见类注释里的规则说明。
     *
     * @param chronological 在线列表是否按时间排的（排序 chip 选「最新」时为 true）。
     */
    fun mergeAll(
        online: List<IwaraVideoItem>,
        local: List<VideoEntity>,
        chronological: Boolean
    ): List<AuthorVideoRow> {
        // ---- 本地索引：iwaraId 优先，标题+作者兜底 ----
        val byId = HashMap<String, VideoEntity>()
        val byTitle = HashMap<String, VideoEntity>()
        local.forEach { v ->
            v.iwaraId?.takeIf { it.isNotBlank() }?.let { byId.putIfAbsent(it, v) }
            titleKey(v.title, v.author)?.let { byTitle.putIfAbsent(it, v) }
        }

        // ---- 主干：在线顺序，命中的就地替换（一条本地只会被消费一次）----
        val used = HashSet<String>(local.size)
        val trunk = ArrayList<AuthorVideoRow>(online.size)
        online.forEach { item ->
            val hit = item.id?.takeIf { it.isNotBlank() }?.let { byId[it] }
                ?: titleKey(item.title, item.user?.username ?: item.user?.name)?.let { byTitle[it] }
            if (hit != null && used.add(hit.localUri)) {
                trunk += AuthorVideoRow.LocalV(hit)
            } else {
                trunk += AuthorVideoRow.OnlineV(item)
            }
        }

        val rest = local.filter { it.localUri !in used }
        if (rest.isEmpty()) return trunk

        val dated = rest.filter { it.createdAt != null }.sortedByDescending { it.createdAt!! }
        val undated = rest.filter { it.createdAt == null }.sortedByDescending { it.addedAt }

        val trunkHasDates = online.any { onlineMs(it) != null }
        if (!chronological || !trunkHasDates) {
            return trunk +
                dated.map { AuthorVideoRow.LocalV(it) } +
                undated.map { AuthorVideoRow.LocalV(it) }
        }

        // ---- 时间序：未匹配但**有**发布时间的按时间插进主干，时间未知的排最后 ----
        val pending = ArrayDeque(dated.map { it.createdAt!! to it })
        val out = ArrayList<AuthorVideoRow>(trunk.size + rest.size)
        trunk.forEach { row ->
            val ms = rowMs(row)
            while (pending.isNotEmpty() && (ms == null || pending.first().first > ms)) {
                out += AuthorVideoRow.LocalV(pending.removeFirst().second)
            }
            out += row
        }
        while (pending.isNotEmpty()) out += AuthorVideoRow.LocalV(pending.removeFirst().second)
        undated.forEach { out += AuthorVideoRow.LocalV(it) }
        return out
    }
}
