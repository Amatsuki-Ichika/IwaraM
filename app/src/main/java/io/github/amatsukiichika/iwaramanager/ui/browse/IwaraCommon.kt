package io.github.amatsukiichika.iwaramanager.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.os.Build
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import io.github.amatsukiichika.iwaramanager.ui.components.glassPillEffects
import io.github.amatsukiichika.iwaramanager.ui.components.glassViewBackdrop
import io.github.amatsukiichika.iwaramanager.ui.components.rememberGlassViewBackdrop
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.amatsukiichika.iwaramanager.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaramanager.data.IwaraAccount
import io.github.amatsukiichika.iwaramanager.data.IwaraClient
import io.github.amatsukiichika.iwaramanager.data.IwaraImageItem
import io.github.amatsukiichika.iwaramanager.data.IwaraPostItem
import io.github.amatsukiichika.iwaramanager.data.IwaraVideoItem
import io.github.amatsukiichika.iwaramanager.data.VideoEntity
import io.github.amatsukiichika.iwaramanager.data.WatchHistoryEntity
import io.github.amatsukiichika.iwaramanager.data.filterBy
import io.github.amatsukiichika.iwaramanager.data.parseIwaraDate
import io.github.amatsukiichika.iwaramanager.ui.FastScrollbarBasic
import io.github.amatsukiichika.iwaramanager.ui.components.BAR_BOTTOM_INSET
import io.github.amatsukiichika.iwaramanager.ui.components.BAR_HEIGHT_BASE
import io.github.amatsukiichika.iwaramanager.ui.components.GLASS_BAR_SIZE
import io.github.amatsukiichika.iwaramanager.ui.components.GlassRole
import io.github.amatsukiichika.iwaramanager.ui.components.LocalGlassSettings
import io.github.amatsukiichika.iwaramanager.ui.components.R18Pill
import io.github.amatsukiichika.iwaramanager.ui.components.glassMaterialFor
import io.github.amatsukiichika.iwaramanager.ui.components.glassScale
import io.github.amatsukiichika.iwaramanager.ui.TagChip
import io.github.amatsukiichika.iwaramanager.ui.VideoCardContent
import io.github.amatsukiichika.iwaramanager.ui.VideoCardModel
import io.github.amatsukiichika.iwaramanager.ui.components.PillSegment
import io.github.amatsukiichika.iwaramanager.ui.components.PillSelector
import io.github.amatsukiichika.iwaramanager.ui.components.SearchPill
import io.github.amatsukiichika.iwaramanager.ui.components.TagSuggestionRow
import io.github.amatsukiichika.iwaramanager.ui.components.debugAwareErrorText
import io.github.amatsukiichika.iwaramanager.ui.normalizeCardText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** 覆盖层栈中的条目：后进先出，逐层返回。 */
sealed interface IwaraOverlay {
    data class Comments(val type: String, val targetId: String, val title: String) : IwaraOverlay
    data class Profile(
        /** @handle（`GET /profile/{username}` 的路径参数）。可能为空 —— 只有 UUID 时靠 [userId]。 */
        val username: String,
        /** 作者 UUID：拿不到 handle 时兜底（作品/关注/粉丝都用 UUID，不依赖 profile 解析）。 */
        val userId: String? = null,
        /** 昵称（header 在资料尚未解析出来时的占位显示）。 */
        val displayName: String? = null,
        /**
         * 本地库的**作者分组键**（`VideoEntity.authorId`，未匹配时 `UNKNOWN_AUTHOR_KEY`）。
         * 有值 → 作者页「视频」栏先渲染该作者的本地视频（**离线也能进**）；null → 纯在线作者页。
         */
        val localAuthorKey: String? = null,
        /** 本地作者的显示名（在线资料拉不到时 header 用它，例如「未指定」）。 */
        val localAuthorName: String? = null
    ) : IwaraOverlay

    /** 作者页头部「关注 / 粉丝」计数点进来的关系列表。 */
    data class Relations(val userId: String, val kind: RelationKind) : IwaraOverlay
    data class PlaylistDetail(val playlistId: String, val title: String) : IwaraOverlay
    data class ImageViewer(val image: IwaraImageItem) : IwaraOverlay
    data class PostDetail(val post: IwaraPostItem) : IwaraOverlay
    /** [pickPlaylistDirectly]：播放页「收藏」按钮直达"加入播放列表"模式（网站"添加到"）。 */
    data class VideoActions(val video: IwaraVideoItem, val pickPlaylistDirectly: Boolean = false) : IwaraOverlay
    data class ImageActions(val image: IwaraImageItem) : IwaraOverlay
    data object Favorites : IwaraOverlay
    data object Playlists : IwaraOverlay
    data object Downloads : IwaraOverlay
    data object Following : IwaraOverlay
    /** 观看历史二级页（「我的」页入口；纯本地数据，不依赖登录）。 */
    data object WatchHistory : IwaraOverlay
    data class TagVideos(val tagId: String) : IwaraOverlay
}

/**
 * 覆盖层栈的**条目** —— 给每层一个自增序号 [seq]，作为组合 key、退场集合与冻结位移的判据。
 *
 * 直接用 `IwaraOverlay`（data class）当 key / 比身份时：两个**参数相同**的页面（例：从作者页点了同一个
 * 作者、或快速 pop→push 同一页）结构相等 → 撞 key、`leavingPages` 误删 → 层级错乱甚至整页不可见。
 */
data class OverlayEntry(val seq: Long, val overlay: IwaraOverlay) {
    /** 动作面板（ModalBottomSheet 独立窗口）不参与页面栈渲染。 */
    val isActionSheet: Boolean get() = overlay.isActionSheet()
}

/**
 * 某个栈层**是不是"被盖住"**（要左移让位 + 压暗）。
 *
 * 规则只有一条：**它后面还有别的页面**（动作面板不算，它是独立窗口、不占页面栈）。
 * 抽成纯函数是为了可单测 —— 这条规则一旦被破坏，表现就是"顶层页被当成底层让位"或"整页消失"。
 */
fun isCoveredAt(entries: List<OverlayEntry>, index: Int): Boolean {
    if (index < 0 || index >= entries.size) return false
    return entries.drop(index + 1).any { !it.isActionSheet }
}

