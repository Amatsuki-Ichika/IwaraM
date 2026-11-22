package io.github.amatsukiichika.iwaram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.amatsukiichika.iwaram.data.DOWNLOAD_MAX_AUTO_RETRIES
import io.github.amatsukiichika.iwaram.data.BrokenReason
import io.github.amatsukiichika.iwaram.data.DownloadHistoryEntry
import io.github.amatsukiichika.iwaram.data.DownloadControl
import io.github.amatsukiichika.iwaram.data.DownloadIncompleteException
import io.github.amatsukiichika.iwaram.data.deleteReplacedVideoRow
import io.github.amatsukiichika.iwaram.data.libraryFileIsComplete
import io.github.amatsukiichika.iwaram.data.upsertDownloadedVideo
import io.github.amatsukiichika.iwaram.data.videoRowByIwaraId
import io.github.amatsukiichika.iwaram.data.videoRowByUri
import io.github.amatsukiichika.iwaram.data.DownloadRangeMismatchException
import io.github.amatsukiichika.iwaram.data.FileNameSanitizer
import io.github.amatsukiichika.iwaram.data.IntegrityVerdict
import io.github.amatsukiichika.iwaram.data.IwaraClient
import io.github.amatsukiichika.iwaram.data.IwaraSign
import io.github.amatsukiichika.iwaram.data.PendingDownload
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.addDownloadHistory
import io.github.amatsukiichika.iwaram.data.deleteLocalFile
import io.github.amatsukiichika.iwaram.data.deleteLocalThumbnailFile
import io.github.amatsukiichika.iwaram.data.downloadCardFailureReason
import io.github.amatsukiichika.iwaram.data.downloadRetryDelayMs
import io.github.amatsukiichika.iwaram.data.fetchAndApplyMetadata
import io.github.amatsukiichika.iwaram.data.generateLocalThumbnail
import io.github.amatsukiichika.iwaram.data.isDownloadComplete
import io.github.amatsukiichika.iwaram.data.isSignedUrlExpired
import io.github.amatsukiichika.iwaram.data.loadDownloadHistory
import io.github.amatsukiichika.iwaram.data.loadPendingDownloads
import io.github.amatsukiichika.iwaram.data.needsThumbnailRepair
import io.github.amatsukiichika.iwaram.data.removePendingDownload
import io.github.amatsukiichika.iwaram.data.resolveDownloadTarget
import io.github.amatsukiichika.iwaram.data.resumeRange
import io.github.amatsukiichika.iwaram.data.savePendingDownload
import io.github.amatsukiichika.iwaram.data.shouldAutoRedownload
import io.github.amatsukiichika.iwaram.data.shouldRetryDownload
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.DiagLog
import okhttp3.Request
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 这条库记录是不是"同一支视频、但指向一份已经不在的文件"的旧行。
 *
 * 三个条件缺一不可：同 iwaraId（调用方已按 iwaraId 查过来）、不是本次写入的 URI、
 * 且旧文件**确实不在了**。文件还在的行属于用户数据，不能因为一次下载就删掉。
 * 抽成纯函数是为了把"什么时候算旧行"钉进测试。
 */
internal fun isStaleRowForSameVideo(
    rowUri: String,
    rowIwaraId: String?,
    newLocalUri: String,
    oldFileExists: Boolean
): Boolean = rowUri != newLocalUri && !rowIwaraId.isNullOrBlank() && !oldFileExists

/**
 * 带状态码的下载失败（替代原先的 RuntimeException("HTTP 状态码")）。
 * 只有拿到具体 code 才能区分可重试的 408/429/5xx 与不可重试的 401/403/404，
 * 分类规则见 [shouldRetryDownload]。
 */
internal class DownloadHttpException(val code: Int) : RuntimeException("HTTP $code")

/** 一次下载尝试的收尾方式；网络/IO 异常不在这里表示，由调用方按 [shouldRetryDownload] 分类重试。 */
private data class DownloadAttemptResult(
    val completed: Boolean = false,
    val paused: Boolean = false,
    val cancelled: Boolean = false,
    val received: Long = 0,
    val total: Long = -1
)

/**
 * 下载任务状态（UI 可观察）。
 *
 * 列表保留**全部未完成任务**：排队/下载/暂停/失败都必须能在界面看见并操作，
 * 只对完成态历史封顶（见 [trimDownloadTasks]）。
 */
object DownloadState {
    /** 任务状态机：queued → running →（done | failed | paused）。 */
    const val STATE_QUEUED = "queued"
    const val STATE_RUNNING = "running"
    const val STATE_PAUSED = "paused"
    const val STATE_FAILED = "failed"
    const val STATE_DONE = "done"

    data class Task(
        val videoId: String,
        val title: String,
        val received: Long = 0,
        val total: Long = -1,
        val state: String = STATE_RUNNING,
        val message: String? = null,
        // 续传/管理所需的上下文（暂停与失败时回填）。
        val url: String? = null,
        val fileName: String? = null,
        val quality: String? = null,
        val docUri: String? = null
    ) {
        val finished: Boolean get() = state == STATE_DONE || state == STATE_FAILED
        val success: Boolean get() = state == STATE_DONE
    }

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    fun upsert(task: Task) {
        synchronized(this) {
            _tasks.value = mergeDownloadTask(_tasks.value, task, DONE_TASK_HISTORY_LIMIT)
        }
    }

    /** UI 移除一个任务（仅列表条目；文件与持久化记录由调用方处理）。 */
    fun remove(videoId: String) {
        synchronized(this) {
            _tasks.value = _tasks.value.filterNot { it.videoId == videoId }
        }
    }

    /**
     * 应用重启后把持久化的未完成任务（暂停/失败）恢复进列表；
     * 已有同 id 的活动条目不覆盖。
     */
    fun restoreIfAbsent(restored: List<Task>) {
        synchronized(this) {
            _tasks.value = mergeRestoredDownloadTasks(_tasks.value, restored, DONE_TASK_HISTORY_LIMIT)
        }
    }
}

/**
 * 「正在下载」列表要显示的条目：**只排除已完成**。
 *
 * 判据不能写成 `!it.finished` —— [DownloadState.Task.finished] = DONE **或 FAILED**，那样会把
 * 失败任务整条过滤掉，「重新下载 / 移除」永久不可达（死代码），断点续传也就无法使用。
 * 失败/暂停中的任务正是**最需要**续传入口的状态，必须留在列表里。抽成纯函数以便单测盯住。
 */
internal fun activeDownloadTasks(tasks: List<DownloadState.Task>): List<DownloadState.Task> =
    tasks.filter { it.state != DownloadState.STATE_DONE }

/**
 * 完成态条目的保留上限：完成态不显示在「正在下载」里（见 [activeDownloadTasks]），
 * 只是收尾的瞬时值，留最近几条足够；真正的完成历史在下载记录库里。
 */
private const val DONE_TASK_HISTORY_LIMIT = 20

/**
 * 列表裁剪：**非完成态条目永不丢弃**，完成态最多保留最近 [doneLimit] 条。
 *
 * 旧实现用 `(list + rest).take(6)` 对整个列表一刀切：第 7 个任务起会从界面整条消失，
 * 但 Job 仍在跑、持久化记录也还在 —— 用户既看不到进度，也没有暂停/继续/取消/重试入口。
 * 真正需要封顶的只有完成态历史：排队/下载/暂停/失败是用户必须能看见并能操作的状态，
 * 数量不设上限（[activeDownloadTasks] 也只排除完成态）。
 *
 * 输入顺序即“最近在前”（[DownloadState.upsert] 把新任务放头部、恢复的记录追加在尾部）；
 * 完成态按出现顺序保留前 [doneLimit] 条（即最近的几条）。[doneLimit] <= 0 表示不留完成态。
 */
internal fun trimDownloadTasks(
    tasks: List<DownloadState.Task>,
    doneLimit: Int
): List<DownloadState.Task> {
    val limit = doneLimit.coerceAtLeast(0)
    val kept = ArrayList<DownloadState.Task>(tasks.size)
    var doneSeen = 0
    for (task in tasks) {
        if (task.state == DownloadState.STATE_DONE) {
            if (doneSeen >= limit) continue
            doneSeen++
        }
        kept.add(task)
    }
    return kept
}

/**
 * [DownloadState.upsert] 的纯函数部分：同 videoId 去重（新任务排最前）后按
 * [trimDownloadTasks] 裁剪。去重同时保证界面列表的 `key = videoId` 不会撞车。
 */
internal fun mergeDownloadTask(
    tasks: List<DownloadState.Task>,
    task: DownloadState.Task,
    doneLimit: Int
): List<DownloadState.Task> =
    trimDownloadTasks(listOf(task) + tasks.filterNot { it.videoId == task.videoId }, doneLimit)

/**
 * [DownloadState.restoreIfAbsent] 的纯函数部分：已有同 id 的条目不覆盖，恢复的记录追加在尾部；
 * 恢复列表内部也按 videoId 去重（重复 key 会让界面列表组合崩溃），最后按 [trimDownloadTasks]
 * 裁剪 —— **未完成任务一条都不能因恢复而被丢掉**。
 */
