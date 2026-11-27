package io.github.amatsukiichika.iwaram.data

import io.github.amatsukiichika.iwaram.consumesRepairBudget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载收尾/续传判据的纯函数回归用例。
 *
 * 覆盖审查 Medium-7（替换式重下不得走"已在库"快捷路径）、
 * Medium-47（只有 Broken 消耗每进程一次自动修复预算）、
 * Medium-58（重新解析的档位必须与旧半成品一致才允许续写）。
 */
class DownloadServicePoliciesTest {

    @Test
    fun `a replace job never reuses the already-in-library shortcut`() {
        assertTrue(canReuseLibraryFile(fileComplete = true, replaceExisting = false))
        // 替换式重下的旧文件就是坏文件：一旦走快捷路径，修复会报成功却什么都没做。
        assertFalse(canReuseLibraryFile(fileComplete = true, replaceExisting = true))
        assertFalse(canReuseLibraryFile(fileComplete = false, replaceExisting = false))
        assertFalse(canReuseLibraryFile(fileComplete = false, replaceExisting = true))
    }

    @Test
    fun `only broken verdicts consume the automatic repair budget`() {
        assertTrue(consumesRepairBudget(IntegrityVerdict.Broken(BrokenReason.Truncated)))
        // Ok/Unknown/NoDownloadRecord 不是一次修复事件；占掉名额会让之后的 Broken 永远不能自动修复。
        assertFalse(consumesRepairBudget(IntegrityVerdict.Ok))
        assertFalse(consumesRepairBudget(IntegrityVerdict.Unknown))
        assertFalse(consumesRepairBudget(IntegrityVerdict.NoDownloadRecord))
        assertFalse(consumesRepairBudget(null))
    }

    @Test
    fun `a re-resolved target must keep the same quality to resume`() {
        assertTrue(isSameDownloadRendition("Source", "Source"))
        assertTrue(isSameDownloadRendition(" Source ", "Source"))
        // 回退到别的档位时继续 append 会把两种编码拼成损坏文件。
        assertFalse(isSameDownloadRendition("Source", "720"))
        assertFalse(isSameDownloadRendition("720", "Source"))
        // 无法确认（任一侧为空）按不一致处理。
        assertFalse(isSameDownloadRendition("", ""))
        assertFalse(isSameDownloadRendition("Source", ""))
    }
}
