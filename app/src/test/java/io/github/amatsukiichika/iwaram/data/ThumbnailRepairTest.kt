package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 本地封面"缺失判定"与目录边界（纯 JVM，不碰 Android API）。
 *
 * 背景：旧版本把封面写进 cacheDir/thumbs，用户清一次应用缓存封面就永久消失，而库里仍留着
 * 指向已删文件的路径（见 docs/decisions/0007-persist-local-thumbnails.md）。扫描靠
 * [needsThumbnailRepair] 判断"路径还在不在"，删行/迁移靠 [isInsideDir] 保证只碰自己的目录。
 * 这里把两类边界逐条钉住，避免修复逻辑退化成"又清缓存又丢封面"。
 */
class ThumbnailRepairTest {

    /* ---------- needsThumbnailRepair ---------- */

    @Test
    fun missingPathNeedsRepair() {
        assertTrue(needsThumbnailRepair(null) { true })
        assertTrue(needsThumbnailRepair("") { true })
        assertTrue(needsThumbnailRepair("   ") { true })
    }

    @Test
    fun storedPathThatStillExistsNeedsNoRepair() {
        val path = File("thumbs", "thumb_1.jpg").absolutePath
        // 存在性由调用方注入：这里验证"路径交给 fileExists 判定"，不落真实文件系统。
        assertFalse(needsThumbnailRepair(path) { it == path })
    }

    @Test
    fun storedPathPointingToDeletedFileNeedsRepair() {
        val path = File("thumbs", "thumb_1.jpg").absolutePath
        assertTrue(needsThumbnailRepair(path) { false })
    }

    @Test
    fun fileExistsReceivesTheStoredPathVerbatim() {
        var seen: String? = null
        needsThumbnailRepair("/tmp/thumbs/thumb_9.jpg") { p ->
            seen = p
            true
        }
        assertEquals("/tmp/thumbs/thumb_9.jpg", seen)
    }

    /* ---------- isInsideDir ---------- */

    @Test
    fun childPathOfDirIsInside() {
        val dir = File("library", "thumbs")
        val child = File(dir, "thumb_7.jpg")
        assertTrue(isInsideDir(child.absolutePath, dir))
    }

    @Test
    fun nestedPathOfDirIsInside() {
        val dir = File("library", "thumbs")
        val child = File(File(dir, "old"), "thumb_7.jpg")
        assertTrue(isInsideDir(child.absolutePath, dir))
    }

    @Test
    fun siblingDirectoryWithSamePrefixIsOutside() {
        // 关键边界："…/thumbs2" 以 "…/thumbs" 开头，但不是子路径 —— 用目录分隔符判定。
        val dir = File("library", "thumbs")
        val sibling = File(dir.absolutePath + "2", "thumb_7.jpg")
        assertFalse(isInsideDir(sibling.absolutePath, dir))
    }

    @Test
    fun parentAndTheDirectoryItselfAreOutside() {
        val dir = File("library", "thumbs")
        assertFalse(isInsideDir(dir.absolutePath, dir))
        assertFalse(isInsideDir(File("library", "thumb_7.jpg").absolutePath, dir))
    }
}
