package com.ella.music.ui.online

/** Provider switches reuse a submitted query; late responses cannot replace the new provider. */
internal class OnlineProviderSearchRequests {
    data class Request(val query: String, val revision: Long)
    private var revision = 0L
    private var submitted = false
    fun request(query: String, automatic: Boolean = false): Request? {
        val text = query.trim()
        if (text.isEmpty() || (automatic && !submitted)) return null
        submitted = true
        return Request(text, ++revision)
    }
    fun invalidate() { revision++ }
    fun isCurrent(request: Request): Boolean = request.revision == revision
}
