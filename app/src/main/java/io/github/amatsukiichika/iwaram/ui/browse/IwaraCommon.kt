package io.github.amatsukiichika.iwaram.ui.browse

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.amatsukiichika.iwaram.data.IwaraAccount
import io.github.amatsukiichika.iwaram.data.IwaraImageItem
import io.github.amatsukiichika.iwaram.data.IwaraPostItem
import io.github.amatsukiichika.iwaram.data.IwaraVideoItem
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.WatchHistoryEntity

/* ---------- 跨页共享的轻量显示偏好（由 MainActivity 在根部下发） ---------- */

/**
 * 本地库已有的 iwara 视频 id 集合 —— 在线卡片左上角「已下载」药丸勾的判据。
 * 用 CompositionLocal 下发，免得把它逐层穿过 4 个在线网格 + 搜索 + 作者页的所有调用点。
 */
val LocalDownloadedIwaraIds = compositionLocalOf { emptySet<String>() }

/** 是否显示 R18 角标（设置里的独立开关；本地卡/在线卡共用）。 */
val LocalShowR18Tag = compositionLocalOf { true }

/**
 * 搜索行（搜索槽 + 排序/分级 chip）的**固定最小高度**。
 *
 * 展开搜索时排序/分级 chip 从行里移除，而 chip 自带 Material3 的 48dp 最小点击高度、
 * 搜索槽只有 44dp，行高会从 56dp 掉到 52dp，导致下方内容整体上移 4dp 且不再回位。
 * 这里把行高钉死，两页（本地库 / Iwara 页）共用。
 *
 * 取值 52dp，并配合两页那一行的 `vertical` 内边距 2dp：搜索槽本身仍是 48dp，
 * 只是"搜索槽底边 → 材质底边"的空白收窄、整条顶栏矮 4dp。
 */
val SEARCH_ROW_HEIGHT = 52.dp

/* ---------- 底栏与覆盖层模型 ---------- */

// 枚举声明序 = 底栏从左到右的顺序；AnimatedContent 按 ordinal 判滑动方向，两者须保持一致。
// 订阅并入视频页顶部分类（不再占底栏），收藏入口在「我的」页。
enum class HomeTab(val label: String, val icon: ImageVector) {
    MANAGER("本地", Icons.Filled.Folder),
    VIDEO("Iwara", Icons.Filled.SmartDisplay),
    ME("我的", Icons.Filled.Person)
}

/** 视频页顶部分类（声明序 = 从左到右，订阅在最左端）。 */
enum class Segment(val label: String) {
    SUBSCRIBED("订阅"),
    VIDEOS("视频"),
    IMAGES("图片"),
    POSTS("帖子")
}

/** 作者页头部计数点进去的关系列表类型。 */
enum class RelationKind(val label: String) {
    FOLLOWING("关注"),
    FOLLOWERS("粉丝")
}

/** 浏览界面与 MainActivity 之间的导航回调集合。 */
data class IwaraNavActions(
    val playVideo: (IwaraVideoItem) -> Unit,
    /**
     * **唯一的"打开作者二级页"入口** —— 收一个完整的 [IwaraAccount]。
     *
     * 若入口只收 handle 而不带 UUID，从「我的关注」「评论」「图片/帖子的作者」这些**手上就有账号对象**
     * 的地方点进去时，作者页要先多打一次 `GET /profile/{username}` 才拿到 UUID，之后才能拉作品/计数，
     * 加载会明显变慢。统一走这一个入口，UUID 一并带上。
     */
    val openAccountProfile: (IwaraAccount) -> Unit,
    val openComments: (String, String, String) -> Unit,
    val openPlaylist: (String, String) -> Unit,
    val openImage: (IwaraImageItem) -> Unit,
    val openPost: (IwaraPostItem) -> Unit,
    val openVideoActions: (IwaraVideoItem) -> Unit,
    val openImageActions: (IwaraImageItem) -> Unit,
    val openFavorites: () -> Unit,
    val openPlaylists: () -> Unit,
    val openDownloads: () -> Unit,
    val openFollowing: () -> Unit,
    /** 观看历史二级页（「我的」页入口）。 */
    val openWatchHistory: () -> Unit,
    /** 从观看历史行点开播放（本地/在线分流与续播起点在宿主内完成）。 */
    val playHistory: (WatchHistoryEntity) -> Unit,
    /** 播放**本地**视频（作者页「视频」栏的本地条目用；走与本地库同一条播放路径）。 */
    val playLocalVideo: (VideoEntity) -> Unit,
    /** 作者页头部的「关注 / 粉丝」计数 → 关系列表二级页。 */
    val openRelations: (userId: String, kind: RelationKind) -> Unit,
    val openSettings: () -> Unit,
    val openTagVideos: (String) -> Unit,
    val close: () -> Unit
)

/**
 * 把 [keyOf] 的结果整理成**全列表唯一**的字符串，供 Lazy 容器的 `key` 使用。
 *
 * Compose 的 `key` 一旦重复，LazyLayout 会直接抛
 * `IllegalArgumentException: Key "x" was already used`，**整页崩掉**。而调用点的写法是
 * `it.id.orEmpty().ifEmpty { it.hashCode().toString() }`：id 为空时回退到内容哈希，两条内容
 * 完全相同的条目（服务端偶发重复、或同一视频在合并列表里出现两次）就会撞 key。
 * 这里把「首条用原值、后续重复项加 `#序号` 后缀」做成确定性规则：既保住 key 的身份稳定性
 * （列表头部插入新条目时不会重建整个可见网格），又不可能崩。纯函数，可单测。
 */
internal fun <T> uniqueLazyKeys(items: List<T>, keyOf: (T) -> String): List<String> {
    val used = HashSet<String>(items.size * 2)
    val out = ArrayList<String>(items.size)
    for (item in items) {
        val base = keyOf(item).ifBlank { "item" }
        if (used.add(base)) {
            out.add(base)
            continue
        }
        var n = 1
        while (!used.add("$base#$n")) n++
        out.add("$base#$n")
    }
    return out
}
