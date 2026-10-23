package com.ahdownload.domain.network

import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicInternetDnsTest {
    @Test
    fun blocksPrivateAndSpecialPurposeIpv4Ranges() {
        listOf(
            "0.0.0.0",
            "10.1.2.3",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.0.2.1",
            "192.168.1.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1",
        ).forEach { address ->
            assertTrue("Expected non-public IPv4 address to be blocked: $address", isNonPublic(address))
        }
    }

    @Test
    fun allowsOrdinaryPublicIpv4Addresses() {
        listOf("1.1.1.1", "8.8.8.8", "93.184.216.34").forEach { address ->
            assertFalse("Expected public IPv4 address to be allowed: $address", isNonPublic(address))
        }
    }

    @Test
    fun blocksIpv6NonGlobalAndSpecialPurposeRanges() {
        listOf(
            "::",
            "::1",
            "fe80::1",
            "fec0::1",
            "fc00::1",
            "ff02::1",
            "2001::1",
            "2001:db8::1",
            "2002:c000:0201::1",
            "3fff::1",
            "64:ff9b:1::1",
        ).forEach { address ->
            assertTrue("Expected non-public IPv6 address to be blocked: $address", isNonPublic(address))
        }
    }

    @Test
    fun allowsOrdinaryGlobalUnicastIpv6Addresses() {
        listOf("2001:4860:4860::8888", "2606:4700:4700::1111").forEach { address ->
            assertFalse("Expected public IPv6 address to be allowed: $address", isNonPublic(address))
        }
    }

    @Test
    fun evaluatesIpv4MappedIpv6AddressesUsingEmbeddedIpv4() {
        val privateMapped = mappedIpv4(192, 168, 1, 1)
        val publicMapped = mappedIpv4(8, 8, 8, 8)

        assertTrue(PublicInternetDns.isNonPublicAddress(privateMapped))
        assertFalse(PublicInternetDns.isNonPublicAddress(publicMapped))
    }

    @Test
    fun nat64WellKnownPrefixIsAcceptedOnlyForPublicEmbeddedIpv4() {
        assertTrue(isNonPublic("64:ff9b::a00:1")) // 10.0.0.1
        assertFalse(isNonPublic("64:ff9b::808:808")) // 8.8.8.8
    }

    private fun isNonPublic(literal: String): Boolean =
        PublicInternetDns.isNonPublicAddress(InetAddress.getByName(literal))

    private fun mappedIpv4(a: Int, b: Int, c: Int, d: Int): Inet6Address {
        val bytes = ByteArray(16)
        bytes[10] = 0xff.toByte()
        bytes[11] = 0xff.toByte()
        bytes[12] = a.toByte()
        bytes[13] = b.toByte()
        bytes[14] = c.toByte()
        bytes[15] = d.toByte()
        return Inet6Address.getByAddress(null, bytes, 0)
    }
}
