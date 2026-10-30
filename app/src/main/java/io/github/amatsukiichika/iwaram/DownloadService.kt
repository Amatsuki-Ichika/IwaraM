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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.amatsukiichika.iwaram.data.DownloadHistoryEntry
import io.github.amatsukiichika.iwaram.data.FileNameSanitizer
import io.github.amatsukiichika.iwaram.data.IwaraClient
import io.github.amatsukiichika.iwaram.data.IwaraSign
import io.github.amatsukiichika.iwaram.data.PendingDownload
import io.github.amatsukiichika.iwaram.data.VideoEntity
import io.github.amatsukiichika.iwaram.data.addDownloadHistory
import io.github.amatsukiichika.iwaram.data.fetchAndApplyMetadata
import io.github.amatsukiichika.iwaram.data.generateLocalThumbnail
import io.github.amatsukiichika.iwaram.data.isSignedUrlExpired
import io.github.amatsukiichika.iwaram.data.loadPendingDownloads
import io.github.amatsukiichika.iwaram.data.removePendingDownload
import io.github.amatsukiichika.iwaram.data.resolveDownloadTarget
import io.github.amatsukiichika.iwaram.data.savePendingDownload
import io.github.amatsukiichika.iwaram.ui.DiagLog
import okhttp3.Request
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

