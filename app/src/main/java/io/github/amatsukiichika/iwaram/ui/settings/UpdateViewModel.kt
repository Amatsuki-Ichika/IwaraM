package io.github.amatsukiichika.iwaram.ui.settings

import android.app.Application
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.amatsukiichika.iwaram.IwaraApp
import io.github.amatsukiichika.iwaram.data.GitHubRelease
import io.github.amatsukiichika.iwaram.data.GitHubReleaseClient
import io.github.amatsukiichika.iwaram.data.GitHubReleaseResult
import io.github.amatsukiichika.iwaram.data.IwaraRepository
import io.github.amatsukiichika.iwaram.data.UpdateChannel
import io.github.amatsukiichika.iwaram.data.UpdateInstallException
import io.github.amatsukiichika.iwaram.data.UpdateInstallFailure
import io.github.amatsukiichika.iwaram.data.UpdateInstaller
import io.github.amatsukiichika.iwaram.data.needsUpdate
import io.github.amatsukiichika.iwaram.data.parse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 与 IwaraRepository 更新设置存储值对应的检查频率。 */
private const val UPDATE_INTERVAL_EACH_START = "each_start"
private const val UPDATE_INTERVAL_DAILY = "daily"
private const val UPDATE_INTERVAL_WEEKLY = "weekly"
private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
private const val WEEK_MILLIS = 7L * DAY_MILLIS

/** 更新页与更新弹窗共享的状态机（同一时刻只有一个状态）。 */
sealed interface UpdateUiState {
    /** 空闲：没有进行中的检查/下载，也没有待处理的版本提示。 */
    data object Idle : UpdateUiState

    /** 正在请求 GitHub Releases。 */
    data object Checking : UpdateUiState

    /** 手工检查完成且确实已是最新（自动检查保持静默，不进入此状态）。 */
    data class UpToDate(val currentVersion: String) : UpdateUiState

    /** 发现高于本机的新版本。 */
    data class Available(val release: GitHubRelease) : UpdateUiState

    /** 正在下载 APK；progress 为 null 表示总大小未知（无限进度）。 */
    data class Downloading(val progress: Float?) : UpdateUiState

    /** 下载并校验完成，已交给系统安装器（或等待重开安装器）。 */
    data class ReadyToInstall(val file: File) : UpdateUiState

    /** 任一环节失败；httpCode 在能拿到 HTTP 状态码时带上（当前客户端不上报，保留扩展位）。 */
    data class Failed(val kind: UpdateFailureKind, val httpCode: Int? = null) : UpdateUiState
}

/** 更新失败的界面可区分类型（文案由 UI 映射，data 层不出现中文）。 */
enum class UpdateFailureKind { NETWORK, SHA_MISMATCH, INSTALL, CANCELLED, UNKNOWN }

