package io.github.amatsukiichika.iwaramanager.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主题模式解析：system 跟随系统，light/dark 强制覆盖。 */
class ResolveDarkThemeTest {

    @Test
    fun `light mode forces light regardless of system`() {
        assertFalse(resolveDarkTheme("light", systemDark = true))
        assertFalse(resolveDarkTheme("light", systemDark = false))
    }

    @Test
    fun `dark mode forces dark regardless of system`() {
        assertTrue(resolveDarkTheme("dark", systemDark = true))
        assertTrue(resolveDarkTheme("dark", systemDark = false))
    }

    @Test
    fun `system mode and unknown values follow the system flag`() {
        assertTrue(resolveDarkTheme("system", systemDark = true))
        assertFalse(resolveDarkTheme("system", systemDark = false))
        assertFalse(resolveDarkTheme("anything-else", systemDark = false))
    }
}
