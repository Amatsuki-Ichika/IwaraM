package io.github.amatsukiichika.iwaram.data

import okhttp3.Interceptor
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Iwara 浏览客户端（浏览/登录/互动模块专用），与旧 [IwaraApi]（本地元数据补全）相互独立：
 * - 基址 2026 年起为 apiq.iwara.tv，api.iwara.tv 作回退；连接失败自动切换并重试一次；
 * - 公共请求头（Referer/Origin/X-Site/浏览器 UA）与 Bearer 鉴权由拦截器注入；
 * - 401 时由 Authenticator 同步刷新 access token 并重试一次。
 */
object IwaraClient {

    const val SITE_URL = "https://www.iwara.tv"
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * API 主域 apiq.iwara.tv，备用域 api.iwara.tv
     * （后者部分路由异常：like 返 200 不入库、search 单数 type 500）。
     */
    private const val BASE_PRIMARY = "https://apiq.iwara.tv/"
    private const val BASE_SECONDARY = "https://api.iwara.tv/"

    /**
     * 订阅流（`GET /videos?subscribed=true`）固定使用的基址 = **api.iwara.tv**。
     *
     * 不直接用 [activeBase]：`subscribed` 在 apiq.iwara.tv 上被**静默忽略**
     * （不过滤、不报错，返回全站最新）→「订阅」分段会退化成"所有最新视频"。
     * 单测 `IwaraSubscriptionContractTest` 盯着它，别再被粘性切换带回去。
     */
    internal val subscriptionBase: String get() = BASE_SECONDARY

    /** 粘性切到备用域超过该时长后，下一次调用自动尝试切回主域（瞬时 DNS 故障不该让整场会话滞留备用域）。 */
    private const val PRIMARY_RETRY_MS = 10 * 60 * 1000L

    @Volatile
    private var usePrimary = true

    @Volatile
    private var switchedToSecondaryAt = 0L

    val activeBase: String get() = if (usePrimary) BASE_PRIMARY else BASE_SECONDARY

    /* ---------- 图片 / 封面 URL 模板 ---------- */

    fun thumbnailUrl(fileId: String?): String? =
        fileId?.takeIf { it.isNotBlank() }
            ?.let { "https://files.iwara.tv/image/thumbnail/$it/thumbnail-00.jpg" }

    /**
     * 三个 image/avatar URL 构造函数统一用提前返回 + 局部不可空变量的写法，
     * 避免 `ref!!.id` 这类只看签名无法判断非空性的形态。
     */
    fun avatarUrl(ref: IwaraFileRef?): String? {
        val id = ref?.id?.takeIf { it.isNotBlank() } ?: return null
        val name = ref.name?.takeIf { it.isNotBlank() } ?: return null
        return "https://i.iwara.tv/image/avatar/$id/$name"
    }

    fun imageOriginalUrl(ref: IwaraFileRef?): String? {
        val id = ref?.id?.takeIf { it.isNotBlank() } ?: return null
        val name = ref.name?.takeIf { it.isNotBlank() } ?: return null
        return "https://i.iwara.tv/image/original/$id/$name"
    }

    fun imageThumbnailUrl(ref: IwaraFileRef?): String? {
        val id = ref?.id?.takeIf { it.isNotBlank() } ?: return null
        val name = ref.name?.takeIf { it.isNotBlank() } ?: return null
        return "https://i.iwara.tv/image/thumbnail/$id/$name"
    }

    /* ---------- OkHttp ---------- */

    /** 公共头：浏览器 UA + 站点来源；鉴权头在请求时注入当前 access token。 */
    private fun baseHeaders(): Interceptor = Interceptor { chain ->
        val req: Request = chain.request().newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$SITE_URL/")
            .header("Origin", SITE_URL)
            // 上游均发纯主机名（不带 scheme），带 https:// 前缀可能被服务端忽略
            .header("X-Site", "www.iwara.tv")
            .header("Accept", "application/json")
            .build()
        chain.proceed(req)
    }

    private fun authHeaders(): Interceptor = Interceptor { chain ->
        val token = IwaraSession.accessTokenFlow()
        val req = if (token.isNullOrBlank()) chain.request() else chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        chain.proceed(req)
    }

