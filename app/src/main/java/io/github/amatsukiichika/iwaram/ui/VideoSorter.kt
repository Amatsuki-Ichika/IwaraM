package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.data.VideoEntity

/**
 * 本地库排序的纯函数集合：不依赖 ViewModel/数据库，输入输出确定，可单元测试。
 * 桶视图（文件夹/作者/标签/时间）对"按条目时间排序"没有语义，统一落入 else 兜底
 * （名称或数量），与既有行为一致。
 */
object VideoSorter {

    /**
     * 名称排序的**分节主键**：`#` 档恒为 0，a-z 为 1..26。
     *
     * 直接按 `title.lowercase()` 排时，ASCII 数字/符号（"2020…"、"#…"）排在所有字母**前面**，
     * 中文/全角排在**后面**，两堆都被 `nameSection` 归进 "#"，于是滚动条出现两个不相连的 "#"
     * 标记。先按分节档位排、再按标题排，同一分节必然连续。
     */
    internal fun sectionRank(title: String): Int {
        val c = title.trim().firstOrNull()?.lowercaseChar() ?: return 0
        return if (c in 'a'..'z') c - 'a' + 1 else 0
    }

    /** 名称排序（ASC/DESC 共用一个比较器，保证分节连续）。 */
    internal fun <T> nameOrder(titleOf: (T) -> String, desc: Boolean): Comparator<T> =
        if (desc) {
            compareByDescending<T> { sectionRank(titleOf(it)) }.thenByDescending { titleOf(it).lowercase() }
        } else {
            compareBy({ sectionRank(titleOf(it)) }, { titleOf(it).lowercase() })
        }

    fun sortVideos(list: List<VideoEntity>, mode: SortMode): List<VideoEntity> = when (mode) {
        SortMode.NEWEST -> list.sortedByDescending { it.createdAt ?: Long.MIN_VALUE }
        SortMode.OLDEST -> list.sortedBy { it.createdAt ?: Long.MAX_VALUE }
        SortMode.ADDED_NEW -> list.sortedByDescending { it.addedAt }
        SortMode.ADDED_OLD -> list.sortedBy { it.addedAt }
        SortMode.NAME_ASC -> list.sortedWith(nameOrder(VideoEntity::title, desc = false))
        SortMode.NAME_DESC -> list.sortedWith(nameOrder(VideoEntity::title, desc = true))
        SortMode.COUNT_DESC -> list.sortedByDescending { it.viewCount ?: -1 }
        SortMode.COUNT_ASC -> list.sortedBy { it.viewCount ?: Int.MAX_VALUE }
    }

    fun sortFolderBuckets(list: List<FolderBucket>, mode: SortMode): List<FolderBucket> = when (mode) {
        SortMode.COUNT_DESC -> list.sortedWith(compareByDescending<FolderBucket> { it.count }.thenBy { it.name.lowercase() })
        SortMode.COUNT_ASC -> list.sortedWith(compareBy<FolderBucket> { it.count }.thenBy { it.name.lowercase() })
        SortMode.NAME_DESC -> list.sortedWith(nameOrder(FolderBucket::name, desc = true))
        else -> list.sortedWith(nameOrder(FolderBucket::name, desc = false))
    }

    fun sortAuthorBuckets(list: List<AuthorBucket>, mode: SortMode): List<AuthorBucket> = when (mode) {
        SortMode.COUNT_DESC -> list.sortedWith(compareByDescending<AuthorBucket> { it.count }.thenBy { it.name.lowercase() })
        SortMode.COUNT_ASC -> list.sortedWith(compareBy<AuthorBucket> { it.count }.thenBy { it.name.lowercase() })
        SortMode.NAME_DESC -> list.sortedWith(nameOrder(AuthorBucket::name, desc = true))
        else -> list.sortedWith(nameOrder(AuthorBucket::name, desc = false))
    }

    fun sortTagBuckets(list: List<TagBucket>, mode: SortMode): List<TagBucket> = when (mode) {
        SortMode.COUNT_ASC -> list.sortedWith(compareBy<TagBucket> { it.count }.thenBy { it.name.lowercase() })
        SortMode.NAME_ASC -> list.sortedWith(nameOrder(TagBucket::name, desc = false))
        SortMode.NAME_DESC -> list.sortedWith(nameOrder(TagBucket::name, desc = true))
        else -> list.sortedWith(compareByDescending<TagBucket> { it.count }.thenBy { it.name.lowercase() })
    }

    /** 时间桶：新/最旧按月份起始时间排序且"未识别时间"恒在末尾，其余模式连同未知桶一起排。 */
    fun sortTimeBuckets(dated: List<TimeBucket>, unknown: List<TimeBucket>, mode: SortMode): List<TimeBucket> =
        when (mode) {
            SortMode.NEWEST -> dated.sortedByDescending { it.startEpochMs } + unknown
            SortMode.OLDEST -> dated.sortedBy { it.startEpochMs } + unknown
            SortMode.COUNT_DESC -> (dated + unknown).sortedByDescending { it.count }
            SortMode.COUNT_ASC -> (dated + unknown).sortedBy { it.count }
            SortMode.NAME_DESC -> (dated + unknown).sortedByDescending { it.label.lowercase() }
            else -> (dated + unknown).sortedBy { it.label.lowercase() }
        }
}
