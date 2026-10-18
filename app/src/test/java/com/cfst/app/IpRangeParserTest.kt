package com.cfst.app

import com.cfst.app.utils.IpRangeParser
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class IpRangeParserTest {
    @Test fun ipv4LiteralIsOneAddress() {
        val range = IpRangeParser.parseCidr("192.0.2.9")
        assertNotNull(range)
        assertEquals(range!!.startIp, range.endIp)
    }
    @Test fun ipv4SmallNetworksIncludeEveryAddress() {
        for ((cidr, size) in listOf("192.0.2.8/31" to 2, "192.0.2.9/32" to 1, "192.0.2.0/24" to 256)) {
            val range = IpRangeParser.parseCidr(cidr)!!
            assertEquals(cidr, BigInteger.valueOf(size.toLong()), range.endIp - range.startIp + BigInteger.ONE)
        }
    }
    @Test fun ipv6SmallNetworksIncludeEveryAddress() {
        for ((cidr, size) in listOf("2001:db8::/127" to 2, "2001:db8::1/128" to 1, "::1" to 1)) {
            val range = IpRangeParser.parseCidr(cidr)
            assertNotNull(cidr, range)
            assertEquals(cidr, BigInteger.valueOf(size.toLong()), range!!.endIp - range.startIp + BigInteger.ONE)
        }
    }
    @Test fun malformedInputsNeverResolveOrCoerce() {
        for (input in listOf("localhost", "1.2.3", "01.2.3.4/24", "+1.2.3.4/24", "256.1.1.1/24", "1.2.3.4/+2", "1.2.3.4/33", "::gg/64", "1::2::3/64", "1:2:3:4:5:6:7:8::/64", "[::1]/128", "fe80::1%eth0/64", "::/129", ":::/64")) {
            assertNull(input, IpRangeParser.parseCidr(input))
        }
    }
    @Test fun fullIpv4RangeHasUnsignedBounds() {
        val range = IpRangeParser.parseCidr("0.0.0.0/0")!!
        assertEquals(BigInteger.ZERO, range.startIp)
        assertEquals(BigInteger("4294967295"), range.endIp)
    }
}