internal fun mergeRestoredDownloadTasks(
    tasks: List<DownloadState.Task>,
    restored: List<DownloadState.Task>,
    doneLimit: Int
): List<DownloadState.Task> {
    if (restored.isEmpty()) return tasks
    val known = tasks.mapTo(HashSet(tasks.size)) { it.videoId }
    val extra = ArrayList<DownloadState.Task>(restored.size)
    for (task in restored) {
        // add 返回 false 表示已存在：既不覆盖既有条目，也不重复恢复同一条记录。
        if (known.add(task.videoId)) extra.add(task)
    }
    if (extra.isEmpty()) return tasks
    return trimDownloadTasks(tasks + extra, doneLimit)
}

/**
 * 续传目标：应该往哪个文件写，以及可以从多少字节续起。
 *
 * 只在**下载根目录的最外层** `root.findFile(名字)` 找一次半成品、且**从不读**
 * `PendingDownload.docUri`（那个字段只写不读），只要半成品不在根目录最外层
 * （改过下载目录、或文件本来就在子文件夹里）就会找不到 → 走 `createFile` **新建一份**
 * → 同一个视频在磁盘上两份、本地库两条记录。
 *
 * 按优先级挑目标（纯函数，可单测）：
 *  ① 上次记录里的半成品 —— 它才是"真正的断点"，位置可能在任意目录；
 *  ② 下载根目录下的同名文件；
 *  ③ 子树里的同名文件（名字里带 ID 与档位，所以不会张冠李戴、也不会把两种画质拼在一起）。
 * 全部不可用（不存在 / 长度为 0）时返回 null ⇒ 调用方新建文件。
 * （"库里已经有这支视频"由调用方**提前收尾**处理，不走这里，见 runDownload。）
 */
internal data class ResumeTarget(val docUri: String, val length: Long)

internal fun pickResumeTarget(
    recordedDocUri: String?,
    recordedLength: Long,
    rootDocUri: String?,
    rootLength: Long,
    treeDocUri: String?,
    treeLength: Long
): ResumeTarget? {
    // 长度用 -1L 表示"文件不存在/取不到"。**0 字节也算有效目标**：那是上次失败留下的空文件，
    // 以它为目标继续写，胜过 new 一个「xxx (1).mp4」（后者正是"同一支视频两个文件"的来源）。
    // 0 字节时下游不会带 Range 头（`resumeFrom > 0` 才带），服务端返回 200 ⇒ 截断重写，安全。
    fun of(uri: String?, len: Long) = if (len >= 0 && !uri.isNullOrBlank()) ResumeTarget(uri, len) else null
    return of(recordedDocUri, recordedLength)
        ?: of(rootDocUri, rootLength)
        ?: of(treeDocUri, treeLength)
}

/**
 * 持久化的未完成任务（[PendingDownload]）→「正在下载」列表条目（[DownloadState.Task]）。
 *
 * - 排队（queued）与下载中（running）被进程死亡打断的记录，恢复时统一按 **queued** 呈现
 *   （断点信息其实还在磁盘的半成品文件里，点「继续」重新解析地址即可续传）；
 * - message 三态文案在这里收敛，UI 与单测共用同一份映射。
 */
internal fun pendingToTask(p: PendingDownload): DownloadState.Task = DownloadState.Task(
    p.videoId, p.title, p.received, p.total,
    state = when (p.state) {
        DownloadState.STATE_PAUSED -> DownloadState.STATE_PAUSED
        DownloadState.STATE_FAILED -> DownloadState.STATE_FAILED
        // queued / running / 其它未知历史值 → 一律按"排队中断"处理（继续按钮走重新解析路径）
        else -> DownloadState.STATE_QUEUED
    },
    message = when (p.state) {
        DownloadState.STATE_PAUSED -> tr("已暂停", "Paused")
        DownloadState.STATE_FAILED -> {
            // 自动重试过 n 次才失败的记录，恢复后也要让用户知道"不是没试过"；
            // attempts=0（不可重试的失败）保持原文案。
            val retries = downloadCardFailureReason(p.state, p.attempts) ?: 0
            if (retries > 0) {
                tr(
                    "下载失败（已自动重试 $retries 次，可重新下载续传）",
                    "Download failed after $retries automatic retries (you can resume)"
                )
            } else {
                tr("下载失败（可重新下载续传）", "Download failed (you can resume)")
            }
        }
        else -> tr("下载中断（点「继续」重新下载）", "Download interrupted (tap Resume to continue)")
    },
    fileName = p.fileName, quality = p.quality, docUri = p.docUri
)

/**
 * 在线视频下载前台服务：把视频写入下载目录（SAF），
 * 文件名带 [iwaraId]（与 IwaraIdParser 的方括号规则匹配），完成后自动建条目并拉取元数据，
 * 视频随即出现在「管理器」库里。
 *
 * 暂停 / 断点续传：
 * - 暂停 = 读循环检查标记 → 关流、保留半成品文件、任务记为 PAUSED 并持久化到 repo；
 * - 继续 = UI 重新解析出**新鲜**签名地址（旧地址带 expires 会过期，不可复用）→ 服务发现
 *   同名半成品文件 → 带 `Range: bytes=<已收>-` 请求：206 追加写入（"wa"），200（服务器
 *   不支持 Range 或文件与本次档位不匹配）则**截断重下**，绝不写坏文件；
 * - 失败同样保留半成品与记录，「重新下载」走同一条续传路径。
 */
/**
 * 常驻 worker 的核心消费循环（抽成可注入的纯协程函数以便单测）：
 * 串行消费直到 channel 关闭；每个任务结束后若队列已空，则以「最后消费任务的 startId」触发 [onIdle]
 * （服务里用于收起前台通知并按框架 startId 协议自停，见 [DownloadService.onCreate] 注释）。
 *
 * 关键不变量：
 * - **没有空闲退出**：`withTimeoutOrNull(1500){queue.receive()} ?: break` 会在 worker
 *   收尾窗口内新任务入队 + 新 drainQueue CAS 失败时导致任务永久滞留（表现为"下载中"卡死）。
 *   常驻 worker 下任何时刻入队的任务都会被消费。
 * - **不捕获 CancellationException**：服务销毁（scope.cancel）时取消必须原样传播，
 *   绝不能落进"下载失败"通知路径。
 */
internal suspend fun drainDownloadQueue(
    queue: ReceiveChannel<DownloadService.Job>,
    execute: suspend (DownloadService.Job) -> Unit,
    onIdle: suspend (Int) -> Unit = {},
    /**
     * 出队即判定"这个任务在排队期间被取消了"。返回 true 时不执行、改走 [onCancelled]。
     *
     * 判定带副作用（消费掉取消标记）而不是查询状态：取消是**一次性事件**，
     * 标记被读走后不能残留，否则用户之后再下同一支视频会被立刻取消。
     */
    isCancelled: (DownloadService.Job) -> Boolean = { false },
    onCancelled: suspend (DownloadService.Job) -> Unit = {}
) {
    for (job in queue) {
        if (isCancelled(job)) onCancelled(job) else execute(job)
        // 注意 Channel 语义：isEmpty 表示"receive 会挂起吗"——对"已关闭且已取空"的 channel
        // 它返回 false（receive 不挂起而是立即返回关闭结果），所以必须并上 isClosedForReceive
        // 才能覆盖"最后一批任务消费完"的情形。
        if (queue.isEmpty || queue.isClosedForReceive) onIdle(job.startId)
    }
}

/**
 * 下载并发闸门：最多放行 [maxParallel] 个文件同时下载（不拆分单个文件）。
 *
 * 默认 2；一旦命中风控（HTTP 429/403）就**自适应降为 1 并整体退避**一段时间，成功一次后再逐级
 * 恢复到 2 —— 不做用户开关，用户看到的只是"稍慢一点"而不是失败。
 *
 * 时钟与挂起都可注入：JVM 单测里推进"等待"而不真实 sleep。
 */
