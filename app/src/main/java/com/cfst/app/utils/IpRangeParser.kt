package com.cfst.app.utils

import java.math.BigInteger
import java.net.InetAddress
import java.util.Random

/** Literal-only parser. CIDRs include all addresses (RFC 3021/6164 small networks). */
class IpRangeParser {
    data class IpRange(val startIp: BigInteger, val endIp: BigInteger, val isIpv6: Boolean = false)
    companion object {
        private val random = Random()
        fun parseCidr(cidr: String): IpRange? {
            val parts = cidr.trim().split('/')
            if (parts.size !in 1..2) return null
            val bytes = literalBytes(parts[0]) ?: return null
            val bits = bytes.size * 8
            val prefix = if (parts.size == 1) bits else {
                if (!parts[1].matches(Regex("0|[1-9][0-9]{0,2}"))) return null
                parts[1].toIntOrNull() ?: return null
            }
            if (prefix !in 0..bits) return null
            val hostBits = bits - prefix
            val start = BigInteger(1, bytes).shiftRight(hostBits).shiftLeft(hostBits)
            return IpRange(start, start + BigInteger.ONE.shiftLeft(hostBits) - BigInteger.ONE, bits == 128)
        }
        fun literalBytes(ip: String): ByteArray? {
            if (':' !in ip) {
                val value = ipToLong(ip) ?: return null
                return ByteArray(4) { (value shr (24 - it * 8)).toByte() }
            }
            if (!ip.matches(Regex("[0-9a-fA-F:.]+"))) return null
            var value = ip
            if ('.' in value) {
                val tail = value.substringAfterLast(':')
                val v4 = ipToLong(tail) ?: return null
                value = value.substringBeforeLast(':') + ":" + (v4 shr 16).toString(16) + ":" + (v4 and 65535).toString(16)
            }
            val halves = value.split("::")
            if (halves.size > 2) return null
            fun groups(s: String) = if (s.isEmpty()) emptyList() else s.split(':')
            val left = groups(halves[0])
            val right = if (halves.size == 2) groups(halves[1]) else emptyList()
            val missing = 8 - left.size - right.size
            if ((halves.size == 1 && missing != 0) || (halves.size == 2 && missing < 1)) return null
            val all = left + List(missing) { "0" } + right
            if (all.any { !it.matches(Regex("[0-9a-fA-F]{1,4}")) }) return null
            return ByteArray(16).also { bytes -> all.forEachIndexed { i, group ->
                val n = group.toInt(16); bytes[i * 2] = (n shr 8).toByte(); bytes[i * 2 + 1] = n.toByte()
            } }
        }
        fun literalAddress(ip: String): InetAddress = InetAddress.getByAddress(requireNotNull(literalBytes(ip)) { "Invalid IP literal" })
        fun ipToLong(ip: String): Long? {
            val parts = ip.split('.')
            if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) || it.toInt() > 255 }) return null
            return parts.fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
        }
        fun longToIp(ip: Long) = (3 downTo 0).joinToString(".") { ((ip shr (it * 8)) and 255).toString() }
        fun getRandomIpFromRange(range: IpRange, random: Random = this.random): String {
            val size = range.endIp - range.startIp + BigInteger.ONE
            require(size.signum() > 0)
            // Curated CDN IPv6 sampling; not upstream CFST-equivalent.
            val samplingSize = if (range.isIpv6) size.min(BigInteger.valueOf(65536)) else size
            var offset: BigInteger
            do { offset = BigInteger(samplingSize.bitLength(), random) } while (offset >= samplingSize)
            val n = range.startIp + offset
            return if (!range.isIpv6) longToIp(n.toLong()) else (7 downTo 0).joinToString(":") { n.shiftRight(it * 16).and(BigInteger.valueOf(65535)).toString(16) }
        }
        fun getRandomIpFromRanges(ranges: List<IpRange>, random: Random = this.random): String? =
            if (ranges.isEmpty()) null else getRandomIpFromRange(ranges[random.nextInt(ranges.size)], random)
        fun parseIpFile(content: String): List<IpRange> {
            require(content.toByteArray(Charsets.UTF_8).size <= 1048576 && content.lines().size <= 10000) { "IP list too large (1 MiB / 10000 physical lines maximum)" }
            require('\u0000' !in content) { "NUL is not allowed" }
            val errors = mutableListOf<Int>()
            val ranges = content.removePrefix("\uFEFF").lines().mapIndexedNotNull { index, line ->
                require(line.toByteArray(Charsets.UTF_8).size <= 4096) { "Line ${index + 1} exceeds 4096 bytes" }
                val value = line.trim()
                if (value.isEmpty() || value.startsWith('#')) null else parseCidr(value).also { if (it == null) errors.add(index + 1) }
            }
            require(errors.isEmpty()) { "Invalid IP/CIDR at lines: ${errors.joinToString(",")}" }
            require(ranges.isNotEmpty()) { "IP list is empty (comments are not entries)" }
            return ranges
        }
        fun getTotalIpCount(ranges: List<IpRange>) = ranges.fold(BigInteger.ZERO) { n, r -> n + r.endIp - r.startIp + BigInteger.ONE }
        fun isIpv6Address(ip: String) = literalBytes(ip)?.size == 16
    }
}