    /**
     * 401 时同步刷新 access token 后重试一次；刷新失败则放行原 401。
     *
     * 把「本次请求用的 token」透传给 [IwaraSession.refreshBlocking]、与其锁合并后，
     * 并发 401 只会触发一次真正刷新；若刷新拿回的仍是同一个 token
     * （未登录 / 刷新失败 / token 被撤销），重试只会再撞一次 401，故直接放行。
     */
    private fun refreshOn401(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val resp = chain.proceed(request)
        if (resp.code != HttpURLConnection.HTTP_UNAUTHORIZED) return@Interceptor resp
        // 请求没带鉴权头（未登录）时的 401 属于权限问题，刷新没有意义。
        val stale = request.header("Authorization")?.removePrefix("Bearer ")?.trim()
        if (stale.isNullOrBlank()) return@Interceptor resp
        val fresh = IwaraSession.refreshBlocking(stale) ?: return@Interceptor resp
        if (fresh == stale) return@Interceptor resp
        resp.close()
        val retry = request.newBuilder()
            .header("Authorization", "Bearer $fresh")
            .build()
        chain.proceed(retry)
    }

    /**
     * 全 app 共享的连接池与调度器 —— 各客户端若各自养一套（连接池 / 线程池），
     * 空闲连接与线程会成倍起跳。各客户端的差异只在拦截器与超时。
     */
    internal val sharedConnectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)
    internal val sharedDispatcher = Dispatcher()

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // JSON API 的整次调用上限（含重定向/读体）：防一次挂起的调用无限占住调度线程。
            // 流媒体（mediaHttp）与文件下载（fileHttp）绝不能加 —— callTimeout 覆盖整个读体过程。
            .callTimeout(30, TimeUnit.SECONDS)
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .addInterceptor(baseHeaders())
            .addInterceptor(authHeaders())
            .addInterceptor(refreshOn401())
            .build()
    }

    /**
     * 文件服务器（files/CDN）专用客户端：带公共头但不带鉴权头——
     * 播放/下载地址本身已带签名，多余的 Bearer 头可能被 CDN 拒绝。
     */
    val fileHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .addInterceptor(baseHeaders())
            .build()
    }

    /**
     * 播放器媒体请求专用客户端：与 [fileHttp] 同款浏览器头，但对 iwara 域名下带 expires
     * 参数的地址额外动态附加 X-Version 签名（算法与 [IwaraSign] 一致，防站点收紧校验）。
     * Accept-Encoding 固定 identity，避免 OkHttp 透明 gzip 让 Content-Length 与 Range 失配。
     */
    val mediaHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .connectionPool(sharedConnectionPool)
            .dispatcher(sharedDispatcher)
            .addInterceptor { chain ->
                val req = chain.request()
                val signed = if (req.url.host.endsWith("iwara.tv") &&
                    req.url.queryParameter("expires") != null
                ) {
                    req.newBuilder()
                        .header("X-Version", IwaraSign.xVersion(req.url.toString()))
                        .build()
                } else req
                chain.proceed(signed)
            }
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$SITE_URL/")
                    .header("Origin", SITE_URL)
                    .header("Accept-Encoding", "identity")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    /* ---------- Retrofit 服务 ---------- */

    private val services = java.util.concurrent.ConcurrentHashMap<String, IwaraBrowseService>()

    private fun serviceFor(base: String): IwaraBrowseService = services.getOrPut(base) {
        Retrofit.Builder()
            .baseUrl(base)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IwaraBrowseService::class.java)
    }

    val api: IwaraBrowseService get() = serviceFor(activeBase)

    /**
     * 统一调用入口：连接类失败（DNS/连接拒绝）时切换备用域名重试一次。
     * 网络调用一律走这里，不要直接持有 service。
     */
    suspend fun <T> call(block: suspend (IwaraBrowseService) -> T): T {
        // 备用域滞留超时回切主域；若主域仍不可达，本次调用的 catch 会再次切走。
        if (!usePrimary && System.currentTimeMillis() - switchedToSecondaryAt > PRIMARY_RETRY_MS) {
            synchronized(this) { usePrimary = true }
        }
        var retried = false
        while (true) {
            try {
                return block(api)
            } catch (e: UnknownHostException) {
                if (retried || !switchBase()) throw e
                retried = true
            } catch (e: ConnectException) {
                if (retried || !switchBase()) throw e
                retried = true
            }
        }
    }

    /**
     * 写操作（点赞/关注等）专用入口：固定走主域 apiq。
     * 备用域 api 的 like 路由返 200 却不入库（假成功），变更类请求绝不能被粘性切换带到备用域。
     */
    suspend fun <T> callPrimary(block: suspend (IwaraBrowseService) -> T): T =
        block(serviceFor(BASE_PRIMARY))

    /**
     * 订阅流专用调用：**必须始终打在 [subscriptionBase]（api.iwara.tv）**，不跟随 sticky 的主/备切换。
     *
     * `apiq.iwara.tv` 会**静默忽略** `?subscribed=true` 并返回**全站最新视频**（不报错），
     * 故订阅流钉死在 api.iwara.tv。网络类失败（DNS/连接拒绝）也**原地重试同一个 base**
     * （吃瞬时抖动），仍失败就向上抛给订阅页显示"加载失败 · 重试" —— 宁可明着报错，
     * 也不静默把请求落到 apiq 拿到错的"全站最新"内容。
     */
    suspend fun <T> callSubscription(block: suspend (IwaraBrowseService) -> T): T =
        try {
            block(serviceFor(subscriptionBase))
        } catch (e: UnknownHostException) {
            block(serviceFor(subscriptionBase))
        } catch (e: ConnectException) {
            block(serviceFor(subscriptionBase))
        }

    @Synchronized
    private fun switchBase(): Boolean {
        usePrimary = !usePrimary
        if (!usePrimary) switchedToSecondaryAt = System.currentTimeMillis()
        return true
    }
}