/**
 * 应用内更新的状态持有者。
 *
 * 设置项（通道 / 自动检查 / 频率 / 上次检查 / 忽略版本）都以 IwaraRepository 的
 * StateFlow 为准，本类只做"读 + 写回仓库"，不另建缓存；下载与安装交给 UpdateInstaller。
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    // 无 DI 框架：非组合代码按现有 ViewModel 模式从进程级 IwaraApp 取仓库（见 AndroidManifest 的 android:name）。
    private val repository: IwaraRepository = (application as IwaraApp).repository

    /** 当前安装包版本名（PackageManager 读取；读取失败时为问号）。 */
    val currentVersionName: String

    /** 当前安装包 versionCode（安装器据此判断能否原地升级）。 */
    private val currentVersionCode: Int

    init {
        val packageInfo = runCatching {
            application.packageManager.getPackageInfo(application.packageName, 0)
        }.getOrNull()
        currentVersionName = packageInfo?.versionName ?: "?"
        currentVersionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it).toInt() } ?: 0
    }

    /** 更新通道（"stable" / "beta"），直接来自仓库。 */
    val channel: StateFlow<String> = repository.updateChannel

    /** 是否自动检查更新。 */
    val autoCheck: StateFlow<Boolean> = repository.updateAutoCheck

    /** 检查频率（"each_start" / "daily" / "weekly"）。 */
    val interval: StateFlow<String> = repository.updateInterval

    /** 已忽略的版本名（空串 = 未忽略）。 */
    val ignoredVersion: StateFlow<String> = repository.updateIgnoredVersion

    /** 上次成功检查的时间戳（毫秒）。 */
    val lastCheck: StateFlow<Long> = repository.updateLastCheck

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)

    /** 更新流程的当前状态；设置页与弹窗都观察它。 */
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _latestRelease = MutableStateFlow<GitHubRelease?>(null)

    /** 最近一次成功检查到的最新 Release（与 state 解耦：关闭弹窗后仍可展示/忽略）。 */
    val latestRelease: StateFlow<GitHubRelease?> = _latestRelease.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingRelease: GitHubRelease? = null
    private var autoCheckStarted = false

    /** 切换更新通道；仓库会清空忽略项与上次检查时间，界面状态同步回到空闲。 */
    fun setChannel(value: String) {
        if (repository.updateChannel.value == value) return
        // 在途检查的结果属于旧通道：先取消，避免它回来覆盖新通道的状态。
        checkJob?.cancel()
        checkJob = null
        pendingRelease = null
        // 通道变了，旧通道的"最新版本"不再有参考意义，等下次检查再刷新。
        _latestRelease.value = null
        _state.value = UpdateUiState.Idle
        repository.setUpdateChannel(value)
    }

    /** 自动检查开关（直接写仓库）。 */
    fun setAutoCheck(value: Boolean) {
        repository.setUpdateAutoCheck(value)
    }

    /** 检查频率（直接写仓库）。 */
    fun setInterval(value: String) {
        repository.setUpdateInterval(value)
    }

    /**
     * 检查更新。
     *
     * @param manual 手动检查：查询失败进入 [UpdateUiState.Failed] 供重试；API 成功但没有可用
     *   版本、或已是最新，都进入 [UpdateUiState.UpToDate]（不再误报网络失败），且不套用
     *   "忽略此版本"；自动检查则全程静默。
     */
    fun checkForUpdates(manual: Boolean) {
        val current = _state.value
        if (current is UpdateUiState.Checking || current is UpdateUiState.Downloading) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            // 通道在发起请求时固定：请求期间切换通道的话，这份结果属于旧通道，必须丢弃。
            val checkedChannel = repository.updateChannel.value
            val result = try {
                GitHubReleaseClient.fetch(channelOf(checkedChannel), currentVersionName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                GitHubReleaseResult.Failure
            }
            // 关闭弹窗会取消检查；即使客户端把取消吞成 IOException，这里也立即退出，避免把状态写回。
            currentCoroutineContext().ensureActive()
            // setChannel 会取消本任务；这里再兜住"响应已返回、取消尚未生效"的窗口。
            if (repository.updateChannel.value != checkedChannel) return@launch
            val mapping = mapCheckResult(result, manual, currentVersionName)
            // API 调用本身成功（含"没有可用版本"）就记录检查时间，自动检查的频率据此计算；
            // 只认"查到了版本"会让健康但没有新版本的响应在每次启动时都重新打 GitHub。
            if (mapping.recordLastCheck) {
                repository.setUpdateLastCheck(System.currentTimeMillis())
            }
            val release = (result as? GitHubReleaseResult.Found)?.release
            if (release == null) {
                pendingRelease = null
                _state.value = mapping.terminalState ?: UpdateUiState.Idle
                return@launch
            }
            _latestRelease.value = release
            if (!isNewerThanCurrent(release)) {
                pendingRelease = null
                _state.value = if (manual) UpdateUiState.UpToDate(currentVersionName) else UpdateUiState.Idle
                return@launch
            }
            if (!manual && isIgnored(release)) {
                _state.value = UpdateUiState.Idle
                return@launch
            }
            pendingRelease = release
            _state.value = UpdateUiState.Available(release)
        }
    }

    /**
     * 启动时调用一次：按「自动检查」开关与检查频率决定是否静默检查。
     *
     * 频率语义：每次启动 = 本次一定查；每天/每周 = 距上次成功检查满 24h/7d 才查。
     * 进程内重复调用无副作用。
     */
    fun autoCheckIfDue() {
        if (autoCheckStarted) return
        autoCheckStarted = true
        if (!repository.updateAutoCheck.value) return
        if (!isAutoCheckDue()) return
        checkForUpdates(manual = false)
    }

    /** 忽略当前待处理版本：写仓库并回到空闲；下次自动检查不会再提示它。 */
    fun ignoreCurrentVersion() {
        val release = pendingRelease
            ?: (_state.value as? UpdateUiState.Available)?.release
            ?: _latestRelease.value
            ?: return
        repository.setUpdateIgnoredVersion(release.versionName)
        pendingRelease = null
        _state.value = UpdateUiState.Idle
    }

    /** 取消忽略：清空仓库里的忽略记录。 */
    fun clearIgnoredVersion() {
        repository.setUpdateIgnoredVersion(null)
    }

    /**
     * 下载并安装：流式下载 → SHA-256 校验 → 交给系统安装器。
     *
     * 已处于 [UpdateUiState.ReadyToInstall] 时直接重开安装器，不重复下载。
     */
    fun downloadAndInstall() {
        val current = _state.value
        if (current is UpdateUiState.Downloading || current is UpdateUiState.Checking) return
        if (current is UpdateUiState.ReadyToInstall) {
            install(current.file)
            return
        }
        val release = pendingRelease ?: _latestRelease.value ?: return
        pendingRelease = release
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _state.value = UpdateUiState.Downloading(null)
            val context = getApplication<Application>()
            val file = try {
                UpdateInstaller.download(context, release) { fraction ->
                    // 用户取消后不再回写进度，避免把空闲态覆盖回下载中。
                    if (_state.value is UpdateUiState.Downloading) {
                        _state.value = UpdateUiState.Downloading(fraction)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: UpdateInstallException) {
                _state.value = UpdateUiState.Failed(failureKindOf(failure.kind))
                return@launch
            } catch (_: Exception) {
                _state.value = UpdateUiState.Failed(UpdateFailureKind.UNKNOWN)
                return@launch
            }
            currentCoroutineContext().ensureActive()
            _state.value = UpdateUiState.ReadyToInstall(file)
            install(file)
        }
    }

    /** 重试：已确定待装版本时重新下载/安装；否则重新检查。 */
    fun retry() {
        _state.value = UpdateUiState.Idle
        if (pendingRelease != null) {
            downloadAndInstall()
        } else {
            checkForUpdates(manual = true)
        }
    }

    /** 取消进行中的下载（弹窗「取消」）。 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateUiState.Idle
    }

    /** 关闭当前弹窗（「稍后」「关闭」或点遮罩）：取消进行中的检查/下载并回到空闲。 */
    fun dismiss() {
        checkJob?.cancel()
        checkJob = null
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateUiState.Idle
    }

    /** 拉起系统安装器；失败按失败类型进入 [UpdateUiState.Failed]。 */
    private fun install(file: File) {
        try {
            UpdateInstaller.install(getApplication<Application>(), file)
            // 安装器已拉起：保留 ReadyToInstall，用户从安装器返回后仍可关闭或重开。
            _state.value = UpdateUiState.ReadyToInstall(file)
        } catch (failure: UpdateInstallException) {
            _state.value = UpdateUiState.Failed(failureKindOf(failure.kind))
        }
    }

    /** 自动检查是否到期（每次启动 / 距上次满 24h / 满 7d）。 */
    private fun isAutoCheckDue(): Boolean {
        val now = System.currentTimeMillis()
        val last = repository.updateLastCheck.value
        return when (repository.updateInterval.value) {
            UPDATE_INTERVAL_EACH_START -> true
            UPDATE_INTERVAL_DAILY -> last <= 0L || now < last || now - last >= DAY_MILLIS
            UPDATE_INTERVAL_WEEKLY -> last <= 0L || now < last || now - last >= WEEK_MILLIS
            else -> true
        }
    }

    /** 远端版本是否高于本机（versionCode 判据 + 同号换正式版，见 data 层 needsUpdate）。 */
    private fun isNewerThanCurrent(release: GitHubRelease): Boolean {
        val remoteVersionCode = parse(release.versionName)?.versionCode() ?: return false
        return needsUpdate(currentVersionName, release.versionName, currentVersionCode, remoteVersionCode)
    }

    /** 自动检查是否应静默跳过该版本（手工检查不看忽略列表）。 */
    private fun isIgnored(release: GitHubRelease): Boolean {
        val ignored = repository.updateIgnoredVersion.value.trim()
        if (ignored.isEmpty()) return false
        return ignored.equals(release.versionName, ignoreCase = true) ||
            ignored.equals(release.tagName, ignoreCase = true)
    }

    /** 仓库通道字符串 -> 枚举；未知值按正式版处理。 */
    private fun channelOf(value: String): UpdateChannel =
        if (value == "beta") UpdateChannel.BETA else UpdateChannel.STABLE

    /** data 层失败类型 -> 界面失败类型。 */
    private fun failureKindOf(kind: UpdateInstallFailure): UpdateFailureKind = when (kind) {
        UpdateInstallFailure.NETWORK -> UpdateFailureKind.NETWORK
        UpdateInstallFailure.SHA_MISMATCH -> UpdateFailureKind.SHA_MISMATCH
        UpdateInstallFailure.INSTALL -> UpdateFailureKind.INSTALL
        UpdateInstallFailure.UNKNOWN -> UpdateFailureKind.UNKNOWN
    }
}

