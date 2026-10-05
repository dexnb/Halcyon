package com.ella.music.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLyricOverlayChromeTest {
    @Test
    fun statusBarPassThroughStillRespectsAndroidTouchOpacityLimit() {
        assertEquals(0.8f, desktopLyricWindowAlpha(true, true, 0.8f), 0.001f)
        assertEquals(1f, desktopLyricWindowAlpha(true, false, 0.8f), 0.001f)
    }

    @Test
    fun lockedDesktopLyricsDoNotKeepTheControlPanelChrome() {
        assertFalse(
            desktopLyricControlPanelVisible(
                locked = true,
                statusBarMode = false,
                controlsVisible = true
            )
        )
        assertTrue(
            desktopLyricControlPanelVisible(
                locked = false,
                statusBarMode = false,
                controlsVisible = true
            )
        )
        assertFalse(
            desktopLyricControlPanelVisible(
                locked = false,
                statusBarMode = true,
                controlsVisible = true
            )
        )
    }

    @Test
    fun lockedLyricsPassTouchesThroughAndKeepCompactWindow() {
        assertFalse(desktopLyricPassThroughTouches(statusBarMode = false))
        assertTrue(desktopLyricPassThroughTouches(statusBarMode = true))
        assertTrue(desktopLyricPassThroughTouches(statusBarMode = false, locked = true))
        assertTrue(desktopLyricUsesCompactWindow(locked = true, statusBarMode = false))
        assertFalse(desktopLyricUsesCompactWindow(locked = false, statusBarMode = false))
        assertFalse(desktopLyricUsesCompactWindow(locked = true, statusBarMode = true))
    }
}
