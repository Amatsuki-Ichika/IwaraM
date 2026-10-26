package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描收尾的清理判据：**这次没扫到** 且 **文件确实不存在**，两条同时成立才删。
 *
 * 这是唯一一个会主动删用户库索引的地方，所以"与"不能写成"或"：
 * 换了根目录、外置存储没挂上、SAF 授权暂时失效时，扫描会一个文件都看不到，
 * 但那些文件其实还在 —— 按"没扫到就删"处理等于把用户的库清空。
 */
class ScanPruneTest {

    private fun ref(uri: String) = VideoRefRow(
        localUri = uri, iwaraId = "id-" + uri.hashCode(), fileName = "f.mp4", fileModifiedAt = 0L
    )

    @Test
    fun `a row whose file is gone is pruned`() {
        val unseen = listOf(ref("content://tree/gone.mp4"))
        val doomed = rowsToPrune(unseen) { false }
        assertEquals(listOf("content://tree/gone.mp4"), doomed.map { it.localUri })
    }

    @Test
    fun `a row whose file still exists survives even though the scan did not see it`() {
        val unseen = listOf(ref("content://tree/still-here.mp4"))
        assertTrue(
            "扫描没见到但文件还在（换根目录/暂不可达）绝不能删",
            rowsToPrune(unseen) { true }.isEmpty()
        )
    }

    @Test
    fun `only the rows with a missing file are pruned`() {
        val unseen = listOf(ref("content://tree/a.mp4"), ref("content://tree/b.mp4"))
        val doomed = rowsToPrune(unseen) { uri -> uri.endsWith("b.mp4") }
        assertEquals(listOf("content://tree/a.mp4"), doomed.map { it.localUri })
    }
}
