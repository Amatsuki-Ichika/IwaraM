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
import kotlinx.coroutines.CancellationException
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

/* ---------- 元数据拉取与本地缩略图（原 IwaraRepository 方法，扩展函数形式） ---------- */
    /* ---------- 元数据拉取与落库 ---------- */

suspend fun IwaraRepository.fetchAndApplyMetadata(video: VideoEntity) {
        val id = video.iwaraId ?: return
        try {
            val resp = IwaraApi.service.video(id)
            if (resp.title.isNullOrBlank() && resp.id == null) return

            val tags = resp.tags.orEmpty().mapNotNull { it.id }.filter { it.isNotBlank() }
            val updated = video.copy(
                title = resp.title?.takeIf { it.isNotBlank() } ?: video.title,
                author = resp.user?.name,
                authorId = resp.user?.username ?: resp.user?.id,
                createdAt = parseIwaraDate(resp.createdAt),
                viewCount = resp.numViews,
                likeCount = resp.numLikes,
                commentCount = resp.numComments,
                rating = resp.rating,
                thumbnailUrl = resp.file?.id?.let { IwaraApi.thumbnailUrl(it) },
                tagsCsv = tags.joinToString(","),
                metaFetched = true
            )
            // 四步写收进单个 Room 事务（upsert → 清旧关联 → 重建标签/关联），
            // 中途崩溃不再留下孤儿 cross-ref 或半更新的条目。
            dao.upsertVideoWithTags(
                video = updated,
                tags = tags.map { TagEntity(id = it, name = it) },
                refs = tags.map { VideoTagCrossRef(video.localUri, it) }
            )
        } catch (e: Exception) {
            // 取消必须原样上抛。`CancellationException` 是 `IllegalStateException` 的子类，
            // 被这个 catch 吞掉后扫描协程会"正常结束"，服务算不出自己是被取消的；
            // 更糟的是取消被记成"元数据拉取失败"，日志里全是假故障。
            if (e is CancellationException) throw e
            // 网络失败时保留本地条目，等待后续重试。
            // 无痕迹时"检索完成但元数据缺失"类问题无法归因，这里落 DiagLog。
            DiagLog.log("Repo", "元数据拉取失败 ${video.iwaraId}: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    /** 手动指定某条视频的 iwara ID 后调用。 */
suspend fun IwaraRepository.linkIwaraId(video: VideoEntity, id: String) {
        withContext(Dispatchers.IO) {
            dao.upsert(video.copy(iwaraId = id, metaFetched = false))
            fetchAndApplyMetadata(video.copy(iwaraId = id))
        }
    }

    /* ---------- 本地缩略图 ---------- */

    /** 本地封面目录名：持久目录是 filesDir/thumbs；旧版本误用 cacheDir/thumbs（清缓存即丢）。 */
    internal const val LOCAL_THUMBS_DIR = "thumbs"

    /**
     * 库里的本地封面是否需要重建 —— 没有路径，或路径指向的文件已不存在。
     *
     * 触发场景：用户清了应用缓存（旧版本封面就在 cacheDir 里）、系统回收了缓存、
     * 换机恢复后库里留着死路径。抽成纯函数是为了把"什么算丢失"钉在测试里
     * （app/src/test/java/.../data/ThumbnailRepairTest.kt）。
     */
    internal fun needsThumbnailRepair(storedPath: String?, fileExists: (String) -> Boolean): Boolean =
        storedPath.isNullOrBlank() || !fileExists(storedPath)

    /** 封面持久目录（filesDir/thumbs）：放 filesDir 而非 cacheDir，清缓存不会丢封面。 */
    internal fun localThumbsDir(context: Context): File =
        File(context.filesDir, LOCAL_THUMBS_DIR).apply { mkdirs() }

    /** 该路径是否落在 [dir] 里 —— 迁移/删除只碰自己目录下的文件，路径来自库也不越界。 */
    internal fun isInsideDir(path: String, dir: File): Boolean =
        path.startsWith(dir.absolutePath + File.separator)

    /** 行被清理时顺手删掉它的封面文件；只删持久目录里的（缓存目录留给系统回收）。 */
    internal fun IwaraRepository.deleteLocalThumbnailFile(path: String?) {
        val p = path?.takeIf { it.isNotBlank() } ?: return
        if (!isInsideDir(p, localThumbsDir(context))) return
        runCatching { File(p).delete() }
    }

    /**
     * 把旧版本写在 cacheDir/thumbs 的封面搬到 filesDir/thumbs，并改写库里的路径。
     *
     * 背景：0.2.3-rc.1 及更早把封面写进 cacheDir —— 用户清一次应用缓存封面就永久消失，
     * 而库里还存着已删文件的路径。扫描现在会按"缺即补"自愈，这里再把还能救的旧文件搬出来，
     * 老用户升级后不必等一次全量重扫就能恢复封面。
     *
     * @return 迁移成功并改写库的条数（0 = 无事可做）。
     */
    internal suspend fun IwaraRepository.migrateLegacyLocalThumbs(): Int = withContext(Dispatchers.IO) {
        val legacyDir = File(context.cacheDir, LOCAL_THUMBS_DIR)
        if (!legacyDir.isDirectory) return@withContext 0
        val targetDir = localThumbsDir(context)
        val moved = ArrayList<Pair<String, String?>>()
        for (row in dao.getAllThumbRefs()) {
            val path = row.localThumbnailPath?.takeIf { it.isNotBlank() } ?: continue
            val src = File(path)
            if (!isInsideDir(path, legacyDir) || !src.isFile) continue
            val dest = File(targetDir, src.name)
            // 目标已存在 = 上一次迁移中断留下的那份，直接认它，只改写库路径。
            val ok = dest.isFile || src.renameTo(dest) || runCatching {
                src.copyTo(dest, overwrite = true)
                src.delete()
            }.getOrDefault(false)
            if (!ok) continue
            moved.add(row.localUri to dest.absolutePath)
        }
        if (moved.isNotEmpty()) dao.setLocalThumbnailBatch(moved)
        // 搬空后删掉旧目录；还有库外文件就留着，交给系统清缓存。
        runCatching { if (legacyDir.listFiles().isNullOrEmpty()) legacyDir.delete() }
        moved.size
    }

fun IwaraRepository.generateLocalThumbnail(uri: Uri): String? {
        return try {
            val mmr = MediaMetadataRetriever()
            val frame: Bitmap? = try {
                mmr.setDataSource(context, uri)
                val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                // 从多个时间点取样，遇到过暗（开头黑场）的帧就往后找，最多取 3 帧，控制成本。
                pickDecentFrame(mmr, durationMs)
            } finally {
                mmr.release()
            }
            if (frame == null) {
                null
            } else {
                // filesDir/thumbs：清应用缓存不会删掉封面（旧版本写 cacheDir，见 migrateLegacyLocalThumbs）。
                val dir = localThumbsDir(context)
                val f = File(dir, "thumb_${uri.hashCode()}.jpg")
                f.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                frame.recycle()
                f.absolutePath
            }
        } catch (e: Throwable) {
            // 缩略图失败（含个别大分辨率/损坏文件导致的 OOM）不阻断扫描，回退到在线封面或占位图。
            // 留痕以便区分"文件损坏"与"解析不到封面"。
            DiagLog.log("Repo", "本地缩略图生成失败 uri#${uri.hashCode()}: ${e.javaClass.simpleName}")
            null
        }
    }

    /** 依次在 1s / 20% / 50% 处取样，返回第一帧不是近黑的帧；全部过暗则返回最后一帧。 */
    private fun pickDecentFrame(mmr: MediaMetadataRetriever, durationMs: Long): Bitmap? {
        val durUs = durationMs * 1000
        val candidates = buildList {
            add(1_000_000L)
            if (durUs > 0) {
                add(durUs / 5)
                add(durUs / 2)
            }
        }.filter { durUs <= 0L || it < durUs }.distinct()

        var last: Bitmap? = null
        for (t in candidates) {
            val f = grabFrame(mmr, t) ?: continue
            last?.recycle()
            last = f
            if (!isMostlyBlack(f)) return f
        }
        return last
    }

    /** 优先抽缩小帧（降低大分辨率视频的内存占用，避免 OOM 崩溃），API 26 回退整帧。 */
    private fun grabFrame(mmr: MediaMetadataRetriever, timeUs: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
        } else {
            mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }

    /**
     * 缩放后按平均亮度判断是否为近黑帧（阈值约 12/255）。
     *
     * 注意两点：
     *  1. 逐像素 `getPixel` 每次都是 JNI 调用 —— 32×32 即 1024 次、扫 1000 个视频就是
     *     300 万次 JNI。这里用**一次 `getPixels`** 把 1024 个像素读进 IntArray，再在 Kotlin 里循环。
     *  2. `Bitmap.createScaledBitmap` 在入参**恰好就是 32×32** 时会返回**同一个实例**，
     *     无条件 `recycle()` 会把调用方那一帧回收掉。故加 `!==` 判断（当前调用路径不会命中）。
     */
    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val pixels = IntArray(32 * 32)
        small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        var sum = 0L
        for (p in pixels) {
            sum += (android.graphics.Color.red(p) +
                android.graphics.Color.green(p) +
                android.graphics.Color.blue(p)) / 3
        }
        if (small !== bitmap) small.recycle()
        return (sum / 1024) < 12
    }