/** 覆盖层栈里"参与页面渲染"的部分（动作面板除外），顺序 = 绘制顺序（后面的压在上面）。 */
fun livePageStack(entries: List<OverlayEntry>): List<OverlayEntry> = entries.filterNot { it.isActionSheet }

/**
 * 某一层让位量（cover）的**动画目标**。
 *
 * - 上层页正被拖动（[gestureCover] != null，值 = `1 - p`）→ 目标就是**手指位置**，
 *   配合 snap 规格让页面实时贴住手指；
 * - 否则由栈序决定：被盖住 = 1、露出 = 0。
 *
 * 关键在松手那一帧：`gestureCover` 变 null 与"下层页变成顶层（covered = false）"发生在**同一次重组**，
 * 于是目标从"手指位置"直接变成 0，而 `animateFloatAsState` 的当前值正是最后贴上的那个手指位置
 * ⇒ **从手指位置接着滑出**，不会先跳回"盖住"的位置。
 */
internal fun coverTargetFor(gestureCover: Float?, covered: Boolean): Float =
    gestureCover ?: if (covered) 1f else 0f

/**
 * 各层的**绘制顺序**序号集合（大序号画在上面）。
 *
 * 与"谁盖着谁"（让位 / 让出返回键）不同，**这里必须把"退场动画还没播完的层"也算在场**。
 *
 * 绘制顺序按 `layerZ(seq)` 给出。若写成 `.zIndex(layerZ(playerSeq.takeIf { showingPlayer }))`，
 * 返回那一刻 `showingPlayer` 变 false、实参成了 **null** ⇒ `layerZ(null) = 0f` ⇒ 播放页的 zIndex
 * 从 1+n **当帧塌到 0**，掉到覆盖层栈**之下**：正在向右滑出的播放页被下面的作者页盖住，只剩
 * 作者页让位后右边那条黑缝里露出的一小条，表现为「作者页错误的覆盖在播放页上面」。
 *
 * @param overlayTopSeq 覆盖层栈里最上面**页面**的打开序号（动作面板不算）；没有则为 null
 * @param settingsOnScreen 设置页是否"还在屏上"＝打开着 **或** 退场动画未播完（见 [layerStillOnScreen]）
 * @param playerOnScreen 播放页同上
 */
fun drawOrderSeqs(
    overlayTopSeq: Long?,
    settingsSeq: Long,
    settingsOnScreen: Boolean,
    playerSeq: Long,
    playerOnScreen: Boolean
): List<Long> = listOfNotNull(
    overlayTopSeq,
    settingsSeq.takeIf { settingsOnScreen },
    playerSeq.takeIf { playerOnScreen }
)

/** 该层的 zIndex：比自己序号小的**在屏上**层数 + 1（0 留给底色/压暗层/主内容）。 */
fun zIndexOf(seq: Long?, onScreenSeqs: List<Long>): Float =
    if (seq == null) 0f else 1f + onScreenSeqs.count { it < seq }.toFloat()

/**
 * 两个作者页是不是**同一个作者**。
 *
 * 判据（任一成立即算）：`userId` 都有值且相同 / `username` 都非空且相同 / `localAuthorKey` 都非空且相同。
 * 三条都不成立 ⇒ false —— 宁可多压一层，也不要把两个不同作者错并成一层。
 */
fun sameProfileKey(a: IwaraOverlay.Profile, b: IwaraOverlay.Profile): Boolean =
    (a.userId != null && a.userId == b.userId) ||
        (a.username.isNotBlank() && a.username.equals(b.username, ignoreCase = true)) ||
        (a.localAuthorKey != null && a.localAuthorKey == b.localAuthorKey)

/**
 * 要打开的作者页**已经在栈里**时，应该回退到哪一层（-1 = 栈里没有，需要新压一层）。
 *
 * 场景：作者页 → 进视频（播放页）→ 在播放页再点作者（**同一个作者**）。
 * `pushOverlay` 若因两个 Profile 的字段不完全相同（一个带 localAuthorKey、一个不带）而
 * **新压一层作者页**，新页就会当帧盖住正在退场的播放页（表现为"播放页突兀地消失了"），
 * 而且栈里叠出两个同样的作者页。正确行为：**回到栈里那一层**（它就在播放页下面）。
 */
fun profileRevertIndex(stack: List<IwaraOverlay>, incoming: IwaraOverlay.Profile): Int =
    stack.indexOfLast { it is IwaraOverlay.Profile && sameProfileKey(it, incoming) }

/**
 * 该层是否"还在屏上"—— 打开着、或退场动画还没播完。
 *
 * `currentState` 表示"这一帧画的是可见态"（退场中它仍为 true），
 * `targetState` 表示"已下发的目标"（`SideEffect` 在组合之后才落值，只判 currentState 会漏掉
 * "刚变 false 的那一帧"，层序仍会闪一下）。
 */
fun layerStillOnScreen(shown: Boolean, currentState: Boolean, targetState: Boolean): Boolean =
    shown || currentState || targetState

/**
 * **打开播放页时要不要补一次入场滑入**。
 *
 * 播放页被二级页盖住时**并不销毁** —— 可见状态恒为 true，只是按 `covered` 让位（左移 + 压暗），
 * 而它的**根 Box 是透明的**（底色在内层 Column 上，为的是预测式返回时露出底页）。所以只要它带着
 * 那条让位位移就冒到最上层，**右边就会露出一条缝**，缝里正是下面那页，表现为
 * 「作者页错误的覆盖在播放页上面」。
 *
 * ⇒ **每一次真正把播放页提到台前，都必须自己补一次右滑入**（不能依赖 `AnimatedVisibility` 的
 * enter：它只在"可见状态由 false 变 true"时播，播放页被盖住时可见状态本来就是 true，不重播）。
 *
 * 唯一不需要补的情况：播放页**已经在台前**（活着、且上面没有序号更大的可见层）。
 * 那时 `openPlayer()` 只是刷新层序，补动画会让整页凭空飞一次。
 *
 * 抽成纯函数是为了能直接单测（见 `OverlayStackTest`）。
 */
