package ltd.gsio.utils.blocklist

import java.net.Inet4Address
import java.net.InetAddress

/** Utility helpers for IPv4 address parsing and formatting. */
object IpUtils {
    /** Parse an IPv4 string like "1.2.3.4" into an unsigned Int stored in an Int. */
    fun parseIpv4ToInt(ip: String): Int {
        val parts = ip.trim().split('.')
        require(parts.size == 4) { "Invalid IPv4 address: $ip" }
        var v = 0
        for (p in parts) {
            val b = p.toIntOrNull() ?: error("Invalid IPv4 octet in $ip: $p")
            require(b in 0..255) { "Invalid IPv4 octet in $ip: $p" }
            v = (v shl 8) or b
        }
        return v
    }

    /** Convert an IPv4 int to dotted-quad string. */
    fun intToIpv4(v: Int): String =
        listOf((v ushr 24) and 0xFF, (v ushr 16) and 0xFF, (v ushr 8) and 0xFF, v and 0xFF).joinToString(".")

    /** Convert CIDR like 1.2.3.0/24 into a start..end pair (inclusive), both as IPv4 ints. */
    fun cidrToRange(cidr: String): IntRange {
        val parts = cidr.trim().split('/')
        require(parts.size == 2) { "Invalid CIDR: $cidr" }
        val base = parseIpv4ToInt(parts[0])
        val maskLen = parts[1].toIntOrNull() ?: error("Invalid CIDR mask length in $cidr")
        require(maskLen in 0..32) { "Invalid CIDR mask length in $cidr" }
        val mask = if (maskLen == 0) 0 else -1 shl (32 - maskLen)
        val network = base and mask
        val broadcast = network or mask.inv()
        return network..broadcast
    }

    /** Convert InetAddress to IPv4 int, or null if not IPv4. */
    fun inetAddressToIpv4Int(addr: InetAddress): Int? {
        if (addr is Inet4Address) {
            val bytes = addr.address
            return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        }
        return null
    }
}
