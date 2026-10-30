package com.ella.music.data.bilibili

import android.content.Context
import com.ella.music.data.AppNetworkLoggingInterceptor
import com.ella.music.data.model.Song
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal const val BILIBILI_SOURCE = "bilibili"
internal const val BILIBILI_SCHEME = "halcyon-bilibili"
internal const val BILIBILI_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

data class BilibiliFavoriteFolder(val id: Long, val title: String, val mediaCount: Int, val cover: String = "")
internal data class BilibiliLoginQr(val url: String, val qrcodeKey: String)
internal data class BilibiliLoginPoll(val code: Int, val cookies: String)
data class BilibiliSubtitleTrack(val id: Long, val lan: String, val lanDoc: String, val isAi: Boolean, val lrc: String)

/** A resolved, playable audio URL for one video. */
internal data class BilibiliAudio(val url: String, val mime: String = "")

/** Login QR poll codes from passport.bilibili.com. */
internal object BilibiliQrCode {
    const val SUCCESS = 0
    const val EXPIRED = 86038
    const val SCANNED_NOT_CONFIRMED = 86090
    const val WAIT = 86101
}

internal class BilibiliApiException(val code: Int) : IOException("Bilibili API (" + code + ")")

internal class BilibiliApiClient(context: Context) {
    private val accounts = BilibiliAccountStore.getInstance(context)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(AppNetworkLoggingInterceptor("BilibiliNetwork"))
        .build()
    private val wbi = BilibiliWbi(http)

    // ---- 登录 ----

    suspend fun login(cookie: String, midHint: Long = 0): BilibiliAccount = withContext(Dispatchers.IO) {
        val normalized = normalizeCookie(cookie)
        require(bilibiliCookies(normalized)["SESSDATA"].orEmpty().isNotBlank()) { "SESSDATA session required" }
        val data = get("/x/space/myinfo", emptyMap(), normalized)
        val mid = data.optLong("mid").takeIf { it > 0 }
            ?: midHint.takeIf { it > 0 }
            ?: bilibiliCookies(normalized)["DedeUserID"]?.toLongOrNull()
            ?: 0L
        require(mid > 0) { "User ID required" }
        val name = data.optString("name").ifBlank { mid.toString() }
        BilibiliAccount(normalized, mid, name, normalizeImage(data.optString("face")))
    }

    // ---- 扫码登录（PC 二维码，参考 BBPlayer）----

