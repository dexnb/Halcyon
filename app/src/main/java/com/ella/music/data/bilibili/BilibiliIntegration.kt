package com.ella.music.data.bilibili

import android.content.Context
import com.ella.music.data.SettingsManager
import com.ella.music.data.model.AudioInfo
import com.ella.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 哔哩哔哩音源与宿主应用的**唯一**接触面。
 *
 * 共享层（MusicRepository / PlaybackService 等）只调用这里的方法，
 * 所有哔哩哔哩特有的判断与实现都留在这个包内，不再散落到网易云等其它音源的代码路径里。
 */
internal object BilibiliIntegration {

    fun isBilibili(song: Song): Boolean =
        song.onlineSource == SettingsManager.LIBRARY_SOURCE_BILIBILI && song.onlineId.isNotBlank()

    /** 流媒体没有本地文件可探测，按用户选择的音质给出确定的音频信息。 */
    fun streamInfo(context: Context, song: Song): AudioInfo? {
        if (!isBilibili(song)) return null
        val quality = BilibiliLibraryStore.getInstance(context).audioQuality()
        return AudioInfo(
            format = when (quality) {
                30250 -> "EC3"
                30251 -> "FLAC"
                else -> "AAC"
            },
            bitRate = when (quality) {
                30280 -> 192_000
                30232 -> 132_000
                30216 -> 64_000
                else -> 0
            },
            sampleRate = 44_100,
            channels = 2
        )
    }

    /** 在线歌词；非哔哩哔哩曲目原样返回。 */
    suspend fun lyrics(context: Context, song: Song): Song =
        if (isBilibili(song)) BilibiliLibraryStore.getInstance(context).lyrics(song) else song

    /** 刷新收藏夹/歌单缓存。 */
    suspend fun refresh(context: Context, force: Boolean): List<Song> =
        BilibiliLibraryStore.getInstance(context).refresh(force)

    /** 在线音源不走本地库缓存（必须每次重新拉取）。 */
    fun bypassLibraryCache(source: String): Boolean =
        source == SettingsManager.LIBRARY_SOURCE_BILIBILI
}
