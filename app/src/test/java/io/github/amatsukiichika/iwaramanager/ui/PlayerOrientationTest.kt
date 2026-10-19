package io.github.amatsukiichika.iwaramanager.ui

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 全屏方向解析纯函数。
 * `ActivityInfo.SCREEN_ORIENTATION_*` 是编译期常量（int），会内联进测试类，JVM 单测可用。
 */
class PlayerOrientationTest {

    @Test
    fun `smart - 横屏画幅锁定为强制横屏`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, resolveOrientation("smart", true))
    }

    @Test
    fun `smart - 竖屏画幅锁定为强制竖屏`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, resolveOrientation("smart", false))
    }

    @Test
    fun `smart - 画幅未知时什么都不设`() {
        assertEquals(null, resolveOrientation("smart", null))
    }

    @Test
    fun `横向竖向档位用 USER 常量尊重系统自动旋转开关`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, resolveOrientation("landscape", null))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, resolveOrientation("portrait", true))
    }

    @Test
    fun `自动与未知历史值跟随系统姿态`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, resolveOrientation("auto", true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, resolveOrientation("auto", null))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, resolveOrientation("whatever", null))
    }
}