    suspend fun generateLoginQr(): BilibiliLoginQr = withContext(Dispatchers.IO) {
        val root = executeRoot("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
        val data = root.optJSONObject("data") ?: JSONObject()
        BilibiliLoginQr(url = data.optString("url"), qrcodeKey = data.optString("qrcode_key"))
    }

    suspend fun pollLoginQr(qrcodeKey: String): BilibiliLoginPoll = withContext(Dispatchers.IO) {
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" +
            URLEncoder.encode(qrcodeKey, "UTF-8")
        val request = Request.Builder().url(url)
            .header("Referer", "https://www.bilibili.com/")
            .header("User-Agent", BILIBILI_USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val root = runCatching { JSONObject(body) }.getOrNull() ?: JSONObject()
            val data = root.optJSONObject("data") ?: JSONObject()
            val cookies = response.headers.values("Set-Cookie")
                .joinToString("; ") { it.substringBefore(';') }
            BilibiliLoginPoll(code = data.optInt("code", -1), cookies = cookies)
        }
    }

    // ---- 收藏夹（歌单）----

    suspend fun favoriteFolders(): List<BilibiliFavoriteFolder> = withContext(Dispatchers.IO) {
        val account = accounts.account.value
        check(account.loggedIn) { "Bilibili login required" }
        val data = get("/x/v3/fav/folder/created/list-all", mapOf("up_mid" to account.mid.toString()), account.cookie)
        val list = data.optJSONArray("list") ?: JSONArray()
        (0 until list.length()).mapNotNull { i ->
            val row = list.optJSONObject(i) ?: return@mapNotNull null
            BilibiliFavoriteFolder(
                id = row.optLong("id"),
                title = row.optString("title"),
                mediaCount = row.optInt("media_count"),
                cover = normalizeImage(row.optString("cover"))
            )
        }.filter { it.id > 0 }
    }

    suspend fun createFolder(title: String): Long = withContext(Dispatchers.IO) {
        val account = accounts.account.value
        check(account.loggedIn) { "Bilibili login required" }
        val csrf = bilibiliCookies(account.cookie)["bili_jct"].orEmpty()
        require(csrf.isNotBlank()) { "CSRF token (bili_jct) required" }
        val body = FormBody.Builder()
            .add("title", title.trim())
            .add("intro", "")
            .add("privacy", "0")
            .add("csrf", csrf)
            .build()
        val data = post("/x/v3/fav/folder/add", body, account.cookie)
        data.optLong("id").takeIf { it > 0 } ?: throw IOException("Create folder failed")
    }

    suspend fun favoriteFolderSongs(folderId: Long): List<Song> = withContext(Dispatchers.IO) {
        val account = accounts.account.value
        check(account.loggedIn) { "Bilibili login required" }
        val songs = mutableListOf<Song>()
        var pn = 1
        while (pn <= 500) {
            val data = get(
                "/x/v3/fav/resource/list",
                mapOf("media_id" to folderId.toString(), "pn" to pn.toString(), "ps" to "40"),
                account.cookie
            )
            val medias = data.optJSONArray("medias") ?: JSONArray()
            if (medias.length() == 0) break
            for (i in 0 until medias.length()) {
                val row = medias.optJSONObject(i) ?: continue
                if (row.optInt("type") != 2) continue
                parseBilibiliSong(row)?.let { songs += it }
            }
            if (!data.optBoolean("has_more", false)) break
            pn++
        }
        songs
    }

    // ---- 搜索 ----

    suspend fun searchVideos(keyword: String, page: Int = 1): List<Song> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val account = accounts.account.value
        val query = wbi.sign(
            mapOf(
                "keyword" to keyword.trim(),
                "search_type" to "video",
                "page" to page.coerceAtLeast(1).toString()
            )
        )
        val data = getRaw("/x/web-interface/wbi/search/type?$query", account.cookie)
        val result = data.optJSONArray("result") ?: JSONArray()
        (0 until result.length()).mapNotNull { i ->
            val row = result.optJSONObject(i) ?: return@mapNotNull null
            if (row.optString("type") == "ketang") return@mapNotNull null
            parseBilibiliSearchSong(row)
        }
    }

    // ---- 视频信息 ----

    suspend fun videoCid(bvid: String): Long = withContext(Dispatchers.IO) {
        val account = accounts.account.value
        val view = get("/x/web-interface/view", mapOf("bvid" to bvid), account.cookie)
        view.optLong("cid").takeIf { it > 0 }
            ?: view.optJSONArray("pages")?.optJSONObject(0)?.optLong("cid")?.takeIf { it > 0 }
            ?: throw IOException("Video cid unavailable")
    }

    // ---- 播放（参考 BBPlayer：dolby / hi-res / 普通音频流优先级，durl 回退）----

    suspend fun resolveAudio(
        bvid: String,
        enableDolby: Boolean = false,
        enableHiRes: Boolean = false
    ): BilibiliAudio = withContext(Dispatchers.IO) {
        val account = accounts.account.value
        val view = get("/x/web-interface/view", mapOf("bvid" to bvid), account.cookie)
        val cid = view.optLong("cid").takeIf { it > 0 }
            ?: view.optJSONArray("pages")?.optJSONObject(0)?.optLong("cid")?.takeIf { it > 0 }
            ?: throw IOException("Video cid unavailable")
        val query = wbi.sign(
            mapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "fnval" to "4048",
                "fnver" to "0",
                "fourk" to "1",
                "qlt" to "30280",
                "voice_balance" to "1"
            )
        )
        val play = getRaw("/x/player/wbi/playurl?$query", account.cookie)
        val dash = play.optJSONObject("dash")

