package io.github.amatsukiichika.iwaram.ui.settings

import io.github.amatsukiichika.iwaram.data.GitHubRelease
import io.github.amatsukiichika.iwaram.data.GitHubReleaseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查结果 → 状态/检查时间的纯映射（Medium-59 回归）。
 *
 * 关键契约：「API 成功但没有可用版本」在手工检查时是「已是最新」而不是网络失败，
 * 且 Found/None 都要记录检查时间；只有真正的查询失败才不记录。
 */
class UpdateCheckResultMappingTest {

    private val release = GitHubRelease(
        versionName = "0.3.0",
        tagName = "v0.3.0",
        body = "",
        apkUrl = "https://example.com/IwaraM-0.3.0-arm64-v8a.apk",
        shaUrl = "https://example.com/IwaraM-0.3.0-arm64-v8a.apk.sha256",
        prerelease = false
    )

    @Test
    fun manualCheckWithNoEligibleReleaseShowsUpToDateAndRecordsLastCheck() {
        val mapping = mapCheckResult(GitHubReleaseResult.None, manual = true, currentVersionName = "0.2.3")
        assertEquals(UpdateUiState.UpToDate("0.2.3"), mapping.terminalState)
        assertTrue(mapping.recordLastCheck)
    }

    @Test
    fun automaticCheckWithNoEligibleReleaseStaysSilentButRecordsLastCheck() {
        val mapping = mapCheckResult(GitHubReleaseResult.None, manual = false, currentVersionName = "0.2.3")
        assertEquals(UpdateUiState.Idle, mapping.terminalState)
        assertTrue(mapping.recordLastCheck)
    }

    @Test
    fun manualCheckNetworkFailureShowsFailedWithoutRecordingLastCheck() {
        val mapping = mapCheckResult(GitHubReleaseResult.Failure, manual = true, currentVersionName = "0.2.3")
        assertEquals(UpdateUiState.Failed(UpdateFailureKind.NETWORK), mapping.terminalState)
        assertFalse(mapping.recordLastCheck)
    }

    @Test
    fun automaticCheckNetworkFailureIsSilentWithoutRecordingLastCheck() {
        val mapping = mapCheckResult(GitHubReleaseResult.Failure, manual = false, currentVersionName = "0.2.3")
        assertEquals(UpdateUiState.Idle, mapping.terminalState)
        assertFalse(mapping.recordLastCheck)
    }

    @Test
    fun foundReleaseRecordsLastCheckAndLeavesVersionComparisonToCaller() {
        val mapping = mapCheckResult(GitHubReleaseResult.Found(release), manual = true, currentVersionName = "0.2.3")
        assertNull(mapping.terminalState)
        assertTrue(mapping.recordLastCheck)
    }
}
