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
                val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
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
