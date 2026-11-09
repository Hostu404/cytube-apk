package com.cytube.mobile.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The guard on the app's own connections (LocalNetwork.guard), with real
 *  sockets: servers on this machine's loopback, which is local. */
class ConnectionGuardTest {

    /** An HTTP server on 127.0.0.1 that answers every request with
     *  [respond], counting the connections it's sent. */
    private class Server(private val respond: (requestLine: String) -> String) : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val connections = AtomicInteger()
        val requests = CopyOnWriteArrayList<String>()

        init {
            Thread {
                while (true) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    client.use { c ->
                        val reader = c.getInputStream().bufferedReader()
                        val line = reader.readLine() ?: return@use
                        requests += line
                        while (!reader.readLine().isNullOrEmpty()) { /* headers */ }
                        c.getOutputStream().apply { write(respond(line).toByteArray()); flush() }
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() = socket.close()
    }

    private fun ok(body: String = "ok") =
        "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

    private fun redirect(to: String) =
        "HTTP/1.1 302 Found\r\nLocation: $to\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    private fun selector(vararg proxies: Proxy) = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = proxies.toList().ifEmpty { listOf(Proxy.NO_PROXY) }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }

    private fun client(guard: ConnectionGuard, dns: Dns = Dns.SYSTEM): OkHttpClient =
        OkHttpClient.Builder()
            .proxySelector(guard.proxySelector)
            .socketFactory(guard.socketFactory)
            .dns(dns)
            .build()

    private fun get(client: OkHttpClient, url: String): Result<String> = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
    }

    private fun Result<*>.refused(): Boolean =
        generateSequence(exceptionOrNull()) { it.cause }.any { it is LocalAddressRefused }

    @Test fun anAddressOnTheLocalNetworkIsRefusedBeforeConnecting() {
        Server { ok() }.use { server ->
            val result = get(client(ConnectionGuard(system = { selector() })), "http://127.0.0.1:${server.port}/")
            assertTrue(result.exceptionOrNull().toString(), result.refused())
            assertEquals("nothing reached the server", 0, server.connections.get())
        }
    }

    @Test fun soIsANameThatResolvesToOne() {
        Server { ok() }.use { server ->
            val dns = object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            }
            val result = get(client(ConnectionGuard(system = { selector() }), dns), "http://nas.example:${server.port}/")
            assertTrue(result.refused())
            assertEquals(0, server.connections.get())
        }
    }

    @Test fun andARedirectToOne() {
        Server { ok("secret") }.use { local ->
            Server { redirect("http://127.0.0.1:${local.port}/admin") }.use { public ->
                // Only the first server counts as public here.
                val guard = ConnectionGuard(system = { selector() }, refuses = { it.port == local.port })
                val result = get(client(guard), "http://127.0.0.1:${public.port}/video.mp4")
                assertTrue(result.refused())
                assertEquals(1, public.connections.get())
                assertEquals("the redirect wasn't followed", 0, local.connections.get())
            }
        }
    }

    @Test fun anythingElseConnectsAsBefore() {
        Server { ok("hello") }.use { server ->
            val guard = ConnectionGuard(system = { selector() }, refuses = { false })
            assertEquals("hello", get(client(guard), "http://127.0.0.1:${server.port}/").getOrThrow())
            assertEquals(1, server.connections.get())
        }
    }

    // A proxy the phone is set to use is reached wherever it is: a school or
    // work proxy, or an app's own on this device. It then fetches the page.
    @Test fun aProxyThePhoneUsesIsReachedEvenOnTheLocalNetwork() {
        Server { ok("via proxy") }.use { proxy ->
            for (address in listOf(
                InetSocketAddress("127.0.0.1", proxy.port),
                InetSocketAddress.createUnresolved("localhost", proxy.port)
            )) {
                val guard = ConnectionGuard(system = { selector(Proxy(Proxy.Type.HTTP, address)) })
                assertEquals("via proxy", get(client(guard), "http://example.com/page").getOrThrow())
            }
            assertEquals("GET http://example.com/page HTTP/1.1", proxy.requests.first())
            // Not as a destination, though: without it set, it's refused.
            val direct = get(client(ConnectionGuard(system = { selector() })), "http://127.0.0.1:${proxy.port}/")
            assertTrue(direct.refused())
            assertEquals(2, proxy.connections.get())
        }
    }
}
