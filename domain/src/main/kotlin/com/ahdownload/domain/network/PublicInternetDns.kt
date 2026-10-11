package com.ahdownload.domain.network

import okhttp3.Dns
import java.net.Inet4Address
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
        if (address is Inet4Address && bytes.size == 4) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            return a == 0 || a == 10 || a == 127 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 0) ||
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
                val ipv4 = InetAddress.getByAddress(bytes.takeLast(4).map { it.toByte() }.toByteArray())
                return isNonPublicAddress(ipv4)
            }

            // Unique-local, link-local and documentation-only IPv6 blocks are not public targets.
            val uniqueLocal = (bytes[0] and 0xfe) == 0xfc
            val linkLocal = bytes[0] == 0xfe && (bytes[1] and 0xc0) == 0x80
            val documentation = bytes[0] == 0x20 && bytes[1] == 0x01 &&
                bytes[2] == 0x0d && bytes[3] == 0xb8
            return uniqueLocal || linkLocal || documentation
        }

        return false
    }
}
