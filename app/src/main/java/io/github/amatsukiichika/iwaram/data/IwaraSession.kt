package io.github.amatsukiichika.iwaram.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.DiagLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Iwara 账号会话：两段式 token。
 * - refresh token：登录 POST /user/login {email,password} 返回的 JWT，约 3~4 周有效，持久化保存；
 * - access token：POST /user/token（Bearer refresh）返回，约 1 小时有效，内存缓存 + JWT exp 自检自动续期。
 *
 * 登录无需验证码（多开源客户端一致做法）。会话数据独立存放在 prefs 的 iwara_* 键下，
 * 是 access token 的唯一来源：旧「手动粘贴 access_token」入口已移除，其残留键在 [restore]
 * 时清理。token 落盘前经 [TokenCrypto]（AndroidKeyStore AES-256-GCM）加密，旧版明文在恢复
 * 时平滑迁移成密文；加密不可用时显式降级为 `plain:` 明文并置降级标记（设置页提示）。
 *
 * 会话恢复 [restoreAsync] 在 IO 线程完成，主线程不再触发 Keystore；鉴权注入路径用
 * [awaitAccessToken] 有界等待就绪，保证进程起点发起的元数据请求仍尽量带上 token。
 */
object IwaraSession {

    /** 会话失效（refresh token 过期/被撤销）时由 UI 层设置，用于回到未登录态。 */
    interface Listener {
        fun onSessionExpired()
    }

    @Volatile var listener: Listener? = null

    /**
     * 「会话已失效、待通知 UI」的标记。
     *
     * [clear] 在持有 [refreshMutex] 时被调用，若在锁内直接回调 `listener?.onSessionExpired()`，
     * 一旦 listener 实现里再取需要该锁的资源，就会构成不可重入 Mutex 死锁。
     * 因此锁内只置标记，出锁后再回调（见 [notifyExpiredIfPending]）。
     */
    private val pendingExpiredNotify = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    var refreshToken: String? = null
        private set

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var accessExpireAtMs: Long = 0L

    private val refreshMutex = Mutex()
    private lateinit var prefs: SharedPreferences

    /** 恢复流程只在进程起点启动一次；测试用同步 [restore] 不经过这个标记。 */
    private val restoreStarted = AtomicBoolean(false)

    /** 恢复就绪信号：完成（成功或失败）后鉴权注入路径不再等待。 */
    @Volatile
    private var restoreReady = CompletableDeferred<Unit>()

    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * token 存储是否已降级为明文（AndroidKeyStore 不可用）。
     * 设置页据此提示用户；状态同时持久化在专用布尔键里，进程重启后重新计算。
     */
    private val _tokenStorageDegraded = MutableStateFlow(false)
    val tokenStorageDegraded: StateFlow<Boolean> = _tokenStorageDegraded.asStateFlow()

    /** 降级诊断日志每个进程只记一条（日志只含原因，不含 token 内容）。 */
    private val degradedLogged = AtomicBoolean(false)

