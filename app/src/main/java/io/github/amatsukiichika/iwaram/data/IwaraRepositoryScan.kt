package io.github.amatsukiichika.iwaram.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
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

/**
 * 递归扫描深度上限。SAF 提供方可能把整块存储挂成一棵树（含指回上层的目录），用户也可能把
 * 库根目录选在很浅的公共目录上；无上限递归会遍历整台设备，环状结构还会无限深入。
 * 12 层远超正常的「库 / 作者 / 作品集 / 文件」嵌套，触顶的目录被跳过并留一条诊断日志。
 */
internal const val MAX_SCAN_DEPTH = 12

/**
 * 扫描根目录的可访问性探测结果（见 [probeTreeRoot]）。
 *
 * `DocumentFile.fromTreeUri` 在 minSdk 26 上永远返回对象，`listFiles()` 又会把
 * SecurityException 吞成空列表 —— 只有显式查询根文档才能区分"授权失效/目录没了"与"空目录"。
 */
internal enum class RootProbe { Accessible, Denied, Missing, Unknown }

/** [importFolder] 的结局：调用方必须区分"打不开"与"打开了但没视频"，不能再报假成功。 */
enum class ImportFolderResult { Success, Inaccessible, Empty }

/**
 * 由探测结果与扫描到的文件数判定结局（纯函数，可单测）：
 * - 授权被撤销 / 根目录已不存在 ⇒ [ImportFolderResult.Inaccessible]（提示重新选择文件夹）；
 * - 根能访问但一个视频都没扫到 ⇒ [ImportFolderResult.Empty]（警告，而不是"检索完成：本地共 N 个"；
 *   N 是旧计数，会把"一个都没扫到"粉饰成成功）；
 * - 其余 ⇒ [ImportFolderResult.Success]。
 */
internal fun folderImportResult(probe: RootProbe, scannedFiles: Int): ImportFolderResult = when {
    probe == RootProbe.Denied || probe == RootProbe.Missing -> ImportFolderResult.Inaccessible
    scannedFiles == 0 -> ImportFolderResult.Empty
    else -> ImportFolderResult.Success
}

/**
 * 显式探测 SAF 树根是否可访问（调用方需在 IO 线程执行）。
 *
 * 直接查一次根文档：SecurityException = 授权被撤销；查询成功但没有行 = 目录已不存在。
 * provider 不认这个查询时返回 [RootProbe.Unknown]，交给后续实际扫描判断（至少不误报授权失效）。
 */
internal fun probeTreeRoot(context: Context, treeUri: Uri): RootProbe {
    val documentUri = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
    }.getOrNull() ?: return RootProbe.Unknown
    return try {
        val found = context.contentResolver
            .query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() }
        when (found) {
            true -> RootProbe.Accessible
            false, null -> RootProbe.Missing
        }
    } catch (e: SecurityException) {
        RootProbe.Denied
    } catch (e: java.io.FileNotFoundException) {
        // 部分 provider 对已删除的文档抛 FileNotFoundException，而不是返回空游标。
        RootProbe.Missing
    } catch (e: Exception) {
        // 含 provider 不支持该查询：此刻无法判定，按未知处理（不据此误报"授权失效"）。
        DiagLog.log("Repo", "扫描根目录探测失败，按未知处理：" + e.javaClass.simpleName)
        RootProbe.Unknown
    }
}

/* ---------- 扫描导入（原 IwaraRepository 方法，扩展函数形式） ---------- */
    /* ---------- 扫描导入 ---------- */

    /**
     * 扫描 SAF 树下的所有视频文件，逐个入库并拉取 iwara 元数据。
     *
     * @return [ImportFolderResult.Inaccessible] = 根目录打不开（授权撤销/目录被删），
     *   [ImportFolderResult.Empty] = 能打开但一个视频都没扫到，[ImportFolderResult.Success] = 正常。
     */
