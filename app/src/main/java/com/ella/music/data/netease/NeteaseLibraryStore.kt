package com.ella.music.data.netease

import android.content.Context
import android.util.AtomicFile
import com.ella.music.data.model.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import com.ella.music.data.model.AudioInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Account-scoped online snapshots; local playlists are never overwritten. */
class NeteaseLibraryStore private constructor(private val context: Context) {
    private val accounts = NeteaseAccountStore.getInstance(context)
    private val client = CatClawNeteaseClient(context)
    private val lock = Mutex()
    private val mutablePlaylists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    private val mutableFavorites = MutableStateFlow<List<Song>>(emptyList())
    private val mutableStatus = MutableStateFlow("")
    private val mutableStreamInfo = MutableStateFlow<Map<String, NeteaseStreamInfo>>(emptyMap())
    @Volatile private var playbackProvider = NeteasePlaybackProvider.Official
    private val mutableRecentPlays = MutableStateFlow<List<NeteaseRecentPlay>>(emptyList())
    /** Cloud listening history of the signed-in account; empty when signed out. */
    internal val recentPlays = mutableRecentPlays.asStateFlow()
    @Volatile private var recentPlaysFetchedAt = 0L
    @Volatile private var recentPlaysUserId = 0L
    /** Served quality per song id, updated whenever the player resolves a stream. */
    internal val streamInfo = mutableStreamInfo.asStateFlow()
    val playlists = mutablePlaylists.asStateFlow()
    val favorites = mutableFavorites.asStateFlow()
    val status = mutableStatus.asStateFlow()
    private var snapshotUserId = 0L
    private var loaded = false
    private var metadataNeedsRefresh = false
    private var favoritePlaylistId = ""

    suspend fun refresh(force: Boolean): List<Song> = withContext(Dispatchers.IO) {
        lock.withLock {
            val account = accounts.account.value
            if (!account.loggedIn) { clearMemory(); return@withLock emptyList() }
            if (snapshotUserId != account.userId) {
                clearMemory(); snapshotUserId = account.userId
                readCache(account.userId)?.let(::apply)
            }
            if (!force && loaded && !metadataNeedsRefresh) return@withLock mutableFavorites.value
            try {
                val result = client.collections(account)
                if (accounts.account.value != account) return@withLock emptyList()
                val json = JSONObject().put("metadataVersion", 1).put("userId", account.userId)
                    .put("favoritePlaylistId", result.favoritePlaylistId)
                    .put("favorites", JSONArray().apply { result.favorites.forEach { put(it.toPlaylistSong().toJson()) } })
                    .put("playlists", JSONArray().apply { result.playlists.forEach { put(it.toJson()) } })
                val cache = cache(account.userId)
                val stream = cache.startWrite()
                try { stream.write(json.toString().toByteArray()); cache.finishWrite(stream) }
                catch (e: Exception) { cache.failWrite(stream); throw e }
                apply(result)
                metadataNeedsRefresh = false
                mutableStatus.value = ""
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                // Do not replace a usable snapshot with a failed/partial response.
                mutableStatus.value = if (error is NeteaseApiException && error.code in setOf(301, 302))
                    context.getString(com.ella.music.R.string.netease_session_expired)
                else context.getString(com.ella.music.R.string.netease_sync_failed)
                if (!loaded) throw error
            }
            mutableFavorites.value
        }
    }
    suspend fun login(cookie: String, userId: Long = 0) = withContext(Dispatchers.IO) {
        val account = client.login(cookie, userId)
        lock.withLock { accounts.save(account); clearMemory() }
    }
    suspend fun logout() = withContext(Dispatchers.IO) {
        lock.withLock {
            val userId = accounts.account.value.userId
            accounts.clear(); clearMemory()
            if (userId > 0) {
                cache(userId).delete()
                File(context.filesDir, "remote_library_netease_$userId.json").delete()
            }
        }
    }
    /** 最近解析结果，避免「切歌预热」和「播放器取流」重复请求同一首。 */
    private val recentStreams = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, NeteaseStreamInfo>>()