/* ---------- 端点定义 ---------- */
/* 变更类端点统一用 Response<ResponseBody> 接收：点赞/取消等空体成功响应是 204 No Content，
 * Retrofit 对 204/205 强制返回 null body，声明非空 ResponseBody 会在 suspend 桥接层直接抛
 * KotlinNullPointerException（"Response from … was null"，服务端其实已执行成功）；
 * 改拿 Response 后由调用方按 isSuccessful（含 204）判定。 */

interface IwaraBrowseService {

    /* 视频流 */

    @GET("videos")
    suspend fun videos(
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("sort") sort: String?,
        @Query("rating") rating: String?,
        @Query("subscribed") subscribed: Boolean?,
        @Query("user") user: String?,
        @Query("tags") tags: String? = null
    ): IwaraPage<IwaraVideoItem>

    @GET("video/{id}")
    suspend fun video(@Path("id") id: String): IwaraVideoItem

    @GET("video/{id}/related")
    suspend fun relatedVideos(
        @Path("id") id: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): IwaraPage<IwaraVideoItem>

    @POST("video/{id}/view")
    suspend fun reportView(@Path("id") id: String, @Body body: okhttp3.RequestBody): retrofit2.Response<ResponseBody>

    /* 图片与帖子 */

    @GET("images")
    suspend fun images(
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("sort") sort: String?,
        @Query("rating") rating: String?,
        @Query("subscribed") subscribed: Boolean?,
        /** 作者 UUID（作者页的「图片」分段按作者过滤）。 */
        @Query("user") user: String? = null
    ): IwaraPage<IwaraImageItem>

    @GET("image/{id}")
    suspend fun image(@Path("id") id: String): IwaraImageItem

    @GET("posts")
    suspend fun posts(
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("sort") sort: String?,
        @Query("rating") rating: String?
    ): IwaraPage<IwaraPostItem>

    @GET("post/{id}")
    suspend fun post(@Path("id") id: String): IwaraPostItem

    /* 搜索 */

    @GET("search")
    suspend fun searchVideos(
        @Query("query") query: String,
        @Query("type") type: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("sort") sort: String?,
        @Query("rating") rating: String?
    ): IwaraPage<IwaraVideoItem>

    /** tag 联想（GET /tags?filter=前缀）：id 即 slug，可直接喂给 videos 的 tags 参数。 */
    @GET("tags")
    suspend fun tags(
        @Query("filter") filter: String,
        @Query("limit") limit: Int
    ): IwaraPage<IwaraTag>

    @GET("search")
    suspend fun searchImages(
        @Query("query") query: String,
        @Query("type") type: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("sort") sort: String?,
        @Query("rating") rating: String?
    ): IwaraPage<IwaraImageItem>

    /* 评论 */

