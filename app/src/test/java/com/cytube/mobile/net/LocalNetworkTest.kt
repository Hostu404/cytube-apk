package com.cytube.mobile.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class LocalNetworkTest {
    // Written as addresses, so nothing is looked up.
    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    @Test fun theHomeNetworkThisDeviceAndNonAddressesAreLocal() {
        for (a in listOf(
            "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.1", "192.168.0.254",
            "127.0.0.1", "127.8.9.10", "0.0.0.0", "0.1.2.3", "169.254.169.254",
            "100.64.0.1", "100.127.255.255", "192.0.0.170",
            "224.0.0.251", "239.255.255.250", "240.0.0.1", "255.255.255.255",
            "::1", "::", "fe80::1", "fd12:3456::1", "fc00::1", "fec0::1", "ff02::1",
            "::ffff:192.168.1.1", "::ffff:127.0.0.1", "64:ff9b::c0a8:101", "64:ff9b::7f00:1", "64:ff9b:1::a00:1"
        )) assertTrue(a, LocalNetwork.isLocal(ip(a)))
    }

    @Test fun publicAddressesAreNot() {
        for (a in listOf(
            "1.1.1.1", "8.8.8.8", "104.21.79.61", "11.0.0.1",
            "172.15.255.255", "172.32.0.1", "192.169.0.1", "100.63.255.255", "100.128.0.1", "169.253.1.1",
            "2606:4700:3033::6815:4f3d", "2001:4860:4860::8888", "::ffff:8.8.8.8",
            // A public site reached through an IPv6-only mobile network's NAT64.
            "64:ff9b::808:808"
        )) assertFalse(a, LocalNetwork.isLocal(ip(a)))
    }

    // Proxy apps in "fake IP" mode answer every name with one of these; refusing
    // them would cut those users off from everything.
    @Test fun fakeIpAddressesStayAllowed() {
        assertFalse(LocalNetwork.isLocal(ip("198.18.0.1")))
        assertFalse(LocalNetwork.isLocal(ip("198.19.255.254")))
    }

    @Test fun namesThatCanOnlyBeLocal() {
        for (h in listOf(
            "localhost", "LOCALHOST", "localhost.", "app.localhost", "router", "nas", "nas.local",
            "printer.lan", "hub.home.arpa", "box.internal", "pi.localdomain", "192.168.1.1", "127.0.0.1",
            "[::1]", "::1", "fe80::1", ""
        )) assertTrue(h, LocalNetwork.isLocalName(h))
    }

    @Test fun publicNamesAndAddressesAreNot() {
        for (h in listOf(
            "cytu.be", "www.youtube.com", "framatube.org", "8.8.8.8", "[2606:4700:3033::6815:4f3d]",
            "bbc.co.uk", "localhost.example.com", "local.example", "home.example.org"
        )) assertFalse(h, LocalNetwork.isLocalName(h))
    }

    // Only the plain a.b.c.d form is read as an address without a lookup.
    @Test fun otherSpellingsOfAnAddressAreLookedUpInstead() {
        assertNull(LocalNetwork.literalAddress("127.1"))
        assertNull(LocalNetwork.literalAddress("0x7f.0.0.1"))
        assertNull(LocalNetwork.literalAddress("010.0.0.1"))
        assertNull(LocalNetwork.literalAddress("2130706433"))
        assertNull(LocalNetwork.literalAddress("256.1.1.1"))
        assertNull(LocalNetwork.literalAddress("example.com"))
    }

    private val dns = mapOf(
        "nas.example" to listOf(ip("192.168.1.10")),
        "cdn.example" to listOf(ip("93.184.216.34")),
        "mixed.example" to listOf(ip("93.184.216.34"), ip("10.0.0.5")),
        // What the phone's resolver makes of these spellings.
        "2130706433" to listOf(ip("127.0.0.1")),
        "0x7f.1" to listOf(ip("127.0.0.1"))
    )
    private val lookup: (String) -> List<InetAddress> = { dns[it] ?: throw UnknownHostException(it) }

    private fun blocks(url: String) = LocalNetwork.blocksWebRequest(url, lookup)

    @Test fun webRequestsToTheLocalNetworkAreRefused() {
        assertTrue(blocks("https://192.168.1.1/admin"))
        assertTrue(blocks("https://[::1]:8080/"))
        assertTrue(blocks("http://router/"))
        assertTrue(blocks("https://nas.local/x"))
        assertTrue(blocks("https://localhost:3000/"))
        // By what the name resolves to: one local address is enough.
        assertTrue(blocks("https://nas.example/"))
        assertTrue(blocks("https://mixed.example/"))
        assertTrue(blocks("http://2130706433/"))
        assertTrue(blocks("http://0x7f.1/"))
    }

    @Test fun everythingElseIsLeftToTheWebView() {
        assertFalse(blocks("https://cdn.example/video.mp4"))
        assertFalse(blocks("https://8.8.8.8/"))
        // Can't be looked up: the WebView finds that out for itself.
        assertFalse(blocks("https://unknown.example/"))
        assertFalse(blocks("data:text/html,hi"))
        assertFalse(blocks("about:blank"))
        assertFalse(blocks("blob:https://cdn.example/1234"))
    }

    @Test fun aPeerTubeInstanceMustBeAPublicName() {
        assertTrue(MediaTypes.isPublicHostname("framatube.org"))
        assertTrue(MediaTypes.isPublicHostname("peertube.example.com"))
        assertFalse(MediaTypes.isPublicHostname("192.168.1.1"))
        assertFalse(MediaTypes.isPublicHostname("127.0.0.1"))
        assertFalse(MediaTypes.isPublicHostname("0x7f.0.0.1"))
        assertFalse(MediaTypes.isPublicHostname("10.0.0.1.2"))
        assertFalse(MediaTypes.isPublicHostname("nas.local"))
        assertFalse(MediaTypes.isPublicHostname("localhost.localdomain"))
        assertFalse(MediaTypes.isPublicHostname("app.localhost"))
        assertFalse(MediaTypes.isPublicHostname("router"))
        assertFalse(MediaTypes.isPublicHostname("evil.example@framatube.org"))
    }
}