fun shouldReplayPlayerEntry(
    showingPlayer: Boolean,
    playerSeq: Long,
    /** 覆盖层栈里最上面的**页面**（动作面板不算）的打开序号；没有页面则为 null。 */
    overlayTopSeq: Long?,
    showingSettings: Boolean,
    settingsSeq: Long
): Boolean {
    // 没开着 → 这是"新打开"，必须补。
    if (!showingPlayer) return true
    // 开着但被盖着（上面有序号更大的可见层）→ 必须补。
    if (showingSettings && settingsSeq > playerSeq) return true
    if (overlayTopSeq != null && overlayTopSeq > playerSeq) return true
    // 已经就在台前 → 不补。
    return false
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

/* ---------- 信息流通用组件 ---------- */

/**
 * 入场动画"已看过"标记（key = "网格key:条目id"）。进程级普通集合（非 Compose state）：
 * 以【条目】为粒度——没看过的内容（首屏、翻页新加载、刷新后新出现的视频）第一次
 * 出现时播入场动画；看过的（滚动回看/切 tab 划回/同 id 刷新）直接显示、不重播不重建。
 * [resetEntrancePlayed] 在离开应用（onStop）时清空：回到 app 后重新按"没看过"判定。
 */
private val seenItemKeys = mutableSetOf<String>()

/** 离开应用（MainActivity.onStop 调用）时重置入场动画标记。 */
fun resetEntrancePlayed() {
    seenItemKeys.clear()
}

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

/** 无限滚动网格：滚动接近底部自动 [onLoadMore]，带加载/错误/空态。columns 用 GridCells.Fixed(2) 与管理器一致。
 *  [entranceKey] 非空时，该网格的卡片入场动画只在内容首次出现时播放一次。
 *  [itemKey] **必填**：既是 Lazy 网格的 `key`，也是入场动画的判定键。
 *  必须把它作为 `key` 传给 [itemsIndexed]：否则下拉刷新在头部插入新条目时 Compose 无法识别条目身份，
 *  会把整个可见网格当成"全变了"重建，Coil 图片请求也被重新驱动。
 *  也不给「内容哈希」这类默认值：数据一变（例如点赞数刷新）key 就变，会连带重建入场动画的组合组、
 *  让一批卡片重播淡入，所以强制调用方显式给稳定 id。 */
@Composable
fun <T> InfiniteGrid(
    items: List<T>,
    loading: Boolean,
    endReached: Boolean,
    error: String?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    itemKey: (T) -> String,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    columns: GridCells = GridCells.Fixed(2),
    emptyHint: String = "暂无内容",
    /**
     * 全局筛选器：网格**渲染前**过最后一道 —— 全仓库所有在线列表共用这一处实现。
     *
     * - 传 null = 这个列表**不参与**全局筛选（"人 / 播放列表"这种不是作品的列表）；
     * - 返回的 tags 为 **null** 表示"本列表没有标签字段"（在线图片 / 帖子）⇒ 标签规则对它豁免；
     * - 过滤只影响**显示**：翻页判定仍按过滤后的可见条数（照旧语义）；被筛空时底部会给
     *   「继续加载」按钮 —— 列表空了就滚不到底，不给入口会让人以为筛选器坏了。
     *
     * 取键统一用 data 里的 `::videoKeys` / `::imageKeys` / `::postKeys`（见 GlobalFilter.kt）。
     */
    globalFilter: ((T) -> Pair<String, List<String>?>)? = null,
    entranceKey: String? = null,
    /**
     * **空态**下追加的自定义内容（跟在空态文案后面、`继续加载` 之前）。
     * 目前只有搜索结果在用 —— 结果为空时要说清"当前有几条筛选在起作用"，
     * 否则只会看到"网页上有、app 里搜不到"这种无从下手的现象。
     */
    emptyExtra: (@Composable () -> Unit)? = null,
    /**
     * 列表底部额外让位（悬浮玻璃底栏的高度）——主 tab 的网格要"穿到栏后"，
     * 尾部空白由 contentPadding(bottom) 承担；其余全屏调用点默认 0 零影响。
     */
    bottomPadding: Dp = 0.dp,
    /**
     * **悬浮顶栏**的高度 → 顶部 contentPadding。
     * 必须走 contentPadding（不能给容器加 padding）—— 否则网格视口被整个推到条下面，
     * 卡片就再也不会"从顶栏底下穿过"了。
     */
    topPadding: Dp = 0.dp,
    /**
     * **外部背景源**（顶栏要用同一份）。
     *
     * 顶栏不能自己在上层再录一遍："**嵌套平台 RenderNode × 外层 RenderEffect 模糊**"这条
     * 合成路径会把该层内容丢成透明。
     * 所以顶栏用**网格自己那一份**源（由调用方从上面传进来，网格负责挂载；块是源的兄弟节点）。
     * null = 网格自建（其它所有调用点行为不变）。
     */
    backdrop: LayerBackdrop? = null,
    itemContent: @Composable (T) -> Unit
) {
    val gridState = state
    val scope = rememberCoroutineScope()
    // 全局筛选：不生效时 filterBy **原样返回同一个实例**（连一次遍历都不做）。
    //   lambda 参与 remember 的 key ⇒ 若每帧都是新实例会多跑一遍过滤；列表很小（一页 20~24 条），
    //   相比之下"取键逻辑变了却不重算"的风险更大，所以这里取保守写法。
    // 列表消费端订阅防抖视图：打字期间不再每键全列表重算。
    val filter by GlobalFilterHolder.listState.collectAsStateWithLifecycle()
    val shown = remember(items, filter, globalFilter) {
        if (globalFilter == null) items else items.filterBy(filter, globalFilter)
    }
    val hiddenByFilter = items.size - shown.size
    // 唯一化后的 key 列表：与 items 结构相等则复用（内容没变不重算）。
    val lazyKeys = remember(shown) { uniqueLazyKeys(shown, itemKey) }
    val shouldLoadMore by remember(shown.size, loading, endReached) {
        derivedStateOf {
            if (shown.isEmpty() || loading || endReached) return@derivedStateOf false
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= shown.size - 6
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }
    // 背景源由 **GlassBackdrop 模块**统一创建，它负责两件容易漏的事（见模块文件头）：
    //   ① **录制时先铺页面底色** —— 否则录出来的层在"卡片之间的缝隙"全是透明像素，
    //      模糊算法无像素可采，玻璃里的卡片外边缘会"糊不动"；
    //   ② 统一判据（液态玻璃档 + API 31+）；非玻璃档返回 null ⇒ 零录制成本。
    //   录制层挂在**网格**上、回顶按钮是它的兄弟节点（录制子树外，防自采样 SIGSEGV）。
    // 外部给了源就用它（顶栏共用同一份），否则自建。
    val viewBackdrop = backdrop ?: rememberGlassViewBackdrop()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = columns,
            state = gridState,
            modifier = modifier
                .fillMaxSize()
                .glassViewBackdrop(viewBackdrop),
            // 卡片间距收一点（10 → [GRID_CARD_GAP]），外侧对称留白不动。
            horizontalArrangement = Arrangement.spacedBy(GRID_CARD_GAP),
            verticalArrangement = Arrangement.spacedBy(GRID_CARD_GAP),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, top = 12.dp + topPadding, bottom = 12.dp + bottomPadding
            )
        ) {
            // 声明 contentType —— 内容条目与通栏状态条（加载/错误/空态）
            //   不再共享复用槽，LazyGrid 的槽位复用更准（重拉时状态条不会顶掉卡片槽）。
            itemsIndexed(shown, key = { index, _ -> lazyKeys[index] }, contentType = { _, _ -> "content" }) { index, item ->
                val key = entranceKey
                if (key == null) {
                    itemContent(item)
                } else {
                    // 按条目定格：没看过的条目播入场动画（渲染后标记），看过的直接显示。
                    val seenKey = "$key:${lazyKeys[index]}"
                    val animateThisSlot = remember(seenKey) { !seenItemKeys.contains(seenKey) }
                    if (animateThisSlot) {
                        Entrance(seenKey, index) { itemContent(item) }
                    } else {
                        itemContent(item)
                    }
                }
            }
            if (loading) {
                item(contentType = "status", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            error?.let { msg ->
                item(contentType = "status", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Filled.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        // 普通模式显示人话（friendlyIwaraError），debug 开关打开时显示原样技术串
                        Text(
                            debugAwareErrorText(msg),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
            }
            if (!loading && shown.isEmpty() && error == null) {
                item(contentType = "status", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            // 被全局筛选器筛空 vs 真的没内容 —— 两句话必须分开，
                            // 否则会把"筛选生效"当成"这一页加载失败/坏了"。
                            if (hiddenByFilter > 0) "筛选后没有符合的内容（已隐藏 $hiddenByFilter 项）" else emptyHint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        emptyExtra?.invoke()
                        // 筛空时列表是空的 ⇒ 没法再"滚到底"触发翻页，后面几页里可能有命中的内容。
                        // 给一个手动入口，而不是让人以为筛选器不工作。
                        if (hiddenByFilter > 0 && !endReached) {
                            TextButton(onClick = onLoadMore, enabled = !loading) { Text("继续加载") }
                        }
                    }
                }
            }
            // 列表**非空**时的尾部状态行 —— 把两种"看不见的原因"变成看得见的一句话。
            //
            // 没有它时"被筛选器隐藏了一部分"与"已经翻到底了"**在界面上没有任何痕迹**，
            // 看到的就是"我记得有这条视频，现在翻遍也找不到"，容易被当成"内容丢失/被隐藏"。
            // 排序上"隐藏条数"优先于"到底"——前者是**可能让内容消失**的那一个，必须显眼。
            if (shown.isNotEmpty() && error == null && !loading) {
                val tailText = when {
                    hiddenByFilter > 0 -> "已按筛选条件隐藏 $hiddenByFilter 项"
                    endReached -> "已经到底了"
                    else -> null
                }
                if (tailText != null) {
                    item(contentType = "tail", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                tailText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 一键清除，不用再去记住"那个漏斗图标在哪、里面留了什么规则"。
                            if (hiddenByFilter > 0) {
                                TextButton(onClick = { GlobalFilterHolder.reset() }) { Text("清除筛选") }
                            }
                        }
                    }
                }
            }
        }
        // 信息流无分类：连续比例滚动条 + 回到顶部
        FastScrollbarBasic(
            state = gridState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(horizontal = 2.dp)
        )
        val showTop by remember {
            derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }
        }
        ScrollToTopButton(
            visible = showTop,
            onClick = { scope.launch { gridState.animateScrollToTop() } },
            // 落在"底栏顶边之上 13dp"的右下角（贴内容区底会压在底栏右侧）。
            // 液态玻璃档下消费本视图背景层（真模糊 + 折射）。
            backdrop = viewBackdrop,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = scrollToTopBottomPadding(bottomPadding))
        )
    }
}

