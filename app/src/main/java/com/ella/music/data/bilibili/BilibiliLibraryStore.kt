package com.ella.music.data.bilibili

import android.content.Context
import android.util.AtomicFile
import com.ella.music.R
import com.ella.music.data.model.Song
import com.ella.music.data.model.toPlaylistSong
import com.ella.music.data.model.toJson
import com.ella.music.data.model.toSong
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Account-scoped Bilibili library snapshot; local playlists are never overwritten. */
class BilibiliLibraryStore private constructor(private val context: Context) {
    private val accounts = BilibiliAccountStore.getInstance(context)
    private val client = BilibiliApiClient(context)
    private val lock = Mutex()
    private val mutableSongs = MutableStateFlow<List<Song>>(emptyList())
    private val mutableFolders = MutableStateFlow<List<BilibiliFavoriteFolder>>(emptyList())
    private val mutableStatus = MutableStateFlow("")

    val songs = mutableSongs.asStateFlow()
    val folders = mutableFolders.asStateFlow()
    val status = mutableStatus.asStateFlow()

    private val prefs = context.getSharedPreferences("bilibili_library", Context.MODE_PRIVATE)
    private var snapshotUserId = 0L
    private var loaded = false

    suspend fun refresh(force: Boolean): List<Song> = withContext(Dispatchers.IO) {
        lock.withLock {
            val account = accounts.account.value
            if (!account.loggedIn) { clearMemory(); return@withLock emptyList() }
            if (snapshotUserId != account.mid) {
                clearMemory(); snapshotUserId = account.mid
                readCache(account.mid)?.let { mutableSongs.value = it; loaded = true }
            }
            if (!force && loaded) return@withLock mutableSongs.value
            try {
                val folderId = selectedFolderId()
                val result = if (folderId > 0) client.favoriteFolderSongs(folderId) else emptyList()
                if (accounts.account.value != account) return@withLock emptyList()
                writeCache(account.mid, result)
                mutableSongs.value = result
                loaded = true
                mutableStatus.value = ""
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                mutableStatus.value = if (error is BilibiliApiException && error.code == -101)
                    context.getString(R.string.bilibili_session_expired)
                else context.getString(R.string.bilibili_sync_failed)
                if (!loaded) throw error
            }
            mutableSongs.value
        }
    }

    suspend fun refreshFolders() = withContext(Dispatchers.IO) {
        val account = accounts.account.value
        if (!account.loggedIn) { mutableFolders.value = emptyList(); return@withContext }
        runCatching { client.favoriteFolders() }.onSuccess { mutableFolders.value = it }
    }

    suspend fun login(cookie: String, midHint: Long = 0) = withContext(Dispatchers.IO) {
        val account = client.login(cookie, midHint)
        lock.withLock { accounts.save(account); clearMemory() }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        lock.withLock {
            val mid = accounts.account.value.mid
            accounts.clear(); clearMemory()
            if (mid > 0) cache(mid).delete()
        }
    }

    suspend fun createFolder(title: String): Long = withContext(Dispatchers.IO) {
        val id = client.createFolder(title)
        refreshFolders()
        id
    }

    fun selectedFolderId(): Long = prefs.getLong("favorite_folder_id", 0L)

    fun selectFolder(id: Long) {
        prefs.edit().putLong("favorite_folder_id", id).apply()
        clearMemory()
    }

    // ---- 字幕歌词配置（主行/副行/注音的语言优先级）----

    fun subtitleMainLang(): String = prefs.getString("subtitle_main", "auto") ?: "auto"
    fun subtitleSecondaryLang(): String = prefs.getString("subtitle_secondary", "zh") ?: "zh"
    fun subtitlePronunciationLang(): String = prefs.getString("subtitle_pronunciation", "none") ?: "none"
    fun subtitlePreferNonAi(): Boolean = prefs.getBoolean("subtitle_prefer_non_ai", true)

    fun setSubtitleMainLang(v: String) { prefs.edit().putString("subtitle_main", v).apply() }
    fun setSubtitleSecondaryLang(v: String) { prefs.edit().putString("subtitle_secondary", v).apply() }
    fun setSubtitlePronunciationLang(v: String) { prefs.edit().putString("subtitle_pronunciation", v).apply() }
    fun setSubtitlePreferNonAi(v: Boolean) { prefs.edit().putBoolean("subtitle_prefer_non_ai", v).apply() }

    suspend fun streamUrl(bvid: String): String {
        val account = accounts.account.value
        if (!account.loggedIn) throw IOException(context.getString(R.string.bilibili_session_expired))
        return client.resolveStream(bvid)
    }

    /** 取视频全部 CC 字幕并按用户配置映射到 主歌词/副歌词/罗马音。 */
    suspend fun lyrics(song: Song): Song {
        return try {
            val tracks = client.fetchSubtitles(song.onlineId)
            if (tracks.isEmpty()) return song
            val preferNonAi = subtitlePreferNonAi()
            val sorted = tracks.sortedWith(
                compareBy<BilibiliSubtitleTrack> { if (preferNonAi && it.isAi) 1 else 0 }.thenBy { it.id }
            )
            val main = pickSubtitleTrack(sorted, subtitleMainLang(), emptySet())
            val secondary = pickSubtitleTrack(sorted, subtitleSecondaryLang(), setOfNotNull(main?.id))
            val pronunciation = pickSubtitleTrack(sorted, subtitlePronunciationLang(), setOfNotNull(main?.id, secondary?.id))
            song.copy(
                onlineLyrics = main?.lrc ?: "",
                onlineLyricTranslation = secondary?.lrc ?: "",
                onlineLyricPronunciation = pronunciation?.lrc ?: ""
            )
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { song }
    }

    private fun pickSubtitleTrack(tracks: List<BilibiliSubtitleTrack>, lang: String, usedIds: Set<Long>): BilibiliSubtitleTrack? {
        if (lang == "none") return null
        val available = tracks.filter { it.id !in usedIds }
        if (available.isEmpty()) return null
        if (lang == "auto") return available.first()
        val target = lang.lowercase()
        return available.firstOrNull { t ->
            val lan = t.lan.lowercase()
            lan == target || lan.startsWith(target + "-") || lan.startsWith("ai-" + target)
        } ?: available.first()
    }

    private fun cache(userId: Long) = AtomicFile(File(context.filesDir, "remote_library_bilibili_$userId.json"))

    private fun readCache(userId: Long): List<Song>? = runCatching {
        val bytes = cache(userId).readFully()
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val arr = root.optJSONArray("songs") ?: return@runCatching emptyList()
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toPlaylistSong()?.toSong() }
    }.getOrNull()

    private fun writeCache(userId: Long, songs: List<Song>) {
        val json = JSONObject().put("songs", JSONArray().apply {
            songs.forEach { put(it.toPlaylistSong().toJson()) }
        })
        val c = cache(userId)
        val stream = c.startWrite()
        try { stream.write(json.toString().toByteArray()); c.finishWrite(stream) }
        catch (e: Exception) { c.failWrite(stream); throw e }
    }

    private fun clearMemory() {
        mutableSongs.value = emptyList(); mutableFolders.value = emptyList()
        mutableStatus.value = ""; loaded = false; snapshotUserId = 0L
    }

    companion object {
        @Volatile private var instance: BilibiliLibraryStore? = null
        fun getInstance(context: Context): BilibiliLibraryStore = instance ?: synchronized(this) {
            instance ?: BilibiliLibraryStore(context.applicationContext).also { instance = it }
        }
    }
}