    /**
     * 仅用于登录/刷新的裸客户端：不带鉴权头、不重试，避免递归。
     * 共享连接池与调度器；callTimeout(20s) 给整次刷新封顶 ——
     * 它在 401 拦截器里同步执行，挂死会拖住整条调用链。
     */
    private val bareClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .connectionPool(IwaraClient.sharedConnectionPool)
            .dispatcher(IwaraClient.sharedDispatcher)
            .build()
    }

    /**
     * 进程起点恢复会话（[io.github.amatsukiichika.iwaram.IwaraApp.onCreate] 调用）。
     *
     * 读 prefs、旧值迁移与 Keystore 解密全部在 IO 线程执行，主线程不再做加密操作；
     * 完成后置位 [restoreReady]，[awaitAccessToken] 据此做有界等待。幂等：只启动一次。
     */
    fun restoreAsync(context: Context) {
        // 句柄创建本身不读 token 内容；放在调用线程，让用户名/头像等非加密读取立即可用。
        prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (!restoreStarted.compareAndSet(false, true)) return
        val ready = restoreReady
        restoreScope.launch {
            try {
                restoreStoredSession()
            } catch (t: Throwable) {
                DiagLog.log("Session", "会话恢复失败: ${t.javaClass.simpleName}")
            } finally {
                ready.complete(Unit)
            }
        }
    }

    /**
     * 同步恢复入口：测试与"恢复完成后再断言"的迁移场景使用。
     * 进程启动路径必须走 [restoreAsync]，否则 Keystore 解密会回到主线程。
     */
    internal fun restore(context: Context) {
        prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        restoreStoredSession()
        restoreReady.complete(Unit)
    }

    /** 真正的恢复逻辑：读落盘值、迁移旧值、更新内存态。必须在非主线程执行。 */
    private fun restoreStoredSession() {
        if (!::prefs.isInitialized) return
        val storedRefresh = prefs.getString(KEY_REFRESH, null)?.takeIf { it.isNotBlank() }
        // 解密失败（Keystore 被系统重置）返回 null ⇒ 按未登录处理，绝不回退明文。
        refreshToken = storedRefresh?.let { TokenCrypto.decrypt(it) }
        accessToken = readStoredToken(KEY_ACCESS)
        // 旧版明文 / 上次降级写入的 plain: 值一次性迁移成密文（不可加密时保持可读并置降级）。
        migrateStoredToken(KEY_REFRESH)
        migrateStoredToken(KEY_ACCESS)
        accessExpireAtMs = jwtExp(accessToken ?: "") * 1000L
        removeLegacyManualToken()
        if (refreshToken == null && storedRefresh != null) {
            // 有落盘会话却解不开：让已在运行的 UI 立刻回到未登录（监听器可能尚未注册）。
            listener?.onSessionExpired()
        }
        refreshDegradedFlag()
    }

    /**
     * 读落盘 token：兼容旧版裸明文与 `v1:` 密文（[TokenCrypto.decrypt]）。
     * 密文解密失败（KeyStore 被系统重置）返回 null ⇒ 按未登录处理，用户重新登录即可。
     */
    private fun readStoredToken(key: String): String? {
        val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
        return TokenCrypto.decrypt(raw)
    }

    /** 写落盘 token：优先加密；不可加密时写 `plain:` 显式降级值并置降级状态。 */
    private fun writeStoredToken(key: String, value: String) {
        if (!::prefs.isInitialized) return
        val stored = TokenCrypto.sealForStorage(value)
        prefs.edit().putString(key, stored.value).apply()
        refreshDegradedFlag()
    }

    /**
     * 一次性迁移：旧版裸明文 / 上次降级的 `plain:` → `v1:` 密文。
     * 加密仍不可用时写成显式 `plain:`（值不丢、可读），降级状态由 [refreshDegradedFlag] 汇总。
     */
    private fun migrateStoredToken(key: String) {
        if (!::prefs.isInitialized) return
        val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return
        if (raw.startsWith(TokenCrypto.PREFIX)) return
        val plain = TokenCrypto.decrypt(raw) ?: return
        val stored = TokenCrypto.sealForStorage(plain)
        prefs.edit().putString(key, stored.value).apply()
    }

    /** 清掉旧「手动粘贴 access_token」的残留键：入口已移除，IwaraSession 是唯一 token 来源。 */
    private fun removeLegacyManualToken() {
        if (prefs.contains(LEGACY_MANUAL_TOKEN_KEY)) {
            prefs.edit().remove(LEGACY_MANUAL_TOKEN_KEY).apply()
            DiagLog.log("Session", "已清除旧版手动 access token 残留")
        }
    }

    /**
     * 汇总降级状态：任一 token 以 `plain:` 落盘即降级。
     * 变化时同步持久标记；每个进程首次降级记一条诊断日志（不含 token 内容）。
     */
    private fun refreshDegradedFlag() {
        if (!::prefs.isInitialized) return
        val degraded = isStoredAsPlaintext(KEY_REFRESH) || isStoredAsPlaintext(KEY_ACCESS)
        _tokenStorageDegraded.value = degraded
        prefs.edit().putBoolean(KEY_TOKEN_DEGRADED, degraded).apply()
        if (degraded && degradedLogged.compareAndSet(false, true)) {
            DiagLog.log("Session", "token 已降级为明文存储（AndroidKeyStore 不可用）")
        }
    }

    private fun isStoredAsPlaintext(key: String): Boolean =
        prefs.getString(key, null)?.startsWith(TokenCrypto.PLAIN_PREFIX) == true

    val isLoggedIn: Boolean get() = !refreshToken.isNullOrBlank() || pendingRestoreHasStoredSession()

    /** 会话恢复是否仍在进行（鉴权注入路径据此决定要不要等待）。 */
    private val isRestorePending: Boolean
        get() = restoreStarted.get() && !restoreReady.isCompleted

    /**
     * 恢复尚未完成时，只要落盘已有未解密的 refresh token 就先按"已登录（检查中）"返回，
     * 避免浏览页在启动瞬间把已登录用户显示成未登录；恢复完成后只看解密结果。
     */
    private fun pendingRestoreHasStoredSession(): Boolean =
        isRestorePending && ::prefs.isInitialized &&
            !prefs.getString(KEY_REFRESH, null).isNullOrBlank()

    /** 当前缓存的 access token（可能为 null 或已过期；同步读取，不做续期）。 */
    fun accessTokenFlow(): String? = accessToken

    /**
     * 鉴权注入用：有界等待会话恢复完成后返回 access token。
     *
     * OkHttp 拦截器运行在网络线程，阻塞等待是可接受的：[timeoutMs] 封顶保证请求不会挂死，
     * 超时按无 token 继续（公开模式）。主线程不得调用；同步读取用 [accessTokenFlow]。
     */
    internal fun awaitAccessToken(timeoutMs: Long = RESTORE_READY_TIMEOUT_MS): String? {
        if (isRestorePending) {
            val ready = restoreReady
            kotlinx.coroutines.runBlocking { withTimeoutOrNull(timeoutMs) { ready.await() } }
        }
        return accessToken
    }

    /** 三个 getter 统一加 isInitialized 守卫（与 saveUserInfo 一致，防御进程极早期访问）。 */
    fun savedUsername(): String? {
        if (!::prefs.isInitialized) return null
        return prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() }
    }

    fun savedUserId(): String? {
        if (!::prefs.isInitialized) return null
        return prefs.getString(KEY_USER_ID, null)?.takeIf { it.isNotBlank() }
    }

    /** 登录后用远端 /user 的最新用户名与 id 覆盖本地缓存；avatarUrl 非空时同步持久化（离线也显示）。 */
    fun saveUserInfo(username: String?, userId: String?, avatarUrl: String? = null) {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putString(KEY_USERNAME, username ?: "")
            .putString(KEY_USER_ID, userId ?: "")
            .apply()
        if (avatarUrl != null) prefs.edit().putString(KEY_AVATAR, avatarUrl).apply()
    }

    fun savedAvatarUrl(): String? {
        if (!::prefs.isInitialized) return null
        return prefs.getString(KEY_AVATAR, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * 账号密码登录。成功后 refresh token 持久化，并立即换取 access token。
     * 全程在 IO 线程执行（OkHttp 同步调用，放主线程会抛 NetworkOnMainThreadException）。
     * @return 登录成功后的用户名（用于界面提示）
     */
    suspend fun login(email: String, password: String): String {
        return withContext(Dispatchers.IO) {
            val refresh = postJson(
                url = IwaraClient.activeBase + "user/login",
                body = JSONObject().put("email", email.trim()).put("password", password)
            ) { it.optString("token").takeIf { t -> t.isNotBlank() } }
                ?: throw IwaraApiException(
                    tr("登录失败：服务器未返回令牌", "Login failed: the server did not return a token")
                )

            refreshToken = refresh
            writeStoredToken(KEY_REFRESH, refresh)

            // 立即换取 access token 并拉取用户信息；任一步失败都视为登录失败（回滚）。
            try {
                obtainAccessToken(force = true)
                val me = fetchMe()
                // 不把邮箱兜底存成用户名（会导致 profile(邮箱) 404 与界面显示邮箱）。
                // 解析不到就留空，界面显示"已登录"，随后 refreshSession 会用 /user 补全。
                saveUserInfo(me.displayName, me.userId, me.avatarUrl)
                me.displayName ?: me.userId ?: tr("已登录", "Signed in")
            } catch (t: Throwable) {
                clear()
                throw t
            }
        }
    }

    /**
     * 确保拿到未过期的 access token；即将过期（<5 分钟）或 force 时刷新。
     *
     * [base] 指定用哪台服务器换取凭证。登录与续期默认打在 [IwaraClient.activeBase]（apiq），
     * 而订阅流必须打 `api.iwara.tv`（见 callSubscription），否则会出现"凭证由 apiq 签发、
     * 却拿去打 api.iwara.tv"这种跨服务端的不确定项。订阅流显式传 `subscriptionBase` 后，
     * 凭证来源与请求目标被锁成同一个。默认 `null` 保持原行为，其它调用点不受影响。
     *
     * @return 当前可用的 access token（未登录返回 null）
     */
    suspend fun ensureAccessToken(force: Boolean = false, base: String? = null): String? {
        // 恢复可能仍在 IO 线程进行：先有界等就绪，避免启动瞬间的请求因 token 尚未载入而失效。
        awaitRestoreSettled()
        if (!isLoggedIn) return null
        if (!force && accessToken != null && System.currentTimeMillis() < accessExpireAtMs - FIVE_MINUTES_MS) {
            return accessToken
        }
        val token = try {
            refreshMutex.withLock {
                // 双重检查：并发调用时第一个协程已刷新完成。
                if (!force && accessToken != null && System.currentTimeMillis() < accessExpireAtMs - FIVE_MINUTES_MS) {
                    return@withLock accessToken
                }
                obtainAccessToken(force = true, base = base)
            }
        } finally {
            // 出锁即通知，覆盖成功与失败两条路径：若写成 `.also { notifyExpiredIfPending() }`，
            // `.also` 只在正常返回时执行，而"refresh token 被服务器拒绝"恰恰是抛异常那条路
            // （obtainAccessToken 里 clear() + 置标记后 rethrow）⇒ 失败路径不会通知，UI 收不到
            // "会话已失效"。此处已在 refreshMutex 之外，不存在持锁回调 UI 的死锁风险。
            notifyExpiredIfPending()
        }
        return token
    }

    /**
     * 401 时的同步刷新（供 OkHttp 拦截器在网络线程调用）。
     *
     * 与 [ensureAccessToken] 共用 [refreshMutex]：一批并发请求同时收到 401 时，
     * 只有第一个协程真正换 token，其余在锁内拿到结果直接复用，避免刷新风暴
     * （后到者可能把先到者刚写入的新 token 覆盖或作废）。
     *
     * 线程安全：锁内请求走 [bareClient]（独立 OkHttp 线程池），与调用方媒体/接口客户端
     * 的分发线程池互不共享，不存在线程饥饿死锁。
     *
     * @param staleToken 刚刚收到 401 的那个请求所用的 token（拦截器传入）。
     *   锁内若发现当前 token 已经与它不同（说明别的协程刚刷新过）则直接复用，
     *   避免同一个失效 token 上做重复刷新；无法解析时传 null。
     * @return 可用的新 access token；未登录或刷新失败（refresh token 已失效）返回 null，
     *   由调用方放行原始 401。
     */
    fun refreshBlocking(staleToken: String? = null): String? = runCatching {
        kotlinx.coroutines.runBlocking {
            awaitRestoreSettled()
            refreshMutex.withLock {
                val cached = accessToken
                val fresh = cached != null && cached != staleToken &&
                    System.currentTimeMillis() < accessExpireAtMs - FIVE_MINUTES_MS
                if (fresh) cached else obtainAccessToken(force = true)
            }
        }
    }.onFailure {
        // 静默吞掉一切异常会掩盖问题（含瞬时 500/超时），故此处记一条诊断日志。
        // 失败返回 null 由拦截器放行原始 401；会话是否清除由 obtainAccessToken 的分类决定。
        DiagLog.log("Session", "token 刷新失败: ${it.message ?: it.javaClass.simpleName}")
    }.getOrNull().also {
        // runBlocking + withLock 已经退出，这里才安全地回调 UI。
        notifyExpiredIfPending()
    }

    fun logout() {
        clear()
        // 登出路径没有持锁，直接通知。
        listener?.onSessionExpired()
    }

    /**
     * 清空会话状态（token / 元数据 / prefs）。
     * 本函数会在 [refreshMutex] 锁内被调用，因此不在这里通知 UI，
     * 通知由 [notifyExpiredIfPending] 在出锁后补发。
     */
    private fun clear() {
        refreshToken = null
        accessToken = null
        accessExpireAtMs = 0L
        if (::prefs.isInitialized) {
            prefs.edit()
                .remove(KEY_REFRESH)
                .remove(KEY_ACCESS)
                .remove(KEY_USERNAME)
                .remove(KEY_USER_ID)
                .remove(KEY_AVATAR)
                .apply()
            refreshDegradedFlag()
        }
    }

    /**
     * 若本次流程里清过会话，就在出锁之后通知 UI（幂等：标记只会被消费一次）。
     * 调用点必须在所有 `refreshMutex.withLock { … }` 之外。
     */
    private fun notifyExpiredIfPending() {
        if (pendingExpiredNotify.compareAndSet(true, false)) listener?.onSessionExpired()
    }

    /**
     * 有界等待会话恢复就绪：恢复是 IO 上的短任务，超时按"无 token"继续。
     * 协程语境（含主线程协程）只挂起不阻塞线程；网络线程路径见 [awaitAccessToken]。
     */
    private suspend fun awaitRestoreSettled() {
        if (!isRestorePending) return
        val ready = restoreReady
        withTimeoutOrNull(RESTORE_READY_TIMEOUT_MS) { ready.await() }
    }

    /**
     * 用 refresh token 换一张新的 access token。
     *
     * @param base 换凭证打哪台服务器；null = [IwaraClient.activeBase]。订阅流传
     *   `subscriptionBase`，保证"凭证来源"与"请求目标"同域。
     */
    private suspend fun obtainAccessToken(force: Boolean, base: String? = null): String? {
        val refresh = refreshToken ?: return null
        val access = try {
            withContext(Dispatchers.IO) {
                postJson(
                    url = (base ?: IwaraClient.activeBase) + "user/token",
                    body = null,
                    bearer = refresh
                ) { it.optString("accessToken").takeIf { t -> t.isNotBlank() } }
            } ?: throw IwaraApiException(tr("会话已过期，请重新登录", "Your session has expired. Please sign in again"))
        } catch (e: IwaraApiException) {
            // 仅 refresh token 被服务器明确拒绝（401/403/invalidLogin）才清空会话；
            // 瞬时错误（500/429/解析失败等）保留会话并向上抛，下次请求还会重试刷新，
            // 避免服务端抖动导致登出（登录流程的回滚 clear 不走这里，语义不变）。
            // 这里在锁内，所以只清状态 + 置标记，通知交给出锁后的
            // notifyExpiredIfPending()（避免持锁回调导致不可重入死锁）。
            if (e.isAuthError) {
                clear()
                pendingExpiredNotify.set(true)
            }
            throw e
        }
        accessToken = access
        accessExpireAtMs = jwtExp(access) * 1000L
        // 顺手缓存，加速下次启动（可能已过期，仅作初始值）。IwaraSession 是唯一 token 来源：
        // 拦截器经 accessTokenFlow()/awaitAccessToken() 读取，不再同步到其它单例。
        writeStoredToken(KEY_ACCESS, access)
        return access
    }

    /**
     * GET /user 获取当前用户信息。
     *
     * 该端点与 `/profile/{username}` 同属嵌套响应 `{"user":{...}}`：按扁平字段读 `id`/`username`
     * 会得到 null，KEY_USER_ID 存不进去，`session.userId` 恒空，进而关注页报"登录信息缺失"、
     * 收藏/播放列表/点赞一并失效。因此优先读嵌套 user 对象，扁平形态回退兼容。
     */
    private fun fetchMe(): MeInfo {
        val token = accessToken ?: throw IwaraApiException(tr("尚未登录", "Not signed in"))
        val request = Request.Builder()
            .url(IwaraClient.activeBase + "user")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", IwaraClient.USER_AGENT)
            .header("Referer", IwaraClient.SITE_URL + "/")
            .header("Origin", IwaraClient.SITE_URL)
            .header("X-Site", "www.iwara.tv")
            .header("Accept", "application/json")
            .get()
            .build()
        bareClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IwaraApiException(
                tr(
                    "获取用户信息失败（HTTP ${resp.code}）",
                    "Failed to load account info (HTTP ${resp.code})"
                )
            )
            val root = JSONObject(text)
            val user = root.optJSONObject("user") ?: root
            val displayName = user.optString("name").takeIf { it.isNotBlank() }
                ?: user.optString("username").takeIf { it.isNotBlank() }
            val avatarRef = user.optJSONObject("avatar")?.let { ref ->
                val aId = ref.optString("id").takeIf { it.isNotBlank() }
                val aName = ref.optString("name").takeIf { it.isNotBlank() }
                if (aId != null && aName != null) IwaraFileRef(aId, aName) else null
            }
            return MeInfo(
                displayName = displayName,
                userId = user.optString("id").takeIf { it.isNotBlank() },
                avatarUrl = IwaraClient.avatarUrl(avatarRef)
            )
        }
    }

    /** /user 解析结果（登录时落库）。 */
    private data class MeInfo(
        val displayName: String?,
        val userId: String?,
        val avatarUrl: String?
    )

    private fun <T> postJson(
        url: String,
        body: JSONObject?,
        bearer: String? = null,
        parse: (JSONObject) -> T?
    ): T? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", IwaraClient.USER_AGENT)
            .header("Referer", IwaraClient.SITE_URL + "/")
            .header("Origin", IwaraClient.SITE_URL)
            .header("X-Site", "www.iwara.tv")
            .header("Accept", "application/json")
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        builder.post((body?.toString() ?: "").toRequestBody(mediaType))

        bareClient.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                // 携带分类结果：401/403/invalidLogin = refresh token 真失效；500/429 等 = 瞬时错误。
                throw IwaraApiException(
                    mapLoginError(resp.code, msg),
                    classifyTokenError(resp.code, msg)
                )
            }
            if (text.isBlank()) return null
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
            return parse(json)
        }
    }

    /**
     * refresh token 是否被服务器明确拒绝（HTTP 401/403 或 invalidLogin）。
     * 只有它为 true 时 [obtainAccessToken] 才允许 clear()；服务器 500/429/超时等瞬时错误
     * 不清会话，否则服务端抖一下就会丢失登录态（删掉 refresh token，必须重输密码）。
     * internal 保持可测。
     */
    internal fun classifyTokenError(code: Int, message: String?): Boolean =
        code == 401 || code == 403 || message?.contains("invalidLogin") == true

    internal fun mapLoginError(code: Int, message: String?): String = when {
        classifyTokenError(code, message) -> tr("邮箱或密码错误", "Incorrect email or password")
        code == 429 -> tr("请求过于频繁，请稍后再试", "Too many requests. Please try again later")
        !message.isNullOrBlank() -> tr("登录失败：$message", "Login failed: $message")
        else -> tr("登录失败（HTTP $code）", "Login failed (HTTP $code)")
    }

    /** 解析 JWT payload 的 exp（秒）；解析失败返回 0。 */
    private fun jwtExp(jwt: String): Long {
        if (jwt.isBlank()) return 0L
        return try {
            // JWT 形如 header.payload.signature，payload 是第二段。
            val payload = jwt.split('.').getOrNull(1) ?: return 0L
            val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            JSONObject(decoded).optLong("exp", 0L)
        } catch (_: Throwable) {
            0L // 纯格式解析（JWT payload Base64/JSON），吞异常是有意的：解析失败按"无过期信息"处理。
        }
    }

    /** 仅测试用：重置对象级恢复状态，避免 Robolectric 测试之间共享单例状态。 */
    internal fun resetRestoreStateForTest() {
        restoreStarted.set(false)
        restoreReady = CompletableDeferred()
        refreshToken = null
        accessToken = null
        accessExpireAtMs = 0L
        _tokenStorageDegraded.value = false
        degradedLogged.set(false)
    }

    class IwaraApiException(message: String, val isAuthError: Boolean = false) : Exception(message)

    private const val KEY_REFRESH = "iwara_refresh_token"
    private const val KEY_ACCESS = "iwara_access_token"
    private const val KEY_USERNAME = "iwara_username"
    private const val KEY_USER_ID = "iwara_user_id"
    private const val KEY_AVATAR = "iwara_avatar_url"

    /** 落盘降级状态（仅布尔标记，不含 token）。 */
    private const val KEY_TOKEN_DEGRADED = "iwara_token_storage_degraded"

    /** 旧「手动粘贴 access_token」的落盘键；功能入口已移除，恢复时清理残留。 */
    private const val LEGACY_MANUAL_TOKEN_KEY = "access_token"

    /** 鉴权注入等待会话恢复的上限：正常恢复远小于它，超时按公开模式继续。 */
    private const val RESTORE_READY_TIMEOUT_MS = 3_000L

    private const val FIVE_MINUTES_MS = 5 * 60 * 1000L
}
