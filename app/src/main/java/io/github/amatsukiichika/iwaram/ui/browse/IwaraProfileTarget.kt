package io.github.amatsukiichika.iwaram.ui.browse

/**
 * 作者页目标：把「用户名 / 用户 UUID」两种入口归一成一份参数。
 *
 * 同一个作者页会从很多地方打开：卡片作者名（handle）、播放页作者（可能只有 UUID）、
 * 本地库作者（可能两者都没有）。进页面前先归一，页面只认这三样：
 * - [handle]：@用户名，用于 GET /profile/{username}；
 * - [uid]：账号 UUID，`?user=` 筛选与 `/user/{id}/...` 路径的唯一可靠键；
 * - [contentId]：拉视频/图片/帖子时实际传的键（有 UUID 用 UUID，否则退回 handle）。
 *
 * [needsProfileLookup] / [unavailable] 是派生状态，不参与数据类的 equals：
 * 三个字段相同即同一目标。
 */
data class IwaraProfileTarget(
    /** @用户名（已 trim、非空才算），没有则为 null。 */
    val handle: String? = null,
    /** 账号 UUID（只有确实符合 UUID 格式时才填），没有则为 null。 */
    val uid: String? = null,
    /** 拉内容时用的键：有 uid 用 uid，否则用 handle。 */
    val contentId: String? = null
) {
    /** 只有 handle 没有 uid：必须先查一次 /profile 才能拿到内容键。 */
    val needsProfileLookup: Boolean get() = handle != null && uid == null

    /** 两个入口都为空：页面没有任何可用的作者标识，直接空态，不发请求。 */
    val unavailable: Boolean get() = handle == null && uid == null
}

/**
 * 归一作者页参数。
 *
 * 规则：
 * 1. 两侧都 trim，空串按缺失处理（null / "" / 纯空格等价）；
 * 2. [userId] 只有符合 UUID 格式才当 uid（见 [isIwaraUserUuid]）；否则说明它是被误传成
 *    userId 的 handle，当 handle 用（[username] 缺失时补位）；
 * 3. [username] 优先当 handle；它和 [userId] 可能同时有值（播放页会两个都给）。
 */
fun iwaraProfileTargetOf(username: String?, userId: String?): IwaraProfileTarget {
    val rawUsername = username?.trim()?.takeIf { it.isNotEmpty() }
    val rawUserId = userId?.trim()?.takeIf { it.isNotEmpty() }
    val uid = rawUserId?.takeIf { isIwaraUserUuid(it) }
    val handle = rawUsername ?: rawUserId?.takeIf { uid == null }
    return IwaraProfileTarget(
        handle = handle,
        uid = uid,
        contentId = uid ?: handle
    )
}

/**
 * 资料请求失败是否让整页不可用。
 *
 * 判定只有一条：有没有可用的内容键（[IwaraProfileTarget.contentId]）。
 * 有键（UUID 或 handle）就还能拉内容，资料失败只影响头部昵称 / 计数，页面不该整页报错；
 * 两者都空才是真的没东西可显示。
 */
fun profileFailureIsFatal(target: IwaraProfileTarget): Boolean = target.contentId.isNullOrBlank()

/**
 * 资料请求（GET /profile/{username}）的错误码是否值得重试一次。
 *
 * 429（限流）与 5xx（服务端瞬时故障）重试；401/403/404 及其余 4xx 是确定性失败，
 * 重试只会白费一次往返。网络类失败（DNS / 连接拒绝）已由 IwaraClient.call 切备用域处理。
 */
fun retryableProfileError(code: Int?): Boolean = code == 429 || (code != null && code in 500..599)

/** 关注列表打底失败后的重试冷却（5 分钟）：失败后短时间内不再翻关注列表。 */
internal const val FOLLOW_SEED_RETRY_COOLDOWN_MS = 5 * 60 * 1000L

/**
 * 关注列表打底现在是否允许重试。
 *
 * @param lastFailedAtMs 上次失败的时间戳；null = 没失败过，可以试。
 * @param nowMs 当前时间戳（由调用方注入，单测用固定值）。
 * 失败后冷却 [FOLLOW_SEED_RETRY_COOLDOWN_MS] 内不允许；时钟回拨（now 早于上次失败）
 * 按「已过冷却」处理，避免把打底永久卡死。
 */
internal fun followSeedRetryAllowed(lastFailedAtMs: Long?, nowMs: Long): Boolean {
    if (lastFailedAtMs == null) return true
    val elapsed = nowMs - lastFailedAtMs
    return elapsed < 0 || elapsed >= FOLLOW_SEED_RETRY_COOLDOWN_MS
}
