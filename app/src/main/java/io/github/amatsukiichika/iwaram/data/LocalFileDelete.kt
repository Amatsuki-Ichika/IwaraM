package io.github.amatsukiichika.iwaram.data

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.amatsukiichika.iwaram.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 删除一个本地视频文件（SAF）。**只在用户显式勾选"同时删除磁盘文件"时调用。**
 *
 * @return null = 成功，或文件本来就不在了（幂等，不算错）；
 *         非 null = 给用户看的原因（SAF 拒绝删除时有发生，不能静默吞掉）。
 */
internal suspend fun IwaraRepository.deleteLocalFile(uriString: String?): String? =
    withContext(Dispatchers.IO) {
        val target = uriString?.takeIf { it.isNotBlank() } ?: return@withContext null
        val uri = runCatching { Uri.parse(target) }.getOrNull()
            ?: return@withContext tr("文件地址无法解析", "The file address could not be parsed")
        // 不用 DocumentFile.exists()：它吞掉查询异常并返回 false，会把暂时的 provider
        // 故障当成"文件本来就不在"报成功，文件其实还留在盘上。三值查询只信确定的 false。
        when (documentExists(context, uri)) {
            false -> return@withContext null
            null -> if (uri.scheme.equals("content", ignoreCase = true)) {
                return@withContext tr(
                    "无法确认文件是否存在，未执行删除；请稍后重试",
                    "Could not confirm whether the file exists, so nothing was deleted; try again later"
                )
            }
            true -> Unit
        }
        runCatching {
            val doc = DocumentFile.fromSingleUri(context, uri)
                ?: return@withContext tr(
                    "文件删除被系统拒绝（可能未授予该目录的写入权限）",
                    "The system refused to delete the file (write permission may be missing)"
                )
            if (doc.delete()) null
            else tr(
                "文件删除被系统拒绝（可能未授予该目录的写入权限）",
                "The system refused to delete the file (write permission may be missing)"
            )
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }
