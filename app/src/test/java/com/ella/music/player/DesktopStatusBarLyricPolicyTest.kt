package com.ella.music.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopStatusBarLyricPolicyTest {

    @Test
    fun secondaryWhitespaceNormalizationKeepsOneVisualRun() {
        assertEquals(
            "wa ta shi ni ai sare tai",
            "  wa   ta shi\nni\t ai sare tai  ".normalizeDesktopStatusBarSecondaryText()
        )
    }
}
