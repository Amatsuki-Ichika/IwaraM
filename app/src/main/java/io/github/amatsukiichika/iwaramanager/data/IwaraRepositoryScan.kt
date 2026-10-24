package io.github.amatsukiichika.iwaramanager.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.amatsukiichika.iwaramanager.ui.DiagLog
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

/* ---------- 扫描导入（原 IwaraRepository 方法，扩展函数形式） ---------- */
    /* ---------- 扫描导入 ---------- */

    /**
     * 扫描 SAF 树下的所有视频文件，逐个入库并拉取 iwara 元数据。
     */
suspend fun IwaraRepository.importFolder(treeUri: Uri, onProgress: (done: Int, total: Int) -> Unit) {
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
            setRootName(root.name)
            val files = mutableListOf<Pair<DocumentFile, String>>()
            collectVideoFiles(root, "", files)

            // 一次性把已入库的条目读进内存判重 —— 对**每个文件**都 `dao.byUri(uri)`
            //   查一次库是 N 次查询。这里一次性取 URI + iwaraId + fileName（最小投影），
            //   既做判重，也供下面「老条目补 ID」用。
            val existing = dao.getAllRefs().associateBy { it.localUri }
            // 同一批里重复出现时只处理一次（防御性：与原实现的 set.add 语义一致）。
            val handled = HashSet<String>(files.size)

            var done = 0
            // 新文件**攒批落库**（每 [importBatchSize] 个一次批量 upsert）。
            //   逐文件 upsert 会在千级库扫描时写库千次，每次写库都触发 observeAll 重发射 ⇒
            //   全链路（重算 / 列表 diff / 作者页过滤）被放大千遍。攒批后每批只发一次失效；
            //   元数据拉取是逐条网络请求（天然节流），放在批落库之后跑 —— 行先可见、资料后到，
            //   最终态与原行为一致（中断时未拉到资料的行由 fetchAllMissing 兜底补拉）。
            val batch = ArrayList<Pair<VideoEntity, String?>>(importBatchSize) // row → 待拉元数据的 iwaraId
            suspend fun flushBatch() {
                if (batch.isEmpty()) return
                dao.upsertAll(batch.map { it.first })
                for ((v, id) in batch) {
                    if (id != null) fetchAndApplyMetadata(v)
                }
                batch.clear()
            }
            for ((f, rel) in files) {
                val name = f.name ?: continue
                val uri = f.uri.toString()
                if (!handled.add(uri)) {
                    done++
                    onProgress(done, files.size)
                    continue
                }
                val row = existing[uri]
                if (row == null) {
                    val id = parseIwaraId(name)
                    val local = VideoEntity(
                        localUri = uri,
                        fileName = name,
                        iwaraId = id,
                        title = name,
                        author = null,
                        authorId = null,
                        createdAt = null,
                        viewCount = null,
                        likeCount = null,
                        commentCount = null,
                        rating = null,
                        thumbnailUrl = null,
                        localThumbnailPath = generateLocalThumbnail(f.uri),
                        relPath = rel.ifBlank { null },
                        tagsCsv = null,
                        metaFetched = false,
                        addedAt = System.currentTimeMillis()
                    )
                    batch.add(local to id)
                    if (batch.size >= importBatchSize) flushBatch()
                } else if (row.iwaraId.isNullOrBlank()) {
                    // 老条目补救：更早的解析器要求 ID 必须含数字，纯字母 ID 全被拒 ⇒ 这些文件早已入库、
                    //   iwaraId 为 null、metaFetched=false。而导入判重是按 URI 命中的，
                    //   所以**只修解析器、重扫一遍也不会重新解析它们**。
                    //   这里对"已存在但没 ID"的条目就地补一次解析 ⇒ 重扫一次即可，
                    //   历史数据自动修复，不需要删库重来。
                    parseIwaraId(row.fileName)?.let { fixedId ->
                        dao.setIwaraId(uri, fixedId)
                        // 重新读出完整实体（upsert 需要整行），再补元数据。
                        dao.byUri(uri)?.let { fetchAndApplyMetadata(it) }
                    }
                }
                done++
                onProgress(done, files.size)
            }
            flushBatch()
        }
    }

    /** 重扫已保存的根目录（用于刷新时增量更新）。 */
suspend fun IwaraRepository.rescanStoredRoot(onProgress: (Int, Int) -> Unit) {
        getRoot()?.let { importFolder(Uri.parse(it), onProgress) }
    }

    /** 为所有尚未拉取元数据的视频补拉信息（含手动录入 ID 后的重试）。 */
suspend fun IwaraRepository.fetchAllMissing(onProgress: (Int, Int) -> Unit) {
        withContext(Dispatchers.IO) {
            // 查询下推到 SQL（getAll() 整表读进内存再 filter 开销更大）。
            val pending = dao.getMissingMetadata()
            var done = 0
            for (v in pending) {
                fetchAndApplyMetadata(v)
                done++
                onProgress(done, pending.size)
            }
        }
    }

    /** 已入库视频总数。 */
suspend fun IwaraRepository.countVideos(): Int = dao.count()

    private fun collectVideoFiles(
        dir: DocumentFile,
        rel: String,
        out: MutableList<Pair<DocumentFile, String>>
    ) {
        for (f in dir.listFiles()) {
            when {
                f.isDirectory -> collectVideoFiles(
                    f,
                    if (rel.isEmpty()) (f.name ?: "") else "$rel/${f.name}",
                    out
                )
                f.isFile && isVideo(f.name) -> out.add(f to rel)
            }
        }
    }

    private fun isVideo(name: String?): Boolean {
        if (name == null) return false
        val n = name.lowercase()
        return n.endsWith(".mp4") || n.endsWith(".webm") ||
            n.endsWith(".mkv") || n.endsWith(".mov") || n.endsWith(".m4v")
    }
