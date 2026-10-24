package io.github.amatsukiichika.iwaram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 断点续传「该往哪个文件写」的回归用例。
 *
 * 若只在**下载根目录的最外层** `root.findFile(名字)` 找一次半成品、且**从不读**
 * `PendingDownload.docUri`（该字段一直只写不读），则半成品不在根目录最外层时
 * （下载目录被改过、或文件本来就在子文件夹里）就找不到 ⇒ 走 `createFile` 新建一份
 * ⇒ 磁盘上两份、本地库两条记录。
 *
 * 这个纯函数是"选目标"这条契约的唯一出口。
 */
class DownloadResumeTargetTest {

    private val recorded = "content://d/recorded"
    private val inRoot = "content://d/root"
    private val inTree = "content://d/tree"

    /** 长度 -1 = 取不到 / 不存在（见 DownloadService 里的约定）。 */
    private val missing = -1L

    @Test
    fun `上次记录的半成品优先（哪怕它不在根目录最外层）`() {
        val t = pickResumeTarget(
            recorded, 12_345L,
            inRoot, 99L,
            inTree, 88L
        )
        assertEquals(recorded, t?.docUri)
        assertEquals(12_345L, t?.length)
    }

    @Test
    fun `没有记录时回退到根目录同名文件`() {
        val t = pickResumeTarget(null, missing, inRoot, 999L, inTree, 10L)
        assertEquals(inRoot, t?.docUri)
    }

    @Test
    fun `根目录没有时回退到子树同名文件`() {
        val t = pickResumeTarget(null, missing, null, missing, inTree, 777L)
        assertEquals(inTree, t?.docUri)
    }

    @Test
    fun `全都找不到时返回 null（调用方新建文件）`() {
        assertNull(pickResumeTarget(null, missing, null, missing, null, missing))
    }

    @Test
    fun `空文件也算有效目标 —— 用它胜过新建 xxx (1)mp4`() {
        // 上次失败留下的 0 字节文件：以它为目标继续写，比 new 一个「xxx (1).mp4」更对
        //（后者正是"同一支视频两个文件"的来源）。0 字节时下游不带 Range 头、服务端返 200 ⇒ 截断重写。
        val t = pickResumeTarget(recorded, 0L, null, missing, null, missing)
        assertEquals(recorded, t?.docUri)
        assertEquals(0L, t?.length)
    }

    @Test
    fun `记录里的 uri 为空串时视为无效`() {
        assertNull(pickResumeTarget("", 100L, null, missing, null, missing))
        assertNull(pickResumeTarget("   ", 100L, null, missing, null, missing))
    }

    @Test
    fun `记录文件被删掉后回退到其他候选（长度 -1）`() {
        val t = pickResumeTarget(recorded, missing, null, missing, inTree, 5L)
        assertEquals(inTree, t?.docUri)
    }
}
