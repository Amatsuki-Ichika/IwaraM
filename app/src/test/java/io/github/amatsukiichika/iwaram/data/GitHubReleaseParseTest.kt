package io.github.amatsukiichika.iwaram.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GitHub Releases 解析、资产配对与通道过滤。
 *
 * org.json 是 Android API，JVM 单测由 Robolectric 提供真实实现（仓库既有做法，见 test/AGENTS.md）。
 * 选错版本会把错的 APK 推给用户，所以过滤规则（draft / 通道 / APK+sha256 配对 / 取 semver 最大）
 * 逐条回归。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GitHubReleaseParseTest {

    /** 固定样例：按 GitHub 真实顺序（新→旧）排列，含一条 rc 与一条 draft。 */
    private val sampleReleases = """
        [
          {
            "tag_name": "v0.3.0",
            "prerelease": false,
            "draft": true,
            "body": "draft notes",
            "assets": [
              {"name": "IwaraM-0.3.0-arm64-v8a.apk", "browser_download_url": "https://github.com/IwaraM-0.3.0-arm64-v8a.apk"},
              {"name": "IwaraM-0.3.0-arm64-v8a.apk.sha256", "browser_download_url": "https://github.com/IwaraM-0.3.0-arm64-v8a.apk.sha256"}
            ]
          },
          {
            "tag_name": "v0.2.3-rc.1",
            "prerelease": true,
            "draft": false,
            "body": "rc notes",
            "assets": [
              {"name": "IwaraM-0.2.3-rc.1-arm64-v8a.apk", "browser_download_url": "https://github.com/IwaraM-0.2.3-rc.1-arm64-v8a.apk"},
              {"name": "IwaraM-0.2.3-rc.1-arm64-v8a.apk.sha256", "browser_download_url": "https://github.com/IwaraM-0.2.3-rc.1-arm64-v8a.apk.sha256"}
            ]
          },
          {
            "tag_name": "v0.2.2",
            "prerelease": false,
            "draft": false,
            "body": "stable notes",
            "assets": [
              {"name": "source.zip", "browser_download_url": "https://github.com/source.zip"},
              {"name": "IwaraM-0.2.2-arm64-v8a.apk", "browser_download_url": "https://github.com/IwaraM-0.2.2-arm64-v8a.apk"},
              {"name": "IwaraM-0.2.2-arm64-v8a.apk.sha256", "browser_download_url": "https://github.com/IwaraM-0.2.2-arm64-v8a.apk.sha256"}
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun stableChannelPicksNewestStableReleaseAndSkipsPrereleaseAndDraft() {
        val release = parseReleaseList(sampleReleases, UpdateChannel.STABLE)!!
        assertEquals("0.2.2", release.versionName)
        assertEquals("v0.2.2", release.tagName)
        assertEquals("stable notes", release.body)
        assertEquals("https://github.com/IwaraM-0.2.2-arm64-v8a.apk", release.apkUrl)
        assertEquals("https://github.com/IwaraM-0.2.2-arm64-v8a.apk.sha256", release.shaUrl)
        assertFalse(release.prerelease)
    }

    @Test
    fun betaChannelPicksNewerPrereleaseOverOlderStable() {
        val release = parseReleaseList(sampleReleases, UpdateChannel.BETA)!!
        assertEquals("0.2.3-rc.1", release.versionName)
        assertEquals("v0.2.3-rc.1", release.tagName)
        assertEquals("rc notes", release.body)
        assertTrue(release.prerelease)
    }

    @Test
    fun highestSemverWinsRegardlessOfArrayOrder() {
        val json = listJson(
            releaseJson("v0.2.1"),
            releaseJson("v0.2.3"),
            releaseJson("v0.2.2")
        )
        assertEquals("0.2.3", parseReleaseList(json, UpdateChannel.STABLE)!!.versionName)
    }

    @Test
    fun draftReleaseIsNeverSelectedInEitherChannel() {
        val json = listJson(releaseJson("v0.2.2"), releaseJson("v0.2.3", draft = true))
        assertEquals("0.2.2", parseReleaseList(json, UpdateChannel.STABLE)!!.versionName)
        assertEquals("0.2.2", parseReleaseList(json, UpdateChannel.BETA)!!.versionName)
    }

    @Test
    fun releaseMissingSha256AssetIsSkippedForNextCandidate() {
        val json = listJson(
            releaseJson("v0.2.6", assets = listOf(assetJson("IwaraM-0.2.6-arm64-v8a.apk"))),
            releaseJson("v0.2.5")
        )
        assertEquals("0.2.5", parseReleaseList(json, UpdateChannel.STABLE)!!.versionName)
    }

    @Test
    fun sha256OfAnotherApkDoesNotCount() {
        val json = listJson(
            releaseJson(
                "v0.2.6",
                assets = listOf(
                    assetJson("IwaraM-0.2.6-arm64-v8a.apk"),
                    assetJson("IwaraM-0.2.5-arm64-v8a.apk.sha256")
                )
            ),
            releaseJson("v0.2.5")
        )
        assertEquals("0.2.5", parseReleaseList(json, UpdateChannel.STABLE)!!.versionName)
    }

    @Test
    fun apkUrlComesFromNamedAssetNotFromFirstAsset() {
        val release = parseReleaseList(sampleReleases, UpdateChannel.STABLE)!!
        assertTrue(release.apkUrl.contains("IwaraM-0.2.2-arm64-v8a.apk"))
        assertFalse(release.apkUrl.contains("source.zip"))
    }

    @Test
    fun assetUrlOutsideTheGithubAllowlistIsSkippedForNextCandidate() {
        val apkName = "IwaraM-0.2.6-arm64-v8a.apk"
        val json = listJson(
            releaseJson(
                "v0.2.6",
                assets = listOf(
                    assetJson(apkName, host = "evil.example"),
                    assetJson(apkName + ".sha256", host = "evil.example")
                )
            ),
            releaseJson("v0.2.5")
        )
        assertEquals("0.2.5", parseReleaseList(json, UpdateChannel.STABLE)!!.versionName)
    }

    @Test
    fun invalidTagOrMissingAssetsNeverMatch() {
        assertNull(parseReleaseList("[]", UpdateChannel.STABLE))
        assertNull(parseReleaseList(listJson(releaseJson("nightly")), UpdateChannel.BETA))
        assertNull(parseReleaseList(listJson(releaseJson("v1.2")), UpdateChannel.STABLE))
        // 没有任何资产。
        assertNull(parseReleaseList(
            listJson(releaseJson("v0.2.2", assets = emptyList())),
            UpdateChannel.STABLE
        ))
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(parseReleaseList("not json at all", UpdateChannel.STABLE))
        // GitHub 限流/鉴权失败时返回对象而不是数组，也按"没有候选"处理。
        assertNull(parseReleaseList("""{"message":"API rate limit exceeded"}""", UpdateChannel.BETA))
    }

    @Test
    fun objectEntryPointDelegatesToTopLevelPureFunction() {
        val release = GitHubReleaseClient.parseReleaseList(sampleReleases, UpdateChannel.STABLE)!!
        assertEquals("0.2.2", release.versionName)
    }

    /* ---------- fixtures ---------- */

    private fun assetJson(name: String, host: String = "github.com"): JSONObject =
        JSONObject()
            .put("name", name)
            .put("browser_download_url", "https://$host/" + name)

    private fun releaseJson(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: List<JSONObject> = defaultAssets(tag)
    ): JSONObject = JSONObject()
        .put("tag_name", tag)
        .put("prerelease", prerelease)
        .put("draft", draft)
        .put("assets", JSONArray(assets))

    private fun defaultAssets(tag: String): List<JSONObject> {
        val apkName = "IwaraM-" + tag.removePrefix("v") + "-arm64-v8a.apk"
        return listOf(assetJson(apkName), assetJson(apkName + ".sha256"))
    }

    private fun listJson(vararg releases: JSONObject): String =
        JSONArray(releases.toList()).toString()
}
