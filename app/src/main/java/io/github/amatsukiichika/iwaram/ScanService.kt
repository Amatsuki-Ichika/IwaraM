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
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.amatsukiichika.iwaram.i18n.tr
import io.github.amatsukiichika.iwaram.ui.DiagLog
import java.util.concurrent.atomic.AtomicBoolean
import io.github.amatsukiichika.iwaram.data.countVideos
import io.github.amatsukiichika.iwaram.data.fetchAllMissing
import io.github.amatsukiichika.iwaram.data.importFolder

/** 前台服务与 UI 共享的扫描进度；null 代表当前未在扫描。 */
object ScanState {
    data class Progress(val phase: String, val done: Int, val total: Int)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    fun set(p: Progress?) {
        _progress.value = p
    }
}

/**
 * 后台检索前台服务：扫描本地目录并逐个抓取 iwara 元数据，
 * 在通知栏展示进度，完成后发送提醒通知并自动停止。
 */
class ScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 重入保护：单次扫描可能是分钟量级（本地目录遍历 + 逐个抓取 iwara 元数据）。
     * `onStartCommand` 每次无条件 `scope.launch { runScan() }` 会在连点「检索」或从通知栏
     * 重入时**并发跑多遍扫描**（重复遍历目录、重复打接口、进度条互相打架）。
     * 用一个原子标志保证同一时刻只有一条扫描在跑。
     */
    private val running = AtomicBoolean(false)

    /**
     * 进度节流：`importFolder` / `fetchAllMissing` 的回调是**逐文件**触发的，
     * 每个文件都写一次 `ScanState`（UI 订阅）+ 发一次通知（Binder IPC）会造成"进度风暴"，
     * 且下游 `VideoViewModel` 每个 tick 还要重算一遍全库。
     * 口径与 [DownloadService] 的 `PROGRESS_THROTTLE_MS`（400ms）统一。
     * 首尾两次（`done <= 1` / `done >= total`）强制发，保证进度条不会永远停在 98%。
     */
    private var lastProgressAt = 0L

    /** 推进进度：写 UI 状态 + 刷通知，按 [PROGRESS_THROTTLE_MS] 限流（首尾强制）。 */
    private fun pushProgress(phase: String, done: Int, total: Int) {
        val now = SystemClock.elapsedRealtime()
        val isBoundary = done <= 1 || done >= total
        if (!isBoundary && now - lastProgressAt < PROGRESS_THROTTLE_MS) return
        lastProgressAt = now
        ScanState.set(ScanState.Progress(phase, done, total))
        notify(PROGRESS_NOTIF_ID, progressNotification("$phase  $done / $total", done, total, false))
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running.compareAndSet(false, true)) {
            // 已在扫描：仍需调用 startForeground 以满足 startForegroundService 契约，
            // 并按当前进度刷新通知（而不是把它重置回"准备扫描…"）。
            val p = ScanState.progress.value
            startForeground(
                PROGRESS_NOTIF_ID,
                if (p != null && p.total > 0) {
                    progressNotification("${p.phase}  ${p.done} / ${p.total}", p.done, p.total, false)
                } else {
                    progressNotification(tr("扫描进行中…", "Scanning…"), 0, 0, true)
                }
            )
            return START_NOT_STICKY
        }
        startForeground(PROGRESS_NOTIF_ID, progressNotification(tr("准备扫描…", "Preparing scan…"), 0, 0, true))
        ScanState.set(ScanState.Progress(tr("准备扫描", "Preparing scan"), 0, 0))
        scope.launch { runScan() }
        return START_NOT_STICKY
    }

    /**
     * Android 15+ 的 dataSync「6 小时/24h」限额对扫描同样生效；正常扫描远短于限额，这里是兜底：
     * 立即收起前台并自停（stopSelf → onDestroy 取消扫描协程，ScanState 停在最后进度，
     * 再点「检索」会重新走一遍）。onTimeout 之后系统只给几秒宽限，不做任何慢操作。
     */
    override fun onTimeout(startId: Int) {
        // 到限额等于这次扫描结束了：进度状态必须当场归零，否则 UI 的
        // `running = progress != null` 会一直是 true，「正在检索」永远转圈。
        ScanState.set(null)
        running.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        // 进度状态的唯一正常清空点是 [finish]，而取消/销毁路径到不了那里（runScan 对
        // CancellationException 原样上抛）。不在这里兜底，UI 会永久停在「正在检索」，
        // 而且 running 重入锁不放开，再点「检索」也起不来。
        ScanState.set(null)
        running.set(false)
        super.onDestroy()
    }

    private suspend fun runScan() {
        val repo = (application as IwaraApp).repository
        val root = repo.getRoot()
        if (root.isNullOrBlank()) {
            finish(false, tr("尚未设置根目录，请先在设置里选择文件夹", "No library folder is set. Choose one in Settings first"))
            return
        }
        try {
            val imported = repo.importFolder(Uri.parse(root)) { done, total ->
                pushProgress(tr("扫描文件", "Scanning files"), done, total)
            }
            if (!imported) {
                // 根目录打不开（SAF 授权失效等）：必须报错。以前这里静默继续，
                // 最后照样提示"检索完成：本地共 0 个视频"。
                finish(
                    false,
                    tr(
                        "无法访问该文件夹（授权可能已失效），请在设置里重新选择",
                        "Cannot access the folder (permission may have expired). Choose it again in Settings"
                    )
                )
                return
            }
            repo.fetchAllMissing { done, total ->
                pushProgress(tr("抓取信息", "Fetching metadata"), done, total)
            }
            val count = repo.countVideos()
            finish(true, tr("检索完成：本地共 $count 个视频", "Scan complete: $count local videos"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 服务销毁/协程取消时原样传播：不发"检索中断"的假失败通知。
            throw e
        } catch (e: Exception) {
            // Debug 模式开关：通知文案人话化，技术细节（异常 message/类名）进 DiagLog。
            DiagLog.log("Scan", "检索中断: ${e.message ?: e.javaClass.simpleName}")
            finish(false, tr("检索中断，可重新扫描", "Scan interrupted. You can scan again"))
        }
    }

    private fun finish(success: Boolean, message: String) {
        ScanState.set(null)
        running.set(false) // 放开重入锁，允许下一次扫描
        notify(DONE_NOTIF_ID, doneNotification(success, message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun progressNotification(title: String, done: Int, total: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (total > 0) "$done / $total" else tr("正在处理，请稍候…", "Processing, please wait…"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .setProgress(if (total > 0) total else 100, if (total > 0) done else 0, indeterminate)
            .build()

    private fun doneNotification(success: Boolean, message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (success) tr("检索完成", "Scan complete") else tr("检索失败", "Scan failed"))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
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
                CHANNEL_ID, tr("检索进度", "Scan progress"), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = tr("后台检索本地视频与 iwara 元数据的进度通知", "Background scan progress notification")
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun notify(id: Int, n: Notification) {
        getSystemService(NotificationManager::class.java).notify(id, n)
    }

    companion object {
        private const val CHANNEL_ID = "scan_progress"
        private const val PROGRESS_NOTIF_ID = 1001
        private const val DONE_NOTIF_ID = 1002

        /** 进度 UI / 通知的最小刷新间隔（毫秒）。与 DownloadService.PROGRESS_THROTTLE_MS 同值同口径。 */
        private const val PROGRESS_THROTTLE_MS = 400L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ScanService::class.java))
        }
    }
}
