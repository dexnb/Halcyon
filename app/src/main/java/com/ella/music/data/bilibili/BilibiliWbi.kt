package com.ella.music.data.bilibili

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/** Bilibili WBI 签名。参考 socialSisterYi/bilibili-API-collect 与 BBPlayer。 */
internal class BilibiliWbi(private val client: OkHttpClient) {

    private data class WbiKeys(val imgKey: String, val subKey: String, val fetchedAt: Long)

    @Volatile private var cached: WbiKeys? = null
    private val cacheLock = Any()
    private val keyTtlMs = 24L * 3600_000L

    private fun mixinKey(orig: String): String {
        val sb = StringBuilder(32)
        for (i in MIXIN_KEY_ENC_TAB) {
            if (i < orig.length) sb.append(orig[i])
        }
        return sb.toString().take(32)
    }

    private fun keys(): WbiKeys {
        val now = System.currentTimeMillis()
        cached?.let { if (now - it.fetchedAt < keyTtlMs) return it }
        synchronized(cacheLock) {
            cached?.let { if (now - it.fetchedAt < keyTtlMs) return it }
            val request = Request.Builder().url("https://api.bilibili.com/x/web-interface/nav")
                .header("Referer", "https://www.bilibili.com/")
                .header("User-Agent", BILIBILI_USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val root = JSONObject(body)
                val wbi = root.optJSONObject("data")?.optJSONObject("wbi_img") ?: JSONObject()
                val imgKey = wbi.optString("img_url").substringAfterLast('/').substringBefore('.')
                val subKey = wbi.optString("sub_url").substringAfterLast('/').substringBefore('.')
                require(imgKey.isNotBlank() && subKey.isNotBlank()) { "WBI keys unavailable" }
                val keys = WbiKeys(imgKey, subKey, now)
                cached = keys
                return keys
            }
        }
    }

    /** Returns the query string with wts + w_rid appended (no leading '?'). */
    fun sign(params: Map<String, String>): String {
        val keys = keys()
        val mixin = mixinKey(keys.imgKey + keys.subKey)
        val sorted = (params + ("wts" to (System.currentTimeMillis() / 1000).toString())).toSortedMap()
        val query = sorted.entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v.replace(Regex("[!'()*]"), ""))}"
        }
        val wrid = md5(query + mixin)
        return "$query&w_rid=$wrid"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52
        )
    }
}
