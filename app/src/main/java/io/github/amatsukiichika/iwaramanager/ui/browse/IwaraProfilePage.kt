package io.github.amatsukiichika.iwaramanager.ui.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.amatsukiichika.iwaramanager.data.GlobalFilterHolder
import io.github.amatsukiichika.iwaramanager.data.IwaraAccount
import io.github.amatsukiichika.iwaramanager.data.IwaraApi
import io.github.amatsukiichika.iwaramanager.data.IwaraClient
import io.github.amatsukiichika.iwaramanager.data.IwaraImageItem
import io.github.amatsukiichika.iwaramanager.data.IwaraPostItem
import io.github.amatsukiichika.iwaramanager.data.IwaraProfileData
import io.github.amatsukiichika.iwaramanager.data.IwaraVideoItem
import io.github.amatsukiichika.iwaramanager.data.VideoEntity
import io.github.amatsukiichika.iwaramanager.data.filterBy
import io.github.amatsukiichika.iwaramanager.data.localVideoKeys
import io.github.amatsukiichika.iwaramanager.data.videoKeys
import io.github.amatsukiichika.iwaramanager.ui.DiagLog
import io.github.amatsukiichika.iwaramanager.ui.components.debugAwareErrorText
import kotlin.math.roundToInt
import io.github.amatsukiichika.iwaramanager.ui.VideoCard
import io.github.amatsukiichika.iwaramanager.ui.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 作者页：离线优先，顶栏固定 + 吸顶的药丸分段栏 + 跟着内容滚的作者信息。
 *
 * 作者信息是网格的**通栏首 item**，药丸栏 + 两个小按钮是**覆盖在内容视口上的吸顶块（全页唯一一份渲染）**，
 * 位移 = `max(0, 作者信息实测高 − 已滚出像素)`：下滑整页一起上走 → 触顶锁定 → 之后只有内容动；
 * 上滑位移回涨期间只有内容动 → 内容到顶才把作者信息带回来。
 * 整页零形变：`LocalOverscrollConfiguration provides null` 关掉顶部拉伸（见内容区注释）。
 *
 * 同一个页面既服务在线作者（有 UUID），也服务纯本地作者（本地库里没匹配到 iwara 的「未指定」桶），
 * 后者不发网络请求即可进入。「视频」栏下本地条目首帧就渲染（零网络、点开即本地播放），
 * 在线数据返回后按 `iwaraId` 去重、其余以 `animateItem()` 淡入补在后面。
 *
 * 端点：资料 `GET /profile/{username}`、作品 `GET /videos?user=<uuid>`（回退 `content/video`）、
 * `GET /images?user=`、帖子 `GET /posts?user=`（回退 `content/post`）、`GET /user/{id}/following|followers`。
 */

private enum class ProfileSegment(val label: String) {
    VIDEOS("视频"),
    IMAGES("图片"),
    POSTS("帖子")
}

/** 「视频」栏来源筛选选项（默认全部）。 */
private val SOURCE_FILTERS = listOf(
    SortOption("全部", "全部"),
    SortOption("在线", "在线"),
    SortOption("本地", "本地")
)

/**
 * 作者页「在线内容能不能用」的状态。
 *
 * - [LOADING]：只有 handle、资料还没回来 → 转圈；
 * - [READY]：拿到 UUID → 正常拉取；
 * - [FAILED]：解析失败 → 显示"在线内容加载失败 · 重试"，不再无限转圈；
 * - [UNAVAILABLE]：没有 handle 也没有 UUID（纯本地作者）→ "需要联网才能查看"。
 *
 * 关键：资料"还没解析完"必须与"解析失败"区分开 —— 否则解析失败后会永远停在转圈。
 */
private enum class OnlineState { LOADING, READY, FAILED, UNAVAILABLE }

/** 单个分段的分页状态（每段独立加载，互不阻塞）。 */
private data class PagedState<T>(
    val items: List<T> = emptyList(),
    val page: Int = 0,
    val end: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null
)

