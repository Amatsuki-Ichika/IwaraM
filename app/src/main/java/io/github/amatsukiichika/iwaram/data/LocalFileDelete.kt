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
        runCatching {
            val doc = DocumentFile.fromSingleUri(context, Uri.parse(target))
                ?: return@withContext null
            if (!doc.exists()) return@withContext null
            if (doc.delete()) null
            else tr(
                "文件删除被系统拒绝（可能未授予该目录的写入权限）",
                "The system refused to delete the file (write permission may be missing)"
            )
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }
