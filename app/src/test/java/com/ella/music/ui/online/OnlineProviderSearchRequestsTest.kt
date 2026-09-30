package com.ella.music.ui.online

import org.junit.Assert.*
import org.junit.Test

class OnlineProviderSearchRequestsTest {
    @Test fun switchingBeforeFirstSubmissionDoesNotSearch() {
        assertNull(OnlineProviderSearchRequests().request("醒来折花", automatic = true))
    }
    @Test fun switchingAfterSubmissionUsesTheCurrentEditedQuery() {
        val requests = OnlineProviderSearchRequests()
        requests.request("old")
        assertEquals("醒来折花", requests.request("  醒来折花  ", automatic = true)!!.query)
    }
    @Test fun clearingQueryPreventsAutomaticRequests() {
        val requests = OnlineProviderSearchRequests()
        requests.request("query")
        assertNull(requests.request("  ", automatic = true))
    }
    @Test fun oldProviderCannotOverwriteNewProviderResults() {
        val requests = OnlineProviderSearchRequests()
        val first = requests.request("query")!!
        val second = requests.request("query", automatic = true)!!
        assertFalse(requests.isCurrent(first))
        assertTrue(requests.isCurrent(second))
        requests.invalidate()
        assertFalse(requests.isCurrent(second))
    }
}
