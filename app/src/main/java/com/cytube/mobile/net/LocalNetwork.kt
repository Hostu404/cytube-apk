package com.cytube.mobile.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory

/**
 * Keeps the addresses a channel chooses off the user's own network.
 *
 * A channel decides many of the addresses the app goes to: its videos (and
 * wherever they redirect), the PeerTube instance a video is on, its emotes,
 * the stylesheets its CSS imports, the pages its embedded players load. Any
 * of them could name a device on the user's home network instead: the
 * router's admin page, a NAS, a printer, a smart-home hub, or the phone
 * itself (localhost). The app sits on that network, so it would connect, and
 * a stranger in a channel could reach past the user's router. Browsers keep
 * public pages from doing this; the app now does too, in two places:
 *
 *  - Its own connections. Every OkHttp client in the app is built from
 *    Graph.http, which gets [guard]: each connection is checked as it opens,
 *    against the address actually being connected to. So a raw IP address, a
 *    name that resolves to a local address, and a redirect to either are all
 *    refused, before anything is sent.
 *  - WebView pages (embedded players, Compatibility View), whose requests
 *    the app only gets to see, not make: [blocksWebRequest].
 *
 * Not refused: a proxy the phone is set to use, wherever it is (a school or
 * work proxy, an app's own local one), since then every connection goes to
 * it. It's the proxy that reaches the real address then.
 */
object LocalNetwork {

    /**
     * Whether [address] is somewhere a channel mustn't send the app: this
     * device, the local network, or no real destination at all.
     *
     * IPv4: 0.0.0.0/8 (0.0.0.0 reaches this device), 10/8, 100.64/10 (carrier
     * NAT, and VPNs such as Tailscale), 127/8, 169.254/16, 172.16/12,
     * 192.0.0/24, 192.168/16, and multicast and above (224/3).
     * IPv6: ::, ::1, fc00::/7, fe80::/10, fec0::/10, ff00::/8, and an IPv4
     * address carried inside one (mapped, compatible or NAT64) when that one
     * is local.
     *
     * Left alone on purpose: 198.18/15, where proxy apps in "fake IP" mode put
     * every public site (refusing it would cut those users off from
     * everything), and NAT64 addresses of public sites (how IPv6-only mobile
     * networks reach IPv4 ones).
     */
    fun isLocal(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val bytes = address.address
        return when (address) {
            is Inet4Address -> isLocalV4(bytes, 0)
            is Inet6Address -> isLocalV6(bytes)
            else -> false
        }
    }

    private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

    /** The IPv4 address at [o] in [b]. */
    private fun isLocalV4(b: ByteArray, o: Int): Boolean {
        val a = b.u(o)
        val c = b.u(o + 1)
        return a == 0 || a == 10 || a == 127 ||
            (a == 100 && (c and 0xC0) == 64) ||
            (a == 169 && c == 254) ||
            (a == 172 && (c and 0xF0) == 16) ||
            (a == 192 && c == 168) ||
            (a == 192 && c == 0 && b.u(o + 2) == 0) ||
            a >= 224
    }

    private fun isLocalV6(b: ByteArray): Boolean {
        val first = b.u(0)
        val second = b.u(1)
        if ((first and 0xFE) == 0xFC) return true                    // fc00::/7, unique local
        if (first == 0xFE && (second and 0x80) == 0x80) return true  // fe80::/10 and fec0::/10
        if (first == 0xFF) return true                               // multicast
        // An IPv4 address inside: ::ffff:a.b.c.d (mapped), ::a.b.c.d (the
        // old compatible form, which also covers :: and ::1), and NAT64's
        // 64:ff9b::/96 and 64:ff9b:1::/48 (the address in the last 4 bytes).
        val zerosTo9 = (0..9).all { b[it].toInt() == 0 }
        if (zerosTo9 && b.u(10) == 0xFF && b.u(11) == 0xFF) return isLocalV4(b, 12)
        if (zerosTo9 && b.u(10) == 0 && b.u(11) == 0) return isLocalV4(b, 12)
        if (first == 0x00 && second == 0x64 && b.u(2) == 0xFF && b.u(3) == 0x9B) return isLocalV4(b, 12)
        return false
    }

    /** Endings only ever used inside a local network, never on the public
     *  internet (none is a real top-level domain). */
    private val LOCAL_SUFFIXES = listOf(
        "localhost", "local", "localdomain", "lan", "home", "home.arpa", "internal", "intranet", "corp", "private"
    )

