package com.ahdownload.domain.network

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicInternetDnsTest {
    @Test
    fun rejectsPrivateAndSpecialUseIpv4Ranges() {
        listOf(
            "127.0.0.1",
            "10.2.3.4",
            "172.20.1.2",
            "192.168.1.2",
            "100.64.0.1",
            "169.254.1.2",
            "192.0.2.4",
            "198.51.100.8",
            "203.0.113.9",
        ).forEach { host ->
            assertTrue(PublicInternetDns.isNonPublicAddress(InetAddress.getByName(host)), host)
        }
    }

    @Test
    fun rejectsLocalAndDocumentationIpv6RangesAndIpv4MappedAddresses() {
        listOf(
            "::1",
            "fc00::1",
            "fe80::1",
            "2001:db8::1",
            "::ffff:127.0.0.1",
        ).forEach { host ->
            assertTrue(PublicInternetDns.isNonPublicAddress(InetAddress.getByName(host)), host)
        }
    }

    @Test
    fun acceptsRepresentativePublicAddresses() {
        listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111").forEach { host ->
            assertFalse(PublicInternetDns.isNonPublicAddress(InetAddress.getByName(host)), host)
        }
    }
}