    @GET("{type}/{id}/comments")
    suspend fun comments(
        @Path("type") type: String,
        @Path("id") id: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("parent") parent: String?
    ): IwaraPage<IwaraComment>

    @POST("{type}/{id}/comments")
    suspend fun postComment(
        @Path("type") type: String,
        @Path("id") id: String,
        @Body body: IwaraCommentBody
    ): retrofit2.Response<ResponseBody>

    @PUT("comment/{id}")
    suspend fun updateComment(@Path("id") id: String, @Body body: IwaraCommentBody): retrofit2.Response<ResponseBody>

    @DELETE("comment/{id}")
    suspend fun deleteComment(@Path("id") id: String): retrofit2.Response<ResponseBody>

    /* 点赞 / 收藏（同端点）；成功码 POST=201、DELETE=204 */

    @POST("{type}/{id}/like")
    suspend fun like(@Path("type") type: String, @Path("id") id: String): retrofit2.Response<ResponseBody>

    @DELETE("{type}/{id}/like")
    suspend fun unlike(@Path("type") type: String, @Path("id") id: String): retrofit2.Response<ResponseBody>

    @GET("favorites/videos")
    suspend fun favoriteVideos(
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): IwaraPage<IwaraFavVideoEdge>

    @GET("favorites/images")
    suspend fun favoriteImages(
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): IwaraPage<IwaraFavImageEdge>

    /* 账号与关系 */

    @GET("user")
    suspend fun me(): IwaraProfileData

    @GET("profile/{username}")
    suspend fun profile(@Path("username") username: String): IwaraProfileData

    @GET("user/{id}/following")
    suspend fun following(
        @Path("id") id: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): IwaraPage<IwaraFollowEdge>

    /** 粉丝列表（作者页「粉丝」分段；GET /user/{id}/followers）。 */
    @GET("user/{id}/followers")
    suspend fun followers(
        @Path("id") id: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): IwaraPage<IwaraFollowEdge>

    @POST("user/{id}/followers")
    suspend fun follow(@Path("id") id: String): retrofit2.Response<ResponseBody>

    @DELETE("user/{id}/followers")
    suspend fun unfollow(@Path("id") id: String): retrofit2.Response<ResponseBody>

    @GET("user/{id}/content/{type}")
    suspend fun userContent(
        @Path("id") id: String,
        @Path("type") type: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("sort") sort: String?
    ): IwaraPage<IwaraVideoItem>

    /**
     * 作者页「帖子」分段首选端点（与 content/video 同族）。服务端不支持 content/post 时抛错，
     * 由调用方回退 [postsByUser]。
     */
    @GET("user/{id}/content/{type}")
    suspend fun userContentPosts(
        @Path("id") id: String,
        @Path("type") type: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("sort") sort: String?
    ): IwaraPage<IwaraPostItem>

    /** 作者页「帖子」分段回退端点：GET /posts?user=<作者 UUID>。 */
    @GET("posts")
    suspend fun postsByUser(
        @Query("user") user: String,
        @Query("page") page: Int,
        @Query("limit") limit: Int,
        @Query("sort") sort: String?,
        @Query("rating") rating: String?
    ): IwaraPage<IwaraPostItem>

    /* 播放列表 */

    @GET("playlists")
    suspend fun playlists(
        @Query("user") user: String?,
        @Query("page") page: Int,
        @Query("limit") limit: Int
    ): IwaraPage<IwaraPlaylist>

    @POST("playlists")
    suspend fun createPlaylist(@Body body: IwaraPlaylistBody): IwaraPlaylist

    @GET("playlist/{id}")
    suspend fun playlist(@Path("id") id: String): IwaraPlaylist

    @DELETE("playlist/{id}")
    suspend fun deletePlaylist(@Path("id") id: String): retrofit2.Response<ResponseBody>

    @POST("playlist/{playlistId}/{videoId}")
    suspend fun addToPlaylist(
        @Path("playlistId") playlistId: String,
        @Path("videoId") videoId: String
    ): retrofit2.Response<ResponseBody>

    @DELETE("playlist/{playlistId}/{videoId}")
    suspend fun removeFromPlaylist(
        @Path("playlistId") playlistId: String,
        @Path("videoId") videoId: String
    ): retrofit2.Response<ResponseBody>
}