    /**
     * Whether [host], as written in an address, is local without looking
     * anything up: an IP address that [isLocal], "localhost" or a name under
     * it, a single word ("router", "nas"), or a name ending in one of
     * LOCAL_SUFFIXES (".local", ".lan", ".home.arpa"...).
     */
    fun isLocalName(host: String): Boolean {
        val h = host.trim().trimEnd('.').removePrefix("[").removeSuffix("]").lowercase()
        if (h.isEmpty()) return true
        literalAddress(h)?.let { return isLocal(it) }
        if (':' in h) return true // not a valid IPv6 address either: nothing public
        if ('.' !in h) return true
        return LOCAL_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    /** [host] as an IP address when it's written as one, without any lookup;
     *  otherwise null. */
    internal fun literalAddress(host: String): InetAddress? {
        val h = host.removePrefix("[").removeSuffix("]")
        if (':' in h) {
            // Checked with OkHttp's own parser first, so nothing but a valid
            // IPv6 address reaches InetAddress (which looks anything else up).
            if ("http://[$h]/".toHttpUrlOrNull() == null) return null
            return runCatching { InetAddress.getByName(h) }.getOrNull()
        }
        // Only the plain a.b.c.d form. Others ("127.1", "0x7f.0.0.1", a
        // leading zero, which some read as octal) aren't taken as an address
        // here: they're looked up, and the answer is checked instead.
        val parts = h.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            bytes[i] = value.toByte()
        }
        return InetAddress.getByAddress(h, bytes)
    }

    /**
     * Whether a WebView should refuse [url]: an http(s) address whose host is
     * local by its name ([isLocalName]), or which [lookup] finds at a local
     * address (any of them: which one the WebView would use can't be known).
     * Anything else, including a name that can't be looked up, is left to the
     * WebView.
     *
     * What this can't do, and the app's own connections can: the WebView
     * looks the name up again itself, so a name that answers differently the
     * second time slips through; and a page's scripts can open WebSockets
     * and WebRTC connections, which a WebView doesn't show the app at all.
     */
    fun blocksWebRequest(url: String, lookup: (String) -> List<InetAddress>): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        if (isLocalName(host)) return true
        if (literalAddress(host) != null) return false // an IP address isLocalName found public
        val addresses = runCatching { lookup(host) }.getOrNull() ?: return false
        return addresses.any(::isLocal)
    }

    /** [builder] with the local network refused (see the class doc). Set
     *  once, on Graph.http: the clients built from it keep it. */
    fun guard(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val guard = ConnectionGuard()
        return builder.proxySelector(guard.proxySelector).socketFactory(guard.socketFactory)
    }
}

/** A connection the app wouldn't open: [target] is on the local network (see
 *  LocalNetwork). */
class LocalAddressRefused(val target: InetSocketAddress) :
    IOException("${target.address?.hostAddress ?: target.hostString} is on the local network, so the app won't connect to it")

/**
 * The check on the app's own connections (see LocalNetwork.guard): every
 * socket the client opens is checked against the address it's about to
 * connect to. OkHttp asks [socketFactory] for every connection, whatever the
 * address was written as and through every redirect, and tries the next
 * address of a name when one is refused.
 *
 * [proxySelector] is the phone's own proxy settings ([system], asked afresh
 * for each request, so it follows them as they change), noting each proxy
 * they name: those are let through, wherever they are.
 */
internal class ConnectionGuard(
    private val system: () -> ProxySelector? = { ProxySelector.getDefault() },
    /** Whether a connection to [InetSocketAddress] is refused; apart for testing. */
    private val refuses: (InetSocketAddress) -> Boolean = { target ->
        target.address?.let(LocalNetwork::isLocal) ?: false
    }
) {
    private val proxies: MutableSet<InetSocketAddress> = ConcurrentHashMap.newKeySet()

    val proxySelector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val chosen = system()?.takeIf { it !== this }?.select(uri)
                ?.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
            for (proxy in chosen) {
                if (proxy.type() != Proxy.Type.DIRECT) (proxy.address() as? InetSocketAddress)?.let(proxies::add)
            }
            return chosen
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            system()?.takeIf { it !== this }?.connectFailed(uri, sa, ioe)
        }
    }

    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = GuardedSocket()
        override fun createSocket(host: String, port: Int): Socket =
            GuardedSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            GuardedSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int): Socket =
            GuardedSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            GuardedSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
    }

    /** Throws LocalAddressRefused for a connection to [endpoint] that isn't
     *  allowed. */
    fun check(endpoint: SocketAddress?) {
        val target = endpoint as? InetSocketAddress ?: return
        if (target.address == null || !refuses(target) || isProxy(target)) return
        throw LocalAddressRefused(target)
    }

    private fun isProxy(target: InetSocketAddress): Boolean = proxies.any { proxy ->
        proxy.port == target.port && (
            proxy.address?.let { it == target.address }
                ?: runCatching { InetAddress.getAllByName(proxy.hostString) }.getOrNull()?.contains(target.address) == true
            )
    }

    private inner class GuardedSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            check(endpoint)
            super.connect(endpoint, timeout)
        }
    }
}
