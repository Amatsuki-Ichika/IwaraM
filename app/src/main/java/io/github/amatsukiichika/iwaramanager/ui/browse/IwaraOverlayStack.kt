package io.github.amatsukiichika.iwaramanager.ui.browse

import io.github.amatsukiichika.iwaramanager.data.IwaraImageItem
import io.github.amatsukiichika.iwaramanager.data.IwaraPostItem
import io.github.amatsukiichika.iwaramanager.data.IwaraVideoItem

/* ---------- 覆盖层栈模型与纯逻辑（原 IwaraCommon.kt 覆盖层栈区块，按声明整体搬移） ---------- */
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
