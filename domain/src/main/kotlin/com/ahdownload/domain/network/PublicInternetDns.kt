package com.ahdownload.domain.network

import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS policy for requests initiated from user-supplied internet URLs.
 * Every resolved address must be publicly routable, including redirect targets.
 */
object PublicInternetDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.isEmpty() || addresses.any(::isNonPublicAddress)) {
            throw UnknownHostException("Blocked non-public destination")
        }
        return addresses
    }

    fun isNonPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress
        ) return true

        val bytes = address.address.map { it.toInt() and 0xff }
        if (address !is Inet6Address && bytes.size == 4) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            return a == 0 || a == 10 || a == 127 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 0 && c == 0) ||
                (a == 192 && b == 168) ||
                (a == 192 && b == 0 && c == 2) ||
                (a == 192 && b == 88 && c == 99) ||
                (a == 198 && b in 18..19) ||
                (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113) ||
                a >= 224
        }

        if (address is Inet6Address && bytes.size == 16) {
            // IPv4-mapped IPv6 addresses must be evaluated by the embedded IPv4 address.
            val mappedIpv4 = bytes.take(10).all { it == 0 } &&
                bytes[10] == 0xff && bytes[11] == 0xff
            if (mappedIpv4) {
                val ipv4 = InetAddress.getByAddress(
                    bytes.takeLast(4).map { it.toByte() }.toByteArray(),
                )
                return isNonPublicAddress(ipv4)
            }

            // The well-known NAT64 prefix is usable only when its embedded IPv4 target is
            // public. Otherwise a public-looking IPv6 literal could route to a private host.
            if (isWellKnownNat64Prefix(bytes)) {
                val embeddedIpv4 = InetAddress.getByAddress(
                    bytes.takeLast(4).map { it.toByte() }.toByteArray(),
                )
                return isNonPublicAddress(embeddedIpv4)
            }

            // RFC 8215 local-use translation prefix (64:ff9b:1::/48) is not a public target.
            if (bytes[0] == 0x00 && bytes[1] == 0x64 &&
                bytes[2] == 0xff && bytes[3] == 0x9b &&
                bytes[4] == 0x00 && bytes[5] == 0x01
            ) return true

            // Reject IPv6 addresses outside Global Unicast (2000::/3). This includes
            // unspecified, loopback, link-local, unique-local, multicast, and other
            // non-global ranges even when a platform API does not classify them as such.
            if ((bytes[0] and 0xe0) != 0x20) return true

            val ietfProtocolAssignments = bytes[0] == 0x20 && bytes[1] == 0x01 &&
                (bytes[2] and 0xfe) == 0x00 // 2001::/23
            val documentationPrefix = bytes[0] == 0x20 && bytes[1] == 0x01 &&
                bytes[2] == 0x0d && bytes[3] == 0xb8 // 2001:db8::/32
            val sixToFourTransition = bytes[0] == 0x20 && bytes[1] == 0x02 // 2002::/16
            val documentationPrefixV2 = bytes[0] == 0x3f && bytes[1] == 0xff &&
                (bytes[2] and 0xf0) == 0x00 // 3fff::/20

            return ietfProtocolAssignments || documentationPrefix ||
                sixToFourTransition || documentationPrefixV2
        }

        return false
    }

    private fun isWellKnownNat64Prefix(bytes: List<Int>): Boolean =
        bytes.size == 16 &&
            bytes[0] == 0x00 && bytes[1] == 0x64 &&
            bytes[2] == 0xff && bytes[3] == 0x9b &&
            (4..11).all { bytes[it] == 0x00 }
}
