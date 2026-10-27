package io.github.amatsukiichika.iwaram.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.amatsukiichika.iwaram.ui.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/* ---------- 下载历史 / 待续传 / 播放列表缓存 / 观看历史（原 IwaraRepository 方法，扩展函数形式） ---------- */
    /** 下载历史：最新在前，同一视频只保留最近一次记录，上限 100 条。 */
fun IwaraRepository.addDownloadHistory(entry: DownloadHistoryEntry) = synchronized(prefsJsonLock) {
        val list = loadDownloadHistory().filterNot { it.videoId == entry.videoId }.toMutableList()
        list.add(0, entry)
        prefs.edit().putString("download_history_cache", gson.toJson(list.take(100))).apply()
    }

fun IwaraRepository.loadDownloadHistory(): List<DownloadHistoryEntry> =
        prefs.getString("download_history_cache", null)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                runCatching {
                    gson.fromJson<List<DownloadHistoryEntry>>(it, downloadHistoryType)
                }.getOrNull()
            }
            .orEmpty()

    /** 移除某视频的下载历史记录（「移除任务 / 移除任务和文件」用）。 */
fun IwaraRepository.removeDownloadHistory(videoId: String) = synchronized(prefsJsonLock) {
        val rest = loadDownloadHistory().filterNot { it.videoId == videoId }
        prefs.edit().putString("download_history_cache", gson.toJson(rest)).apply()
    }

    /** 未完成下载（暂停/失败）持久化：重启后「下载列表」还能看到并可续传/重新下载。 */
fun IwaraRepository.savePendingDownload(p: PendingDownload) = synchronized(prefsJsonLock) {
        val list = loadPendingDownloads().filterNot { it.videoId == p.videoId }.toMutableList()
        list.add(0, p)
        prefs.edit().putString("download_pending_cache", gson.toJson(list.take(50))).apply()
    }

fun IwaraRepository.removePendingDownload(videoId: String) = synchronized(prefsJsonLock) {
        val rest = loadPendingDownloads().filterNot { it.videoId == videoId }
        prefs.edit().putString("download_pending_cache", gson.toJson(rest)).apply()
    }

fun IwaraRepository.loadPendingDownloads(): List<PendingDownload> =
        prefs.getString("download_pending_cache", null)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                runCatching {
                    gson.fromJson<List<PendingDownload>>(it, pendingDownloadType)
                }.onFailure { e ->
                    // 解析失败若无痕迹地清空整个续传列表，会变成"数据不对但查不出来"。
                    // 这里只记原文长度，绝不打原始内容（内含文件路径等本地信息）。
                    DiagLog.log("Repo", "待续传记录解析失败(${e.javaClass.simpleName})，已忽略 ${it.length} 字符的旧记录")
                }.getOrNull()
            }
            .orEmpty()

/** 旧版本用的全局 key；只用于在 init 里清除，不参与读写。 */
internal const val LEGACY_PLAYLISTS_CACHE_KEY = "playlists_cache"

/**
 * 播放列表离线缓存的 prefs key，**按账号分区**。
 *
 * 全局 key 的问题是：A 断网登录过、B 再断网登录时，缓存里装的是 A 的播放列表，
 * 而界面无从判断这是谁的。userId 为空（会话尚未恢复）时返回 null ⇒ 不读也不写：
 * 拿不到账号归属时，宁可没有缓存也不冒串号的风险。
 */
internal fun playlistsCacheKey(userId: String): String? =
    userId.takeIf { it.isNotBlank() }?.let { "playlists_cache_$it" }

    /** 播放列表离线缓存（JSON），断网时「我的」页展示。 */
fun IwaraRepository.savePlaylistsCache(userId: String, json: String) {
        playlistsCacheKey(userId)?.let { prefs.edit().putString(it, json).apply() }
    }

fun IwaraRepository.loadPlaylistsCache(userId: String): String? =
        playlistsCacheKey(userId)?.let { key -> prefs.getString(key, null)?.takeIf { it.isNotBlank() } }

    /**
     * 记一条观看（fire-and-forget）。同 key 重看经唯一索引 REPLACE 原地覆盖 = 天然置顶；
     * 超上限裁最旧。key 推不出（无 iwaraId 且无 localUri）直接放弃，不写脏行。
     */
fun IwaraRepository.recordWatch(
        iwaraId: String?,
        localUri: String?,
        title: String,
        author: String?,
        authorId: String?,
        thumbnail: String?,
        positionMs: Long = 0,
        durationMs: Long = 0,
    ) {
        val key = watchHistoryKey(iwaraId, localUri) ?: return
        historyScope.launch {
            runCatching {
                historyDao.upsert(
                    WatchHistoryEntity(
                        videoKey = key,
                        iwaraId = iwaraId?.takeIf { it.isNotBlank() },
                        localUri = localUri?.takeIf { it.isNotBlank() },
                        title = title,
                        author = author,
                        authorId = authorId,
                        thumbnail = thumbnail,
                        isLocal = !localUri.isNullOrBlank(),
                        lastWatchAt = System.currentTimeMillis(),
                        positionMs = positionMs.coerceAtLeast(0),
                        durationMs = durationMs.coerceAtLeast(0),
                    )
                )
                val count = historyDao.count()
                // 裁剪收进单条 SQL（逐条 deleteById 会给每条一个事务）。
                if (count > watchHistoryLimit) {
                    historyDao.deleteOldest(count - watchHistoryLimit)
                }
            }.onFailure { e ->
                DiagLog.log("Repo", "观看记录写入失败(${e.javaClass.simpleName}): ${e.message}")
            }
        }
    }

    /**
     * 更新进度（fire-and-forget）。记录不存在时忽略——进度更新只跟随首帧那条记录，
     * 不凭空造行；durationMs > 0 时顺带补写总时长（首帧时常常还拿不到）。
     */
fun IwaraRepository.updateWatchProgress(key: String, positionMs: Long, durationMs: Long) {
        historyScope.launch {
            runCatching {
                val row = historyDao.byKey(key) ?: return@launch
                historyDao.upsert(
                    row.copy(
                        positionMs = positionMs.coerceAtLeast(0),
                        durationMs = if (durationMs > 0) durationMs else row.durationMs,
                        lastWatchAt = System.currentTimeMillis(),
                    )
                )
            }.onFailure { e ->
                DiagLog.log("Repo", "观看进度更新失败(${e.javaClass.simpleName}): ${e.message}")
            }
        }
    }

fun IwaraRepository.observeWatchHistory(): Flow<List<WatchHistoryEntity>> = historyDao.observeAll()

suspend fun IwaraRepository.deleteWatchHistory(id: Long) = withContext(Dispatchers.IO) { historyDao.deleteById(id) }

suspend fun IwaraRepository.clearWatchHistory() = withContext(Dispatchers.IO) { historyDao.clearAll() }

    /** 历史行点击本地条目时查"文件还在不在库里"。 */
suspend fun IwaraRepository.findVideoByUri(uri: String): VideoEntity? = withContext(Dispatchers.IO) { dao.byUri(uri) }
