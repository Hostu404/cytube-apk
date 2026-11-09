package com.cytube.mobile.net

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketOptionsTest {
    private val http = OkHttpClient()

    // The Socket.IO Java client tries only the first transport listed. With
    // "websocket" first, a network that blocks WebSockets meant no connection
    // at all; polling first works everywhere, and is upgraded where it can be.
    @Test fun pollingFirstThenUpgradedToAWebSocket() {
        val options = CyTubeClient.socketOptions(http, null)
        assertEquals(listOf("polling", "websocket"), options.transports.toList())
        assertTrue(options.upgrade)
        assertFalse(options.rememberUpgrade)
    }

    @Test fun bothTransportsGoThroughTheAppsClient() {
        val options = CyTubeClient.socketOptions(http, null)
        assertSame(http, options.callFactory)
        assertSame(http, options.webSocketFactory)
    }

    @Test fun theCookiesAreSentOnlyWhenThereAreSome() {
        assertNull(CyTubeClient.socketOptions(http, null).extraHeaders)
        val options = CyTubeClient.socketOptions(http, "auth=A; ip-session=B")
        assertEquals(mapOf("Cookie" to listOf("auth=A; ip-session=B")), options.extraHeaders)
    }

    @Test fun reconnectingIsUnchanged() {
        val options = CyTubeClient.socketOptions(http, null)
        assertTrue(options.forceNew)
        assertTrue(options.reconnection)
        assertEquals(1_000L, options.reconnectionDelay)
        assertEquals(10_000L, options.reconnectionDelayMax)
        assertEquals(20, options.reconnectionAttempts)
        assertEquals(20_000L, options.timeout)
    }
}