/**
 * 分段切换器（主页滑块同款机制，药丸形滑块）：options 顺序 = 从左到右，
 * 主色 16% 滑块以 animateDpAsState(220ms) 滑到选中段背后，各段均分撑满宽度。
 * 实现统一委托给 [PillSelector]（全仓库唯一实现），本函数只保留原公共签名。
 */
@Composable
fun PillSegmentSelector(
    options: List<String>,
    current: Int,
    /**
     * 左右内缩。默认 12dp（二级页直接放在 Column 里时的观感）。
     * 放进 **LazyGrid 通栏 item** 时必须传 0.dp —— 网格自己有
     *   `contentPadding = 12.dp`，再叠 12dp 会变成 24dp 双倍内缩（作者页分段栏入滚）。
     * 注意：必须排在 [onSelect] **前面**，否则 `PillSegmentSelector(a, b) { ... }`
     * 这种尾随 lambda 写法会把 lambda 绑到本参数上。
     */
    horizontalPadding: Dp = 12.dp,
    onSelect: (Int) -> Unit
) {
    PillSelector(
        segments = options.mapIndexed { i, label -> PillSegment(i, label) },
        selected = current,
        onSelect = onSelect,
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 4.dp),
        evenWeight = true
    )
}

/** 在线视频卡片的标准接线（各信息流共用）：点击播放、长按动作面板、点作者就地筛选、点 tag 就地筛选。 */
@Composable
fun OnlineVideoCard(video: IwaraVideoItem, actions: IwaraNavActions) {
    VideoCardOnline(
        video = video,
        onClick = { actions.playVideo(video) },
        onLongClick = { actions.openVideoActions(video) },
        onAuthorClick = { video.user?.let { u -> actions.openAccountProfile(u) } },
        onTagClick = { actions.openTagVideos(it) }
    )
}

