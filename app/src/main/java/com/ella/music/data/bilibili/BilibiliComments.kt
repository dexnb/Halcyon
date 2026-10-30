package com.ella.music.data.bilibili

import org.json.JSONArray
import org.json.JSONObject

/** Sort modes of `/x/v2/reply`; [apiValue] is the request `sort` (0=time, 1=like, 2=replies). */
internal enum class BilibiliCommentSort(val apiValue: Int) {
    Recommend(2),
    Hot(1),
    Latest(0);
}

internal data class BilibiliCommentUser(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String,
    /** Bilibili user level (LV0-LV6). */
    val level: Int = 0,
    val vipIconUrl: String = ""
)

/** The comment a reply answers ("回复 @nickname:"). */
internal data class BilibiliRepliedComment(
    val commentId: Long,
    val nickname: String,
    val content: String
)

internal data class BilibiliComment(
    val id: Long,
    val user: BilibiliCommentUser,
    val content: String,
    val timeMs: Long,
    val timeStr: String,
    val ipLocation: String,
    val likedCount: Long,
    val replyCount: Int,
    val parentCommentId: Long,
    val replyTo: BilibiliRepliedComment?,
    val liked: Boolean = false
)

internal const val BILIBILI_COMMENT_MAX_LENGTH = 1000

internal fun bilibiliCommentLength(text: String): Int = text.codePointCount(0, text.length)

internal fun clipBilibiliComment(text: String): String =
    if (bilibiliCommentLength(text) <= BILIBILI_COMMENT_MAX_LENGTH) text
    else text.substring(0, text.offsetByCodePoints(0, BILIBILI_COMMENT_MAX_LENGTH))

internal fun BilibiliComment.withLiked(liked: Boolean): BilibiliComment =
    if (liked == this.liked) this
    else copy(liked = liked, likedCount = (likedCount + if (liked) 1 else -1).coerceAtLeast(0))

internal data class BilibiliCommentPage(
    val comments: List<BilibiliComment>,
    val totalCount: Long,
    val hasMore: Boolean,
    val cursor: String,
    val sortType: Int,
    val pageNo: Int
)

internal data class BilibiliFloorPage(
    val replies: List<BilibiliComment>,
    val totalCount: Int,
    val hasMore: Boolean,
    val nextTime: Long
)

internal enum class BilibiliCommentResource {
    Video;
    fun threadId(id: String): String = id
}

private fun JSONObject.text(key: String): String =
    if (!has(key) || isNull(key)) "" else optString(key).takeUnless { it == "null" }.orEmpty()

private fun httpsUrl(url: String): String =
    when {
        url.isBlank() -> ""
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }

internal fun parseBilibiliCommentUser(json: JSONObject?): BilibiliCommentUser {
    if (json == null) return BilibiliCommentUser(0, "", "")
    return BilibiliCommentUser(
        userId = json.optLong("mid"),
        nickname = json.text("uname"),
        avatarUrl = httpsUrl(json.text("avatar")),
        level = json.optJSONObject("level_info")?.optInt("current_level", 0)?.coerceIn(0, 6) ?: 0
    )
}

internal fun parseBilibiliComment(json: JSONObject): BilibiliComment? {
    val id = json.optLong("rpid").takeIf { it > 0 } ?: return null
    val parent = json.optLong("parent").coerceAtLeast(0)
    val root = json.optLong("root").coerceAtLeast(0)
    val location = json.optJSONObject("reply_control")?.text("location").orEmpty()
        .removePrefix("IP属地：").removePrefix("IP属地:")
    return BilibiliComment(
        id = id,
        user = parseBilibiliCommentUser(json.optJSONObject("member")),
        content = json.optJSONObject("content")?.text("message").orEmpty(),
        timeMs = json.optLong("ctime") * 1000L,
        timeStr = "",
        ipLocation = location,
        likedCount = json.optLong("like").coerceAtLeast(0),
        replyCount = json.optInt("rcount", 0).coerceAtLeast(0),
        parentCommentId = if (root > 0) root else parent,
        replyTo = null,
        liked = json.optInt("action", 0) == 1
    )
}

private fun parseBilibiliCommentArray(array: JSONArray?): List<BilibiliComment> {
    if (array == null) return emptyList()
    val seen = HashSet<Long>()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let(::parseBilibiliComment)?.takeIf { seen.add(it.id) }
    }
}

/** Parses a `/x/v2/reply` response. */
internal fun parseBilibiliCommentPage(root: JSONObject, requestedSort: Int, pageNo: Int): BilibiliCommentPage {
    val data = root.optJSONObject("data") ?: JSONObject()
    val comments = parseBilibiliCommentArray(data.optJSONArray("replies"))
    val page = data.optJSONObject("page")
    val total = page?.optLong("acount")?.takeIf { it > 0 } ?: page?.optLong("count") ?: 0L
    return BilibiliCommentPage(
        comments = comments,
        totalCount = total,
        hasMore = comments.size >= 20,
        cursor = "",
        sortType = requestedSort,
        pageNo = pageNo
    )
}

/** Parses a `/x/v2/reply/reply` response. */
internal fun parseBilibiliFloorPage(root: JSONObject, pageNo: Int): BilibiliFloorPage {
    val data = root.optJSONObject("data") ?: JSONObject()
    val replies = parseBilibiliCommentArray(data.optJSONArray("replies"))
    val page = data.optJSONObject("page")
    val total = page?.optInt("count", replies.size) ?: replies.size
    return BilibiliFloorPage(
        replies = replies,
        totalCount = total,
        hasMore = replies.size >= 20,
        nextTime = pageNo.toLong()
    )
}