internal class DownloadConcurrency(
    private val maxParallel: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    private val lock = Any()
    private var limit = maxParallel
    private var inFlight = 0
    private var resumeAtMs = 0L

    /** 取一个并发额度；被降级或处于退避窗口时挂起等待，直到有空位（任务不会被丢弃）。 */
    suspend fun acquire() {
        while (true) {
            val waitMs = synchronized(lock) {
                val now = clock()
                if (now < resumeAtMs) return@synchronized resumeAtMs - now
                if (inFlight < limit) {
                    inFlight += 1
                    return
                }
                WAIT_STEP_MS
            }
            pause(waitMs)
        }
    }

    /** 归还额度；每个成功返回的 [acquire] 都必须调用一次（放在 finally 里）。 */
    fun release() {
        synchronized(lock) { if (inFlight > 0) inFlight -= 1 }
    }

    /** 风控信号：降为单并发并退避；已在跑的下载不受影响，只限制下一次取额度。 */
    fun onRateLimited(backoffMs: Long) {
        synchronized(lock) {
            limit = 1
            resumeAtMs = maxOf(resumeAtMs, clock() + backoffMs.coerceAtLeast(WAIT_STEP_MS))
        }
    }

    /** 一次成功：结束退避并逐级恢复并发（一次只 +1，不立刻打满，避免再次撞风控）。 */
    fun onSucceeded() {
        synchronized(lock) {
            resumeAtMs = 0L
            if (limit < maxParallel) limit += 1
        }
    }

    private companion object {
        /** 额度等待的轮询步长；等待是 delay 不是忙等，250ms 足够快也不费 CPU。 */
        const val WAIT_STEP_MS = 250L
    }
}

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Job>(Channel.UNLIMITED)

    data class Job(
        val videoId: String,
        val title: String,
        val url: String,
        val fileName: String,
        val quality: String,
        /** 框架交付序：onStartCommand 的 startId；onIdle 凭最后一个它安全 stopSelf（见 onCreate 注释）。 */
        val startId: Int = 0,
        /** 已自动重试次数（新任务为 0，runDownload 的重试循环内累加）。 */
        val attempt: Int = 0,
        /**
         * 完整性校验失败后的"替换式重下"：开跑前先删旧文件、并跳过"已完整/已在库"快捷路径。
         * 见 [DownloadService.requestRedownload] —— 不这么做会以坏文件秒完成，形成重下死循环。
         */
        val replaceExisting: Boolean = false
    )

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 常驻双 worker（进程内随服务生灭），onStartCommand 只负责入队：最多两个文件并行，
        // 单个文件仍由一个 worker 顺序写入（不拆分单文件）。并发额度由 [concurrency] 管：
        // 命中风控时自适应降为 1 并退避（见 DownloadConcurrency）。
        repeat(MAX_PARALLEL_DOWNLOADS) {
            scope.launch {
                drainDownloadQueue(
                    queue,
                    execute = { job ->
                        // 先登记"在跑"再取额度：降级时这个任务可能在闸门外等一会儿，
                        // 这期间不能被另一个 worker 的 onIdle 当成空闲把服务停掉（任务会丢）。
                        activeJobs.add(job.videoId)
                        try {
                            concurrency.acquire()
                            try {
                                runDownload(job)
                            } finally {
                                concurrency.release()
                            }
                        } finally {
                            activeJobs.remove(job.videoId)
                        }
                    },
                    // 排队期间被取消的任务：出队即丢弃，绝不执行（旧的取消只清了记录与列表条目，
                    // Channel 里的 job 原封不动，worker 出队后又把任务写回「正在下载」并开下）。
                    isCancelled = { job -> control.consumeCancel(job.videoId) },
                    onCancelled = { job -> discardCancelled(job) },
                    onIdle = { lastStartId -> stopIfIdle(lastStartId) }
                )
            }
        }
    }

    /**
     * worker 空闲回调：队列空且没有任务在跑时才收起前台通知并自停。
     * 两个 worker 都会报空闲，先收工的那个不能把另一个还在下的服务停掉。
     */
    private fun stopIfIdle(lastStartId: Int) {
        if (!queue.isEmpty || activeJobs.isNotEmpty()) return
        // 前台通知随任务走：队列清空后收起常驻通知；随后按 startId 协议自停，
        // 空跑的服务不再常驻进程。stopSelf(lastStartId) 是 IntentService 同款的
        // 竞态防护：若自停的同时又有新的 startForegroundService 进来，框架看到
        // 更新的 startId 会忽略本次停止，新任务照常被 worker 消费；只有当前这个
        // 确实是最后交付的 startId 时服务才真正停止。前提是任务在 onStartCommand
        // 内同步入队（入队序 = startId 交付序，见 onStartCommand 注释）。
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val job = intent?.let {
            val id = it.getStringExtra(EXTRA_ID) ?: return@let null
            Job(
                videoId = id,
                title = it.getStringExtra(EXTRA_TITLE) ?: id,
                url = it.getStringExtra(EXTRA_URL) ?: return@let null,
                fileName = it.getStringExtra(EXTRA_FILE_NAME) ?: "$id.mp4",
                quality = it.getStringExtra(EXTRA_QUALITY) ?: "Source",
                startId = startId,
                attempt = it.getIntExtra(EXTRA_ATTEMPTS, 0),
                replaceExisting = it.getBooleanExtra(EXTRA_REPLACE_EXISTING, false)
            )
        }
        if (job == null) {
            // startForegroundService 拉起后必须先 startForeground 再移除，
            // 否则五秒内未进入前台会崩（ForegroundServiceDidNotStartInTime）。
            startForeground(PROGRESS_NOTIF_ID, progressNotification(tr("准备下载", "Preparing download"), 0, 0, true))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 必须在 onStartCommand 内立即 startForeground（worker 可能正忙于上一个任务，
        // 排队中的新任务等不到 5 秒约束）；后续通知由 runDownload 的进度回调接管。
        startForeground(PROGRESS_NOTIF_ID, progressNotification(tr("准备下载", "Preparing download"), 0, 0, true))
        // 排队任务必须立刻登记进「正在下载」列表并持久化：
        // 否则排队中的任务不可见，且杀后台后会凭空消失（只有 runDownload 出队才登记）。
        DownloadState.upsert(
            DownloadState.Task(
                job.videoId, job.title, 0, -1,
                state = DownloadState.STATE_QUEUED, message = tr("排队等待", "Queued"),
                url = job.url, fileName = job.fileName, quality = job.quality
            )
        )
        // trySend：UNLIMITED channel 下必然入队成功，channel 已随 onDestroy 关闭时返回失败
        // 结果而非抛出。必须在 onStartCommand 内同步入队（不能经 launch 异步跳一拍）：
        // startId 协议要求「入队序 = 框架交付序」，onIdle 才能凭最后消费的 startId 安全自停。
        queue.trySend(job)
        // `savePendingDownload` 是「prefs JSON 读 → Gson 反序列化 → 再序列化 → 写回」，
        // 不能直接在 onStartCommand（**主线程**）上跑：待续传记录越多越慢，连点多个下载时
        // 每次都要主线程做一遍整表 JSON 解析。挪进 IO 作用域，不等它。
        val app = application as IwaraApp
        scope.launch {
            runCatching {
                // 这条协程与 worker 并发，落盘顺序不确定：若它把续传字段冲成 0/-1/null，
                // worker 读到的续传起点就丢了。保留已有记录里的 received/total/docUri，
                // 这样无论谁先写，续传信息都不会被排队记录覆盖（替换式重下则本来就该清零）。
                val previous = app.repository.loadPendingDownloads().firstOrNull { it.videoId == job.videoId }
                app.repository.savePendingDownload(
                    PendingDownload(
                        videoId = job.videoId, title = job.title,
                        fileName = job.fileName, quality = job.quality,
                        received = if (job.replaceExisting) 0L else previous?.received ?: 0L,
                        total = if (job.replaceExisting) -1L else previous?.total ?: -1L,
                        docUri = if (job.replaceExisting) null else previous?.docUri,
                        updatedAt = System.currentTimeMillis(),
                        state = DownloadState.STATE_QUEUED,
                        attempts = job.attempt
                    )
                )
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+ 对 dataSync 前台服务施加「6 小时/24h」运行限额，到时系统回调这里（API 35+）。
     * 已有的暂停机制接管：在跑任务全部标记暂停（读循环安全收尾，半成品文件与记录都保留），
     * 队列里未跑的任务留在「正在下载」列表；随后收起前台并自停 —— onTimeout 之后系统只给
     * 几秒宽限，必须立即 stopSelf，否则 ANR。进程被带走的任务按既有路径在下次启动恢复为
     * 「下载中断/排队」，点「继续」即可断点续传。
     */
    override fun onTimeout(startId: Int) {
        DiagLog.log("Download", "dataSync 前台服务到时（6h 限额）：在跑任务全部转暂停并自停")
        activeJobs.forEach { control.requestPause(it) }
        notify(
            DONE_NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr("下载已暂停", "Download paused"))
                .setContentText(
                    tr(
                        "已到系统后台运行限额；打开应用点「继续」断点续传",
                        "The system background limit was reached; open the app and tap Resume to continue"
                    )
                )
                .setAutoCancel(true)
                .build()
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // close 让常驻 worker 的 for-in 正常退出；cancel 立即终止在跑的下载与 scope。
        queue.close()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 排队期间被取消的任务：清掉落盘记录与列表条目。
     * 队列中的任务通常还没有半成品文件（出队才建），真留下了半成品由 UI 的
     * removePendingCompletely 按 docUri 删。
     */
    private suspend fun discardCancelled(job: Job) = withContext(Dispatchers.IO) {
        val app = application as IwaraApp
        runCatching { app.repository.removePendingDownload(job.videoId) }
        DownloadState.remove(job.videoId)
        DiagLog.log("Download", "排队任务已取消：${job.title}")
    }

    private suspend fun runDownload(job: Job): Unit = withContext(Dispatchers.IO) {
        val app = application as IwaraApp
        val repo = app.repository
        DownloadState.upsert(DownloadState.Task(job.videoId, job.title, url = job.url, fileName = job.fileName, quality = job.quality))
        // 双 worker 下有一个极窄的竞态：另一个 worker 刚判空闲时本任务已被它取走、但还没登记进
        // activeJobs，于是前台通知可能被它顺手收掉。开跑时重申一次前台态，避免"服务在下、
        // 通知却没了"被系统按后台服务回收（进程一死，下载就断）。
        startForeground(PROGRESS_NOTIF_ID, progressNotification(job.title, 0, 0, true))
        // 先把已有的续传记录读出来，再写 running 记录：顺序反了的话这次写盘会把
        // received/total/docUri 全冲成 0/-1/null，"半成品已收满"与续传起点就全丢了。
        val pending = repo.loadPendingDownloads().firstOrNull { it.videoId == job.videoId }
        // 开跑即写一条 running 记录（半成品可能已有，替换式重下则从零开始）。
        // 下载中进程被杀时记录留存，重启后按「下载中断」恢复进列表；正常完成/暂停/失败
        // 都会被各自路径覆盖或清除，续传起点始终以磁盘半成品长度为准，不受此记录影响。
        runCatching {
            repo.savePendingDownload(
                PendingDownload(
                    videoId = job.videoId, title = job.title,
                    fileName = job.fileName, quality = job.quality,
                    received = if (job.replaceExisting) 0L else pending?.received ?: 0L,
                    total = if (job.replaceExisting) -1L else pending?.total ?: -1L,
                    docUri = if (job.replaceExisting) null else pending?.docUri,
                    updatedAt = System.currentTimeMillis(),
                    state = DownloadState.STATE_RUNNING,
                    attempts = job.attempt
                )
            )
        }

        // 自定义下载目录优先；未设置则回退管理器根目录。
        // 未设置自定义下载目录时，落盘目录 = 库根目录下的 `IwaraMDownload` 子目录
        //（没有就建一个；建失败回退根目录，不因建目录失败卡死下载）。
        // 已选自定义下载目录时直接用它（它就是下载文件夹，不再套一层）。
        // 已存在的文件不改动、不迁移（只影响新下载）；扫描 `importFolder` 本就递归，子目录里的文件照常能扫到。
        val customRoot = repo.getDownloadRoot()
        val rootUri = customRoot ?: repo.getRoot()
        if (rootUri.isNullOrBlank()) {
            fail(job, repo, tr("尚未设置管理器根目录，请先在设置里选择文件夹", "No library folder is set. Choose one in Settings first"))
            return@withContext
        }

        // 断点续传：先找同名半成品（暂停/失败留下），再决定 Range 起点。
        val baseRoot = withContext(Dispatchers.IO) {
            runCatching { DocumentFile.fromTreeUri(this@DownloadService, Uri.parse(rootUri)) }.getOrNull()
        }
        if (baseRoot == null) {
            fail(
                job, repo,
                tr(
                    "无法访问下载目录（可能未授予写入权限），请在设置里重新选择下载目录或恢复默认",
                    "Cannot access the download folder (write permission may be missing). " +
                        "Choose the download folder again in Settings or reset it to default"
                )
            )
            return@withContext
        }
        // 默认情形把落盘目录下沉到根目录下的 IwaraMDownload；自定义目录则原样使用。
        val root = if (customRoot == null) iwaraDownloadDir(baseRoot) else baseRoot
        val sanitized = FileNameSanitizer.sanitize(job.fileName)

        // 排队久了签名地址会过期（地址带 expires，见 isSignedUrlExpired）。出队时先验一次，
        // 过期就用**同一个 videoId + 同一个档位**重新解析 —— 否则这次下载必然以 401/403 收场，
        // 而用户除了在列表里点「重新下载」没有任何办法（这正是以前排队多任务时的表现）。
        var sourceUrl = job.url
        if (isSignedUrlExpired(sourceUrl, System.currentTimeMillis())) {
            DiagLog.log("Download", "排队期间签名地址已过期，重新解析：${job.title}")
            val fresh = runCatching { resolveDownloadTarget(repo, job.videoId, job.quality) }
            if (fresh.isFailure) {
                fail(job, repo, tr("下载地址已过期，请重新下载", "The download link expired. Please download again"))
                return@withContext
            }
            sourceUrl = fresh.getOrThrow().url
        }

        // 库里**已经有这支视频**（同一个 iwaraId）而且文件完整 ⇒ 直接以已有文件收尾，
        // 不再下载、更不新建第二份。不检查"这支视频是否已存在"时，同名/异名都会再复制一份进库，
        // 而 finishDownload 又会用一条 relPath=null 的裸记录去 upsert ⇒ 同一个视频两条记录
        // （一条保留扫描得到的文件夹路径、一条没有）。
        // 注意：扫描器把"长度 > 0"的文件就收进库，所以只看"存在且非空"会把**半成品**
        // 当成已完成（清单显示成功、播放断点黑屏、也没有续传入口）。有未完成记录时，
        // 用它的总长度判断文件是否真的收满；没满就继续往下走续传流程。
        val libraryRow = repo.videoRowByIwaraId(job.videoId)
        // 完整性校验失败后的自动重下：先删掉旧的（长度可能恰好"正确"但内容损坏的）文件。
        // 不删的话下面的快捷路径看到"库里有且长度达标"会直接以坏文件收尾，
        // 变成"校验坏 → 自动重下 → 秒完成 → 再校验坏"的死循环。
        if (job.replaceExisting) {
            DiagLog.log("Download", "自动重下前删除旧文件：${job.title}")
            listOfNotNull(libraryRow?.localUri?.toString(), pending?.docUri)
                .distinct()
                .forEach { oldUri -> runCatching { repo.deleteLocalFile(oldUri) } }
        }
        val libraryDoc = documentOf(libraryRow?.localUri)
        if (libraryDoc != null && libraryFileIsComplete(libraryDoc.length(), pending?.total ?: -1L)) {
            DiagLog.log("Download", "已在本地库，跳过重复下载：${job.title}")
            // 这份文件不是本次下载的产物（可能是扫描导入、手动关联 iwaraId 的 mkv/webm），
            // 不能按 MP4 结构判坏，更不能自动删掉用户文件再重下。
            finishDownload(
                job, repo, libraryDoc,
                note = tr("本地库已有，未重复下载", "Already in your library; skipped"),
                verifyIntegrity = false
            )
            return@withContext
        }

        // 找半成品（按优先级，见 pickResumeTarget 的注释）：
        // ① 上次记录里的那个（pending.docUri 此前只写不读）；
        // ② 下载根目录最外层的同名文件；
        // ③ 子树里的同名文件。
        // 三者都按**文件名**匹配，而文件名里带 iwara ID 与档位 ⇒ 命中即同一支视频、同一档位，
        // 不会把两种画质的字节拼在一起（安全续传的前提）。
        val recordedDoc = documentOf(pending?.docUri)
        val rootDoc = runCatching { root.findFile(sanitized) }.getOrNull()
        // 子树兜底从 **baseRoot** 往下找：
        // 默认情形 `root` 已是 IwaraMDownload 子目录，从 baseRoot 找能把散在根目录的半成品
        // 一起覆盖到（文件不迁移，但仍可续传），深度限制 3 层不变。
        val treeDoc = if (rootDoc == null) findNamedFileInTree(baseRoot, sanitized) else null
        val target = pickResumeTarget(
            recordedDocUri = recordedDoc?.uri?.toString(), recordedLength = recordedDoc?.length() ?: -1L,
            rootDocUri = rootDoc?.uri?.toString(), rootLength = rootDoc?.length() ?: -1L,
            treeDocUri = treeDoc?.uri?.toString(), treeLength = treeDoc?.length() ?: -1L
        )
        var doc: DocumentFile? = null
        if (target != null) {
            val d = documentOf(target.docUri)
            if (d != null) {
                doc = d
            }
        }
        if (doc == null) {
            // 没有可续传的文件：在下载根目录新建（文件名带 ID，便于日后再次识别 / 续传）。
            doc = runCatching { root.createFile("video/mp4", sanitized) }.getOrNull()
        }
        if (doc == null) {
            fail(
                job, repo,
                tr(
                    "无法在下载目录创建文件（可能未授予写入权限），请在设置里重新选择下载目录或恢复默认",
                    "Cannot create a file in the download folder (write permission may be missing). " +
                        "Choose the download folder again in Settings or reset it to default"
                )
            )
            return@withContext
        }

        // 自动重试：网络抖动/断流按 3s/10s/30s 退避重试，最多 DOWNLOAD_MAX_AUTO_RETRIES 次。
        // 重试留在**同一个 Job、同一条前台通知**里完成：不重新入队 —— 否则 attempt 计数、
        // 半成品长度与暂停/取消标记都会被重复处理，串行 drain 的既有语义也会被打乱。
        // 401/403 先按同一 videoId + 同一档位重新解析一次地址（签名过期最常见），
        // 仍被拒签才按「下载地址已过期」收尾。
        var attempt = job.attempt
        var reResolvedAuth = false
        // 上次已经收满、只差收尾（进程在最后一步被杀）：磁盘长度等于记录的总长度就直接完成。
        // 不再发 Range 请求 —— 对已经到 EOF 的文件请求 bytes=total- 只会换回 416，
        // 把一份完整文件误判成失败。
        val recordedTotal = pending?.total ?: -1L
        if (!job.replaceExisting && recordedTotal > 0 && (doc?.length() ?: 0L) == recordedTotal) {
            DiagLog.log("Download", "半成品已收满，直接收尾：${job.title}")
            finishDownload(job, repo, doc, expectedBytes = recordedTotal)
            return@withContext
        }
        while (true) {
            // 续传起点每轮都从磁盘真实长度取：失败重试前可能已经写入了一部分字节。
            val resumeFrom = (doc?.length() ?: 0L).coerceAtLeast(0L)
            try {
                val result = downloadOnce(job, doc, sourceUrl, resumeFrom)
                when {
                    result.cancelled -> cancelDownload(job, repo, doc)
                    result.paused -> pauseTask(job, repo, doc, result.received, result.total, attempt)
                    else -> {
                        // 一次干净的完成说明链路恢复了：并发额度逐级回到上限。
                        concurrency.onSucceeded()
                        finishDownload(job, repo, doc, expectedBytes = result.total)
                    }
                }
                return@withContext
            } catch (e: CancellationException) {
                // 服务被销毁/协程被取消时原样传播：不发「下载失败」通知、不写失败记录
                //（半成品文件保留，符合断点续传预期）。
                throw e
            } catch (e: Throwable) {
                val httpCode = (e as? DownloadHttpException)?.code
                if (httpCode == 429 || httpCode == 403) {
                    // 风控信号（限流/拒签）：并发自适应降为 1 并整体退避一段，避免两个 worker
                    // 一起撞风控越推越死；当前任务照常走下面的重签/重试流程。
                    concurrency.onRateLimited(
                        downloadRetryDelayMs(attempt).coerceAtLeast(RATE_LIMIT_BACKOFF_MS)
                    )
                    DiagLog.log("Download", "命中风控($httpCode)，下载并发降为 1：${job.title}")
                }
                if (httpCode == 401 || httpCode == 403) {
                    // 签名地址：同一个 Job 内只重新解析一次，避免被 CDN 反复拒签时打转。
                    val freshUrl = if (reResolvedAuth) {
                        null
                    } else {
                        reResolvedAuth = true
                        DiagLog.log("Download", "下载被拒签($httpCode)，重新解析地址：${job.title}")
                        runCatching { resolveDownloadTarget(repo, job.videoId, job.quality) }.getOrNull()?.url
                    }
                    if (freshUrl != null) {
                        sourceUrl = freshUrl
                        continue
                    }
                    // 重新解析仍失败：队列里的地址确实过期了，让用户手动重下。
                    finalizeFailure(
                        job, repo, doc, attempt, e,
                        tr("下载地址已过期，请重新下载", "The download link expired. Please download again")
                    )
                    return@withContext
                }
                if (!shouldRetryDownload(httpCode, e, attempt)) {
                    finalizeFailure(job, repo, doc, attempt, e)
                    return@withContext
                }
                // 可重试失败：同一 Job 内等一等继续，先不清记录、不写历史（最终失败才写）。
                val waitMs = downloadRetryDelayMs(attempt)
                attempt += 1
                val receivedNow = (doc?.length() ?: 0L).coerceAtLeast(0L)
                DiagLog.log(
                    "Download",
                    "下载失败(${httpCode ?: e.javaClass.simpleName})，${waitMs}ms 后第 $attempt/$DOWNLOAD_MAX_AUTO_RETRIES 次自动重试：${job.title}"
                )
                retryTask(job, attempt, waitMs, receivedNow)
                if (!awaitRetryWindow(job, waitMs)) {
                    // 退避期间用户暂停/取消：中止重试，按标记收尾，不把这次失败算成最终失败。
                    when (control.consumeStop(job.videoId)) {
                        DownloadControl.Intent.CANCEL -> cancelDownload(job, repo, doc)
                        DownloadControl.Intent.PAUSE ->
                            pauseTask(job, repo, doc, (doc?.length() ?: 0L).coerceAtLeast(0L), -1L, attempt)
                        null -> Unit
                    }
                    return@withContext
                }
            }
        }
    }

    /**
     * 单次下载尝试：发请求 + 读循环写文件。
     * 完成/暂停/取消用返回值表达，真正的失败抛异常，由 runDownload 的重试循环分类。
     */
    private suspend fun downloadOnce(
        job: Job,
        doc: DocumentFile,
        sourceUrl: String,
        resumeFrom: Long
    ): DownloadAttemptResult {
        // mediaHttp 对带 expires 的地址自动附加 X-Version 签名并带浏览器头，
        // 与播放同栈，避免下载地址被 CDN 拒签；这里再做一次协议补全防御。
        val url = IwaraSign.httpsUrl(sourceUrl.trim())
        val reqBody = Request.Builder()
            .url(url)
            .get()
        if (resumeFrom > 0) reqBody.header("Range", "bytes=$resumeFrom-")
        IwaraClient.mediaHttp.newCall(reqBody.build()).execute().use { resp ->
            val total: Long
            // 这次尝试应得的总字节数（从 0 算起）：206 用 Content-Range 的总量/区间终点，
            // 200 用 Content-Length；<= 0 表示服务器没给长度（分块响应），只能信 EOF。
            val expectedTotal: Long
            var received = 0L
            val input: java.io.InputStream
            val append: Boolean
            if (resp.code == 206) {
                // 续传响应：`Content-Range: bytes start-end/total`。起点必须等于本次请求的
                // 续传位置、区间不得越界 —— 否则 append 会把两段不同位置的内容拼到一起，
                // 长度对得上、内容却损坏（"下载成功却播不了"的来源之一）。
                val contentRange = resp.header("Content-Range")
                val range = resumeRange(contentRange, resumeFrom)
                    ?: throw DownloadRangeMismatchException(contentRange, resumeFrom)
                total = range.total
                expectedTotal = if (total > 0) total else range.end + 1
                input = resp.body?.byteStream() ?: throw java.io.IOException("空响应")
                append = resumeFrom > 0
                received = if (append) resumeFrom else 0L
                DiagLog.log("Download", "续传 ${job.title}：从 $resumeFrom 字节继续（总 $total）")
            } else {
                if (!resp.isSuccessful) throw DownloadHttpException(resp.code)
                // 服务器不支持 Range（200 全量）或没有半成品：截断重下，绝不把两次内容拼在一起。
                total = resp.body?.contentLength() ?: -1L
                expectedTotal = total
                input = resp.body?.byteStream() ?: throw java.io.IOException("空响应")
                append = false
            }
            val mode = if (append) "wa" else "w"
            val output = contentResolver.openOutputStream(doc.uri, mode)
                ?: throw java.io.IOException("无法写入文件")
            input.use { ins ->
                output.use { outs ->
                    // 256KB 分片：SAF/ContentResolver 每读/写一次都有 IPC 开销，分片越大吞吐越好；
                    // 进度刷新仍由 PROGRESS_THROTTLE_MS 限流，与分片大小无关。
                    val buf = ByteArray(BUFFER_BYTES)
                    // 进度限流：每个分片都 upsert + 发通知会在高速网络下造成
                    // 每秒数百次 StateFlow 重组与通知栏 IPC（"进度风暴"）。最密 400ms 一次，
                    // 收尾由 finishDownload() 兜底刷到完成态，观感无差别、开销降两个数量级。
                    var lastProgressAt = 0L
                    while (true) {
                        // 用户在排队/下载中点「取消」或「暂停」：消费一次意图，直接收尾。
                        // 旧实现是「先查取消、再查暂停」两个独立集合；现在由 DownloadControl
                        // 统一给出优先级（取消 > 暂停），同一意图不会被两个检查点各消费一次。
                        when (control.consumeStop(job.videoId)) {
                            DownloadControl.Intent.CANCEL ->
                                return DownloadAttemptResult(cancelled = true, received = received, total = total)
                            DownloadControl.Intent.PAUSE -> {
                                // 暂停：关流保留半成品，状态由 pauseTask 记 PAUSED 并持久化。
                                outs.flush()
                                DiagLog.log("Download", "暂停 " + job.title + "：已接收 " + received + " 字节")
                                return DownloadAttemptResult(paused = true, received = received, total = total)
                            }
                            null -> Unit
                        }
                        // 服务被销毁（scope.cancel）时也必须停：阻塞的 read 不响应协程取消，
                        // 只检查暂停标记会让它在服务销毁后继续把文件读完，最后卡在
                        // finishDownload 的第一个挂起点上抛 CancellationException ——
                        // 磁盘上留下一个既没入库、也不在续传列表里的孤儿文件。
                        currentCoroutineContext().ensureActive()

                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        received += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressAt >= PROGRESS_THROTTLE_MS) {
                            lastProgressAt = now
                            DownloadState.upsert(
                                DownloadState.Task(
                                    job.videoId, job.title, received, total,
                                    url = job.url, fileName = job.fileName, quality = job.quality,
                                    docUri = doc.uri.toString()
                                )
                            )
                            notify(
                                PROGRESS_NOTIF_ID,
                                progressNotification(job.title, received, total, total <= 0)
                            )
                        }
                    }
                    outs.flush()
                    // EOF 不等于下载完整：截断的连接、只回一半的 206 都会"正常"读到 EOF。
                    // 只有字节数正好等于应得长度才算完成，否则抛可重试异常并保留半成品续传。
                    if (!isDownloadComplete(received, expectedTotal)) {
                        throw DownloadIncompleteException(received, expectedTotal)
                    }
                    return DownloadAttemptResult(completed = true, received = received, total = total)
                }
            }
        }
        // 读循环正常结束都会在上面带 received 返回，这里只是兜底（让所有分支都有返回值）。
        return DownloadAttemptResult(completed = true)
    }

    /** 取消收尾：记录、列表条目、半成品一起清（与 UI 的 removePendingCompletely 同语义，两边幂等）。 */
    private suspend fun cancelDownload(
        job: Job,
        repo: io.github.amatsukiichika.iwaram.data.IwaraRepository,
        doc: DocumentFile
    ) {
        runCatching { repo.removePendingDownload(job.videoId) }
        DownloadState.remove(job.videoId)
        runCatching { doc.delete() }
        DiagLog.log("Download", "已取消：${job.title}")
    }

    /** 暂停收尾：保留半成品，记 PAUSED（带 attempts）并持久化，等用户点「继续」。 */
    private suspend fun pauseTask(
        job: Job,
        repo: io.github.amatsukiichika.iwaram.data.IwaraRepository,
        doc: DocumentFile,
        received: Long,
        total: Long,
        attempt: Int
    ) {
        DiagLog.log("Download", "暂停 ${job.title}：已接收 $received 字节")
        val normalizedTotal = if (total > 0) total else -1L
        DownloadState.upsert(
            DownloadState.Task(
                job.videoId, job.title, received, normalizedTotal,
                state = DownloadState.STATE_PAUSED,
                message = tr(
                    "已暂停（已接收 ${received / 1024} KB）",
                    "Paused (${received / 1024} KB received)"
                ),
                url = job.url, fileName = job.fileName, quality = job.quality,
                docUri = doc.uri.toString()
            )
        )
        runCatching {
            repo.savePendingDownload(
                PendingDownload(
                    job.videoId, job.title, job.fileName, job.quality,
                    received, normalizedTotal,
                    doc.uri.toString(), System.currentTimeMillis(),
                    state = DownloadState.STATE_PAUSED,
                    attempts = attempt
                )
            )
        }
        notify(DONE_NOTIF_ID, doneNotification(true, tr("已暂停：${job.title}", "Paused: ${job.title}")))
    }

    /** 退避等待中的任务展示：列表保持 RUNNING + 通知副标题写清第几次重试，不写失败记录/历史。 */
    private fun retryTask(job: Job, retryNo: Int, waitMs: Long, received: Long) {
        val seconds = (waitMs / 1000L).coerceAtLeast(1L)
        val message = tr(
            "网络异常，${seconds} 秒后自动重试（第 $retryNo/$DOWNLOAD_MAX_AUTO_RETRIES 次）",
            "Network error; retrying automatically in ${seconds}s (retry $retryNo/$DOWNLOAD_MAX_AUTO_RETRIES)"
        )
        DownloadState.upsert(
            DownloadState.Task(
                job.videoId, job.title, received, -1,
                message = message,
                url = job.url, fileName = job.fileName, quality = job.quality
            )
        )
        notify(
            PROGRESS_NOTIF_ID,
            progressNotification(job.title, received, -1, true, retryNote = message)
        )
    }

    /**
     * 退避期间等待 [waitMs]，同时轮询用户的暂停/取消标记（250ms 一次，
     * 好让「暂停/取消」不必等满 30s 才生效）。返回 true = 等待结束可重试。
     */
    private suspend fun awaitRetryWindow(job: Job, waitMs: Long): Boolean {
        var remaining = waitMs
        while (remaining > 0) {
            if (control.hasPending(job.videoId)) return false
            val step = minOf(remaining, RETRY_WAIT_POLL_MS)
            delay(step)
            remaining -= step
        }
        return !control.hasPending(job.videoId)
    }

    /**
     * 最终失败收尾：**只有这里**写 FAILED 记录与下载历史；重试过程中不写，
     * 否则「自动重试」会在历史里留下多条失败。
     */
    private suspend fun finalizeFailure(
        job: Job,
        repo: io.github.amatsukiichika.iwaram.data.IwaraRepository,
        doc: DocumentFile,
        attempts: Int,
        error: Throwable,
        message: String? = null
    ) {
        // 失败不删半成品：保留文件与记录，「重新下载」可从断点继续。
        runCatching {
            repo.savePendingDownload(
                PendingDownload(
                    job.videoId, job.title, job.fileName, job.quality,
                    doc.length(), -1, doc.uri.toString(), System.currentTimeMillis(),
                    state = DownloadState.STATE_FAILED,
                    attempts = attempts
                )
            )
        }
        // Debug 模式开关：用户可见文案（列表/历史/通知）一律人话；
        // 技术细节（HTTP 码/异常串）转投 DiagLog。
        DiagLog.log("Download", "下载失败 ${job.title}: ${error.message ?: error.javaClass.simpleName}")
        val retries = downloadCardFailureReason(DownloadState.STATE_FAILED, attempts) ?: 0
        val text = message ?: if (retries > 0) {
            tr(
                "下载失败（已自动重试 $retries 次，半成品已保留，可重新下载续传）",
                "Download failed after $retries automatic retries (the partial file was kept; you can resume)"
            )
        } else {
            tr(
                "下载失败（半成品已保留，可重新下载续传）",
                "Download failed (the partial file was kept; you can resume)"
            )
        }
        fail(job, repo, text, doc.uri.toString())
    }

    /** 下载成功共用收尾：入库 + 本地缩略图 + 元数据补全 + 历史/待续记录清理 + 完整性校验。
     *  [note] 非空时替换完成文案（例如"本地库已有，未重复下载"）。
     *  [expectedBytes] 服务端声明的文件长度（未知为 null），写进下载历史供之后的完整性校验比对。
     *  [verifyIntegrity] 是否做"下载后静默校验 + 坏文件自动重下"。只有本次真的下载/续传过的文件
     *   才传 true；"本地库已有，跳过下载"的快捷路径必须传 false —— 那份文件可能是用户自己导入
     *   或手动关联的任意格式（mkv/webm 等），按 MP4 结构判坏会误报，自动重下还会先删掉用户文件。 */
    private suspend fun finishDownload(
        job: Job,
        repo: io.github.amatsukiichika.iwaram.data.IwaraRepository,
        doc: DocumentFile,
        note: String? = null,
        expectedBytes: Long? = null,
        verifyIntegrity: Boolean = true
    ) {
        // **若这支视频已经在库里**（同一个 localUri，典型是"文件先被扫描入库、
        // 之后又下载/续传它一次"），就以已有记录为底、只更新下载相关字段。
        // 无条件 upsert 一条 `relPath = null`、`title = 文件名` 的裸记录，
        // 会把扫描时得到的**文件夹路径（relPath）**以及标题等元数据**冲掉**。
        // 这两种都算"已经在库里"，都应当**替换**而不是新增第二条：
        //  ① 同一个 localUri（文件先被扫描入库、之后又下载/续传它一次）；
        //  ② 同一个 iwaraId 但指向一份**已不存在的文件**的旧行 —— 用户删掉文件后重下，
        //     新文件是新 URI，以前这里按 URI 查不到就再插一条：库里同 iwaraId 两条，
        //     byIwaraId(...) 命中哪条看运气，点播可能打到已经消失的 URI。
        val rowByUri = repo.videoRowByUri(doc.uri.toString())
        val staleRow = if (rowByUri == null) {
            repo.videoRowByIwaraId(job.videoId)?.takeIf {
                isStaleRowForSameVideo(
                    rowUri = it.localUri,
                    rowIwaraId = it.iwaraId,
                    newLocalUri = doc.uri.toString(),
                    oldFileExists = documentOf(it.localUri) != null
                )
            }
        } else null
        val baseRow = rowByUri ?: staleRow
        val entity = if (baseRow != null) {
            baseRow.copy(
                // 旧行可能指向已不存在的文件：主键换成新文件的 URI。
                localUri = doc.uri.toString(),
                fileName = doc.name ?: job.fileName,
                iwaraId = baseRow.iwaraId?.takeIf { it.isNotBlank() } ?: job.videoId,
                // 旧行若指向已消失的封面文件（清过缓存/手动删过），必须重生成，别把死路径带进新行。
                localThumbnailPath = if (needsThumbnailRepair(baseRow.localThumbnailPath) { File(it).isFile }) {
                    repo.generateLocalThumbnail(doc.uri)
                } else {
                    baseRow.localThumbnailPath
                },
                // 文件刚落盘，刷新修改日期（也顺带把旧数据的 0 回填成真值）。
                fileModifiedAt = doc.lastModified().coerceAtLeast(0L),
                // 强制重拉一次元数据（刚下完，服务端信息最全）。
                metaFetched = false
            )
        } else {
            VideoEntity(
                localUri = doc.uri.toString(),
                fileName = doc.name ?: job.fileName,
                iwaraId = job.videoId,
                title = job.title,
                author = null,
                authorId = null,
                createdAt = null,
                viewCount = null,
                likeCount = null,
                commentCount = null,
                rating = null,
                thumbnailUrl = null,
                localThumbnailPath = repo.generateLocalThumbnail(doc.uri),
                relPath = null,
                tagsCsv = null,
                metaFetched = false,
                addedAt = System.currentTimeMillis(),
                // 文件修改日期 = "下载时间"排序的排序键（provider 不上报时为 0，排序垫底）。
                fileModifiedAt = doc.lastModified().coerceAtLeast(0L)
            )
        }
        repo.upsertDownloadedVideo(entity)
        if (staleRow != null) {
            // 旧行已被上面这条顶替：主键是 localUri，新行是新键，旧键必须显式删。
            repo.deleteReplacedVideoRow(staleRow)
            // 旧行的封面文件只属于旧行；新行已换成自己的封面，这里顺手把孤儿文件清掉。
            if (staleRow.localThumbnailPath != entity.localThumbnailPath) {
                repo.deleteLocalThumbnailFile(staleRow.localThumbnailPath)
            }
            DiagLog.log("Download", "替换了指向已丢失文件的旧库行：${job.videoId}")
        }
        repo.fetchAndApplyMetadata(entity)

        repo.addDownloadHistory(
            DownloadHistoryEntry(
                job.videoId, job.title, job.quality, System.currentTimeMillis(), true,
                localUri = doc.uri.toString(),
                // 期望长度只信服务端声明的那个；没有就不写，校验时退化为"只查结构"。
                totalBytes = expectedBytes?.takeIf { it > 0L }
            )
        )
        // 下载完成后静默校验一次容器结构（L2）：坏文件自动重下一次，第二次只通知。
        // 挂在"入库之后、STATE_DONE 之前"：文件已经落盘入库是事实，校验只影响完成文案与是否自动重下。
        // verifyIntegrity = false 的快捷路径不校验：文件不是本次下载的产物，见 finishDownload 的参数说明。
        val verdict = if (verifyIntegrity) {
            runCatching { repo.checkDownloadedIntegrity(job.videoId) }
                .getOrDefault(IntegrityVerdict.Unknown)
                .also { repo.markDownloadVerified(job.videoId, it) }
        } else {
            null
        }
        // 每个 videoId 每进程最多自动修一次：第二次仍坏说明重下解决不了（服务端/存储），
        // 再重下只会死循环，改为只通知用户手动处理。
        val alreadyRetried = verdict != null && !integrityRetriedVideoIds.add(job.videoId)
        val autoRedownload = verdict != null && shouldAutoRedownload(verdict, alreadyRetried)
        if (verdict is IntegrityVerdict.Broken) {
            DiagLog.log(
                "Download",
                "完整性校验失败(${verdict.reason.name})，${if (autoRedownload) "自动重下" else "不再自动重下"}：${job.title}"
            )
        }
        val doneMessage = when {
            // 自动修复中：先告诉用户"在重下"，别让"下载完成"和马上又变的进度自相矛盾。
            verdict is IntegrityVerdict.Broken && autoRedownload ->
                tr("文件校验未通过，正在自动重新下载", "The file failed its integrity check; re-downloading")
            verdict is IntegrityVerdict.Broken -> integrityBrokenMessage(verdict)
            note != null -> note
            else -> tr("下载完成", "Download complete")
        }
        DownloadState.upsert(
            DownloadState.Task(
                job.videoId, job.title, state = DownloadState.STATE_DONE,
                message = doneMessage, docUri = doc.uri.toString()
            )
        )
        repo.removePendingDownload(job.videoId)
        // 通知栏带标题、锁屏走脱敏 public 版本（理由见 progressNotification）。
        // 坏文件不再报"下载成功"：自动修复中仍按成功展示（随后会有进度），第二次只报失败。
        notify(DONE_NOTIF_ID, doneNotification(
            autoRedownload || verdict !is IntegrityVerdict.Broken,
            if (verdict is IntegrityVerdict.Broken) {
                tr("$doneMessage：${job.title}", "$doneMessage: ${job.title}")
            } else {
                tr("$doneMessage：${job.title}\n已加入管理器库", "$doneMessage: ${job.title}\nAdded to the library")
            }
        ))
        if (autoRedownload) requestRedownload(this, job.videoId)
    }

    /** 把校验失败的原因翻成用户能看懂的"怎么办"；技术细节（detail）只进 DiagLog。 */
    private fun integrityBrokenMessage(verdict: IntegrityVerdict): String =
        when ((verdict as IntegrityVerdict.Broken).reason) {
            BrokenReason.Missing ->
                tr("下载的文件已丢失，请重新下载", "The downloaded file is missing. Please download it again")
            BrokenReason.Truncated ->
                tr("下载的文件不完整，请重新下载", "The downloaded file is incomplete. Please download it again")
            BrokenReason.ExtraBytes ->
                tr("下载的文件大小异常，请重新下载", "The downloaded file size is wrong. Please download it again")
            BrokenReason.NotMp4 ->
                tr("下载的内容不是有效视频，请重新下载", "The downloaded file is not a valid video. Please download it again")
            BrokenReason.NoMoov, BrokenReason.NoMdat ->
                tr("视频文件结构不完整，请重新下载", "The video file is incomplete. Please download it again")
            BrokenReason.BoxOverflow ->
                tr("视频文件结构异常，请重新下载", "The video file is malformed. Please download it again")
            BrokenReason.NoTrack ->
                tr("视频缺少播放轨道，请重新下载", "The video has no playable track. Please download it again")
        }

    /**
     * 置任务为失败。
     * 失败条目会**留在「正在下载」列表里**（见 activeDownloadTasks），所以必须把
     * `url/fileName/quality/docUri` 一并带上：否则点「移除」时 docUri 为 null，半成品文件就删不掉。
     */
    private fun fail(
        job: Job,
        repo: io.github.amatsukiichika.iwaram.data.IwaraRepository,
        message: String,
        docUri: String? = null
    ) {
        DownloadState.upsert(
            DownloadState.Task(
                job.videoId, job.title, state = DownloadState.STATE_FAILED, message = message,
                url = job.url, fileName = job.fileName, quality = job.quality, docUri = docUri
            )
        )
        runCatching {
            repo.addDownloadHistory(
                DownloadHistoryEntry(
                    job.videoId, job.title, job.quality, System.currentTimeMillis(), false, message
                )
            )
        }
        notify(DONE_NOTIF_ID, doneNotification(false, message))
    }

    /* ---------- 文件定位 ---------- */

    /**
     * 把持久化下来的 uri 字符串解析成一个可用的文件（解析不了 / 已不存在返回 null）。
     * 用途：断点续传时按 `PendingDownload.docUri` 找到**真正的半成品**，以及按本地库记录的
     * localUri 找到已下载的文件。
     */
    private fun documentOf(uriString: String?): DocumentFile? {
        val s = uriString?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { DocumentFile.fromSingleUri(this, Uri.parse(s)) }
            .getOrNull()
            ?.takeIf { it.exists() }
    }

    /**
     * 在子树里按文件名找文件（**深度限制 3 层**，够用且不至于把整棵目录树翻一遍）。
     * 文件名里带 iwara ID 与档位 ⇒ 命中即同一支视频、同一档位。
     */
    private fun findNamedFileInTree(dir: DocumentFile, name: String, depth: Int = 0): DocumentFile? {
        if (depth > 3) return null
        runCatching { dir.findFile(name) }.getOrNull()
            ?.takeIf { it.isFile && it.exists() }
            ?.let { return it }
        val subs = runCatching { dir.listFiles().filter { it.isDirectory } }.getOrNull() ?: return null
        for (sub in subs) findNamedFileInTree(sub, name, depth + 1)?.let { return it }
        return null
    }

    /**
     * **默认下载目录** = 库根目录下的 `IwaraMDownload` 子目录。
     *
     * 命中已有同名目录就直接用；没有就 `createDirectory` 新建；
     * **创建失败回退到根目录** —— 不因为建目录失败让整次下载失败（可用性优先）。
     */
    private fun iwaraDownloadDir(base: DocumentFile): DocumentFile {
        val name = "IwaraMDownload"
        val existing = runCatching { base.findFile(name) }.getOrNull()
        if (existing != null && existing.isDirectory) return existing
        val created = runCatching { base.createDirectory(name) }.getOrNull()
        if (created != null && created.isDirectory) return created
        DiagLog.log("Download", "创建 $name 子目录失败 → 本次回退到下载根目录")
        return base
    }

    /* ---------- 通知 ---------- */

    /**
     * 进度通知：通知栏显示标题，**锁屏走脱敏的 public 版本**。
     *
     * 这个应用的内容是成人向的：标题直接落在锁屏预览里，等于把"用户在看什么"告诉旁边的人。
     * 因此：通知栏（设备已解锁）保留标题，方便同时下多个任务时分辨；同时显式声明
     * VISIBILITY_PRIVATE 并给一份**完全不含内容**的 public 版本 —— 系统在「锁屏隐藏
     * 敏感内容」档位下展示的是它，而不是把正文模糊掉。不显式设置的 PRIVATE 只是默认值，
     * 隐私相关的默认值不该是隐含契约。
     */
    private fun progressNotification(
        title: String,
        received: Long,
        total: Long,
        indeterminate: Boolean,
        /** 自动重试文案（"第 n/3 次"）；非空时附加在进度副标题后面，且不泄露到锁屏 public 版本。 */
        retryNote: String? = null
    ): Notification {
        // 进度值先过纯函数钳制（received>total 会让 progress 超过 max、
        // 超大文件 total/1024 有 Int 溢出风险），详见 notificationProgressBounds。
        val (max, progress) = notificationProgressBounds(received, total)
        val baseDetail = if (total > 0) "${sizeText(received)} / ${sizeText(total)}" else tr("正在准备", "Preparing")
        val detail = if (retryNote.isNullOrBlank()) baseDetail else "$baseDetail · $retryNote"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr("正在下载", "Downloading"))
            .setContentText(title)
            .setSubText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redactedPublicVersion(ongoing = true))
            .setContentIntent(openAppIntent())
            .setProgress(max, progress, indeterminate)
            .build()
    }

    /**
     * 锁屏脱敏版本：**任何字段都不含视频信息**。
     * 系统在「锁屏隐藏敏感内容」档位下展示的是它 —— 这是标题能安全出现在通知栏的前提。
     */
    private fun redactedPublicVersion(ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("IwaraM")
            .setContentText(if (ongoing) tr("正在下载", "Downloading") else tr("下载任务已结束", "Download finished"))
            .setOngoing(ongoing)
            .build()

    /** 通知里的体积文案（不含任何内容标识）。 */
    private fun sizeText(bytes: Long): String =
        if (bytes >= 1_048_576L) "${bytes / 1_048_576L} MB" else "${bytes / 1024L} KB"

    private fun doneNotification(success: Boolean, message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (success) tr("下载", "Download") else tr("下载失败", "Download failed"))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redactedPublicVersion(ongoing = false))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, tr("下载进度", "Download progress"), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = tr("在线视频下载进度通知", "Download progress notification")
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun notify(id: Int, n: Notification) {
        getSystemService(NotificationManager::class.java).notify(id, n)
    }

    companion object {
        private const val CHANNEL_ID = "download_progress"
        private const val PROGRESS_NOTIF_ID = 2001
        private const val DONE_NOTIF_ID = 2002

        /** 进度 UI / 通知的最小刷新间隔（毫秒）；配合读循环的 256KB 分片避免"进度风暴"。 */
        private const val PROGRESS_THROTTLE_MS = 400L

        private const val EXTRA_ID = "video_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_ATTEMPTS = "attempts"
        private const val EXTRA_REPLACE_EXISTING = "replace_existing"

        /** 自动重试退避期间暂停/取消标记的轮询间隔（毫秒）。 */
        private const val RETRY_WAIT_POLL_MS = 250L

        /** 同时下载的文件数上限：只并行不同文件，单文件永远一个 worker（Range 续传语义不变）。 */
        private const val MAX_PARALLEL_DOWNLOADS = 2

        /** 命中 429/403 风控后的整体退避下限（毫秒）：并发降到 1 期间谁都不许开新的下载。 */
        private const val RATE_LIMIT_BACKOFF_MS = 3_000L

        /** 单次读写分片大小：SAF 每次 write 都是跨进程调用，分片太小会让吞吐卡在 IPC 上。 */
        private const val BUFFER_BYTES = 256 * 1024

        /** 并发闸门：正常 2 路，命中风控自适应降到 1 路并在退避窗口后逐级恢复。 */
        private val concurrency = DownloadConcurrency(MAX_PARALLEL_DOWNLOADS)

        /**
         * 每个 videoId 每进程最多自动重下一次（不是"每次失败重下一次"）。
         * 第二次仍坏说明重下解决不了，只通知用户，避免坏文件 → 重下 → 秒完成 → 再校验坏的死循环。
         */
        private val integrityRetriedVideoIds = CopyOnWriteArraySet<String>()

        /**
         * 暂停/取消意图：UI 与 onTimeout 置入，worker 消费一次（见 DownloadControl）。
         * 服务进程内单例，跨组件共享；持久状态仍以 DownloadState/PendingDownload 为准。
         */
        private val control = DownloadControl()

        /** 在跑（已出队、尚未收尾）任务的 videoId 集合：onTimeout 据此批量转暂停。 */
        private val activeJobs = CopyOnWriteArraySet<String>()

        fun start(context: Context, job: Job) {
            // 暂停/取消标记在「主动发起新任务」时清除：如果在 runDownload 出队后清，
            // 排队中的任务在入队前置入的标记会被出队时误清，导致"暂停/取消排队任务"失效。
            control.clear(job.videoId)
            val i = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_ID, job.videoId)
                .putExtra(EXTRA_TITLE, job.title)
                .putExtra(EXTRA_URL, job.url)
                .putExtra(EXTRA_FILE_NAME, job.fileName)
                .putExtra(EXTRA_QUALITY, job.quality)
                .putExtra(EXTRA_ATTEMPTS, job.attempt)
                .putExtra(EXTRA_REPLACE_EXISTING, job.replaceExisting)
            context.startForegroundService(i)
        }

        /**
         * 完整性校验失败后的自动重下：先删旧文件再入队，且标记 replaceExisting=true。
         *
         * 顺序不能反：坏文件的长度可能恰好等于期望长度，worker 里"半成品已收满/本地库已有"
         * 的快捷路径会立刻以同一个坏文件收尾，于是"校验坏 → 重下 → 秒完成 → 再校验坏"成死循环。
         * 删除是幂等的，文件本来就不在也不会报错。
         *
         * 解析下载地址失败时不入队，只在下载列表里留一条失败提示：自动修复没做到，要明确告诉用户。
         */
        fun requestRedownload(context: Context, videoId: String) {
            val app = context.applicationContext as? IwaraApp ?: return
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val repo = app.repository
                val entry = runCatching {
                    repo.loadDownloadHistory().firstOrNull { it.videoId == videoId && it.success }
                }.getOrNull()
                val row = runCatching { repo.videoRowByIwaraId(videoId) }.getOrNull()
                // 先把旧文件删掉；entry 的 localUri 与库行的 localUri 都可能是那份坏文件。
                runCatching { repo.deleteLocalFile(entry?.localUri ?: row?.localUri?.toString()) }
                val target = runCatching {
                    resolveDownloadTarget(repo, videoId, entry?.quality)
                }.getOrNull()
                if (target == null) {
                    DiagLog.log("Download", "自动重下解析地址失败：$videoId")
                    DownloadState.upsert(
                        DownloadState.Task(
                            videoId, entry?.title ?: videoId,
                            state = DownloadState.STATE_FAILED,
                            message = tr(
                                "文件校验失败，请手动重新下载",
                                "Integrity check failed. Please download it again"
                            )
                        )
                    )
                    return@launch
                }
                start(
                    app,
                    Job(
                        videoId = videoId,
                        title = target.title,
                        url = target.url,
                        fileName = target.fileName,
                        quality = target.quality,
                        replaceExisting = true
                    )
                )
            }
        }

        /** 暂停：置意图，读循环在下一次分片边界安全停下（文件、记录都保留）。 */
        fun pause(videoId: String) {
            control.requestPause(videoId)
        }

        /**
         * 取消：排队中的任务出队时被丢弃、不会执行；正好在跑的任务由读循环收尾
         * （关流 → 删半成品 → 清记录）。
         *
         * 调用方（UI）仍要照常清理记录/列表/文件 —— 两边都是幂等的，谁先到谁后到都不会留垃圾。
         * 标记是"一次性事件"，会被消费方读走；下次 [start] 同一支视频也会清掉它，
         * 所以取消过的视频不会在下次下载时被误取消。
         */
        fun cancel(videoId: String) {
            control.requestCancel(videoId)
        }
    }
}

/**
 * 通知栏进度条用的 `(max, progress)`：把字节换算成 KB 档的 Int。
 *
 * 直接写 `setProgress((total / 1024).toInt(), (received / 1024).toInt(), ...)` 有两处隐患：
 *  1. `received > total`（服务端 `Content-Length` 不准、或 Range 续传边界）会让 progress **超过 max**，
 *     部分 ROM 会画出越界进度条；
 *  2. `total > 2^31 × 1024` 时 `total / 1024` 的 `Int` 转换会溢出成负数。
 *
 * 统一 clamp 到 `0..max`，max 夹到 `1..Int.MAX_VALUE`；`total <= 0`（未知长度）时沿用原来的 100。
 * 纯函数，可单测。
 */
internal fun notificationProgressBounds(received: Long, total: Long): Pair<Int, Int> {
    val max = if (total > 0) (total / 1024L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt() else 100
    val progress = (received / 1024L).coerceIn(0L, max.toLong()).toInt()
    return max to progress
}