/**
 * 检查结果 → 状态写入决策（纯函数，单测见 UpdateCheckResultMappingTest）。
 *
 * - [GitHubReleaseResult.Found]：[terminalState] 为 null，由调用方继续比较版本新旧；
 * - [GitHubReleaseResult.None]：API 调用成功但没有可用版本 —— 手工检查显示「已是最新」，
 *   自动检查保持静默（[UpdateUiState.Idle]），不再误报网络失败；
 * - [GitHubReleaseResult.Failure]：手工检查报网络失败，自动检查静默。
 *
 * [recordLastCheck] 仅在 API 调用本身成功（Found / None）时为 true，
 * 自动检查的每日/每周频率据此计算。
 */
internal data class CheckResultMapping(
    val terminalState: UpdateUiState?,
    val recordLastCheck: Boolean
)

/** 见 [CheckResultMapping]：把一次查询结果映射成状态与"是否记录检查时间"。 */
internal fun mapCheckResult(
    result: GitHubReleaseResult,
    manual: Boolean,
    currentVersionName: String
): CheckResultMapping = when (result) {
    is GitHubReleaseResult.Found -> CheckResultMapping(terminalState = null, recordLastCheck = true)
    GitHubReleaseResult.None -> CheckResultMapping(
        terminalState = if (manual) UpdateUiState.UpToDate(currentVersionName) else UpdateUiState.Idle,
        recordLastCheck = true
    )
    GitHubReleaseResult.Failure -> CheckResultMapping(
        terminalState = if (manual) UpdateUiState.Failed(UpdateFailureKind.NETWORK) else UpdateUiState.Idle,
        recordLastCheck = false
    )
}