/** 下载任务状态（UI 可观察）；列表只保留最近的任务用于界面提示。 */
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
            val rest = _tasks.value.filterNot { it.videoId == task.videoId }
            _tasks.value = (listOf(task) + rest).take(6)
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
            val known = _tasks.value.map { it.videoId }.toSet()
            val extra = restored.filter { it.videoId !in known }
            if (extra.isNotEmpty()) _tasks.value = (_tasks.value + extra).take(6)
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
        DownloadState.STATE_PAUSED -> "已暂停"
        DownloadState.STATE_FAILED -> "下载失败（可重新下载续传）"
        else -> "下载中断（点「继续」重新下载）"
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
        val startId: Int = 0
    )

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 常驻单 worker（进程内随服务生灭），onStartCommand 只负责入队。
        scope.launch {
            drainDownloadQueue(
                queue,
                execute = { job ->
                    // 在跑任务集合：onTimeout（dataSync 6h 限额）据此把所有在跑任务转暂停。
                    activeJobs.add(job.videoId)
                    try {
                        runDownload(job)
                    } finally {
                        activeJobs.remove(job.videoId)
                    }
                },
                // 排队期间被取消的任务：出队即丢弃，绝不执行（旧的取消只清了记录与列表条目，
                // Channel 里的 job 原封不动，worker 出队后又把任务写回「正在下载」并开下）。
                isCancelled = { job -> cancelRequested.remove(job.videoId) },
                onCancelled = { job -> discardCancelled(job) },
                onIdle = { lastStartId ->
                    // 前台通知随任务走：队列清空后收起常驻通知；随后按 startId 协议自停，
                    // 空跑的服务不再常驻进程。stopSelf(lastStartId) 是 IntentService 同款的
                    // 竞态防护：若自停的同时又有新的 startForegroundService 进来，框架看到
                    // 更新的 startId 会忽略本次停止，新任务照常被 worker 消费；只有当前这个
                    // 确实是最后交付的 startId 时服务才真正停止。前提是任务在 onStartCommand
                    // 内同步入队（入队序 = startId 交付序，见 onStartCommand 注释）。
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
                }
            )
        }
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
                startId = startId
            )
        }
        if (job == null) {
            // startForegroundService 拉起后必须先 startForeground 再移除，
            // 否则五秒内未进入前台会崩（ForegroundServiceDidNotStartInTime）。
            startForeground(PROGRESS_NOTIF_ID, progressNotification("准备下载", 0, 0, true))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 必须在 onStartCommand 内立即 startForeground（worker 可能正忙于上一个任务，
        // 排队中的新任务等不到 5 秒约束）；后续通知由 runDownload 的进度回调接管。
        startForeground(PROGRESS_NOTIF_ID, progressNotification("准备下载", 0, 0, true))
        // 排队任务必须立刻登记进「正在下载」列表并持久化：
        // 否则排队中的任务不可见，且杀后台后会凭空消失（只有 runDownload 出队才登记）。
        DownloadState.upsert(
            DownloadState.Task(
                job.videoId, job.title, 0, -1,
                state = DownloadState.STATE_QUEUED, message = "排队等待",
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
                app.repository.savePendingDownload(
                    PendingDownload(
                        videoId = job.videoId, title = job.title,
                        fileName = job.fileName, quality = job.quality,
                        received = 0, total = -1, docUri = null,
                        updatedAt = System.currentTimeMillis(),
                        state = DownloadState.STATE_QUEUED
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
        activeJobs.forEach { pauseRequested.add(it) }
        notify(
            DONE_NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("下载已暂停")
                .setContentText("已到系统后台运行限额；打开应用点「继续」断点续传")
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
        val dao = app.database.videoDao()
        DownloadState.upsert(DownloadState.Task(job.videoId, job.title, url = job.url, fileName = job.fileName, quality = job.quality))
        // 开跑即写一条 running 记录（docUri 为 null，半成品尚未产生）。
        // 下载中进程被杀时记录留存，重启后按「下载中断」恢复进列表；正常完成/暂停/失败
        // 都会被各自路径覆盖或清除，续传起点始终以磁盘半成品长度为准，不受此记录影响。
        runCatching {
            repo.savePendingDownload(
                PendingDownload(
                    videoId = job.videoId, title = job.title,
                    fileName = job.fileName, quality = job.quality,
                    received = 0, total = -1, docUri = null,
                    updatedAt = System.currentTimeMillis(),
                    state = DownloadState.STATE_RUNNING
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
            fail(job, repo, "尚未设置管理器根目录，请先在设置里选择文件夹")
            return@withContext
        }

        // 断点续传：先找同名半成品（暂停/失败留下），再决定 Range 起点。
        val baseRoot = withContext(Dispatchers.IO) {
            runCatching { DocumentFile.fromTreeUri(this@DownloadService, Uri.parse(rootUri)) }.getOrNull()
        }
        if (baseRoot == null) {
            fail(job, repo, "无法访问下载目录（可能未授予写入权限），请在设置里重新选择下载目录或恢复默认")
            return@withContext
        }
        // 默认情形把落盘目录下沉到根目录下的 IwaraMDownload；自定义目录则原样使用。
        val root = if (customRoot == null) iwaraDownloadDir(baseRoot) else baseRoot
        val pending = repo.loadPendingDownloads().firstOrNull { it.videoId == job.videoId }
        val sanitized = FileNameSanitizer.sanitize(job.fileName)

        // 排队久了签名地址会过期（地址带 expires，见 isSignedUrlExpired）。出队时先验一次，
        // 过期就用**同一个 videoId + 同一个档位**重新解析 —— 否则这次下载必然以 401/403 收场，
        // 而用户除了在列表里点「重新下载」没有任何办法（这正是以前排队多任务时的表现）。
        var sourceUrl = job.url
        if (isSignedUrlExpired(sourceUrl, System.currentTimeMillis())) {
            DiagLog.log("Download", "排队期间签名地址已过期，重新解析：${job.title}")
            val fresh = runCatching { resolveDownloadTarget(repo, job.videoId, job.quality) }
            if (fresh.isFailure) {
                fail(job, repo, "下载地址已过期，请重新下载")
                return@withContext
            }
            sourceUrl = fresh.getOrThrow().url
        }

        // 库里**已经有这支视频**（同一个 iwaraId）而且文件还在 ⇒ 直接以已有文件收尾，
        // 不再下载、更不新建第二份。不检查"这支视频是否已存在"时，同名/异名都会再复制一份进库，
        // 而 finishDownload 又会用一条 relPath=null 的裸记录去 upsert ⇒ 同一个视频两条记录
        // （一条保留扫描得到的文件夹路径、一条没有）。
        val libraryRow = dao.byIwaraId(job.videoId)
        val libraryDoc = documentOf(libraryRow?.localUri)
        if (libraryDoc != null && (libraryDoc.length() ?: 0L) > 0L) {
            DiagLog.log("Download", "已在本地库，跳过重复下载：${job.title}")
            finishDownload(job, repo, dao, libraryDoc, note = "本地库已有，未重复下载")
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
        var resumeFrom = 0L
        var doc: DocumentFile? = null
        if (target != null) {
            val d = documentOf(target.docUri)
            if (d != null) {
                doc = d
                resumeFrom = target.length
            }
        }
        if (doc == null) {
            // 没有可续传的文件：在下载根目录新建（文件名带 ID，便于日后再次识别 / 续传）。
            doc = runCatching { root.createFile("video/mp4", sanitized) }.getOrNull()
        }
        if (doc == null) {
            fail(job, repo, "无法在下载目录创建文件（可能未授予写入权限），请在设置里重新选择下载目录或恢复默认")
            return@withContext
        }

        // 读循环里被用户取消时置位：走完 use 块（关流）之后再删文件、清记录。
        var cancelledByUser = false
        try {
            // mediaHttp 对带 expires 的地址自动附加 X-Version 签名并带浏览器头，
            // 与播放同栈，避免下载地址被 CDN 拒签；这里再做一次协议补全防御。
            val url = IwaraSign.httpsUrl(sourceUrl.trim())
            val reqBody = Request.Builder()
                .url(url)
                .get()
            if (resumeFrom > 0) reqBody.header("Range", "bytes=$resumeFrom-")
            IwaraClient.mediaHttp.newCall(reqBody.build()).execute().use { resp ->
                val total: Long
                var received = 0L
                val input: java.io.InputStream
                val append: Boolean
                if (resp.code == 206 && resumeFrom > 0) {
                    // 续传成功：Content-Range: bytes start-end/total。
                    val contentRange = resp.header("Content-Range")
                    total = contentRange?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                    input = resp.body?.byteStream() ?: throw RuntimeException("空响应")
                    append = true
                    received = resumeFrom
                    if (total > 0 && resumeFrom >= total) {
                        // 半成品其实已经齐了（上次在收尾阶段被打断）——直接走完成路径。
                        input.close()
                        finishDownload(job, repo, dao, doc)
                        return@withContext
                    }
                    DiagLog.log("Download", "续传 ${job.title}：从 $resumeFrom 字节继续（总 $total）")
                } else {
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    // 服务器不支持 Range（200 全量）或没有半成品：截断重下，绝不把两次内容拼在一起。
                    total = resp.body?.contentLength() ?: -1L
                    input = resp.body?.byteStream() ?: throw RuntimeException("空响应")
                    append = false
                }
                val mode = if (append) "wa" else "w"
                val output = contentResolver.openOutputStream(doc.uri, mode)
                    ?: throw RuntimeException("无法写入文件")
                input.use { ins ->
                    output.use { outs ->
                        val buf = ByteArray(64 * 1024)
                        // 进度限流：每收 64KB 就 upsert + 发通知会在高速网络下造成
                        // 每秒数百次 StateFlow 重组与通知栏 IPC（"进度风暴"）。最密 400ms 一次，
                        // 收尾由 finishDownload() 兜底刷到完成态，观感无差别、开销降两个数量级。
                        var lastProgressAt = 0L
                        while (true) {
                            // 用户在排队/下载中点「取消」：直接收尾，不把文件下完。
                            if (cancelRequested.remove(job.videoId)) {
                                cancelledByUser = true
                                break
                            }
                            // 服务被销毁（scope.cancel）时也必须停：阻塞的 read 不响应协程取消，
                            // 只检查暂停标记会让它在服务销毁后继续把文件读完，最后卡在
                            // finishDownload 的第一个挂起点上抛 CancellationException ——
                            // 磁盘上留下一个既没入库、也不在续传列表里的孤儿文件。
                            currentCoroutineContext().ensureActive()
                            if (pauseRequested.remove(job.videoId)) {
                                // 暂停：关流保留半成品，任务记为 PAUSED 并持久化，等用户点「继续」。
                                outs.flush()
                                DiagLog.log("Download", "暂停 ${job.title}：已接收 $received 字节")
                                val t = DownloadState.Task(
                                    job.videoId, job.title, received,
                                    if (total > 0) total else -1,
                                    state = DownloadState.STATE_PAUSED,
                                    message = "已暂停（已接收 ${received / 1024} KB）",
                                    url = job.url, fileName = job.fileName, quality = job.quality,
                                    docUri = doc.uri.toString()
                                )
                                DownloadState.upsert(t)
                                repo.savePendingDownload(
                                    PendingDownload(
                                        job.videoId, job.title, job.fileName, job.quality,
                                        received, if (total > 0) total else -1,
                                        doc.uri.toString(), System.currentTimeMillis(),
                                        state = DownloadState.STATE_PAUSED
                                    )
                                )
                                notify(DONE_NOTIF_ID, doneNotification(true, "已暂停：${job.title}"))
                                return@withContext
                            }
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
                    }
                }
            }

            if (cancelledByUser) {
                // 取消 = "这个文件我不要了"：记录、列表条目、半成品一起清。
                // 与 UI 的 removePendingCompletely 同语义，两边都幂等。
                runCatching { repo.removePendingDownload(job.videoId) }
                DownloadState.remove(job.videoId)
                runCatching { doc.delete() }
                DiagLog.log("Download", "已取消：${job.title}")
                return@withContext
            }

            finishDownload(job, repo, dao, doc)
        } catch (e: CancellationException) {
            // 服务被销毁/协程被取消时原样传播：不发"下载失败"通知、不写失败记录
            // （半成品文件保留，符合断点续传预期）。
            throw e
        } catch (e: Throwable) {
            // 失败不删半成品：保留文件与记录，「重新下载」可从断点继续。
            runCatching {
                repo.savePendingDownload(
                    PendingDownload(
                        job.videoId, job.title, job.fileName, job.quality,
                        doc.length(), -1, doc.uri.toString(), System.currentTimeMillis(),
                        state = DownloadState.STATE_FAILED
                    )
                )
            }
            // Debug 模式开关：用户可见文案（列表/历史/通知）一律人话；
            // 技术细节（HTTP 码/异常串）转投 DiagLog。
            DiagLog.log("Download", "下载失败 ${job.title}: ${e.message ?: e.javaClass.simpleName}")
            fail(job, repo, "下载失败（半成品已保留，可重新下载续传）", doc.uri.toString())
        }
    }

    /** 下载成功共用收尾：入库 + 本地缩略图 + 元数据补全 + 历史/待续记录清理。
     *  [note] 非空时替换完成文案（例如"本地库已有，未重复下载"）。 */
    private suspend fun finishDownload(
        job: Job,
        repo: io.github.amatsukiichika.iwaram.data.IwaraRepository,
        dao: io.github.amatsukiichika.iwaram.data.VideoDao,
        doc: DocumentFile,
        note: String? = null
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
        val rowByUri = dao.byUri(doc.uri.toString())
        val staleRow = if (rowByUri == null) {
            dao.byIwaraId(job.videoId)?.takeIf {
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
                localThumbnailPath = baseRow.localThumbnailPath ?: repo.generateLocalThumbnail(doc.uri),
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
        dao.upsert(entity)
        if (staleRow != null) {
            // 旧行已被上面这条顶替：主键是 localUri，新行是新键，旧键必须显式删。
            dao.deleteVideoWithTags(staleRow)
            DiagLog.log("Download", "替换了指向已丢失文件的旧库行：${job.videoId}")
        }
        repo.fetchAndApplyMetadata(entity)

        val doneMessage = note ?: "下载完成"
        DownloadState.upsert(
            DownloadState.Task(
                job.videoId, job.title, state = DownloadState.STATE_DONE,
                message = doneMessage, docUri = doc.uri.toString()
            )
        )
        repo.removePendingDownload(job.videoId)
        repo.addDownloadHistory(
            DownloadHistoryEntry(
                job.videoId, job.title, job.quality, System.currentTimeMillis(), true,
                localUri = doc.uri.toString()
            )
        )
        // 通知栏带标题、锁屏走脱敏 public 版本（理由见 progressNotification）。
        notify(DONE_NOTIF_ID, doneNotification(true, "$doneMessage：${job.title}\n已加入管理器库"))
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
        indeterminate: Boolean
    ): Notification {
        // 进度值先过纯函数钳制（received>total 会让 progress 超过 max、
        // 超大文件 total/1024 有 Int 溢出风险），详见 notificationProgressBounds。
        val (max, progress) = notificationProgressBounds(received, total)
        val detail = if (total > 0) "${sizeText(received)} / ${sizeText(total)}" else "正在准备"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在下载")
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
            .setContentText(if (ongoing) "正在下载" else "下载任务已结束")
            .setOngoing(ongoing)
            .build()

    /** 通知里的体积文案（不含任何内容标识）。 */
    private fun sizeText(bytes: Long): String =
        if (bytes >= 1_048_576L) "${bytes / 1_048_576L} MB" else "${bytes / 1024L} KB"

    private fun doneNotification(success: Boolean, message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (success) "下载" else "下载失败")
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
                CHANNEL_ID, "下载进度", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在线视频下载进度通知"
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

        /** 进度 UI / 通知的最小刷新间隔（毫秒）；配合读循环的 64KB 分片避免"进度风暴"。 */
        private const val PROGRESS_THROTTLE_MS = 400L

        private const val EXTRA_ID = "video_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_QUALITY = "quality"

        /** 暂停标记：UI 置入，读循环每 64KB 检查一次；服务进程内单例，跨组件共享。 */
        private val pauseRequested = CopyOnWriteArraySet<String>()

        /** 在跑（已出队、尚未收尾）任务的 videoId 集合：onTimeout 据此批量转暂停。 */
        private val activeJobs = CopyOnWriteArraySet<String>()

        /**
         * 取消标记：UI 置入。
         * worker 出队前消费一次（排队中取消），读循环每 64KB 再消费一次（正好在跑时取消）。
         */
        private val cancelRequested = CopyOnWriteArraySet<String>()

        fun start(context: Context, job: Job) {
            // 暂停/取消标记在「主动发起新任务」时清除：如果在 runDownload 出队后清，
            // 排队中的任务在入队前置入的标记会被出队时误清，导致"暂停/取消排队任务"失效。
            pauseRequested.remove(job.videoId)
            cancelRequested.remove(job.videoId)
            val i = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_ID, job.videoId)
                .putExtra(EXTRA_TITLE, job.title)
                .putExtra(EXTRA_URL, job.url)
                .putExtra(EXTRA_FILE_NAME, job.fileName)
                .putExtra(EXTRA_QUALITY, job.quality)
            context.startForegroundService(i)
        }

        /** 暂停：置标记，读循环在下一次 64KB 检查时安全停下（文件、记录都保留）。 */
        fun pause(videoId: String) {
            pauseRequested.add(videoId)
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
            cancelRequested.add(videoId)
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