        if (dash != null) {
            if (enableDolby) {
                val dolby = dash.optJSONObject("dolby")?.optJSONArray("audio")?.optJSONObject(0)
                val url = audioUrl(dolby)
                if (url.isNotBlank()) return@withContext BilibiliAudio(url, dolby?.optString("mimeType").orEmpty())
            }
            if (enableHiRes) {
                val flac = dash.optJSONObject("flac")?.optJSONObject("audio")
                val url = audioUrl(flac)
                if (url.isNotBlank()) return@withContext BilibiliAudio(url, flac?.optString("mimeType").orEmpty())
            }
            val audioArray = dash.optJSONArray("audio")
            if (audioArray != null && audioArray.length() > 0) {
                val best = (0 until audioArray.length())
                    .mapNotNull { audioArray.optJSONObject(it) }
                    .maxByOrNull { it.optLong("bandwidth", 0L) }
                val url = audioUrl(best)
                if (url.isNotBlank()) return@withContext BilibiliAudio(url, best?.optString("mimeType").orEmpty())
            }
        }
        val durl = play.optJSONArray("durl")?.optJSONObject(0)?.optString("url").orEmpty()
        if (durl.isNotBlank()) return@withContext BilibiliAudio(durl, "video/mp4")
        throw IOException("Audio stream unavailable")
    }

    private fun audioUrl(node: JSONObject?): String {
        if (node == null) return ""
        node.optString("baseUrl").takeIf { it.isNotBlank() }?.let { return it }
        val backups = node.optJSONArray("backupUrl")
        if (backups != null) {
            for (i in 0 until backups.length()) {
                val candidate = backups.optString(i)
                if (candidate.isNotBlank()) return candidate
            }
        }
        return ""
    }

    // ---- 评论 ----

    private val aidCache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun aidOf(bvid: String): Long {
        aidCache[bvid]?.let { return it }
        val view = get("/x/web-interface/view", mapOf("bvid" to bvid), accounts.account.value.cookie)
        val aid = view.optLong("aid").takeIf { it > 0 } ?: throw IOException("Video aid unavailable")
        aidCache[bvid] = aid
        return aid
    }

    fun signedInAccount(): BilibiliAccount? = accounts.account.value.takeIf { it.loggedIn }

    suspend fun songComments(
        songId: String,
        resource: BilibiliCommentResource,
        sortType: Int,
        pageNo: Int,
        cursor: String = ""
    ): BilibiliCommentPage = withContext(Dispatchers.IO) {
        val oid = aidOf(songId)
        val root = executeRoot(
            "https://api.bilibili.com/x/v2/reply?type=1&oid=" + oid +
                "&pn=" + pageNo.coerceAtLeast(1) + "&ps=20&sort=" + sortType,
            accounts.account.value.cookie
        )
        val code = root.optInt("code", 0)
        if (code != 0) throw BilibiliApiException(code)
        parseBilibiliCommentPage(root, sortType, pageNo)
    }

    suspend fun commentFloor(
        songId: String,
        resource: BilibiliCommentResource,
        parentCommentId: Long,
        time: Long
    ): BilibiliFloorPage = withContext(Dispatchers.IO) {
        val oid = aidOf(songId)
        val pageNo = time.toInt().coerceAtLeast(1)
        val root = executeRoot(
            "https://api.bilibili.com/x/v2/reply/reply?type=1&oid=" + oid +
                "&root=" + parentCommentId + "&pn=" + pageNo + "&ps=20",
            accounts.account.value.cookie
        )
        val code = root.optInt("code", 0)
        if (code != 0) throw BilibiliApiException(code)
        parseBilibiliFloorPage(root, pageNo + 1)
    }

    suspend fun likeComment(
        songId: String,
        resource: BilibiliCommentResource,
        commentId: Long,
        like: Boolean
    ) = withContext(Dispatchers.IO) {
        val account = accounts.account.value
        check(account.loggedIn) { "Bilibili login required" }
        val csrf = bilibiliCookies(account.cookie)["bili_jct"].orEmpty()
        require(csrf.isNotBlank()) { "CSRF token (bili_jct) required" }
        val oid = aidOf(songId)
        val body = FormBody.Builder()
            .add("oid", oid.toString())
            .add("type", "1")
            .add("rpid", commentId.toString())
            .add("action", if (like) "1" else "0")
            .add("csrf", csrf)
            .build()
        post("/x/v2/reply/action", body, account.cookie)
    }

    suspend fun postComment(
        songId: String,
        resource: BilibiliCommentResource,
        content: String,
        replyToCommentId: Long,
        parentCommentId: Long
    ): BilibiliComment? = withContext(Dispatchers.IO) {
        val account = accounts.account.value
        check(account.loggedIn) { "Bilibili login required" }
        val csrf = bilibiliCookies(account.cookie)["bili_jct"].orEmpty()
        require(csrf.isNotBlank()) { "CSRF token (bili_jct) required" }
        val oid = aidOf(songId)
        val builder = FormBody.Builder()
            .add("oid", oid.toString())
            .add("type", "1")
            .add("message", content)
            .add("csrf", csrf)
        if (parentCommentId > 0) {
            builder.add("root", parentCommentId.toString())
            builder.add("parent", (if (replyToCommentId > 0) replyToCommentId else parentCommentId).toString())
        }
        val data = post("/x/v2/reply/add", builder.build(), account.cookie)
        data.optJSONObject("reply")?.let { parseBilibiliComment(it) }
    }

    // ---- 字幕（CC 字幕）----

    /** 拉取视频的全部 CC 字幕轨道（含 AI 字幕），每个轨道转换为 LRC 文本。 */
    suspend fun fetchSubtitles(bvid: String): List<BilibiliSubtitleTrack> = withContext(Dispatchers.IO) {
        if (bvid.isBlank()) return@withContext emptyList()
        val account = accounts.account.value
        val view = get("/x/web-interface/view", mapOf("bvid" to bvid), account.cookie)
        val cid = view.optLong("cid").takeIf { it > 0 }
            ?: view.optJSONArray("pages")?.optJSONObject(0)?.optLong("cid")?.takeIf { it > 0 }
            ?: return@withContext emptyList()
        val query = wbi.sign(mapOf("bvid" to bvid, "cid" to cid.toString()))
        val play = getRaw("/x/player/wbi/v2?$query", account.cookie)
        val subtitles = play.optJSONObject("subtitle")?.optJSONArray("subtitles")
            ?: return@withContext emptyList()
        val tracks = mutableListOf<BilibiliSubtitleTrack>()
        for (i in 0 until subtitles.length()) {
            val item = subtitles.optJSONObject(i) ?: continue
            val subtitleUrl = item.optString("subtitle_url").ifBlank { continue }
            val full = if (subtitleUrl.startsWith("//")) "https:$subtitleUrl" else subtitleUrl
            val lrc = runCatching {
                val request = Request.Builder().url(full)
                    .header("Referer", "https://www.bilibili.com/")
                    .header("User-Agent", BILIBILI_USER_AGENT)
                    .build()
                val body = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching ""
                    response.body?.string().orEmpty()
                }
                parseSubtitleBody(body)
            }.getOrNull().orEmpty()
            if (lrc.isBlank()) continue
            val type = item.optInt("type", 0)
            val aiStatus = item.optInt("ai_status", 0)
            tracks += BilibiliSubtitleTrack(
                id = item.optLong("id").takeIf { it > 0 } ?: i.toLong(),
                lan = item.optString("lan"),
                lanDoc = item.optString("lan_doc").ifBlank { item.optString("lan") },
                isAi = type == 1 || aiStatus > 0,
                lrc = lrc
            )
        }
        tracks
    }

    private fun parseSubtitleBody(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val items = root.optJSONArray("body") ?: return null
        val sb = StringBuilder()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val fromSec = item.optDouble("from", 0.0)
            val content = item.optString("content").trim()
            if (content.isBlank()) continue
            sb.append('[').append(formatLrcTime(fromSec)).append("] ").append(content).append('\n')
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun formatLrcTime(seconds: Double): String {
        val total = seconds.toLong()
        val mm = total / 60
        val ss = total % 60
        val hundredths = ((seconds - total) * 100).toInt().coerceIn(0, 99)
        fun pad2(v: Long) = v.toString().padStart(2, '0')
        return pad2(mm) + ":" + pad2(ss) + "." + pad2(hundredths.toLong())
    }

    // ---- 基础请求 ----

    private fun get(path: String, params: Map<String, String>, cookie: String): JSONObject =
        execute(buildUrl("https://api.bilibili.com$path", params), cookie)

    private fun getRaw(pathAndQuery: String, cookie: String): JSONObject =
        execute("https://api.bilibili.com$pathAndQuery", cookie)

    private fun post(path: String, body: FormBody, cookie: String): JSONObject {
        val request = Request.Builder().url("https://api.bilibili.com$path").post(body)
            .header("Referer", "https://www.bilibili.com/")
            .header("User-Agent", BILIBILI_USER_AGENT)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        return executeRequest(request)
    }

    private fun execute(url: String, cookie: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("Referer", "https://www.bilibili.com/")
            .header("User-Agent", BILIBILI_USER_AGENT)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        return executeRequest(request)
    }

    private fun executeRoot(url: String, cookie: String = ""): JSONObject {
        val request = Request.Builder().url(url)
            .header("Referer", "https://www.bilibili.com/")
            .header("User-Agent", BILIBILI_USER_AGENT)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Bilibili HTTP " + response.code)
            JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun executeRequest(request: Request): JSONObject =
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Bilibili HTTP " + response.code)
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val code = json.optInt("code", 0)
            if (code != 0) throw BilibiliApiException(code)
            json.optJSONObject("data") ?: JSONObject()
        }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val encoded = params.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        return "$base?$encoded"
    }

    private fun normalizeCookie(cookie: String): String =
        bilibiliCookies(cookie).entries.joinToString("; ") { it.key + "=" + it.value }
}

