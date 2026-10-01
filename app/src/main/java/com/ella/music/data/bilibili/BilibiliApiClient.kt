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

data class BilibiliFavoriteFolder(val id: Long, val title: String, val mediaCount: Int)
internal data class BilibiliLoginQr(val url: String, val qrcodeKey: String)
internal data class BilibiliLoginPoll(val code: Int, val cookies: String)

/** Login QR poll codes from passport.bilibili.com. */
internal object BilibiliQrCode {
    const val SUCCESS = 0
    const val EXPIRED = 86038
    const val SCANNED_NOT_CONFIRMED = 86090
    const val WAIT = 86101
}

internal class BilibiliApiException(val code: Int) : IOException("Bilibili API ($code)")

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
        val mid = data.optLong("mid").takeIf { it > 0 } ?: midHint
        require(mid > 0) { "User ID required" }
        val name = data.optString("name").ifBlank { mid.toString() }
        BilibiliAccount(normalized, mid, name, data.optString("face"))
    }

    suspend fun generateLoginQr(): BilibiliLoginQr = withContext(Dispatchers.IO) {
        val json = getFull("https://passport.bilibili.com/x/passport-login/web/qrcode/generate", emptyMap())
        BilibiliLoginQr(url = json.optString("url"), qrcodeKey = json.optString("qrcode_key"))
    }

    suspend fun pollLoginQr(qrcodeKey: String): BilibiliLoginPoll = withContext(Dispatchers.IO) {
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" +
            URLEncoder.encode(qrcodeKey, "UTF-8")
        val request = Request.Builder().url(url)
            .header("User-Agent", BILIBILI_USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: JSONObject()
            val cookies = response.headers.values("Set-Cookie").joinToString("; ") { it.substringBefore(';') }
            BilibiliLoginPoll(code = data.optInt("code", -1), cookies = cookies)
        }
    }

    // ---- 收藏夹 ----

    suspend fun favoriteFolders(): List<BilibiliFavoriteFolder> = withContext(Dispatchers.IO) {
        val account = accounts.account.value
        check(account.loggedIn) { "Bilibili login required" }
        val data = get("/x/v3/fav/folder/created/list-all", mapOf("up_mid" to account.mid.toString()), account.cookie)
        val list = data.optJSONArray("list") ?: JSONArray()
        (0 until list.length()).map { i ->
            val row = list.getJSONObject(i)
            BilibiliFavoriteFolder(
                id = row.optLong("id"),
                title = row.optString("title"),
                mediaCount = row.optInt("media_count")
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
        while (pn <= 200) {
            val data = get(
                "/x/v3/fav/resource/list",
                mapOf("media_id" to folderId.toString(), "pn" to pn.toString(), "ps" to "40"),
                account.cookie
            )
            val medias = data.optJSONArray("medias") ?: JSONArray()
            if (medias.length() == 0) break
            for (i in 0 until medias.length()) {
                val row = medias.getJSONObject(i)
                if (row.optInt("type") == 2) parseBilibiliSong(row)?.let { songs += it }
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
        (0 until result.length()).mapNotNull { i -> parseBilibiliSearchSong(result.getJSONObject(i)) }
    }

    // ---- 播放 ----

    suspend fun resolveStream(bvid: String): String = withContext(Dispatchers.IO) {
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
                "fourk" to "1"
            )
        )
        val play = getRaw("/x/player/wbi/playurl?$query", account.cookie)
        val dash = play.optJSONObject("dash")
        val audio0 = dash?.optJSONArray("audio")?.optJSONObject(0)
        audio0?.optString("baseUrl")?.takeIf { it.isNotBlank() }
            ?: audio0?.optJSONArray("backupUrl")?.optString(0)?.takeIf { it.isNotBlank() }
            ?: play.optJSONArray("durl")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
            ?: throw IOException("Audio stream unavailable")
    }

    // ---- 基础请求 ----

    private fun get(path: String, params: Map<String, String>, cookie: String): JSONObject =
        execute(buildUrl("https://api.bilibili.com$path", params), cookie)

    private fun getRaw(pathAndQuery: String, cookie: String): JSONObject =
        execute("https://api.bilibili.com$pathAndQuery", cookie)

    private fun getFull(url: String, params: Map<String, String>): JSONObject =
        execute(buildUrl(url, params), "")

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

    private fun executeRequest(request: Request): JSONObject =
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Bilibili HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val code = json.optInt("code", 0)
            if (code != 0) throw BilibiliApiException(code)
            json.optJSONObject("data") ?: JSONObject()
        }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        if (params.isEmpty()) return base
        val encoded = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$base?$encoded"
    }

    private fun normalizeCookie(cookie: String): String =
        bilibiliCookies(cookie).entries.joinToString("; ") { "${it.key}=${it.value}" }
}

internal fun parseBilibiliSong(json: JSONObject): Song? {
    val bvid = json.optString("bvid").takeIf { it.isNotBlank() } ?: return null
    val title = json.optString("title").ifBlank { return null }
    val upper = json.optJSONObject("upper") ?: json.optJSONObject("owner") ?: JSONObject()
    val artist = upper.optString("name").ifBlank { json.optString("author") }
    val durationSec = json.optLong("duration", 0L)
    return Song(
        id = bvid.hashCode().toLong(),
        title = title,
        artist = artist,
        album = "",
        albumId = 0L,
        duration = durationSec * 1000L,
        path = "$BILIBILI_SCHEME://video/$bvid",
        fileName = title,
        mimeType = "audio/mpeg",
        coverUrl = json.optString("cover").ifBlank { json.optString("pic") },
        onlineSource = BILIBILI_SOURCE,
        onlineId = bvid
    )
}

internal fun parseBilibiliSearchSong(json: JSONObject): Song? {
    val bvid = json.optString("bvid").takeIf { it.isNotBlank() } ?: return null
    val title = json.optString("title").replace(Regex("<[^>]+>"), "").trim().ifBlank { return null }
    val artist = json.optString("author")
    return Song(
        id = bvid.hashCode().toLong(),
        title = title,
        artist = artist,
        album = "",
        albumId = 0L,
        duration = parseBilibiliDuration(json.optString("duration")),
        path = "$BILIBILI_SCHEME://video/$bvid",
        fileName = title,
        mimeType = "audio/mpeg",
        coverUrl = json.optString("pic"),
        onlineSource = BILIBILI_SOURCE,
        onlineId = bvid
    )
}

private fun parseBilibiliDuration(text: String): Long {
    if (text.isBlank()) return 0L
    val parts = text.trim().split(':')
    if (parts.isEmpty()) return 0L
    var seconds = 0L
    for (part in parts) seconds = seconds * 60 + (part.toLongOrNull() ?: 0L)
    return seconds * 1000L
}
