package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 扫描根目录结局判定的纯函数用例（[folderImportResult]）。
 *
 * 契约：授权失效/目录被删必须报 [ImportFolderResult.Inaccessible]（UI 提示重新选择）；
 * 能访问但一个视频都没扫到是 [ImportFolderResult.Empty]（警告），
 * 绝不能再用旧计数报「检索完成：本地共 N 个视频」的假成功。
 */
class ScanRootAccessTest {

    @Test
    fun `a denied root is reported inaccessible even if some files were listed`() {
        assertEquals(ImportFolderResult.Inaccessible, folderImportResult(RootProbe.Denied, 12))
    }

    @Test
    fun `a missing root is reported inaccessible`() {
        assertEquals(ImportFolderResult.Inaccessible, folderImportResult(RootProbe.Missing, 0))
    }

    @Test
    fun `an accessible root with no videos reports a warning instead of success`() {
        assertEquals(ImportFolderResult.Empty, folderImportResult(RootProbe.Accessible, 0))
    }

    @Test
    fun `an unverifiable root with no videos is still a warning`() {
        assertEquals(ImportFolderResult.Empty, folderImportResult(RootProbe.Unknown, 0))
    }

    @Test
    fun `an accessible root with videos succeeds`() {
        assertEquals(ImportFolderResult.Success, folderImportResult(RootProbe.Accessible, 7))
    }
}