    /** 每首歌一把锁，避免「切歌预热」与「播放器取流」并发重复请求。 */
    private val songLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** 切歌时预热该曲目的流信息（元数据 + 音频地址）。 */
    suspend fun prefetchStream(songId: String) {
        if (audioInfoFor(songId) != null) return
        runCatching { streamUrl(songId) }
    }

    suspend fun streamUrl(songId: String): String =
        songLocks.getOrPut(songId) { kotlinx.coroutines.sync.Mutex() }.withLock { resolveStreamUrl(songId) }

    private suspend fun resolveStreamUrl(songId: String): String {
        val account = accounts.account.value
        if (!account.loggedIn) {
            android.util.Log.w("NeteasePlayback", "streamUrl($songId): not logged in")
            throw IOException(context.getString(com.ella.music.R.string.netease_session_expired))
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        recentStreams[songId]?.let { (at, info) ->
            if (info.url.isNotBlank() && startedAt - at in 0..8_000L) {
                mutableStreamInfo.update { current -> current + (songId to info) }
                rememberServedQuality(songId, info)
                return info.url
            }
        }
        val quality = com.ella.music.data.SettingsManager.getInstance(context).neteaseQuality.first()
        val resolved = try {
            // 先用用户选择的音质；失败再用 auto 全量降级一次（某些音质在账号无权限时
            // 会直接抛 301/404，而 auto 走标准音质通常仍可取到）。
            runCatching { client.resolveStream(songId, account.cookie, quality) }
                .recoverCatching { first ->
                    android.util.Log.w("NeteasePlayback", "streamUrl($songId) quality=$quality failed, retry auto", first)
                    client.resolveStream(songId, account.cookie, "auto")
                }
                .getOrThrow()
        } catch (t: Throwable) {
            android.util.Log.e(
                "NeteasePlayback",
                "streamUrl($songId) failed after " + (android.os.SystemClock.elapsedRealtime() - startedAt) + "ms quality=" + quality,
                t
            )
            throw t
        }
        android.util.Log.i(
            "NeteasePlayback",
            "streamUrl($songId) ok in " + (android.os.SystemClock.elapsedRealtime() - startedAt) +
                "ms quality=" + quality + " type=" + resolved.type + " level=" + resolved.level +
                " host=" + runCatching { android.net.Uri.parse(resolved.url).host }.getOrNull()
        )
        recentStreams[songId] = startedAt to resolved
        if (recentStreams.size > 32) {
            recentStreams.entries.sortedBy { it.value.first }.drop(recentStreams.size - 32)
                .forEach { recentStreams.remove(it.key) }
        }
        mutableStreamInfo.update { current ->
            val next = current + (songId to resolved)
            if (next.size > 64) next.entries.drop(next.size - 64).associate { it.key to it.value } else next
        }
        rememberServedQuality(songId, resolved)
        return resolved.url
    }
    fun audioInfoFor(songId: String): AudioInfo? =
        (mutableStreamInfo.value[songId] ?: if (playbackProvider == NeteasePlaybackProvider.Official) servedQualityFromCache(songId) else null)
            ?.let(::neteaseStreamAudioInfo)

    internal fun setPlaybackProvider(provider: NeteasePlaybackProvider) {
        if (playbackProvider != provider) {
            playbackProvider = provider
            mutableStreamInfo.value = emptyMap()
        }
    }

    internal fun rememberPluginPlayback(songId: String, mimeType: String) {
        val type = when (mimeType) {
            "audio/flac" -> "flac"
            "audio/mp4" -> "m4a"
            "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/wav" -> "wav"
            else -> ""
        }
        // No official tier, preview flag, or persistent official cache-quality record is reused.
        mutableStreamInfo.update { it + (songId to NeteaseStreamInfo("", "plugin", type, 0, 0)) }
    }

    /**
     * A cached stream is replayed without resolving a URL, so the served tier is remembered per
     * cache key; otherwise the quality badge would fall back to a generic label after a restart.
     */
    private val servedQualityPrefs by lazy { context.getSharedPreferences("netease_served_quality", Context.MODE_PRIVATE) }

    private fun rememberServedQuality(songId: String, info: NeteaseStreamInfo) {
        servedQualityPrefs.edit()
            .putString(NeteaseStreamCache.cacheKey(songId), encodeNeteaseServedQuality(info))
            .apply()
    }

    private fun servedQualityFromCache(songId: String): NeteaseStreamInfo? {
        val raw = servedQualityPrefs.getString(NeteaseStreamCache.cacheKey(songId), null) ?: return null
        return decodeNeteaseServedQuality(raw)
            ?.also { info -> mutableStreamInfo.update { it + (songId to info) } }
    }

    fun clearServedQuality() { servedQualityPrefs.edit().clear().apply() }
    suspend fun toggleFavorite(song: Song) {
        if (song.onlineSource != NETEASE_SOURCE) return
        refresh(false)
        val account = accounts.account.value
        check(account.loggedIn)
        val liked = favoritePlaylistId.takeIf { it.isNotBlank() }
            ?: throw IOException("NetEase favorites playlist unavailable")
        val next = favorites.value.none { it.onlineId == song.onlineId }
        client.setFavorite(liked, song.onlineId, next, account.cookie)
        refresh(true)
    }
    suspend fun lyrics(song: Song): Song {
        val account = accounts.account.value
        if (!account.loggedIn) return song
        return try {
            val lyrics = client.lyrics(song.onlineId, account.cookie)
            song.copy(onlineLyrics = lyrics.original, onlineLyricTranslation = lyrics.translation, onlineLyricPronunciation = lyrics.pronunciation)
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { song }
    }
    private fun apply(value: NeteaseCollections) {
        favoritePlaylistId = value.favoritePlaylistId
        mutableFavorites.value = value.favorites
        mutablePlaylists.value = value.playlists
        loaded = true
    }
    /** Refreshes cloud history at most once per [RECENT_PLAYS_TTL_MS] unless [force]. Failures keep the last list. */
    suspend fun refreshRecentPlays(force: Boolean = false) = withContext(Dispatchers.IO) {
        val account = accounts.account.value
        if (!account.loggedIn) {
            mutableRecentPlays.value = emptyList(); recentPlaysUserId = 0L; recentPlaysFetchedAt = 0L
            return@withContext
        }
        val now = System.currentTimeMillis()
        if (!force && recentPlaysUserId == account.userId && now - recentPlaysFetchedAt < RECENT_PLAYS_TTL_MS) return@withContext
        runCatching { client.recentPlays(account.cookie) }.onSuccess { plays ->
            mutableRecentPlays.value = plays
            recentPlaysUserId = account.userId
            recentPlaysFetchedAt = now
        }
    }

    private fun clearMemory() {
        mutableRecentPlays.value = emptyList(); recentPlaysUserId = 0L; recentPlaysFetchedAt = 0L
        mutablePlaylists.value = emptyList(); mutableFavorites.value = emptyList()
        mutableStatus.value = ""; loaded = false; snapshotUserId = 0L; favoritePlaylistId = ""
    }
    private fun cache(id: Long) = AtomicFile(File(context.filesDir, "netease_collections_$id.json"))
    private fun readCache(id: Long): NeteaseCollections? = runCatching {
        val json = JSONObject(cache(id).readFully().toString(Charsets.UTF_8))
        require(json.getLong("userId") == id)
        metadataNeedsRefresh = json.optInt("metadataVersion") < 1
        val playlists = json.getJSONArray("playlists")
        val favorites = json.getJSONArray("favorites")
        NeteaseCollections(List(favorites.length()) { favorites.getJSONObject(it).toPlaylistSong().toSong() },
            List(playlists.length()) { playlists.getJSONObject(it).toUserPlaylist() }, json.optString("favoritePlaylistId"))
    }.getOrNull()
    companion object {
        private const val RECENT_PLAYS_TTL_MS = 90_000L
        @Volatile private var instance: NeteaseLibraryStore? = null
        fun getInstance(context: Context): NeteaseLibraryStore = instance ?: synchronized(this) {
            instance ?: NeteaseLibraryStore(context.applicationContext).also { instance = it }
        }
    }
}