/** 作者页覆盖层：流媒体式布局，离线优先（本地视频先出现，在线数据到了再叠上去）。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
@Suppress("DEPRECATION", "DEPRECATION_ERROR")
fun ProfileOverlay(
    username: String,
    cvm: IwaraClientViewModel,
    actions: IwaraNavActions,
    onClose: () -> Unit,
    /** 作者 UUID：拿不到 handle 时兜底，作品/关注/粉丝都能直接用 UUID 拉。 */
    userId: String? = null,
    /** 昵称：资料尚未解析出来时先显示它，避免 header 空一截。 */
    displayName: String? = null,
    /**
     * 本地库的作者分组键（`VideoEntity.authorId`，未匹配时 `VideoViewModel.UNKNOWN_AUTHOR_KEY`）。
     * 有值 → 「视频」栏先渲染该作者的本地视频；null → 按解析出来的 UUID 再补一份本地分组键。
     */
    localAuthorKey: String? = null,
    /** 本地作者的显示名（在线资料拉不到时 header 用它，例如「未指定」）。 */
    localAuthorName: String? = null,
    /** 本地库 ViewModel（读 `allVideos` 做离线优先的本地视频）；外部渲染时会传。 */
    vm: VideoViewModel
) {
    val session by cvm.session.collectAsStateWithLifecycle()

    var profile by remember { mutableStateOf<IwaraProfileData?>(null) }
    var profileError by remember { mutableStateOf<String?>(null) }
    // 关注/取关失败的提示 —— 失败不能静默，否则 UI 无任何反馈。
    var followError by remember { mutableStateOf<String?>(null) }
    var segment by remember { mutableStateOf(ProfileSegment.VIDEOS) }
    var sort by remember { mutableStateOf("date") }
    // 「视频」栏内容来源筛选 —— 全部（默认，本地+在线穿插）/ 在线 / 本地。
    var sourceFilter by remember { mutableStateOf("全部") }

    var videos by remember { mutableStateOf(PagedState<IwaraVideoItem>()) }
    var images by remember { mutableStateOf(PagedState<IwaraImageItem>()) }
    var posts by remember { mutableStateOf(PagedState<IwaraPostItem>()) }
    /** 头部计数的兜底来源（`/profile` 不返回计数时用各分页端点的 `count`）。 */
    var fetchedCounts by remember { mutableStateOf(IwaraRelationCounts()) }

    // ---- 本地视频：订阅本地库全量列表 → 按**键集合**过滤（固定"发布最新 → 最旧"）----
    // 本地分组键（VideoEntity.authorId）存的是 handle（匹配时取 resp.user?.username），
    //   而在线入口可能传 UUID —— 单键匹配会失配。改用键集合：入口键 + handle + UUID 任一命中，
    //   两个入口首帧都能出本地（在线入口靠 account.username=handle 立即命中，不等 profile）。
    val allLocal by vm.allVideos.collectAsStateWithLifecycle()
    val localKeys = remember(localAuthorKey, userId, username, profile) {
        buildSet {
            localAuthorKey?.takeIf { it.isNotBlank() }?.let { add(it) }
            userId?.takeIf { it.isNotBlank() }?.let { add(it) }
            username.takeIf { it.isNotBlank() }?.let { add(it) }
            profile?.uid?.takeIf { it.isNotBlank() }?.let { add(it) }
            profile?.handle?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }
    // 过滤+排序挪到后台线程 —— 若在组合期对**全库**逐条过滤再排序，作者页开着时
    //   每次 DB 写（扫描/下载落库）都会在主线程重跑一趟。首帧先给空列表、一帧后补上
    //   （页面此时还在滑入动画里，肉眼无感）；重算期间保留旧值不闪。
    val localVideos by produceState(emptyList<VideoEntity>(), allLocal, localKeys) {
        value = withContext(Dispatchers.Default) {
            if (localKeys.isEmpty()) {
                emptyList()
            } else {
                allLocal.filter { (it.authorId ?: "") in localKeys }
                    .sortedByDescending { it.createdAt ?: it.addedAt }
            }
        }
    }

    val uid = profile?.uid ?: userId
    val followingState = uid?.let { cvm.followStates[it] } ?: false
    /** 「未指定」桶：本地没匹配到 iwara，不存在在线内容（提示文案与非未匹配情况不同）。 */
    val unmatchedLocal = localAuthorKey == VideoViewModel.UNKNOWN_AUTHOR_KEY

    /** 解析失败的"重试"计数：进 [LaunchedEffect] 的 key，点重试就重新拉一次资料。 */
    var profileRetry by remember { mutableStateOf(0) }

    // ---- 吸顶药丸栏 + 回顶 ----
    // 整页只有一条可滚动链：作者信息是网格的**通栏首 item**（后面跟一块等高的占位），
    //   药丸栏 + 两个小按钮是**覆盖在内容视口上的吸顶块，且全页只渲染这一份**：
    //     下滑 → 整页一起上走（作者信息滚出，药丸栏跟着上移）→ 药丸栏触顶锁定 → 之后只有内容动；
    //     上滑 → 位移回涨期间只有内容动 → 内容到顶才把作者信息带回来。
    //   位移 = max(0, 作者信息实测高 − 已滚出像素)。高度**全部实测**，不写死数字
    //   （简介长短不同、chip 数量随分段不同 ⇒ 钉死 chip 行高避免抖动）。
    val videosGridState = rememberLazyGridState()
    val imagesGridState = rememberLazyGridState()
    val postsGridState = rememberLazyGridState()
    val activeGridState = when (segment) {
        ProfileSegment.VIDEOS -> videosGridState
        ProfileSegment.IMAGES -> imagesGridState
        ProfileSegment.POSTS -> postsGridState
    }
    val scope = rememberCoroutineScope()
    /** 作者信息（含顶部 12dp 内缩）的实测高 H；也是吸顶块的下沉行程。 */
    var headerHeightPx by remember { mutableIntStateOf(0) }
    /** 吸顶块（药丸栏 + 两个小按钮）的实测高；网格首项的占位块按它撑。 */
    var stickyHeightPx by remember { mutableIntStateOf(0) }
    /** 当前分段的"已滚出像素"：首项还是作者信息块时取它的滚动偏移，首项已换成卡片即视为滚到底。 */
    val scrolledPx by remember {
        derivedStateOf {
            val st = activeGridState
            if (st.firstVisibleItemIndex == 0) st.firstVisibleItemScrollOffset else Int.MAX_VALUE
        }
    }
    /** 作者信息是否已完全滚出 → 顶栏切精简模式（与吸顶块同一个真源，不会两处不一致）。 */
    val headerScrolled by remember {
        derivedStateOf { headerHeightPx > 0 && scrolledPx >= headerHeightPx }
    }
    /** 吸顶块的垂直位移：0 = 已触顶锁定。 */
    val stickyOffsetPx by remember {
        derivedStateOf { (headerHeightPx - scrolledPx).coerceAtLeast(0) }
    }

    // 关注请求的忙碌态：大按钮与精简顶栏的小按钮共用同一条 toggle 通路，任一处发起两处都转圈。
    var followBusy by remember { mutableStateOf(false) }
    val followAccount = profile?.asAccount() ?: IwaraAccount(
        id = userId,
        username = username.takeIf { it.isNotBlank() },
        name = displayName ?: localAuthorName
    )

    // ---- 各分段加载器（uid 到位后才能拉）----
    // 必须定义在 [authorHeader] **之前** —— 头部里的排序 chip 会调它们，
    //   而 Kotlin 的局部函数不支持前向引用（放后面 = Unresolved reference 'loadVideos'）。
    fun loadVideos(force: Boolean) {
        val id = uid ?: return
        val st = videos
        // 守卫只挡"正在加载"与"已到底"（与 FeedPager.load 一致）。
        //   若再加 `!force && loaded && items.isNotEmpty()`，则"继续加载"（force=false）
        //   在页面已有内容时会被永远拦掉。
        if (st.loading || (!force && st.end)) return
        // 首拉页码：若写 `if (force) 0 else page + 1`，则 [PagedState.page] 初值 0 会算出 1，
        //   首拉请求第 1 页、第 0 页（最新一批）永远拿不到（FeedPager 的 page 初值是 -1，
        //   同一算式首拉得 0 才对）。注意"未加载过"不能只看 items 是否为空：加载失败时 items 也是空的。
        val next = if (force || !st.loaded) 0 else st.page + 1
        videos = st.copy(loading = true, error = null)
        cvm.loadUserVideos(id, next, sort, scope = scope) { list, end, err ->
            videos = videos.copy(
                items = if (force) list else videos.items + list,
                page = next, end = end, loading = false, loaded = true, error = err
            )
        }
    }

    fun loadImages(force: Boolean) {
        val id = uid ?: return
        val st = images
        // 守卫同 loadVideos（去掉"已加载过就不翻页"那一项 —— 它让翻页永远失败）。
        if (st.loading || (!force && st.end)) return
        // 首拉必须 page 0（同 loadVideos 的说明：page 初值 0 + `page + 1` 会漏掉第一页）。
        val next = if (force || !st.loaded) 0 else st.page + 1
        images = st.copy(loading = true, error = null)
        cvm.loadUserImages(id, next, sort, scope = scope) { list, end, err ->
            images = images.copy(
                items = if (force) list else images.items + list,
                page = next, end = end, loading = false, loaded = true, error = err
            )
        }
    }

    fun loadPosts(force: Boolean) {
        val id = uid ?: return
        val st = posts
        // 守卫同 loadVideos（去掉"已加载过就不翻页"那一项 —— 它让翻页永远失败）。
        if (st.loading || (!force && st.end)) return
        // 首拉必须 page 0（同 loadVideos 的说明：page 初值 0 + `page + 1` 会漏掉第一页）。
        val next = if (force || !st.loaded) 0 else st.page + 1
        posts = st.copy(loading = true, error = null)
        cvm.loadUserPosts(id, next, sort, scope = scope) { list, end, err ->
            posts = posts.copy(
                items = if (force) list else posts.items + list,
                page = next, end = end, loading = false, loaded = true, error = err
            )
        }
    }

    // 作者信息（**不含**分段栏/筛选 —— 那两个在吸顶块里，见 ProfileStickyBar）。
    // 作为通栏首 item 放进各分段网格；horizontal padding 缩到 4dp，加上网格的 12dp contentPadding
    // 正好回到原 16dp 观感；顶部 12dp 由 measuredHeader 补（网格 contentPadding 顶部已归零，
    // 为的是让"吸顶位移 = H − 已滚出"是干净的线性关系）。
    val authorHeader: @Composable () -> Unit = {
        // 「主页 ↗」入口的浏览器跳转用。
        val browserContext = androidx.compose.ui.platform.LocalContext.current
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AccountAvatar(followAccount, 64)
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        profile?.displayName ?: displayName ?: localAuthorName
                            ?: username.ifBlank { "未知作者" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    (profile?.handle ?: username.takeIf { it.isNotBlank() })?.let { handle ->
                        Text(
                            "@$handle",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // 关注 / 粉丝 / 视频 三个计数就在这里。
                    //   拿不到数字 → 显示「—」占位，且**不可点、不给反馈**。
                    CountRow(
                        counts = IwaraRelationCounts(
                            follows = profile?.followsTotal ?: fetchedCounts.follows,
                            followers = profile?.followersTotal ?: fetchedCounts.followers,
                            videos = profile?.videoTotal ?: fetchedCounts.videos
                        ),
                        onOpenFollowing = { uid?.let { actions.openRelations(it, RelationKind.FOLLOWING) } },
                        onOpenFollowers = { uid?.let { actions.openRelations(it, RelationKind.FOLLOWERS) } }
                    )
                }
                // 「主页 ↗」浏览器入口放在**作者页**头部右侧。
                // 有 handle/username 才显示；本地作者（只有 localAuthorKey）没有个人页链接，不显示。
                (profile?.handle ?: username.takeIf { it.isNotBlank() })?.let { handle ->
                    Spacer(Modifier.size(8.dp))
                    Surface(
                        onClick = { openInBrowser(browserContext, "https://www.iwara.tv/profile/$handle") },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp)
                            )
                            Text("主页", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // 简介支持「展开 / 收起」，「展开」两个字贴在省略号「…」右边。
            //   长简介才出现按钮，短简介不留白。
            profile?.bio?.takeIf { it.isNotBlank() }?.let { ExpandableBio(it) }

            // 关注按钮：登录后、且不是自己才显示。
            // 点击后按钮内转圈 + 变灰不可再点，文字做一次淡入切换；「已关注」用另一套配色。
            if (session.loggedIn && uid != null && uid != session.userId) {
                Button(
                    onClick = {
                        val target = uid ?: return@Button
                        if (followBusy) return@Button
                        followBusy = true
                        cvm.toggleFollow(target, followingState, scope = scope) { _, err ->
                            followBusy = false
                            // 失败不静默 —— 存进 followError 在按钮下方提示
                            //（人话/技术细节按 debug 开关在渲染处取轨）；成功时 err 为 null 顺带清掉旧提示。
                            followError = err
                        }
                    },
                    enabled = !followBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (followingState) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (followingState) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    if (followBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.size(10.dp))
                    }
                    AnimatedContent(
                        targetState = followingState,
                        transitionSpec = {
                            (fadeIn(tween(200)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(tween(120))
                        },
                        label = "followLabel"
                    ) { followed -> Text(if (followed) "已关注" else "关注") }
                }
            }

            // 关注失败提示。
            followError?.let {
                Text(
                    debugAwareErrorText(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }

            profileError?.let {
                Text(
                    debugAwareErrorText(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
        }
    }

    /** 作者信息（含顶部 12dp 内缩）+ 实测高 —— 静态分支（没有网格）也用它，保证吸顶位移算得准。 */
    val measuredHeader: @Composable () -> Unit = {
        Box(
            Modifier
                .onSizeChanged { headerHeightPx = it.height }
                .padding(top = 12.dp)
        ) { authorHeader() }
    }

    // 网格的通栏首 item：作者信息 + **等高的占位块**（静止时占位块正好是吸顶块的位置 ⇒
    // 布局与"没有吸顶"时逐像素一致，且不会出现两个药丸栏重影）。
    val headerItem: @Composable () -> Unit = {
        Column {
            measuredHeader()
            Spacer(Modifier.height(with(LocalDensity.current) { stickyHeightPx.toDp() }))
        }
    }

    // 在线可用性（见 [OnlineState] 的说明）。
    val onlineState = when {
        uid != null -> OnlineState.READY
        username.isNotBlank() && profileError != null -> OnlineState.FAILED
        username.isNotBlank() -> OnlineState.LOADING
        else -> OnlineState.UNAVAILABLE
    }

    // ---- 作者页空白排查：关键节点记诊断日志（设置 › 开发者 › 诊断日志）----
    LaunchedEffect(Unit) {
        DiagLog.log("Profile", "作者页组合：username=$username userId=$userId localKey=$localAuthorKey onlineState=$onlineState")
    }
    LaunchedEffect(stickyHeightPx) {
        if (stickyHeightPx > 0) DiagLog.log("Profile", "吸顶块已量高 sticky=${stickyHeightPx}px → 网格门开")
    }
    LaunchedEffect(headerHeightPx) {
        if (headerHeightPx > 0) DiagLog.log("Profile", "作者信息已量高 header=${headerHeightPx}px → 吸顶块显形")
    }
    LaunchedEffect(profile, profileError) {
        DiagLog.log("Profile", "资料拉取结果：profile=${profile != null} error=${profileError != null}")
    }
    // 3 秒空白守卫：两个高度都没量到 = 页面确实停在"只有顶栏"状态，记一条快照。
    LaunchedEffect(Unit) {
        delay(3000)
        if (stickyHeightPx <= 0 || headerHeightPx <= 0) {
            DiagLog.log(
                "Profile",
                "空白守卫触发：sticky=$stickyHeightPx header=$headerHeightPx onlineState=$onlineState " +
                    "segment=$segment profileLoaded=${profile != null} error=$profileError"
            )
        }
    }

    // ---- 资料 → 首段 ----
    // 有 handle 就走 /profile/{username}；只有 UUID（handle 缺失、或纯本地作者）时跳过解析，
    // 各分段仍能用 UUID 正常拉取（header 用传入的 displayName / 本地作者名兜底）。
    // [profileRetry] 进 key：解析失败后点"重试"会重新拉一次。
    LaunchedEffect(username, userId, profileRetry) {
        if (username.isBlank()) return@LaunchedEffect
        cvm.loadProfile(username, scope = scope) { p, err ->
            profile = p
            profileError = err
        }
    }

    // 头部计数兜底：/profile 常常不返回计数字段，用 ?limit=1 读各分页端点的 count。
    LaunchedEffect(uid) {
        val id = uid ?: return@LaunchedEffect
        cvm.loadUserCounts(id, scope = scope) { fetchedCounts = it }
    }

    // 切换分段时按需首拉。
    LaunchedEffect(segment, uid) {
        when (segment) {
            ProfileSegment.VIDEOS -> loadVideos(force = false)
            ProfileSegment.IMAGES -> loadImages(force = false)
            ProfileSegment.POSTS -> loadPosts(force = false)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---- 顶栏（固定）----
        // 精简模式：头部（作者信息 + 分段栏 + 筛选）整块滚出视口后，**标题槽**切成
        //   「小头像 + 名字」，交给 TopAppBar 自己排版 ⇒ 与普通模式的大标题、与返回键逐位对齐
        //   （自己叠一层 Row 手算内缩会横向纵向都对不上）。
        //   精简状态右上角不放小关注按钮 —— 作者信息块里已有一个大的，滚下去再冒一个纯属重复，
        //   actions 槽留空。
        val topName = profile?.displayName ?: displayName ?: localAuthorName
            ?: username.ifBlank { "作者主页" }
        OverlayTopBar(
            title = topName,
            onBack = onClose,
            titleContent = {
                // 精简标题的出现动画**只要淡入淡出** —— 若用 AnimatedContent 会连容器尺寸一起补间
                //   （大小标题高度不同 ⇒ 顶栏"微缩"、旧内容被裁剪）。现在两层叠放、只动 alpha，尺寸恒定。
                val compactAlpha by animateFloatAsState(
                    targetValue = if (headerScrolled) 1f else 0f,
                    animationSpec = tween(150),
                    label = "profileCompactTitle"
                )
                // 槽内不要 fillMaxSize —— M3 TopAppBar 的 title 槽自带垂直居中
                //   （titleVerticalArrangement = Center），槽内 fillMaxSize 会把标题顶到中部；
                //   改 fillMaxWidth 交还给槽自己居中。
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Text(
                        topName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alpha(1f - compactAlpha)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.alpha(compactAlpha)
                    ) {
                        AccountAvatar(followAccount, 32)
                        Spacer(Modifier.size(10.dp))
                        Text(
                            topName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        )

        // ---- 内容区（各分段各自独立滚动；头部/分段栏都在同一条滚动链里）----
        // 切分段只换网格、头部原地不动（不做整块横向滑动，否则会把头部/分段栏一起推出去）。
        //
        // 顶部拉伸 = 整页零形变，做法是 `LocalOverscrollConfiguration provides null`：
        //   ① stretch / glow 只按 `Build.VERSION.SDK_INT >= 31` 选择（Android 12+ 恒为
        //      DrawStretchOverscrollModifier），**与 glowColor 无关**；
        //   ② `ScrollableDefaults.overscrollEffect()` 内部即
        //      `if (LocalOverscrollConfiguration.current == null) return NoOpOverscrollEffect`
        //      ⇒ 在作用域里 provide null 就能拿到真正的 no-op（拦差量、换 effect 都不行：
        //      `Modifier.overscroll()` 只是 `this then effect.getEffectModifier()`）。
        //   代价：这一层里所有滚动容器（视频/图片/帖子网格）连底部回弹也一起没了。
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            // 门 `if (stickyHeightPx > 0)` 只套网格块，**不能包住 ProfileStickyBar** ——
            //   它是全页唯一写 stickyHeightPx 的地方（onSizeChanged）；若把测量者关在自己测量值的门后面，
            //   stickyHeightPx 恒 0 ⇒ 网格永不组成 ⇒ 整页只剩顶栏（确定性死锁）。故 ProfileStickyBar
            //   提出来做 Box 的直属子节点（与 when 同级）。
            //
            // 关门的时机：网格首项里的"等高占位 Spacer"用的是吸顶块的实测高度，而吸顶块在网格**外面**、
            //   同一帧才量出来 ⇒ 第一帧 Spacer=0，卡片先画在简介的位置上，第二帧再带着 animateItem
            //   的位移动画滑下去。等吸顶块量完（一帧之后）再上网格即可。
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (stickyHeightPx > 0) {
                when (segment) {
                    ProfileSegment.VIDEOS -> AuthorVideosGrid(
                        localVideos = localVideos,
                        state = videos,
                        onlineState = onlineState,
                        sourceFilter = sourceFilter,
                        sort = sort,
                        gridState = videosGridState,
                        header = headerItem,
                        actions = actions,
                        onLoadMore = { loadVideos(force = false) },
                        onRetry = { loadVideos(force = true) },
                        onRetryProfile = { profileRetry += 1 }
                    )

                    ProfileSegment.IMAGES -> when (onlineState) {
                        OnlineState.READY -> PagedGrid(
                            state = images,
                            columns = GridCells.Adaptive(120.dp),
                            emptyHint = "暂无图片",
                            gridState = imagesGridState,
                            header = headerItem,
                            onLoadMore = { loadImages(force = false) },
                            onRetry = { loadImages(force = true) },
                            key = { it.id ?: it.hashCode().toString() }
                        ) { image ->
                            ImageCardOnline(
                                image = image,
                                onClick = { actions.openImage(image) },
                                onLongClick = { actions.openImageActions(image) },
                                onAuthorClick = { image.user?.let { actions.openAccountProfile(it) } }
                            )
                        }
                        // 非 READY 时**头部照旧在最上面**（分段栏是吸顶块，恒在）——
                        //   否则分段栏一起消失，切到「图片」后就切不回「视频」。
                        OnlineState.LOADING -> HeaderWithHint(measuredHeader) { SegmentLoading() }
                        OnlineState.FAILED -> HeaderWithHint(measuredHeader) {
                            OnlineFailedHint { profileRetry += 1 }
                        }
                        OnlineState.UNAVAILABLE -> HeaderWithHint(measuredHeader) {
                            OfflineHint(unmatchedLocal, offlineText = "需要联网才能查看")
                        }
                    }

                    ProfileSegment.POSTS -> when (onlineState) {
                        OnlineState.READY -> PagedGrid(
                            state = posts,
                            columns = GridCells.Fixed(1),
                            emptyHint = "暂无帖子",
                            gridState = postsGridState,
                            header = headerItem,
                            onLoadMore = { loadPosts(force = false) },
                            onRetry = { loadPosts(force = true) },
                            key = { it.id ?: it.hashCode().toString() }
                        ) { post ->
                            PostCard(
                                post = post,
                                onClick = { actions.openPost(post) },
                                onAuthorClick = { post.user?.let { actions.openAccountProfile(it) } }
                            )
                        }
                        // 非 READY 也保留头部（分段栏是吸顶块，恒在，同「图片」栏说明）。
                        OnlineState.LOADING -> HeaderWithHint(measuredHeader) { SegmentLoading() }
                        OnlineState.FAILED -> HeaderWithHint(measuredHeader) {
                            OnlineFailedHint { profileRetry += 1 }
                        }
                        OnlineState.UNAVAILABLE -> HeaderWithHint(measuredHeader) {
                            OfflineHint(unmatchedLocal, offlineText = "需要联网才能查看")
                        }
                    }
                }
                } // ← if (stickyHeightPx > 0) 门只套网格块（ProfileStickyBar 在门外，防死锁）

                // ---- 吸顶块（全页唯一的一份药丸栏 + 两个小按钮）----
                // 位移 0 = 触顶锁定（内容从它下面滑过）；位移 = H 时正好压住网格首项里的等高占位块 ⇒
                // 静止时与"没有吸顶"的排版逐像素一致。位移在 layout 相位读，滚动时不触发重组。
                ProfileStickyBar(
                    segment = segment,
                    sourceFilter = sourceFilter,
                    sort = sort,
                    onSegment = { idx ->
                        val next = ProfileSegment.entries.getOrNull(idx) ?: return@ProfileStickyBar
                        if (next == segment) return@ProfileStickyBar
                        // 切分段保持当前的"展开/吸顶"状态：已经吸顶的新分段也停在吸顶位置
                        //   （作者信息保持隐藏、内容从第 1 条开始），屏幕不跳；没吸顶的照旧停在顶部。
                        val stuck = headerScrolled
                        val h = headerHeightPx
                        val target = when (next) {
                            ProfileSegment.VIDEOS -> videosGridState
                            ProfileSegment.IMAGES -> imagesGridState
                            ProfileSegment.POSTS -> postsGridState
                        }
                        segment = next
                        if (h > 0) scope.launch { target.scrollToItem(0, if (stuck) h else 0) }
                    },
                    onSourceFilter = { sourceFilter = it },
                    onSort = { picked ->
                        sort = picked
                        when (segment) {
                            ProfileSegment.VIDEOS -> loadVideos(force = true)
                            ProfileSegment.IMAGES -> loadImages(force = true)
                            else -> loadPosts(force = true)
                        }
                    },
                    onHeight = { stickyHeightPx = it },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        // H 还没测出来的那一帧（首帧）先别画：此时位移算出来是 0，会闪现在视口顶端。
                        .then(if (headerHeightPx > 0) Modifier else Modifier.alpha(0f))
                        .offset { IntOffset(0, stickyOffsetPx) }
                )
            }
        }
    }
}

/**
 * 非 READY（加载中 / 失败 / 离线）时的内容区：**头部照旧在顶部**，下面挂一个居中的提示。
 *
 * 分段栏在吸顶块里恒在，不随内容区一起被换掉。左右内缩补 12dp（网格外没有 contentPadding），
 * 顶部 12dp 由传进来的 [header]（measuredHeader）自己带。
 */
@Composable
private fun HeaderWithHint(header: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        header()
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * 吸顶块：药丸分段栏 + 两个小按钮（来源筛选 / 排序）。
 *
 * **全页唯一的一份渲染**：覆盖在内容视口顶端，由调用方给的 [Modifier] 位移驱动下沉 / 锁定。
 * 不透明底，内容从它下面滑过不会透视。两个小按钮那一行**钉死高度** ——
 * 「来源筛选」只在视频栏出现，不钉死的话切分段会让整块高度变化、吸顶时抖动。
 *
 * 底边不放 1dp 分隔线（静止状态下像"视频上面多出一条横线"）。块高由 `onSizeChanged` 实测。
 */
@Composable
private fun ProfileStickyBar(
    segment: ProfileSegment,
    sourceFilter: String,
    sort: String,
    onSegment: (Int) -> Unit,
    onSourceFilter: (String) -> Unit,
    onSort: (String) -> Unit,
    onHeight: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            // 底色必须与二级页背景一致（overlay 宿主用的是 colorScheme.background，
            // 不是 surface —— 浅色下两者是 #F7F8FA / #FFFFFF，用错会露出一条色带）。
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { onHeight(it.height) }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        PillSegmentSelector(
            options = ProfileSegment.entries.map { it.label },
            current = segment.ordinal,
            onSelect = onSegment,
            horizontalPadding = 0.dp
        )
        Row(
            Modifier.fillMaxWidth().height(34.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 来源筛选：排序 chip（趋势等）左边，同款样式；仅「视频」栏有意义。
            if (segment == ProfileSegment.VIDEOS) {
                SelectorChip(sourceFilter, SOURCE_FILTERS) { onSourceFilter(it) }
                Spacer(Modifier.size(8.dp))
            }
            SelectorChip(sort, VIDEO_SORTS) { onSort(it) }
        }
    }
}

/** 折叠态简介固定显示的行数；超出才出现「展开」。 */
private const val BIO_COLLAPSED_LINES = 3

/** 「展开」/「收起」—— 都是两个汉字，宽度相同（右侧预留宽按它算）。 */
private const val BIO_EXPAND_LABEL = "展开"
private const val BIO_COLLAPSE_LABEL = "收起"

/**
 * 可展开的作者简介。
 *
 * 「展开」两个字放在省略号『…』的**右边** —— 不是另起一行、也不是右下角浮动。
 * 用 `Box` + `offset` 精确落位（不用 FlowRow：只有这样才能正好贴在「…」正右方）：
 * 1. 先 `rememberTextMeasurer` 把按钮宽**量出来**，连同 4dp 间隙一起作为文字**右侧永久预留**
 *    （折叠态、展开态都留）—— 省略号因此必然落在预留区之前，按钮永远放得下；
 *    预留宽不依赖 "有没有溢出"，所以不会出现「预留 ↔ 溢出」互相触发的抖动。
 * 2. `onTextLayout` 给锚点：折叠态取**被省略号截断的那一行**（第 [BIO_COLLAPSED_LINES] 行）的
 *    右边缘 + 行顶；展开态取最后一行 ⇒ 折叠时紧跟「…」，展开时跟在文字末尾（「收起」也在原位置）。
 *    锚点只被 `Modifier.offset {}` 在 layout 相位读取，不触发重组。
 * 3. 只有 `hasVisualOverflow` 才显示按钮（短简介不留白）。**注意 `overflow` 只在折叠态更新** ——
 *    否则展开后不再溢出会把按钮自己弄没，就收不回去了。
 */
@Composable
private fun ExpandableBio(text: String) {
    val style = MaterialTheme.typography.bodySmall
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val buttonWidthPx = remember(measurer, style) {
        measurer.measure(AnnotatedString(BIO_EXPAND_LABEL), style = style).size.width
    }
    val gapPx = with(density) { 4.dp.roundToPx() }
    val reserveDp = with(density) { (buttonWidthPx + gapPx).toDp() }

    var expanded by remember(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    var anchorX by remember(text) { mutableFloatStateOf(0f) }
    var anchorY by remember(text) { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(
            text = text,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else BIO_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(end = reserveDp),
            onTextLayout = { r ->
                if (!expanded) overflow = r.hasVisualOverflow
                val last = (r.lineCount - 1).coerceAtLeast(0)
                val line = if (expanded) last else (BIO_COLLAPSED_LINES - 1).coerceAtMost(last)
                anchorX = r.getLineRight(line)
                anchorY = r.getLineTop(line)
            }
        )
        if (overflow) {
            Text(
                text = if (expanded) BIO_COLLAPSE_LABEL else BIO_EXPAND_LABEL,
                style = style,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .offset { IntOffset(anchorX.roundToInt() + gapPx, anchorY.roundToInt()) }
                    .clickable { expanded = !expanded }
            )
        }
    }
}

/**
 * 「关注 / 粉丝 / 视频」计数行。
 *
 * - **有数字**的关注 / 粉丝 → 可点，进关系列表二级页；数字那项高亮成主色（暗示可点）。
 * - **拿不到数字**（离线 / 未登录 / 接口没给）→ 显示 `—`，且**不是可点组件**（点了没有任何反馈）。
 * - 「视频」数只作信息展示，不可点（当前页就是视频列表）。
 */
@Composable
private fun CountRow(
    counts: IwaraRelationCounts,
    onOpenFollowing: () -> Unit,
    onOpenFollowers: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CountItem("关注", counts.follows, onOpenFollowing)
        Spacer(Modifier.size(14.dp))
        CountItem("粉丝", counts.followers, onOpenFollowers)
        Spacer(Modifier.size(14.dp))
        CountItem("视频", counts.videos, null)
    }
}

@Composable
private fun CountItem(label: String, value: Int?, onClick: (() -> Unit)?) {
    val clickable = value != null && onClick != null
    val text = "$label ${value?.toString() ?: "—"}"
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (clickable) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .then(if (clickable) Modifier.clickable { onClick?.invoke() } else Modifier)
            .padding(vertical = 2.dp)
    )
}

/**
 * 作者页「视频」栏。
 *
 * **不论从本地还是在线进入都是同一个状态**：
 * 1. 首帧渲染本地视频（零网络）；
 * 2. 在线数据返回后按 [AuthorRows.mergeAll] 重排行序：主干恒等于在线顺序，命中的位置**就地把
 *    在线卡换成本地版本**（点击即本地播放、卡片带「本地」标签），placement 动画滑过去；
 * 3. 未匹配的本地按自己留存的发布日期插进主干，**时间未知的排到最后**（旧实现回落到"导入时间"
 *    ⇒ 本地全堆在上面）；
 * 4. 所有行共用 `Modifier.animateItem()` —— 插入/移位/收尾全程动画。
 *
 * 头部（[header]）是**通栏首 item**：里面是"作者信息 + 等高的吸顶块占位"，所以整页只有一条滚动链；
 * 药丸栏由页面层的吸顶块负责，网格里看不到它，也就不会重影。
 */
/**
 * 作者页的全局筛选取键：本地行按 `tagsCsv`，在线行按 `tag.id`
 * （与本地库 / 其它在线列表用同一套规则，见 data/GlobalFilter.kt）。
 */
private fun authorRowKeys(row: AuthorVideoRow): Pair<String, List<String>?> = when (row) {
    is AuthorVideoRow.LocalV -> localVideoKeys(row.video)
    is AuthorVideoRow.OnlineV -> videoKeys(row.item)
}

@Composable
private fun AuthorVideosGrid(
    localVideos: List<VideoEntity>,
    state: PagedState<IwaraVideoItem>,
    onlineState: OnlineState,
    sourceFilter: String,
    sort: String,
    gridState: LazyGridState,
    header: @Composable () -> Unit,
    actions: IwaraNavActions,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    /** 解析资料失败后的重试。 */
    onRetryProfile: () -> Unit
) {
    val onlineReady = onlineState == OnlineState.READY

    // ---- 行合并（穿插核心，纯函数在 AuthorRows.kt，有单测）----
    // 合并挪到后台线程 —— 每次翻页换列表不在主线程重排整份 rows。
    val rows by produceState(emptyList<AuthorVideoRow>(), sourceFilter, sort, localVideos, state.items) {
        value = withContext(Dispatchers.Default) {
            when (sourceFilter) {
                "本地" -> localVideos.map { AuthorVideoRow.LocalV(it) }
                "在线" -> state.items.map { AuthorVideoRow.OnlineV(it) }
                else -> AuthorRows.mergeAll(
                    online = state.items,
                    local = localVideos,
                    chronological = sort == "date"
                )
            }
        }
    }

    // 全局筛选器：作者页也是"内容列表" ⇒ 合并之后再过最后一道。
    //   注意分工：`rows`（原始）继续负责"要不要继续拉下一页 / 过渡提示"的判据，
    //   `shownRows` 只负责渲染 —— 否则被筛空时翻页判据会误判。
    // 订阅列表消费端的防抖视图（打字期间不重算）。
    val filter by GlobalFilterHolder.listState.collectAsStateWithLifecycle()
    val shownRows = remember(rows, filter) { rows.filterBy(filter, ::authorRowKeys) }

    // 在线还在路上、而本地已经铺满屏幕 → 底部挂一条过渡提示（仅"全部"模式）。
    val showOnlineHint = sourceFilter != "本地" && onlineReady && localVideos.isNotEmpty() &&
        state.items.isEmpty() && state.error == null && !(state.loaded && state.end)
    val scope = rememberCoroutineScope()
    val showTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }
    }

    // **滚动到底自动加载下一批** —— 与视频页 `InfiniteGrid` 同一套判据。
    //   底部「继续加载」按钮保留作**失败重试**的兜底，正常滚动用不到。
    //   注意：`state.loading/end` 是普通值（不是 State），所以必须当 remember 的 key；
    //   真正驱动重算的是 `gridState.layoutInfo`。
    val shouldLoadMore by remember(rows.size, state.loading, state.end, state.items.size, sourceFilter, onlineReady) {
        derivedStateOf {
            if (sourceFilter == "本地" || !onlineReady) return@derivedStateOf false
            if (rows.isEmpty() || state.loading || state.end) return@derivedStateOf false
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            // 顶部不留 contentPadding —— 顶部 12dp 内缩由首项（作者信息）自带，
            //   "已滚出像素 → 吸顶块位移 = H − 已滚出"才是干净的线性关系。
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 头部 = 通栏首 item（作者信息 + 吸顶块占位）：先滚走它再滚内容、同一条惯性。
            item(span = { GridItemSpan(maxLineSpan) }, key = "__header__") { header() }

            if (shownRows.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "__placeholder__") {
                    // 懒布局子项的高度约束是无限的，占位要给固定高度才能居中。
                    Box(
                        Modifier.fillMaxWidth().height(280.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // 有内容、但被全局筛选器筛掉了 —— 必须与"真的没有"分开说，
                        //   并且给一个「继续加载」（后面几页里可能有命中的内容）。
                        if (rows.isNotEmpty()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "筛选后没有符合的内容（已隐藏 ${rows.size} 项）",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (onlineReady && sourceFilter != "本地" && !state.end) {
                                    TextButton(onClick = onLoadMore) { Text("继续加载") }
                                }
                            }
                        } else {
                            when {
                                sourceFilter == "本地" -> Text(
                                    "暂无本地视频",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                onlineState == OnlineState.LOADING -> SegmentLoading()
                                onlineState == OnlineState.FAILED -> OnlineFailedHint(onRetryProfile)
                                !onlineReady -> Text(
                                    // 纯本地作者（没有 UUID）→ 不存在在线内容，别显示"重试"。
                                    "暂无本地视频",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                else -> SegmentPlaceholder(state, "暂无视频", onRetry)
                            }
                        }
                    }
                }
            } else {
                // 单一 items 块：本地/在线混排，全部带 animateItem —— 在线到达时命中的本地行
                //   以 placement 动画滑到自己的在线位置、在线行淡入补位。
                items(shownRows, key = { it.key }) { row ->
                    Box(Modifier.animateItem()) {
                        when (row) {
                            is AuthorVideoRow.LocalV -> VideoCard(
                                video = row.video,
                                onClick = { actions.playLocalVideo(row.video) },
                                onTagClick = { actions.openTagVideos(it) },
                                showTags = true,
                                // 与在线条目共用同一个设置开关（组件内部还会再判 rating 是否真为 ecchi）。
                                showR18Tag = LocalShowR18Tag.current,
                                // 本地/在线穿插混排，本地卡片必须有「本地」标签可辨。
                                showLocalBadge = true
                            )
                            is AuthorVideoRow.OnlineV -> OnlineVideoCard(row.item, actions)
                        }
                    }
                }

                if (showOnlineHint) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "__online_hint__") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "正在加载在线…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 纯本地作者（没有 UUID）不发在线请求 → 也就没有"加载更多"可言；「本地」筛选同理。
                if (onlineReady && sourceFilter != "本地") {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "__footer__") {
                        PagedFooter(state, onLoadMore, onRetry)
                    }
                }
            }
        }

        // 作者页只保留回到顶部按钮，不做比例滚动条。
        ScrollToTopButton(
            visible = showTop,
            onClick = { scope.launch { gridState.animateScrollToTop() } },
            modifier = Modifier
            .align(Alignment.BottomEnd)
            // 统一落点（这些二级页盖住底栏，传 0 ⇒ 仍是贴屏幕底 + 13dp）。
            .padding(end = 24.dp, bottom = scrollToTopBottomPadding(0.dp))
        )
    }
}

/** 解析资料失败（在线内容拉不到）时的占位。 */
@Composable
private fun OnlineFailedHint(onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "在线内容加载失败",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.size(12.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/** 离线 / 无在线内容时的分段占位。 */
@Composable
private fun OfflineHint(unmatchedLocal: Boolean, offlineText: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (unmatchedLocal) "这些本地视频未匹配到 iwara，没有在线内容" else offlineText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

/** 「在线可用性还没确定」时的占位（正在解析资料）—— 不能误显示成"需要联网"。 */
@Composable
private fun SegmentLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * 关注 / 粉丝关系列表二级页（从计数点进来）。
 * 复用 `PagedAccountList` 与 `loadFollowing` / `loadUserFollowers`。
 */
@Composable
fun RelationsOverlay(
    userId: String,
    kind: RelationKind,
    cvm: IwaraClientViewModel,
    actions: IwaraNavActions,
    onClose: () -> Unit
) {
    var state by remember { mutableStateOf(PagedState<IwaraAccount>()) }
    val pageScope = rememberCoroutineScope()

    fun load(force: Boolean) {
        val st = state
        if (st.loading || (!force && st.end) || (!force && st.loaded && st.items.isNotEmpty())) return
        // 首拉必须 page 0（同 loadVideos 的说明：page 初值 0 + `page + 1` 会漏掉第一页）。
        val next = if (force || !st.loaded) 0 else st.page + 1
        state = st.copy(loading = true, error = null)
        val done: (List<IwaraAccount>, Boolean, String?) -> Unit = { list, end, err ->
            state = state.copy(
                items = if (force) list else state.items + list,
                page = next, end = end, loading = false, loaded = true, error = err
            )
        }
        when (kind) {
            RelationKind.FOLLOWING -> cvm.loadFollowing(userId, next, scope = pageScope, onDone = done)
            RelationKind.FOLLOWERS -> cvm.loadUserFollowers(userId, next, scope = pageScope, onDone = done)
        }
    }

    LaunchedEffect(userId, kind) { load(false) }

    Column(Modifier.fillMaxSize()) {
        OverlayTopBar(title = kind.label, onBack = onClose)
        PagedAccountList(
            state = state,
            emptyHint = if (kind == RelationKind.FOLLOWING) "TA 还没有关注任何人" else "TA 还没有粉丝",
            onLoadMore = { load(false) },
            onRetry = { load(true) },
            onOpen = { actions.openAccountProfile(it) }
        )
    }
}

/* ---------- 内容容器 ---------- */

@Composable
private fun <T> PagedGrid(
    state: PagedState<T>,
    columns: GridCells,
    emptyHint: String,
    gridState: LazyGridState,
    header: @Composable () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    key: (T) -> Any,
    itemContent: @Composable (T) -> Unit
) {
    val scope = rememberCoroutineScope()
    val showTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = columns,
            modifier = Modifier.fillMaxSize(),
            // 顶部 12dp 由首项（作者信息）自带 —— 与「视频」栏同样是吸顶位移的干净线性关系。
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 头部 = 通栏首 item（作者信息 + 吸顶块占位），与「视频」栏同一条滚动链。
            item(span = { GridItemSpan(maxLineSpan) }, key = "__header__") { header() }
            if (state.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "__placeholder__") {
                    // 懒布局子项高度约束无限，占位给固定高度才能居中。
                    Box(
                        Modifier.fillMaxWidth().height(280.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        SegmentPlaceholder(state, emptyHint, onRetry)
                    }
                }
            } else {
                items(state.items, key = key) { itemContent(it) }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PagedFooter(state, onLoadMore, onRetry)
                }
            }
        }
        ScrollToTopButton(
            visible = showTop,
            onClick = { scope.launch { gridState.animateScrollToTop() } },
            modifier = Modifier
            .align(Alignment.BottomEnd)
            // 统一落点（这些二级页盖住底栏，传 0 ⇒ 仍是贴屏幕底 + 13dp）。
            .padding(end = 24.dp, bottom = scrollToTopBottomPadding(0.dp))
        )
    }
}

@Composable
private fun PagedAccountList(
    state: PagedState<IwaraAccount>,
    emptyHint: String,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    /** 回传整个账号对象（含 UUID）—— 作者页凭它直接拉内容，省掉一次 profile 解析。 */
    onOpen: (IwaraAccount) -> Unit
) {
    if (state.items.isEmpty()) {
        SegmentPlaceholder(state, emptyHint, onRetry)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            state.items,
            key = { it.id ?: it.username ?: it.name ?: it.hashCode().toString() }
        ) { account ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccountAvatar(account, 40)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            account.name ?: account.username ?: "未知用户",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        account.username?.let { handle ->
                            Text(
                                "@$handle",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    account.username?.let {
                        TextButton(onClick = { onOpen(account) }) { Text("主页") }
                    }
                }
            }
        }
        item(key = "__footer__") {
            PagedFooter(state, onLoadMore, onRetry)
        }
    }
}

/** 列表/网格底部：加载中 / 加载更多 / 出错重试。 */
@Composable
private fun <T> PagedFooter(state: PagedState<T>, onLoadMore: () -> Unit, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        when {
            state.loading -> CircularProgressIndicator()
            state.error != null -> TextButton(onClick = onRetry) { Text("重试") }
            !state.end -> TextButton(onClick = onLoadMore) { Text("加载更多") }
            else -> Text(
                "没有更多了",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 空 / 错误 / 加载占位（首屏）。 */
@Composable
private fun <T> SegmentPlaceholder(state: PagedState<T>, emptyHint: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            state.loading -> CircularProgressIndicator()
            state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    // 普通模式显示人话，debug 开关打开时显示原样技术串
                    debugAwareErrorText(state.error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.size(12.dp))
                TextButton(onClick = onRetry) { Text("重试") }
            }
            else -> Text(emptyHint, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 头像（有则显示，无则占位），尺寸与关注列表保持一致。 */
@Composable
private fun AccountAvatar(account: IwaraAccount?, size: Int) {
    val url = IwaraClient.avatarUrl(account?.avatar)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(Modifier.size(size.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size((size * 0.6f).dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 用系统浏览器打开链接，失败静默忽略（作者页「主页 ↗」入口）。 */
private fun openInBrowser(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
