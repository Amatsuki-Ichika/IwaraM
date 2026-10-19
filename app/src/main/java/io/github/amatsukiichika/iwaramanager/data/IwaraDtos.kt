package io.github.amatsukiichika.iwaramanager.data

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/* ---------- Iwara 客户端模块的 API DTO（浏览/登录/互动用） ----------
 * 全部字段可空：Gson 反序列化遇缺失字段时不会走 Kotlin 默认值，
 * 使用处一律安全判空，坏数据不应导致崩溃。
 *
 * 部分 DTO 标 @Immutable：Compose 编译器对 `List` 字段只能判 unstable
 * （接口类型的实现可能是可变的），而这些 DTO 会被传进每张卡片与导航回调，
 * 不标的话整棵信息流子树都无法跳过重组。该承诺成立的前提：
 * Gson 只在解析时一次性写入、之后从不原地改动，且每次数据变化都产生新实例
 * （FeedPager / PagedState 都是整体替换）。这会让 data 层依赖
 * androidx.compose.runtime —— 仅注解、无运行时行为。
 */

/** 通用分页响应：{ results, count, page, limit }，page 从 0 开始。 */
@Immutable
data class IwaraPage<T>(
    val results: List<T>? = null,
    val count: Int? = null,
    val page: Int? = null,
    val limit: Int? = null
)

/** 文件引用（头像 / 封面 / 图库图片等），URL 由模板拼接。 */
data class IwaraFileRef(
    val id: String? = null,
    val name: String? = null
)

data class IwaraTagRef(
    val id: String? = null
)

// tag 联想结果复用 IwaraApi.kt 里的 IwaraTag(val id)（同包，避免重名声明）：
// GET /tags?filter= 返回 {id, type, sensitive}，id 即 slug，可直接用于 /videos?tags=。

data class IwaraAccount(
    val id: String? = null,
    val name: String? = null,
    val username: String? = null,
    val avatar: IwaraFileRef? = null,
    val role: String? = null
)

/** GET /profile/{username} 响应里嵌套的用户对象。 */
data class IwaraProfileUser(
    val id: String? = null,
    val name: String? = null,
    val username: String? = null,
    val avatar: IwaraFileRef? = null,
    val body: String? = null,
    @SerializedName("followsCount") val followsCount: Int? = null,
    @SerializedName("followersCount") val followersCount: Int? = null,
    @SerializedName("friendsCount") val friendsCount: Int? = null,
    @SerializedName("videoCount") val videoCount: Int? = null
)

/**
 * 用户主页（GET /profile/{username}）。
 *
 * 该端点真实响应是嵌套的 `{"user":{...}}`：作者筛选需要这里的 UUID 再 `GET videos?user=<UUID>`，
 * getFollowing 同样取 `data['user']['id']`。按扁平解析时 `id` 恒为 null，
 * 会导致"在线视频按作者筛选"报「无法获取作者信息」、作者主页的「TA 的视频」也拉不出来。
 * 嵌套与扁平两种形态都要兼容（访问器优先取嵌套 user，缺失时回退扁平字段）。
 */
data class IwaraProfileData(
    /** 嵌套 user 对象（正常响应）。 */
    val user: IwaraProfileUser? = null,

    // 以下为扁平兼容字段（部分端点与早期缓存直接返回用户对象本身）。
    val id: String? = null,
    val name: String? = null,
    val username: String? = null,
    val avatar: IwaraFileRef? = null,
    val body: String? = null,
    @SerializedName("followsCount") val followsCount: Int? = null,
    @SerializedName("followersCount") val followersCount: Int? = null,
    @SerializedName("friendsCount") val friendsCount: Int? = null,
    @SerializedName("videoCount") val videoCount: Int? = null
) {
    /** 用户 UUID：作者筛选（videos?user=）与 /user/{id}/... 路径都必须用它。 */
    val uid: String? get() = user?.id ?: id

    /** 昵称（展示名，可能含空格）。 */
    val displayName: String? get() = user?.name ?: name

    /** @handle（URL 里的用户名，可能为 null；部分账号没有设置）。 */
    val handle: String? get() = user?.username ?: username

    val avatarRef: IwaraFileRef? get() = user?.avatar ?: avatar

    val bio: String? get() = body ?: user?.body

    val followsTotal: Int? get() = followsCount ?: user?.followsCount
    val followersTotal: Int? get() = followersCount ?: user?.followersCount
    val videoTotal: Int? get() = videoCount ?: user?.videoCount

    /** 转成通用账号对象（头像 / 关注按钮等复用同一套组件）。 */
    fun asAccount(): IwaraAccount = IwaraAccount(
        id = uid,
        name = displayName,
        username = handle,
        avatar = avatarRef
    )
}

