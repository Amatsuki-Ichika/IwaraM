package io.github.amatsukiichika.iwaram.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * releases 响应体 → [GitHubReleaseResult] 的纯映射（Medium-59 回归）。
 *
 * 关键契约：API 成功但没有可用版本是 [GitHubReleaseResult.None]，不是 Failure ——
 * 否则界面上「已是最新」会被误报成网络失败，自动检查也会因未记录检查时间而每次启动重打 API。
 *
 * org.json 是 Android API，JVM 单测由 Robolectric 提供实现（仓库既有做法）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubReleaseResultTest {

    @Test
    fun successfulResponseWithOnlyPrereleasesIsNoneOnStableChannel() {
        val json = JSONArray(listOf(releaseJson("v0.3.0-rc.1", prerelease = true))).toString()
        assertEquals(
            GitHubReleaseResult.None,
            GitHubReleaseClient.parseReleaseListResult(json, UpdateChannel.STABLE)
        )
    }

    @Test
    fun emptyReleaseArrayIsNone() {
        assertEquals(
            GitHubReleaseResult.None,
            GitHubReleaseClient.parseReleaseListResult("[]", UpdateChannel.BETA)
        )
    }

    @Test
    fun missingSha256AssetIsNoneNotFailure() {
        val assets = JSONArray(listOf(assetJson("IwaraM-0.2.3-arm64-v8a.apk")))
        val json = JSONArray(
            listOf(
                JSONObject()
                    .put("tag_name", "v0.2.3")
                    .put("prerelease", false)
                    .put("draft", false)
                    .put("assets", assets)
            )
        ).toString()
        assertEquals(
            GitHubReleaseResult.None,
            GitHubReleaseClient.parseReleaseListResult(json, UpdateChannel.STABLE)
        )
    }

    @Test
    fun malformedBodyIsFailure() {
        assertEquals(
            GitHubReleaseResult.Failure,
            GitHubReleaseClient.parseReleaseListResult("not json at all", UpdateChannel.STABLE)
        )
        // GitHub 限流/鉴权失败时返回对象而不是数组：这是协议层面的失败，不是"没有更新"。
        assertEquals(
            GitHubReleaseResult.Failure,
            GitHubReleaseClient.parseReleaseListResult(
                """{"message":"API rate limit exceeded"}""",
                UpdateChannel.BETA
            )
        )
    }

    @Test
    fun eligibleReleaseIsFound() {
        val result = GitHubReleaseClient.parseReleaseListResult(
            JSONArray(listOf(releaseJson("v0.2.3"))).toString(),
            UpdateChannel.STABLE
        )
        assertTrue(result is GitHubReleaseResult.Found)
        assertEquals("0.2.3", (result as GitHubReleaseResult.Found).release.versionName)
    }

    /* ---------- fixtures ---------- */

    private fun assetJson(name: String): JSONObject = JSONObject()
        .put("name", name)
        .put("browser_download_url", "https://example.com/$name")

    private fun releaseJson(tag: String, prerelease: Boolean = false): JSONObject {
        val apk = "IwaraM-" + tag.removePrefix("v") + "-arm64-v8a.apk"
        return JSONObject()
            .put("tag_name", tag)
            .put("prerelease", prerelease)
            .put("draft", false)
            .put("assets", JSONArray(listOf(assetJson(apk), assetJson("$apk.sha256"))))
    }
}
