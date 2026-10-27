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

/* ---------- 扫描导入（原 IwaraRepository 方法，扩展函数形式） ---------- */
    /* ---------- 扫描导入 ---------- */

    /**
     * 扫描 SAF 树下的所有视频文件，逐个入库并拉取 iwara 元数据。
     */
suspend fun IwaraRepository.importFolder(treeUri: Uri, onProgress: (done: Int, total: Int) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
            if (root == null) {
                // 授权失效（重装、清数据、用户在系统里撤销）必须让调用方知道：
                // 静默 return 会让扫描接着报「检索完成：本地共 N 个视频」（N 往往是 0），
                // 用户看到"检索完成"却一条没多，无从归因。
                DiagLog.log("Repo", "扫描根目录不可访问（SAF 授权可能已失效）")
                return@withContext false
            }
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
            // 旧数据回填「文件修改日期」的待写列表（fileModifiedAt == 0 的已入库行）。
            //   攒批最后用一个事务提交 —— 逐行 UPDATE 会让 observeAll 重发射上千次。
            val mtimeBackfill = ArrayList<Pair<String, Long>>()
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
                        addedAt = System.currentTimeMillis(),
                        // 新文件取文件修改日期 —— "下载时间"排序的排序键
                        //   （部分 provider 返回 0 = 未知，排序中垫底、组内退回 addedAt）。
                        fileModifiedAt = f.lastModified().coerceAtLeast(0L)
                    )
                    batch.add(local to id)
                    if (batch.size >= importBatchSize) flushBatch()
                } else {
                    // 已入库行的修改日期一次性回填（旧数据全是 0）。已有值的不再重读 ——
                    //   省一次每文件 SAF binder 往返，稳态重扫零额外成本（代价：文件被原地
                    //   覆盖后修改日期不刷新；下载完成路径会刷新，覆盖下载走不到这里）。
                    if (row.fileModifiedAt == 0L) {
                        val mtime = f.lastModified()
                        if (mtime > 0) mtimeBackfill.add(uri to mtime)
                    }
                    if (row.iwaraId.isNullOrBlank()) {
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
                }
                done++
                onProgress(done, files.size)
            }
            flushBatch()
            if (mtimeBackfill.isNotEmpty()) dao.setFileModifiedAtBatch(mtimeBackfill)

            // 收尾：清掉「文件确实已经不在了」的库行。
            // 只检查这次扫描没见到的行 —— 稳态重扫时这个集合是空的，零开销。
            // 一个文件都没扫到时整体跳过：根目录暂时不可达不能当成"用户把库删光了"。
            val unseen = existing.values.filter { it.localUri !in handled }
            if (files.isNotEmpty() && unseen.isNotEmpty()) {
                val pruned = pruneMissingRows(unseen) { uri ->
                    // 查询本身失败（授权暂失）当作"还在"：宁可留着一条脏行，也不误删库索引。
                    runCatching {
                        DocumentFile.fromSingleUri(context, Uri.parse(uri))?.exists() == true
                    }.getOrDefault(true)
                }
                if (pruned.isNotEmpty()) {
                    DiagLog.log("Repo", "扫描收尾：清理了 ${pruned.size} 条指向已不存在文件的库行")
                }
            }
            true
        }
    }

/**
 * 扫描收尾要删掉哪些行：**这次没扫到** 且 **文件确实不存在**。
 *
 * 两个条件是"与"不是"或"。扫描没看到但文件还在（换了根目录、目录暂时不可达）绝不能删 ——
 * 那删的是用户的库索引。抽成纯函数是为了把这条判据钉在测试里。
 */
internal fun rowsToPrune(unseen: List<VideoRefRow>, fileExists: (String) -> Boolean): List<VideoRefRow> =
    unseen.filterNot { fileExists(it.localUri) }

/**
 * 删除指向已不存在文件的行（连同标签关联）。
 *
 * 扫描以前只 upsert 不删除，于是文件被文件管理器删掉后旧行一直留着：在线卡片的「已下载」
 * 标记、本地库查询、下载查重都还信任它，点播直接失败。
 */
private suspend fun IwaraRepository.pruneMissingRows(
    unseen: List<VideoRefRow>,
    fileExists: (String) -> Boolean
): List<VideoRefRow> {
    val doomed = rowsToPrune(unseen, fileExists)
    for (ref in doomed) {
        dao.byUri(ref.localUri)?.let { dao.deleteVideoWithTags(it) }
    }
    return doomed
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