/** 关注/粉丝列表条目：不同端点分别挂在 user / follower 字段下。 */
data class IwaraFollowEdge(
    val user: IwaraAccount? = null,
    val follower: IwaraAccount? = null,
    // 兼容扁平响应：部分端点直接返回用户对象本身（无 user/follower 包装）。
    val id: String? = null,
    val name: String? = null,
    val username: String? = null,
    val avatar: IwaraFileRef? = null
) {
    /** 取本条边对应的账号：优先包装字段，扁平响应则就地构造。 */
    fun asAccount(): IwaraAccount? =
        user ?: follower
            ?: id?.takeIf { it.isNotBlank() }?.let {
                IwaraAccount(id = it, name = name, username = username, avatar = avatar)
            }
}

/** 收藏列表条目：results[].video / results[].image。 */
data class IwaraFavVideoEdge(
    val video: IwaraVideoItem? = null
)

data class IwaraFavImageEdge(
    val image: IwaraImageItem? = null
)

/** 视频：列表项与详情共用（详情字段为超集）。 */
@Immutable
data class IwaraVideoItem(
    val id: String? = null,
    val title: String? = null,
    val body: String? = null,
    val rating: String? = null,
    val embedUrl: String? = null,
    val fileUrl: String? = null,
    val file: IwaraFileRef? = null,
    val user: IwaraAccount? = null,
    val tags: List<IwaraTagRef>? = null,
    @SerializedName("numLikes") val numLikes: Int? = null,
    @SerializedName("numViews") val numViews: Int? = null,
    @SerializedName("numComments") val numComments: Int? = null,
    val createdAt: String? = null,
    val liked: Boolean? = null,
    val private: Boolean? = null,
    val unlisted: Boolean? = null
)

/**
 * 图片（图集）。列表项与详情共用，字段名与站点实际 JSON 对齐：
 * - 封面字段是 `thumbnail:{id,name}`（不是 `file`），否则封面恒空、「图片」板块整片白板；
 * - 多图字段是 `files:[...]`（不是 `fileList`），否则图片查看器只有 1 页；
 * - 另含 `slug / numViews / numImages`（列表与详情的展示、计数需要）。
 * 旧的 `file`/`fileList` 保留为兼容回退，统一走 [thumbRef] / [fileRefs] 访问器，避免各处再判字段。
 */
@Immutable
data class IwaraImageItem(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val body: String? = null,
    val rating: String? = null,
    /** 站点实际字段：封面缩略图。 */
    val thumbnail: IwaraFileRef? = null,
    /** 站点实际字段：图集全部原图。 */
    val files: List<IwaraFileRef>? = null,
    // 以下为兼容回退字段（部分端点与早期响应形态）。
    val file: IwaraFileRef? = null,
    val fileList: List<IwaraFileRef>? = null,
    val user: IwaraAccount? = null,
    @SerializedName("numLikes") val numLikes: Int? = null,
    @SerializedName("numViews") val numViews: Int? = null,
    @SerializedName("numImages") val numImages: Int? = null,
    @SerializedName("numComments") val numComments: Int? = null,
    val createdAt: String? = null,
    val liked: Boolean? = null
) {
    /** 封面引用：优先站点实际的 thumbnail，回退旧 file 字段。 */
    val thumbRef: IwaraFileRef? get() = thumbnail ?: file

    /** 图集全部图片引用（查看器翻页用）；都缺时退回单张封面。 */
    val fileRefs: List<IwaraFileRef>
        get() = files?.takeIf { it.isNotEmpty() }
            ?: fileList?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(file ?: thumbnail)
}

data class IwaraPostItem(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    val body: String? = null,
    val user: IwaraAccount? = null,
    @SerializedName("numLikes") val numLikes: Int? = null,
    @SerializedName("numViews") val numViews: Int? = null,
    @SerializedName("numComments") val numComments: Int? = null,
    val createdAt: String? = null,
    val liked: Boolean? = null
)

data class IwaraComment(
    val id: String? = null,
    val body: String? = null,
    val user: IwaraAccount? = null,
    @SerializedName("numLikes") val numLikes: Int? = null,
    @SerializedName("numReplies") val numReplies: Int? = null,
    val createdAt: String? = null,
    @SerializedName("parentId") val parentId: String? = null
)

/** 发表评论请求体。rulesAgreement 为 2026 版必填字段。 */
data class IwaraCommentBody(
    val body: String,
    @SerializedName("rulesAgreement") val rulesAgreement: Boolean = true,
    @SerializedName("parentId") val parentId: String? = null
)

data class IwaraPlaylist(
    val id: String? = null,
    val title: String? = null,
    val user: IwaraAccount? = null,
    @SerializedName("numVideos") val numVideos: Int? = null,
    val createdAt: String? = null,
    /** 播放列表详情可能内嵌视频分页；字段缺失时置空、界面降级。 */
    val contents: IwaraPage<IwaraVideoItem>? = null
)

data class IwaraPlaylistBody(val title: String)

/* ---------- 播放地址解析 ---------- */

data class IwaraQualitySource(
    val name: String? = null,
    val type: String? = null,
    val src: IwaraQualitySrc? = null
)

data class IwaraQualitySrc(
    val view: String? = null,
    val download: String? = null
)

/* ---------- 登录 ---------- */

data class IwaraLoginResponse(val token: String? = null)

data class IwaraTokenResponse(val accessToken: String? = null)