/** 未登录占位（订阅/收藏/互动等功能）。 */
@Composable
fun LoginRequiredPanel(hint: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.size(12.dp))
        Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "请到「我的」页登录 Iwara 账号",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* ---------- 卡片 ---------- */

/**
 * 在线视频卡片：把 [IwaraVideoItem] 映射成 [VideoCardModel] 后交给 **同一个** [VideoCardContent] 渲染。
 *
 * 与本地卡片的差别只剩两处，都是**数据**层面的：
 * 1. 缩略图走 iwara 封面 URL；
 * 2. **显示**左上角「本地」标志 —— 当本地库已有该 iwaraId 时（判据 [LocalDownloadedIwaraIds]）。
 */
@Composable
fun VideoCardOnline(
    video: IwaraVideoItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onAuthorClick: () -> Unit = {},
    onTagClick: (String) -> Unit = {},
    showTags: Boolean = true
) {
    val downloaded = video.id != null && LocalDownloadedIwaraIds.current.contains(video.id)
    VideoCardContent(
        model = VideoCardModel(
            thumbnail = IwaraClient.thumbnailUrl(video.file?.id),
            title = normalizeCardText(video.title ?: "（无标题）"),
            author = normalizeCardText(video.user?.name ?: video.user?.username.orEmpty()),
            tags = if (showTags) {
                video.tags.orEmpty().map { normalizeCardText(it.id) }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            },
            rating = video.rating,
            showR18 = LocalShowR18Tag.current,
            showLocalBadge = downloaded,
            // 封面左下角日期胶囊（在线列表 DTO 自带 createdAt）。
            createdAtMs = parseIwaraDate(video.createdAt)
        ),
        onClick = onClick,
        onTagClick = onTagClick,
        onLongClick = onLongClick,
        // 拿不到作者对象时作者行不是可点组件。
        onAuthorClick = if (video.user != null) onAuthorClick else null,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun AuthorRow(user: IwaraAccount?, onClick: () -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = user != null, onClick = onClick)
    ) {
        // 有头像/无头像都占同一 18dp 槽位，避免同一排卡片作者行高度参差。
        val avatar = IwaraClient.avatarUrl(user?.avatar)
        if (avatar != null) {
            AsyncImage(
                model = avatar,
                contentDescription = null,
                modifier = Modifier.size(18.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            normalizeCardText(user?.name ?: user?.username).ifEmpty { "未知作者" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatText(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageCardOnline(
    image: IwaraImageItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    /** 作者名可点（就地按作者筛选），默认不响应以兼容旧调用点。 */
    onAuthorClick: () -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                // 封面字段是 thumbnail（回退 file），只读 file 会让图片板块整片白板。
                val thumb = IwaraClient.imageThumbnailUrl(image.thumbRef)
                if (thumb != null) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = image.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                // 图集多图角标（numImages 缺失时用 files 数量兜底）
                val total = (image.numImages ?: 0).takeIf { it > 1 } ?: image.fileRefs.size.takeIf { it > 1 } ?: 0
                if (total > 0) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                    ) {
                        Text("$total 图", fontSize = 10.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                }
            }
            Column(Modifier.padding(8.dp)) {
                Text(
                    normalizeCardText(image.title ?: "（无标题）"),
                    style = MaterialTheme.typography.bodyMedium,
                    minLines = 1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.size(4.dp))
                AuthorRow(image.user, onAuthorClick)
                // 与帖子卡片同款，点赞 / 评论 / 时间三项都展示。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    StatText(Icons.Filled.Star, formatCount(image.numLikes ?: 0))
                    StatText(Icons.Filled.Person, formatCount(image.numComments ?: 0))
                    timeAgoLabel(image.createdAt)?.let { StatText(Icons.Filled.Subscriptions, it) }
                }
            }
        }
    }
}

@Composable
fun PostCard(
    post: IwaraPostItem,
    onClick: () -> Unit,
    /** 作者名可点（就地按作者筛选），默认不响应以兼容旧调用点。 */
    onAuthorClick: () -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                normalizeCardText(post.title ?: "（无标题）"),
                style = MaterialTheme.typography.titleSmall,
                minLines = 1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(6.dp))
            AuthorRow(post.user, onAuthorClick)
            post.body?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.size(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                StatText(Icons.Filled.Star, formatCount(post.numLikes ?: 0))
                StatText(Icons.Filled.Person, formatCount(post.numComments ?: 0))
                timeAgoLabel(post.createdAt)?.let { StatText(Icons.Filled.Subscriptions, it) }
            }
        }
    }
}

/* ---------- 排序 / 分级选择器 ---------- */

data class SortOption(val value: String, val label: String)

val VIDEO_SORTS = listOf(
    SortOption("trending", "趋势"),
    SortOption("date", "最新"),
    SortOption("popularity", "热门"),
    SortOption("views", "最多播放"),
    SortOption("likes", "最多点赞")
)

val RATING_OPTIONS = listOf(
    SortOption("all", "全部"),
    SortOption("general", "一般"),
    SortOption("ecchi", "R18")
)

/** 与管理器滑块同源的 chip 配色：选中 = 主色 16% 浅底 + 主色文字/图标，全局统一强调色。 */
@Composable
fun accentChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
    selectedLabelColor = MaterialTheme.colorScheme.primary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.primary
)

/** InputChip 版本（管理器与在线页的「过滤」标签用）。 */
@Composable
fun accentInputChipColors() = InputChipDefaults.inputChipColors(
    containerColor = Color.Transparent,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
    selectedLabelColor = MaterialTheme.colorScheme.primary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.primary
)