private fun normalizeImage(url: String): String {
    val value = url.trim()
    if (value.isBlank()) return ""
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://" + value.removePrefix("http://")
        else -> value
    }
}

internal fun parseBilibiliSong(json: JSONObject): Song? {
    val bvid = json.optString("bvid").takeIf { it.isNotBlank() }
        ?: json.optString("bv_id").takeIf { it.isNotBlank() }
        ?: return null
    val title = json.optString("title").ifBlank { return null }
    val upper = json.optJSONObject("upper") ?: json.optJSONObject("owner") ?: JSONObject()
    val artist = upper.optString("name").ifBlank { json.optString("author") }
    val durationSec = json.optLong("duration", 0L)
    val cover = normalizeImage(json.optString("cover").ifBlank { json.optString("pic") })
    return Song(
        id = stableBilibiliId(bvid),
        title = title,
        artist = artist,
        album = "",
        albumId = 0L,
        duration = durationSec * 1000L,
        path = "$BILIBILI_SCHEME://video/$bvid",
        fileName = title,
        mimeType = "",
        coverUrl = cover,
        onlineSource = BILIBILI_SOURCE,
        onlineId = bvid
    )
}

internal fun parseBilibiliSearchSong(json: JSONObject): Song? {
    val bvid = json.optString("bvid").takeIf { it.isNotBlank() } ?: return null
    val title = json.optString("title").replace(Regex("<[^>]+>"), "").replace("&amp;", "&").trim()
        .ifBlank { return null }
    val artist = json.optString("author")
    return Song(
        id = stableBilibiliId(bvid),
        title = title,
        artist = artist,
        album = "",
        albumId = 0L,
        duration = parseBilibiliDuration(json.optString("duration")),
        path = "$BILIBILI_SCHEME://video/$bvid",
        fileName = title,
        mimeType = "",
        coverUrl = normalizeImage(json.optString("pic")),
        onlineSource = BILIBILI_SOURCE,
        onlineId = bvid
    )
}

private fun stableBilibiliId(bvid: String): Long {
    val value = bvid.hashCode().toLong()
    return if (value == Long.MIN_VALUE) 1L else kotlin.math.abs(value).takeIf { it != 0L } ?: 1L
}

private fun parseBilibiliDuration(text: String): Long {
    if (text.isBlank()) return 0L
    val parts = text.trim().split(':')
    if (parts.isEmpty()) return 0L
    var seconds = 0L
    for (part in parts) seconds = seconds * 60 + (part.toLongOrNull() ?: 0L)
    return seconds * 1000L
}