/** 变更类端点收到非 2xx 时抛出；message 尽量带出服务端 JSON 的 message 字段，便于设备端定位。 */
class IwaraServerException(code: Int, serverMessage: String?) :
    RuntimeException("服务器错误（HTTP $code）" + (serverMessage?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""))

/** 从错误响应体中提取服务端 message 的匹配式（形如 {"message":"..."}）。提为常量复用，避免每次新建。 */
private val SERVER_MESSAGE_REGEX = Regex("\"message\"\\s*:\\s*\"([^\"]+)\"")

/** 从错误响应体中提取服务端 message（形如 {"message":"..."}；读不出就返回 null）。 */
private fun serverMessageOf(body: String?): String? = body
    ?.let { SERVER_MESSAGE_REGEX.find(it)?.groupValues?.get(1) }

/** 校验变更类响应（Response<ResponseBody>）：非 2xx 抛 [IwaraServerException]，204 空体视为成功。 */
fun retrofit2.Response<ResponseBody>.ensureIwaraSuccess() {
    if (isSuccessful) return
    val msg = runCatching { errorBody()?.string() }.getOrNull().let(::serverMessageOf)
    throw IwaraServerException(code(), msg)
}

/** 把底层 IOException 转成可读的中文提示。 */
fun describeIwaraError(t: Throwable): String = when (t) {
    // 协程取消（页面关闭终止加载）不是错误 —— 调用方应先判此分支直接静默返回，
    // 这里给出中性文案兜底，避免"取消"被当成"网络错误"展示。
    is kotlinx.coroutines.CancellationException -> "已取消"
    is IwaraSession.IwaraApiException -> t.message ?: "请求失败"
    is UnknownHostException, is ConnectException -> "无法连接 Iwara 服务器，请检查网络或代理"
    is IOException -> "网络异常：${t.message ?: t.javaClass.simpleName}"
    is retrofit2.HttpException -> {
        val msg = runCatching { t.response()?.errorBody()?.string() }.getOrNull().let(::serverMessageOf)
        "服务器错误（HTTP ${t.code()}）" + (msg?.let { "：$it" } ?: "")
    }
    else -> t.message ?: t.javaClass.simpleName
}

/* ---------- Debug 模式开关：错误文案两轨制 ---------- */

/**
 * HTTP 状态码 → 人话短句（唯一映射点，单测 `IwaraErrorTextTest` 盯着）。
 * debug 开关关闭时用它替代「服务器错误（HTTP xxx）：英文 message」这类技术串。
 */
fun friendlyHttpError(code: Int): String = when (code) {
    401, 403 -> "没有权限访问该内容（可能需要登录，或作者已设为私密）"
    404, 410 -> "内容不存在或已被删除"
    429 -> "请求太频繁，请稍后再试"
    in 500..599 -> "Iwara 服务器开小差了，请稍后再试"
    else -> "请求失败，请稍后重试"
}

/**
 * 把 [describeIwaraError] 产出的技术串转成人话（**保守规则**：认不出的原样透传，
 * 已是人话的串（如「邮箱或密码错误」「会话已过期，请重新登录」）不受影响）。
 *
 * 设计取舍：生产侧（[describeIwaraError] 与各回调链/状态位）保持原样存**技术串**
 * （debug 开 = 与现状完全一致），只在渲染出口按开关转轨 —— 状态结构与回调签名零改动，
 * 回滚面最小。本函数是纯函数，映射规则全部有单测。
 */
fun friendlyIwaraError(detail: String): String = when {
    detail.startsWith("服务器错误（HTTP ") ->
        Regex("HTTP (\\d{3})").find(detail)?.groupValues?.get(1)?.toIntOrNull()
            ?.let(::friendlyHttpError) ?: detail
    detail.startsWith("网络异常：") -> "网络连接异常，请检查网络后重试"
    detail.startsWith("无法连接 Iwara 服务器") -> detail
    detail.startsWith("登录失败") -> "登录失败，请稍后再试"
    detail.startsWith("获取用户信息失败（HTTP ") -> "获取账号信息失败，请重新登录"
    // describeIwaraError 的 else 分支可能漏出纯英文技术串（异常 message / 类名）；
    // 含 CJK 的视为已人话透传，纯 ASCII 的按技术串隐藏。
    detail.any { it.code >= 0x2E80 } -> detail
    else -> "操作失败，请稍后重试"
}
