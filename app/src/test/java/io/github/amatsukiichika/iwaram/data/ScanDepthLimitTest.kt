package io.github.amatsukiichika.iwaram.data

import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 扫描深度上限：SAF 目录树可能异常深（甚至含环），递归必须有界。
 * 用临时目录直接构造多层目录，验证「上层文件能收到、超过上限的目录整棵跳过」。
 */
class ScanDepthLimitTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `scan collects files down to the depth limit and skips deeper ones`() {
        val root = temporaryFolder.newFolder("library")
        File(root, "root-video.mp4").writeBytes(ByteArray(1))
        var dir = root
        repeat(MAX_SCAN_DEPTH + 3) { depth ->
            dir = File(dir, "level" + depth).also { it.mkdirs() }
            File(dir, "video" + depth + ".mp4").writeBytes(ByteArray(1))
        }

        val collected = mutableListOf<Pair<DocumentFile, String>>()
        collectVideoFiles(DocumentFile.fromFile(root), "", collected)

        val names = collected.map { it.first.name }
        // 库根自身 + 层数 1..MAX_SCAN_DEPTH 的目录内文件（每层一个）。
        assertEquals(MAX_SCAN_DEPTH + 1, names.size)
        assertTrue(names.contains("root-video.mp4"))
        assertTrue(names.contains("video0.mp4"))
        // 深度 MAX_SCAN_DEPTH 的下一层整个跳过，里面的视频不应入库。
        assertFalse(names.contains("video" + MAX_SCAN_DEPTH + ".mp4"))
    }
}