/**
 * 已选标签筛选行 —— 本地页与在线 Iwara 页**共用这一份**。
 *
 * 逐个 chip 入场（淡入 + 自上落下 + 轻微过冲），且只有**新增的那一枚**自己入场，已有 chip
 * 纹丝不动、行高不补间（key(tag) 保证首组合只播一次）。
 * 用 `AnimatedContent(targetState = 整个列表)` 会让已存在的 chip 也重播一遍（"点第二枚时
 * 第一枚先缩回去再一起出来"），所以改为每枚 chip 各自入场。
 */
@Composable
fun TagFilterRow(
    tags: List<String>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    labelOf: (String) -> String = { "#$it" }
) {
    // **整行的高度做补间**，形成"tag 出现 → 下面的视频向下移一小段"的动画：行高从 0 长到 34dp
    //   的过程中，悬浮顶栏的高度逐帧变大 → 内容区的 contentPadding(top) 逐帧跟着变大 →
    //   下面的内容**平滑下移**（而不是"啪"地闪一下）。参数与全仓其它"行显隐"同一组
    //   （220 进 / 240 长 / 180 出 / 200 收）。删掉最后一枚时也能把收起动画播完
    //   （`if (tags.isEmpty()) return` 的写法会硬切）。
    AnimatedVisibility(
        visible = tags.isNotEmpty(),
        enter = fadeIn(tween(220)) + expandVertically(tween(240)),
        exit = fadeOut(tween(180)) + shrinkVertically(tween(200))
    ) {
        Row(
            // 调用方的 padding 留在**行内**：否则行收起到 0 时仍会残留那几 dp，下方内容位置会偏低。
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tags.forEach { tag ->
                key(tag) {
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { shown = true }
                    AnimatedVisibility(
                        visible = shown,
                        // **一条曲线**：淡入 + 自上落下（带轻微过冲）。
                        //   不用"淡入 160 + 下落 180 + 缩放 180"三条各跑各的曲线（会显得效果过多）。
                        //   过冲靠 spring(dampingRatio < 1) 给：落下时略冲过头再弹回一点点，有"落到位"的手感。
                        //   横滑方向不是重点：**层级**由调用方的 zIndex 保证（搜索行压在它之上），
                        //   所以药丸表现为"从搜索栏底下钻出来"。
                        enter = fadeIn(tween(200)) +
                            slideInVertically(
                                animationSpec = spring(dampingRatio = 0.70f, stiffness = 600f)
                            ) { -it },
                        // 出场镜像：淡出 + 向上收回去（同样是单条曲线）。
                        exit = fadeOut(tween(160)) +
                            slideOutVertically(tween(160)) { -it / 2 }
                    ) {
                        InputChip(
                            selected = true,
                            onClick = { onRemove(tag) },
                            label = { Text(labelOf(tag), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "取消该标签",
                                    modifier = Modifier.heightIn(max = 18.dp)
                                )
                            },
                            shape = RoundedCornerShape(50),
                            colors = accentInputChipColors()
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一排「已选标签」chip，每枚自带关闭按钮 —— **全仓库唯一实现**。
 *
 * 多个标签**分体**显示（一枚标签一枚 chip），不拼成一个"MMD + 3DCG"，并且模块化共用。
 * 三处共用：全局筛选面板 / 主页搜索栏的过滤行 / 本地库的过滤行。
 *
 * @param labelOf chip 上的文字（默认 `#标签`；本地库是标签名，直接原样显示）
 */
@Composable
fun SelectedTagChips(
    tags: List<String>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    labelOf: (String) -> String = { "#$it" }
) {
    if (tags.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tags.forEach { tag ->
            InputChip(
                selected = true,
                onClick = { onRemove(tag) },
                label = { Text(labelOf(tag), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "取消该标签",
                        modifier = Modifier.heightIn(max = 18.dp)
                    )
                },
                shape = RoundedCornerShape(50),
                colors = accentInputChipColors()
            )
        }
    }
}

@Composable
fun SelectorChip(
    current: String,
    options: List<SortOption>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = (options.firstOrNull { it.value == current }?.label ?: current)
    Box {
        // 与管理器页排序按钮同款：FilterChip + 下拉勾选。
        FilterChip(
            selected = true,
            onClick = { expanded = true },
            label = { Text(label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            colors = accentChipColors(),
            shape = RoundedCornerShape(50)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label + if (opt.value == current) " ✓" else "") },
                    onClick = {
                        expanded = false
                        onSelect(opt.value)
                    }
                )
            }
        }
    }
}

/** 收起态搜索槽宽度：与定高 44dp 同宽 → 正圆（"小球"）。 */
private val COLLAPSED_SEARCH_WIDTH = 44.dp

/**
 * 紧凑二级搜索槽（定高 44dp）：收起 = 同高 stadium 圆钮（只显示放大镜），点击后**同一个输入框
 * 就地向左长宽**铺满可用宽度。
 *
 * 只有一个容器 + 一个 `BasicTextField`：收起时容器宽 44dp、输入框宽 0（藏在放大镜后面），
 * 展开时容器宽度补间到可用宽度 —— 由宽度动画驱动全部布局（图标留在左边、输入框逐渐显形、
 * 清除按钮跟着长出来），焦点 / IME / 文本全程不换实例。
 *
 * 宽度用"**可用宽 × 比例**"驱动：外层 `BoxWithConstraints` 吃掉调用方给的 `weight(1f)`（`maxWidth`
 * 就是整条槽的可用宽度），**展开比例恒为 1f** ⇒ 宽度 = 可用宽的 100%，由布局直接保证铺满，
 * 与任何补间终值无关；**收起比例 = 44dp ÷ 可用宽** ⇒ 仍是 44dp 正圆。`Row` 用
 * `fillMaxWidth(比例)`，约束是真实变化的，补间也真的在长宽。另**点击区域固定为整条槽**
 * （不随小球收缩），免得 44dp 那么小、手指没点准就"打不开"。
 *
 * 无搜索按钮——在线页用键盘搜索键触发 [onSearch]（本地页实时过滤，可不传）。
 * 清除按钮用于清空并收起。文字 14sp、图标 18dp；展开态把排序/分级 chip 挤走的布局由调用方配合 `if (!open)` 实现。
 *
 * 胶囊框本体已抽成公用组件 `ui/components/AppSearchField.kt` 的 [SearchPill]（筛选面板共用同一份
 * 样式）。本函数只剩"宽度补间 + 整条槽可点 + 收起态盖点击层"这三件事。
 */
@Composable
fun CompactSearchSlot(
    open: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null
) {
    val focusRequester = remember { FocusRequester() }
    // 展开后再请求焦点；runCatching 兜住"还没 attach"的情况（本项目 Compose 版本没有 awaitFrame）
    LaunchedEffect(open) { if (open) runCatching { focusRequester.requestFocus() } }

    // 收起态：整条槽可点（无涟漪，展开本身就是反馈）；展开态：交给输入框与 ✕。
    val interaction = remember { MutableInteractionSource() }

    // 外层 BoxWithConstraints 吃掉调用方的 weight(1f)：maxWidth 就是"整条槽"的可用宽度。
    BoxWithConstraints(
        modifier = modifier
            .height(44.dp)
            .then(
                if (open) Modifier
                else Modifier.clickable(
                    interactionSource = interaction,
                    indication = null
                ) { onOpen() }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        // 收起宽度与 maxWidth **解耦**：原实现收起宽度 = fillMaxWidth(44dp ÷ maxWidth)，而
        //   maxWidth 在启动后 1~2 帧才落定（右侧 chip 显隐 / 行宽稳定）⇒ 动画目标一变就播 tween
        //   = 冷启动搜索小球"缩放一下"。现在动画只挂在"展开↔收起"一个 0..1 进度上，
        //   宽度 = 44dp→铺满 的插值：收起恒 44dp、展开恒铺满、首帧贴 0 进度零动画。
        val expandProgress by animateFloatAsState(
            targetValue = if (open) 1f else 0f,
            animationSpec = tween(260, easing = FastOutSlowInEasing),
            label = "searchWidth"
        )
        val pillWidth = lerp(COLLAPSED_SEARCH_WIDTH, maxWidth, expandProgress)
        // 胶囊框本体用公用组件 [SearchPill]（筛选面板共用同一份样式与行为）。
        //   这里只负责"宽度补间"（展开铺满 / 收起成 44dp 小球）与"整条槽可点"。
        SearchPill(
            value = query,
            onValueChange = onQueryChange,
            placeholder = placeholder,
            modifier = Modifier.width(pillWidth),
            collapsed = !open,
            onClear = onClose,
            onSearch = onSearch,
            focusRequester = focusRequester,
            containerAlpha = if (open) 0.10f else 0.06f
        )

        // 收起态在**整条槽的最上层**盖一层透明点击层：
        //   任何位置（小球本身、小球右边那片空白）的点击都只会进 onOpen() —— 从结构上杜绝
        //   "点击被行内某个子项抢走 → 它自己聚焦弹键盘、搜索条却不展开"。点哪儿都是
        //   "展开 + 自动聚焦弹键盘"。展开态不加，输入框与清除按钮才接得到手。
        if (!open) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = interaction,
                        indication = null
                    ) { onOpen() }
            )
        }
    }
}

/**
 * 回顶按钮的**落点偏移**（全仓库唯一口径）。
 *
 * 底栏是**浮**在内容之上的（内容一直铺到屏幕底），按钮落点若写成"贴内容区底"
 * （bottom 18~20dp）就会落到"底栏那一层"的右方。统一为：**底栏顶边之上再留 13dp**
 * = "列表末行与底栏之间"的右下角。
 *
 * 传各页自己的 [bottomPadding]：主 tab 是底栏高度（≥0）；全屏二级页盖住底栏时传 0
 * （那时"贴屏幕底"本来就是对的）。
 */
fun scrollToTopBottomPadding(bottomPadding: Dp): Dp = bottomPadding + 13.dp

/**
 * **盖住底栏的二级页**（作者页 / 收藏 / 播放列表…）用的落点。
 *
 * 二级页把底栏盖住了，不能直接用主页那个"底栏高度 + 13dp"，但可以把这段高度算回来：
 * 玻璃高度（常量）+ 底部悬浮 10dp + 手势条高度 + 13dp ⇒ 与主页**逐像素同高**。
 */
@Composable
fun scrollToTopBottomPaddingOverlaid(): Dp {
    val barBlock = BAR_HEIGHT_BASE * glassScale(GLASS_BAR_SIZE) + BAR_BOTTOM_INSET
    val navPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return barBlock + navPadding + 13.dp
}

/**
 * **全仓库唯一**的回顶按钮入口（在 `Box` 里调用）——
 * 位置（右下角 + [scrollToTopBottomPadding] 的落点口径）与样式（半透明玻璃圆钮）都收在这里，
 * 主页 / 本地库四视图 / 作者页 / 各二级页**同一个模块**，不再各自写 align + padding。
 *
 * @param bottomPadding 落点的"底部让位"：主 tab 传底栏高度；盖住底栏的二级页传
 *   [scrollToTopBottomPaddingOverlaid]（它自己把底栏那段高度算回来）。
 * @param backdrop 视图级液态玻璃背景层（null = 半透明路径；二级页未接 → null）。
 */
@Composable
fun BoxScope.ScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    bottomPadding: Dp,
    endPadding: Dp = 24.dp,
    backdrop: Backdrop? = null,
    modifier: Modifier = Modifier
) {
    ScrollToTopButton(
        visible = visible,
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier
            .align(Alignment.BottomEnd)
            .padding(end = endPadding, bottom = scrollToTopBottomPadding(bottomPadding))
    )
}

/**
 * 卡片网格的**卡片之间**的间距（两轴共用）。
 *
 * - **只动这里**（卡片彼此的间隙）：10dp → 8dp；
 * - **不动**网格外侧的 `contentPadding`（本地库与在线都是左右各 12dp 的**对称**留白）——
 *   滚动条悬浮在右侧那条 12dp 里，收掉它就会"压到卡片"或者把留白变得不对称。
 * 本地库（LibraryScreen.VideoGrid）与在线信息流（[InfiniteGrid]）共用这一个常量。
 */
internal val GRID_CARD_GAP = 8.dp

/**
 * 一键回到顶部：内容下滚后于右下角以缩放+上浮弹性动画出现。
 *  底色走**半透明材质模块**，与底栏 / 角标胶囊同一份"材质不透明度"，不自己写 surface 底色。
 *  **自绘圆钮**（不用 M3 Card）：M3 Surface 的 shadow / 最小触控机制与"半透明底色"组合时
 *  会出现"八边形边框"。改成与 [GlassPill] 同一套画法（半透明底 + 1dp 内描边、无 M3 机制），
 *  PILL 材质随「材质方案」变化；涟漪关闭（玻璃上就是"扩散噪点"，与底栏同判）。
 *  **液态玻璃档**：[backdrop] 非 null 且方案选了液态玻璃时走 AndroidLiquidGlass 的 drawBackdrop
 *  （真背景模糊 + 边缘折射）。调用方必须保证按钮不在 backdrop 的录制子树内（各页把录制层挂在
 *  **滚动内容**上、按钮是它的兄弟节点 —— 防库文档明示的自采样 SIGSEGV）。
 *  null（二级页未接背景层 / 半透明 / 关档）→ 半透明路径。
 */
@Composable
fun ScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = null
) {
    val cs = MaterialTheme.colorScheme
    val settings = LocalGlassSettings.current
    val glassActive = backdrop != null &&
        settings.mode.isLiquid &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val isLight = cs.surface.luminance() > 0.5f
    val material = glassMaterialFor(
        role = GlassRole.PILL,
        settings = settings,
        surface = cs.surface,
        dark = !isLight,
        backdropActive = glassActive
    )
    // 主题自适应配色 —— 浅色主题玻璃=亮雾面 ⇒ 深色图标/浅描边；
    //   深色主题玻璃=黑雾面 ⇒ 白图标。半透明/关档的黑底路径维持白字不变。
    val iconTint = if (glassActive && isLight) cs.onSurface.copy(alpha = 0.9f)
    else Color.White.copy(alpha = 0.92f)
    // 描边只在**非玻璃档**画，与底栏同一判据（玻璃激活时不描边）。
    //   玻璃自带边界感，再叠一圈线就是"外轮廓描边"。半透明/关档保留（浅色下需要一条边界才不糊成一片）。
    val borderColor = Color.White.copy(alpha = material.edgeHighlight)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)) + scaleIn(
            initialScale = 0.6f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        ),
        exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.6f, animationSpec = tween(150))
    ) {
        val shape = CircleShape
        val baseModifier = Modifier
            .size(44.dp)
            .then(
                if (glassActive && backdrop != null) {
                    // highlight=null（库默认边缘光 = 灰线，同底栏）；
                    // onDrawSurface 画材质罩（不画罩时玻璃太透、白图标在浅内容上看不清）；
                    // 效果配方收敛到 `GlassBackdrop` 模块（唯一处）——
                    //   小半径模糊 + 轻折射（折射带 3dp / 位移 8dp，比底栏小一档）。
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        highlight = null,
                        // 库默认注入 `Shadow.Default`（radius 24dp / offset (0, 4dp)，画在形状之外）。
                        //   这个 44dp 的小圆钮底下挂着 24dp 大投影不是设计要的（自绘圆钮本来就去掉了 M3 shadow）。
                        shadow = null,
                        effects = { glassPillEffects() },
                        onDrawSurface = { drawRect(color = material.fill) }
                    )
                } else {
                    Modifier.clip(shape).background(material.fill)
                }
            )
        Box(
            modifier = baseModifier
                // 玻璃档不描边（见上面 borderColor 注释）。
                .then(if (glassActive) Modifier else Modifier.border(1.dp, borderColor, shape))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "回到顶部"
                ) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = "回到顶部",
                tint = iconTint,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * 回到顶部的**滚动过渡动画**（[ScrollToTopButton] 各调用点共用一对重载）。
 *
 * 不直接 `animateScrollToItem(0)`：千级库从深处回顶要飞越上千个条目（沿途合成/回收一大串，
 * 有掉帧风险）；而直接 `scrollToItem(0)` 会"闪回去"。折中：比 [TO_TOP_ANIMATE_WINDOW] 远时
 * 先瞬移进窗口、再对最后一段播动画 —— 任何深度都有可见的收尾滑动，合成工作量又恒定。
 * 动画中再点/拖滚动条会自然打断（scroll 互斥）。
 */
