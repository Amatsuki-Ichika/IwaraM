package io.github.amatsukiichika.iwaram.ui

import io.github.amatsukiichika.iwaram.data.BrokenReason
import io.github.amatsukiichika.iwaram.data.IntegrityVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完整性检查状态机的行为：什么时候真的去查、什么结果才自动重下、取消后迟到的结果怎么处理。
 * 这些规则一旦写错，用户会看到"点一下弹两个框"或"没坏的文件被重新下载"，所以全部钉在纯函数上。
 */
class IntegrityCheckTest {

    private val target = IntegrityCheckTarget(videoId = "1ywe1sbkqwumpdxz5", title = "测试视频")

    private fun confirming() = IntegrityCheckState(target = target, phase = IntegrityCheckPhase.Confirming)

    private fun running() = IntegrityCheckState(target = target, phase = IntegrityCheckPhase.Running)

    /* ---------- 确认 ---------- */

    @Test
    fun confirmingStateStartsRunningAndAsksHostToCheck() {
        val (next, effects) = reduceIntegrityCheck(confirming(), IntegrityCheckEvent.Confirm)
        assertEquals(IntegrityCheckPhase.Running, next?.phase)
        assertEquals(listOf<IntegrityCheckEffect>(IntegrityCheckEffect.RunCheck), effects)
    }

    @Test
    fun confirmWhileRunningIsIgnoredSoDoubleTapDoesNotStartTwoChecks() {
        val state = running()
        val (next, effects) = reduceIntegrityCheck(state, IntegrityCheckEvent.Confirm)
        assertEquals(state, next)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun confirmAfterResultIsIgnored() {
        val state = IntegrityCheckState(
            target = target,
            phase = IntegrityCheckPhase.Finished(IntegrityVerdict.Ok),
            redownloadRequested = false
        )
        val (next, effects) = reduceIntegrityCheck(state, IntegrityCheckEvent.Confirm)
        assertEquals(state, next)
        assertTrue(effects.isEmpty())
    }

    /* ---------- 结果 ---------- */

    @Test
    fun okResultFinishesWithoutRedownload() {
        val (next, effects) = reduceIntegrityCheck(running(), IntegrityCheckEvent.Result(IntegrityVerdict.Ok))
        assertEquals(IntegrityCheckPhase.Finished(IntegrityVerdict.Ok), next?.phase)
        assertFalse(next!!.redownloadRequested)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun brokenResultRequestsRedownloadOnceAndRecordsIt() {
        val broken = IntegrityVerdict.Broken(BrokenReason.Truncated, detail = "size=100/200")
        val (next, effects) = reduceIntegrityCheck(running(), IntegrityCheckEvent.Result(broken))
        assertEquals(IntegrityCheckPhase.Finished(broken), next?.phase)
        assertTrue(next!!.redownloadRequested)
        assertEquals(
            listOf<IntegrityCheckEffect>(IntegrityCheckEffect.RequestRedownload(target.videoId)),
            effects
        )
    }

    @Test
    fun unknownResultFinishesWithoutTouchingTheFile() {
        val (next, effects) = reduceIntegrityCheck(running(), IntegrityCheckEvent.Result(IntegrityVerdict.Unknown))
        assertEquals(IntegrityCheckPhase.Finished(IntegrityVerdict.Unknown), next?.phase)
        assertFalse(next!!.redownloadRequested)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun noDownloadRecordFinishesWithoutTouchingTheFile() {
        val (next, effects) =
            reduceIntegrityCheck(running(), IntegrityCheckEvent.Result(IntegrityVerdict.NoDownloadRecord))
        assertEquals(IntegrityCheckPhase.Finished(IntegrityVerdict.NoDownloadRecord), next?.phase)
        assertFalse(next!!.redownloadRequested)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun resultArrivingAfterCancelIsDropped() {
        val (afterCancel, _) = reduceIntegrityCheck(confirming(), IntegrityCheckEvent.Cancel)
        assertNull(afterCancel)
        val (next, effects) = reduceIntegrityCheck(afterCancel, IntegrityCheckEvent.Result(IntegrityVerdict.Broken(BrokenReason.Missing)))
        assertNull(next)
        assertTrue(effects.isEmpty())
    }

    /* ---------- 取消 ---------- */

    @Test
    fun cancelWhileRunningClosesDialogAndCancelsIo() {
        val (next, effects) = reduceIntegrityCheck(running(), IntegrityCheckEvent.Cancel)
        assertNull(next)
        assertEquals(listOf<IntegrityCheckEffect>(IntegrityCheckEffect.CancelCheck), effects)
    }

    @Test
    fun cancelWhileConfirmingClosesDialogWithoutIo() {
        val (next, effects) = reduceIntegrityCheck(confirming(), IntegrityCheckEvent.Cancel)
        assertNull(next)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun cancelWhenNoDialogIsOpenIsNoOp() {
        val (next, effects) = reduceIntegrityCheck(null, IntegrityCheckEvent.Cancel)
        assertNull(next)
        assertTrue(effects.isEmpty())
    }

    /* ---------- 文案 ---------- */

    @Test
    fun confirmMessageNamesTheVideoAndSaysTheCheckItselfDoesNotModifyFiles() {
        val message = integrityConfirmMessage(target)
        assertTrue(message.contains(target.title))
        assertTrue(message.contains("检查本身不改动文件"))
        // 坏下载的自动修复会删文件，确认框必须提前说明，不能笼统承诺"不会删除"。
        assertTrue(message.contains("自动删除并重新下载"))
    }

    @Test
    fun noDownloadRecordMessageExplainsNothingWasModified() {
        val message = integrityResultMessage(IntegrityVerdict.NoDownloadRecord, redownloadRequested = false)
        assertTrue(message.contains("没有该文件的下载记录"))
        assertTrue(message.contains("不会被删除"))
    }

    @Test
    fun brokenResultSaysRedownloadWasRequestedOnlyWhenItWas() {
        val broken = IntegrityVerdict.Broken(BrokenReason.NoMoov)
        assertTrue(integrityResultMessage(broken, redownloadRequested = true).contains("已自动加入重新下载队列"))
        assertFalse(integrityResultMessage(broken, redownloadRequested = false).contains("重新下载"))
    }

    @Test
    fun everyBrokenReasonGetsItsOwnPlainLanguageText() {
        val texts = BrokenReason.entries.map { integrityBrokenReasonText(it) }
        assertEquals(BrokenReason.entries.size, texts.toSet().size)
        BrokenReason.entries.forEach { reason ->
            val text = integrityBrokenReasonText(reason)
            assertTrue("empty text for $reason", text.isNotBlank())
            assertFalse("enum name leaked for $reason", text.contains(reason.name))
        }
    }

    @Test
    fun resultTitlesAreDistinctForEveryVerdict() {
        val titles = listOf(
            integrityResultTitle(IntegrityVerdict.Ok),
            integrityResultTitle(IntegrityVerdict.Broken(BrokenReason.Missing)),
            integrityResultTitle(IntegrityVerdict.Unknown),
            integrityResultTitle(IntegrityVerdict.NoDownloadRecord)
        )
        assertEquals(4, titles.toSet().size)
    }
}
