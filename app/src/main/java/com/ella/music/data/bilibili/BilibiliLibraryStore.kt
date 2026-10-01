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

    suspend fun streamUrl(bvid: String): String {
        val account = accounts.account.value
        if (!account.loggedIn) throw IOException(context.getString(R.string.bilibili_session_expired))
        return client.resolveStream(bvid)
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