suspend fun IwaraRepository.importFolder(treeUri: Uri, onProgress: (done: Int, total: Int) -> Unit): ImportFolderResult {
        return withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
            if (root == null) {
                // 授权失效（重装、清数据、用户在系统里撤销）必须让调用方知道：
                // 静默 return 会让扫描接着报「检索完成：本地共 N 个视频」（N 往往是 0），
                // 用户看到"检索完成"却一条没多，无从归因。
                DiagLog.log("Repo", "扫描根目录不可访问（SAF 授权可能已失效）")
                return@withContext ImportFolderResult.Inaccessible
            }
            // fromTreeUri 在 API 21+ 只会构造对象（minSdk 26 下永远非 null），listFiles 又会
            // 把异常吞成空列表 —— 必须显式探测根文档，否则撤销授权/删除目录都会"扫完 0 个"报成功。
            val probe = probeTreeRoot(context, treeUri)
            if (probe == RootProbe.Denied || probe == RootProbe.Missing) {
                DiagLog.log("Repo", "扫描根目录不可访问（SAF 授权失效或目录已删除）")
                return@withContext ImportFolderResult.Inaccessible
            }
            setRootName(root.name)
            val files = mutableListOf<Pair<DocumentFile, String>>()
            collectVideoFiles(root, "", files)

            // 一次性把已入库的条目读进内存判重 —— 对**每个文件**都 `dao.byUri(uri)`
            //   查一次库是 N 次查询。这里一次性取 URI + iwaraId + fileName（最小投影），
            //   既做判重，也供下面「老条目补 ID」用。
            val existing = dao.getAllRefs().associateBy { it.localUri }
            // 封面路径单读一份：已入库行不重建封面，但路径可能已经失效 —— 只有拿到路径
            //   才能判断"要不要补"（详见下面已存在行分支的封面自愈）。
            val thumbRefs = dao.getAllThumbRefs().associateBy { it.localUri }
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
                // 元数据先全部拉完（网络），再在**一个事务**里落库。以前逐条 applyFetchedMetadata，
                // 每条都是一次独立事务 ⇒ 一批 N 个文件发 N 次 videos/tags 失效，主线程按整库重算 N 次；
                // 单事务批写后每批只发一次失效（与上面的 upsertAll 同一批语义）。
                val writes = batch.mapNotNull { (v, id) ->
                    if (id != null) fetchMetadataWrite(v) else null
                }
                if (writes.isNotEmpty()) dao.applyFetchedMetadataBatch(writes)
                batch.clear()
            }
            // 旧数据回填「文件修改日期」的待写列表（fileModifiedAt == 0 的已入库行）。
            //   攒批最后用一个事务提交 —— 逐行 UPDATE 会让 observeAll 重发射上千次。
            val mtimeBackfill = ArrayList<Pair<String, Long>>()
            // 封面自愈的待写列表：清缓存后老行的封面路径指向已删文件，重扫要按"缺即补"重建。
            //   与其他回填一样攒批，末尾一个事务提交。
            val thumbBackfill = ArrayList<Pair<String, String?>>()
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
                    // 封面自愈：库里记的本地封面为空、或文件已不存在（清缓存/系统回收缓存/
                    //   换机恢复）就重新抽帧。只对缺失行解码，稳态重扫零额外成本。
                    if (needsThumbnailRepair(thumbRefs[uri]?.localThumbnailPath) { File(it).isFile }) {
                        generateLocalThumbnail(f.uri)?.let { thumbBackfill.add(uri to it) }
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
            if (thumbBackfill.isNotEmpty()) dao.setLocalThumbnailBatch(thumbBackfill)

            // 收尾：清掉「文件确实已经不在了」的库行。
            // 只检查这次扫描没见到的行 —— 稳态重扫时这个集合是空的，零开销。
            // 一个文件都没扫到时整体跳过：根目录暂时不可达不能当成"用户把库删光了"。
            val unseen = existing.values.filter { it.localUri !in handled }
            if (files.isNotEmpty() && unseen.isNotEmpty()) {
                val pruned = pruneMissingRows(unseen) { uri ->
                    // 无法确定的（授权暂失、provider 查询异常）一律当作"还在"：
                    // 宁可留着一条脏行，也不误删库索引。
                    documentExists(context, Uri.parse(uri))
                }
                if (pruned.isNotEmpty()) {
                    DiagLog.log("Repo", "扫描收尾：清理了 ${pruned.size} 条指向已不存在文件的库行")
                }
            }
            folderImportResult(probe, files.size)
        }
    }

