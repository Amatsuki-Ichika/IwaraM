package io.github.amatsukiichika.iwaram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本解析、semver 比较与更新判定（纯 JVM，不碰 Android API）。
 *
 * 更新检查判错时用户要么永远收不到新版（静默失效），要么被反复提示同一个版本；
 * 这里把 v 前缀、预发布、非法串、versionCode 双判据与通道过滤逐条钉住。
 */
class UpdateVersionTest {

    /* ---------- parse ---------- */

    @Test
    fun parseReadsPlainAndVPrefixedVersions() {
        assertEquals(UpdateVersion(0, 2, 2, null), parse("0.2.2"))
        assertEquals(UpdateVersion(0, 2, 2, null), parse("v0.2.2"))
        assertEquals(UpdateVersion(0, 2, 2, null), parse("V0.2.2"))
        assertEquals(UpdateVersion(0, 2, 2, null), parse("  v0.2.2  "))
    }

    @Test
    fun parseSplitsPrereleaseOnDotAndDash() {
        assertEquals(UpdateVersion(0, 2, 2, listOf("rc", "1")), parse("0.2.2-rc.1"))
        assertEquals(UpdateVersion(0, 2, 2, listOf("rc", "1")), parse("v0.2.2-rc-1"))
        assertEquals(UpdateVersion(1, 0, 0, listOf("beta", "2")), parse("1.0.0-beta.2"))
    }

    @Test
    fun parseRejectsMalformedAndOutOfRangeVersions() {
        assertNull(parse(null))
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("v"))
        assertNull(parse("abc"))
        assertNull(parse("1.2"))
        assertNull(parse("1.2.3.4"))
        assertNull(parse("1.2.x"))
        assertNull(parse("-1.2.3"))
        assertNull(parse("1.2.3-"))
        assertNull(parse("1.2.3-rc..1"))
        assertNull(parse("1.2.3+build.5"))
        // versionCode 公式可表示范围之外：会撞码/溢出，按非法处理。
        assertNull(parse("2100.0.0"))
        assertNull(parse("1.1000.0"))
        assertNull(parse("1.2.1000"))
    }

    @Test
    fun versionNameIsNormalizedWithoutVOrDashPrefix() {
        assertEquals("0.2.2", parse("v0.2.2")!!.versionName)
        assertEquals("0.2.2-rc.1", parse("v0.2.2-rc-1")!!.versionName)
    }

    @Test
    fun versionCodeMirrorsGradleFormulaAndIgnoresPrerelease() {
        assertEquals(2_002, parse("0.2.2")!!.versionCode())
        assertEquals(2_002, parse("v0.2.2-rc.1")!!.versionCode())
        assertEquals(1_002_003, parse("1.2.3")!!.versionCode())
        assertEquals(2_003, parse("0.2.3-rc.2")!!.versionCode())
        // 上限：2099.999.999 -> 2_099_999_999，正好低于 Android 的 2_100_000_000。
        assertEquals(2_099_999_999, parse("2099.999.999")!!.versionCode())
    }

    @Test
    fun adjacentVersionFieldsNoLongerShareAVersionCode() {
        // 旧公式下 0.2.100 与 0.3.0 都是 300；新公式让 minor/patch 各占三位，互不重叠。
        assertEquals(2_100, parse("0.2.100")!!.versionCode())
        assertEquals(3_000, parse("0.3.0")!!.versionCode())
    }

    /* ---------- compareTo ---------- */

    @Test
    fun patchMinorAndMajorIncrementsAreNewer() {
        assertTrue(parse("0.2.3")!! > parse("0.2.2")!!)
        assertTrue(parse("0.3.0")!! > parse("0.2.9")!!)
        assertTrue(parse("1.0.0")!! > parse("0.9.9")!!)
        assertEquals(0, parse("0.2.2")!!.compareTo(parse("v0.2.2")!!))
    }

    @Test
    fun stableReleaseRanksAbovePrereleaseOfSameCoreVersion() {
        assertTrue(parse("0.2.2")!! > parse("0.2.2-rc.2")!!)
        assertTrue(parse("0.2.2")!! > parse("0.2.2-rc.1")!!)
        assertTrue(parse("0.2.2-rc.2")!! < parse("0.2.2")!!)
    }

    @Test
    fun prereleaseNumbersCompareByValueNotLexicographically() {
        assertTrue(parse("0.2.2-rc.2")!! > parse("0.2.2-rc.1")!!)
        assertTrue(parse("0.2.2-rc.10")!! > parse("0.2.2-rc.2")!!)
        // 段数多的更大：rc.1 > rc。
        assertTrue(parse("0.2.2-rc.1")!! > parse("0.2.2-rc")!!)
        // semver：数字段低于字母段。
        assertTrue(parse("0.2.2-alpha")!! > parse("0.2.2-1")!!)
    }

    /* ---------- needsUpdate ---------- */

    @Test
    fun higherRemoteVersionCodeNeedsUpdate() {
        assertTrue(needsUpdate("0.2.2", "0.2.3", 2002, 2003))
    }

    @Test
    fun identicalVersionCodeAndStableNameNeedsNoUpdate() {
        assertFalse(needsUpdate("0.2.2", "0.2.2", 2002, 2002))
    }

    @Test
    fun lowerRemoteVersionCodeNeedsNoUpdateEvenIfNameLooksNewer() {
        // 版本名看起来更新，但安装器真正比较的 code 更低：不提示。
        assertFalse(needsUpdate("0.2.2", "0.2.3", 2002, 2001))
        assertFalse(needsUpdate("0.2.2", "not-a-version", 2002, 2001))
    }

    @Test
    fun prereleaseUpgradesToStableReleaseOfSameCoreVersion() {
        // versionCode 忽略预发布：rc 与正式版同码，这是唯一允许原地换正式版的判据二。
        assertTrue(needsUpdate("0.2.2-rc.1", "0.2.2", 2002, 2002))
        assertTrue(needsUpdate("v0.2.2-rc.2", "v0.2.2", 2002, 2002))
    }

    @Test
    fun stableNeverDowngradesToPrereleaseOfSameCoreVersion() {
        assertFalse(needsUpdate("0.2.2", "0.2.2-rc.2", 2002, 2002))
    }

    @Test
    fun unknownVersionNamesCannotTriggerSameCodeUpgrade() {
        assertFalse(needsUpdate(null, "0.2.2", 2002, 2002))
        assertFalse(needsUpdate("0.2.2-rc.1", null, 2002, 2002))
    }

    @Test
    fun remoteNameIsIrrelevantWhenVersionCodeAlreadySaysUpdate() {
        assertTrue(needsUpdate("0.2.2", "not-a-version", 2002, 2003))
    }

    @Test
    fun stableChannelRejectsPrereleasesAndBetaChannelAcceptsThem() {
        assertFalse(UpdateChannel.STABLE.accepts(true))
        assertTrue(UpdateChannel.STABLE.accepts(false))
        assertTrue(UpdateChannel.BETA.accepts(true))
        assertTrue(UpdateChannel.BETA.accepts(false))
    }
}
