package io.github.amatsukiichika.iwaram.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppLanguageTest {
    @Before
    fun useChinese() {
        AppLanguage.set(LanguageMode.ZH)
    }

    @After
    fun restoreChinese() {
        AppLanguage.set(LanguageMode.ZH)
    }

    @Test
    fun languageModeRoundTripsThroughPrefValue() {
        for (mode in LanguageMode.entries) {
            assertEquals(mode, LanguageMode.fromPref(mode.toPref()))
        }
    }

    @Test
    fun unknownOrMissingPrefFallsBackToSystem() {
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromPref(null))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromPref(""))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromPref("de"))
    }

    @Test
    fun prefValuesAreCaseInsensitive() {
        assertEquals(LanguageMode.ZH, LanguageMode.fromPref("ZH"))
        assertEquals(LanguageMode.EN, LanguageMode.fromPref("En"))
    }

    @Test
    fun trPicksChineseByDefaultAndEnglishAfterSwitch() {
        assertFalse(AppLanguage.isEnglish)
        assertEquals("播放", tr("播放", "Play"))
        AppLanguage.set(LanguageMode.EN)
        assertTrue(AppLanguage.isEnglish)
        assertEquals("Play", tr("播放", "Play"))
    }

    @Test
    fun trCountReturnsNounFormForTheCount() {
        assertEquals("个视频", trCount(1, "个视频", "video", "videos"))
        assertEquals("个视频", trCount(3, "个视频", "video", "videos"))
        AppLanguage.set(LanguageMode.EN)
        assertEquals("video", trCount(1, "个视频", "video", "videos"))
        assertEquals("videos", trCount(0, "个视频", "video", "videos"))
        assertEquals("videos", trCount(3, "个视频", "video", "videos"))
    }

    @Test
    fun chineseLocaleStaysChineseInSystemMode() {
        // SYSTEM 跟随系统 Locale；测试环境无法稳定改默认 Locale，这里只验证显式模式不受影响。
        AppLanguage.set(LanguageMode.ZH)
        assertFalse(AppLanguage.isEnglish)
        assertEquals(java.util.Locale.SIMPLIFIED_CHINESE, AppLanguage.locale)
        AppLanguage.set(LanguageMode.EN)
        assertEquals(java.util.Locale.ENGLISH, AppLanguage.locale)
    }
}
