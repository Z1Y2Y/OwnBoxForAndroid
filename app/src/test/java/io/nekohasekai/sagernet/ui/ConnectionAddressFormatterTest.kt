package io.nekohasekai.sagernet.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionAddressFormatterTest {
    @Test
    fun prefersControllerHostOverDestinationIp() {
        val metadata = JSONObject()
            .put("host", "play.googleapis.com")
            .put("destinationIP", "74.125.137.188")
            .put("destinationPort", 443)

        assertEquals("play.googleapis.com:443", ConnectionAddressFormatter.format(metadata))
    }

    @Test
    fun usesDestinationHostAliasWhenStandardHostIsUnavailable() {
        val metadata = JSONObject()
            .put("host", "")
            .put("destinationHost", "cdn.example.com")
            .put("destinationIP", "203.0.113.5")
            .put("destinationPort", "8443")

        assertEquals("cdn.example.com:8443", ConnectionAddressFormatter.format(metadata))
    }

    @Test
    fun fallsBackToDestinationIpWhenThereIsNoReliableDomain() {
        val metadata = JSONObject()
            .put("host", "https://not-a-host.example/path")
            .put("destinationIP", "74.125.137.188")
            .put("destinationPort", 443)

        assertEquals("74.125.137.188:443", ConnectionAddressFormatter.format(metadata))
    }

    @Test
    fun formatsIpv6FallbackWithBrackets() {
        val metadata = JSONObject()
            .put("destinationIP", "2001:db8::1")
            .put("destinationPort", 443)

        assertEquals("[2001:db8::1]:443", ConnectionAddressFormatter.format(metadata))
    }

    @Test
    fun showsUnknownAddressRatherThanInventingAHost() {
        val metadata = JSONObject()
            .put("host", "—")
            .put("destinationIP", JSONObject.NULL)
            .put("destinationPort", 443)

        assertEquals("—:443", ConnectionAddressFormatter.format(metadata))
    }
}
