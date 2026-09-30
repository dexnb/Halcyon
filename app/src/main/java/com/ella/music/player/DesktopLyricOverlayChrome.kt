package com.ella.music.player

internal fun desktopLyricControlPanelVisible(
    locked: Boolean,
    statusBarMode: Boolean,
    controlsVisible: Boolean
): Boolean = !locked && !statusBarMode && controlsVisible

internal fun desktopLyricPassThroughTouches(statusBarMode: Boolean, locked: Boolean = false): Boolean = statusBarMode || locked

internal fun desktopLyricUsesCompactWindow(locked: Boolean, statusBarMode: Boolean): Boolean =
    locked && !statusBarMode
