package com.juncehome.lifepo4ble.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteAddressTest {
    @Test
    fun hostWithoutPortUsesDefaultPort() {
        assertEquals(RemoteAddress("home.netbird.cloud", 8765), RemoteAddress.parse("home.netbird.cloud"))
    }

    @Test
    fun explicitPortIsKept() {
        assertEquals(RemoteAddress("100.92.4.17", 9000), RemoteAddress.parse("100.92.4.17:9000"))
    }

    @Test
    fun schemeTrailingSlashAndSpacesAreDropped() {
        assertEquals(RemoteAddress("192.168.1.23", 8765), RemoteAddress.parse(" http://192.168.1.23:8765/ "))
    }

    @Test
    fun hostIsLowercased() {
        assertEquals(
            RemoteAddress("home-phone.netbird.cloud", 8765),
            RemoteAddress.parse("Home-Phone.NetBird.Cloud"),
        )
    }

    @Test
    fun portBoundsAreInclusive() {
        assertEquals(1, RemoteAddress.parse("host:1")?.port)
        assertEquals(65535, RemoteAddress.parse("host:65535")?.port)
    }

    @Test
    fun invalidTextIsRejected() {
        listOf("", "   ", "a b", "host:0", "host:70000", "host:abc", "host:", "host:+80", "host:-1", "[::1]:8765", "::1", "a:1:2", "ho_st", "https://host")
            .forEach { text -> assertNull("'$text' must not parse", RemoteAddress.parse(text)) }
    }

    @Test
    fun derivedTextsUseHostAndPort() {
        val address = RemoteAddress("home.netbird.cloud", 8765)

        assertEquals("remote:home.netbird.cloud:8765", address.id)
        assertEquals("home.netbird.cloud:8765", address.display)
        assertEquals("http://home.netbird.cloud:8765/v1/live", address.liveUrl)
    }
}