/**
 * 扫描收尾要删掉哪些行：**这次没扫到** 且 **文件确实不存在**。
 *
 * 两个条件是"与"不是"或"。扫描没看到但文件还在（换了根目录、目录暂时不可达）绝不能删 ——
 * 那删的是用户的库索引。抽成纯函数是为了把这条判据钉在测试里。
 */
internal fun rowsToPrune(unseen: List<VideoRefRow>, fileExists: (String) -> Boolean?): List<VideoRefRow> =
    unseen.filter { fileExists(it.localUri) == false }

/**
 * 判断 SAF 单文档是否仍存在：true=在，false=确定不在了，null=无法判断（按"还在"处理）。
 *
 * 不直接用 [DocumentFile.exists]：它内部吞掉查询异常并返回 false，
 * 临时撤销/未就绪的授权会被误判成"文件已删除"，进而删掉用户的库索引。
 * 这里自己查 [DocumentsContract.Document.COLUMN_DOCUMENT_ID]，
 * 只有"查询成功且没有任何行"才认定为已删除。
 */
internal fun documentExists(context: Context, uri: Uri): Boolean? {
    if (!ContentResolver.SCHEME_CONTENT.equals(uri.scheme, ignoreCase = true)) return null
    return try {
        context.contentResolver
            .query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { cursor -> cursor.moveToFirst() && !cursor.isNull(0) }
    } catch (e: Exception) {
        // 含 SecurityException：授权状态此刻不可判定，按未知处理，绝不据此删行或判坏。
        DiagLog.log("Repo", "文档存在性查询失败，按未知处理：" + e.javaClass.simpleName)
        null
    }
}

/**
 * 删除指向已不存在文件的行（连同标签关联）。
 *
 * 扫描以前只 upsert 不删除，于是文件被文件管理器删掉后旧行一直留着：在线卡片的「已下载」
 * 标记、本地库查询、下载查重都还信任它，点播直接失败。
 */
private suspend fun IwaraRepository.pruneMissingRows(
    unseen: List<VideoRefRow>,
    fileExists: (String) -> Boolean?
): List<VideoRefRow> {
    val doomed = rowsToPrune(unseen, fileExists)
    for (ref in doomed) {
        dao.byUri(ref.localUri)?.let { row ->
            // 行都没了，封面文件也别留着占空间（只删持久封面目录里的）。
            deleteLocalThumbnailFile(row.localThumbnailPath)
            dao.deleteVideoWithTags(row)
        }
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
            // 与扫描的攒批同口径：先拉完一批响应（网络），再一个事务批写，
            // 避免待补条目多时每条一次事务、每条一次 videos/tags 失效。
            for (chunk in pending.chunked(importBatchSize)) {
                val writes = chunk.mapNotNull { v ->
                    val write = fetchMetadataWrite(v)
                    done++
                    onProgress(done, pending.size)
                    write
                }
                if (writes.isNotEmpty()) dao.applyFetchedMetadataBatch(writes)
            }
        }
    }

    /** 已入库视频总数。 */
suspend fun IwaraRepository.countVideos(): Int = dao.count()

    /**
     * 递归收集 [dir] 下的视频文件；[rel] 是相对库根目录的父目录路径，[depth] 是 [dir] 的层数
     * （库根目录为 0）。超过 [MAX_SCAN_DEPTH] 的子目录整棵跳过，避免异常目录树把扫描拖死。
     * 提取成 internal 顶层函数是为了让 JVM 单测用临时目录直接验证深度上限。
     */
    internal fun collectVideoFiles(
        dir: DocumentFile,
        rel: String,
        out: MutableList<Pair<DocumentFile, String>>,
        depth: Int = 0
    ) {
        for (f in dir.listFiles()) {
            when {
                f.isDirectory -> {
                    val childRel = if (rel.isEmpty()) (f.name ?: "") else "$rel/${f.name}"
                    if (depth + 1 > MAX_SCAN_DEPTH) {
                        DiagLog.log("Repo", "跳过超过 $MAX_SCAN_DEPTH 层的目录：$childRel")
                        continue
                    }
                    collectVideoFiles(f, childRel, out, depth + 1)
                }
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