private const val TO_TOP_ANIMATE_WINDOW = 24

suspend fun LazyGridState.animateScrollToTop() {
    if (firstVisibleItemIndex > TO_TOP_ANIMATE_WINDOW) scrollToItem(TO_TOP_ANIMATE_WINDOW)
    animateScrollToItem(0)
}

/** 列表版：LazyListState 与 LazyGridState 无公共接口，同款各写一份薄实现。 */
suspend fun LazyListState.animateScrollToTop() {
    if (firstVisibleItemIndex > TO_TOP_ANIMATE_WINDOW) scrollToItem(TO_TOP_ANIMATE_WINDOW)
    animateScrollToItem(0)
}

/** 网格卡片入场：渐入 + 上浮，按序错峰（最多 8 档），克制不抢戏。
 *  渲染后（SideEffect）把该条目的 [seenKey] 标记为已看过。 */
@Composable
private fun Entrance(seenKey: String, index: Int, content: @Composable () -> Unit) {
    SideEffect { seenItemKeys.add(seenKey) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index % 8) * 30L)
        progress.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 24f
        }
    ) { content() }
}

/* ---------- 小工具 ---------- */

fun formatCount(n: Int): String = when {
    n >= 100_000_000 -> "${"%.1f".format(n / 100_000_000f)}亿"
    n >= 10_000 -> "${"%.1f".format(n / 10_000f)}万"
    else -> n.toString()
}

/** ISO 时间 → 相对时间文案；解析失败返回 null。 */
fun timeAgoLabel(iso: String?): String? {
    val ms = parseIwaraDate(iso) ?: return null
    val diff = System.currentTimeMillis() - ms
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < hour -> "${(diff / minute).coerceAtLeast(1)} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 30 * day -> "${diff / day} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(ms))
    }
}
