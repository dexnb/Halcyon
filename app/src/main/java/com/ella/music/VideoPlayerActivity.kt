package com.ella.music

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import com.ella.music.ui.theme.EllaTheme
import com.ella.music.ui.video.VideoToolsScreen
import com.ella.music.video.isVideoSource

/** A public, URI-only entry point. The existing MV activity remains private. */
class VideoPlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val stream=intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val candidate=(intent.data ?: stream)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val source=candidate.takeIf(::isVideoSource).orEmpty()
        setContent {
            val settings = androidx.compose.runtime.remember { com.ella.music.data.SettingsManager.getInstance(this) }
            val theme = settings.themeMode.collectAsState(initial = com.ella.music.ui.theme.THEME_FOLLOW_SYSTEM).value
            EllaTheme(themeMode = theme) {
                VideoToolsScreen(onBack={ finish() },initialSource=source,initialMime=intent.type,
                    autoPlay=intent.action==Intent.ACTION_VIEW)
            }
        }
    }
}
