package com.cfst.app

import com.cfst.app.utils.IpRangeParser
import org.junit.Assert.*
import org.junit.Test

class CandidateSamplerTest {
    // Reflective feature discovery lets pre-implementation RED reach an assertion, not compiler failure.
    private fun sample(text:String, cap:Int, seed:Long=0):Any {
        val type=runCatching { Class.forName("com.cfst.app.speedtest.CandidateSampler") }.getOrNull()
        assertNotNull("N3 bounded sampler is absent",type)
        return type!!.getMethod("sample").invoke(type.getConstructor(List::class.java,Int::class.javaPrimitiveType,Long::class.javaPrimitiveType).newInstance(IpRangeParser.parseIpFile(text),cap,seed))
    }
    @Suppress("UNCHECKED_CAST") private fun candidates(value:Any)=value.javaClass.getMethod("getCandidates").invoke(value) as Set<String>
    private fun report(value:Any)=value.javaClass.getMethod("getReport").invoke(value)
    private fun field(value:Any,name:String)=value.javaClass.getMethod("get$name").invoke(value)
    @Test fun literalsAndSmallRangesSurviveBroadOverlap() {
        val result=sample("0.0.0.0/0\n192.0.2.9\n198.51.100.0/31",3)
        assertEquals(setOf("192.0.2.9","198.51.100.0","198.51.100.1"),candidates(result))
    }
    @Test fun finitePoolExhaustsWithoutInventedAddresses() {
        val result=sample("192.0.2.0/31\n192.0.2.0/32",20)
        assertEquals(setOf("192.0.2.0","192.0.2.1"),candidates(result))
        assertEquals("POOL_EXHAUSTED",field(report(result),"Status").toString())
    }
    @Test fun hugeIpv6DoesNotInventLowSuffixes() {
        val result=sample("2606:4700::/32",30)
        assertTrue(candidates(result).isEmpty())
        assertEquals("NO_CANDIDATES",field(report(result),"Status").toString())
        assertEquals(1,field(report(result),"LimitedSources"))
    }
    @Test fun explicitIpv6And256MemberSubnetAreFinite() {
        val result=sample("2001:db8::/120\n2001:db8::/32\n2001:db8::1",300)
        assertEquals(256,candidates(result).size)
        assertEquals("LIMITED_SOURCE",field(report(result),"Status").toString())
    }
    @Test fun seedReproducesAndRotatesTruncatedPriorityPool() {
        val text=(1..20).joinToString("\n") { "192.0.2.$it" }
        assertEquals(candidates(sample(text,4,2)),candidates(sample(text,4,2)))
        assertNotEquals(candidates(sample(text,4,2)),candidates(sample(text,4,3)))
        assertEquals("16",field(report(sample(text,4,2)),"PriorityNotCovered"))
    }
    @Test fun slashZeroIsBoundedAndStratifiedAfterUnion() {
        val a=sample("0.0.0.0/0\n0.0.0.0/1\n128.0.0.0/1",1000,17)
        val b=sample("0.0.0.0/0",1000,17)
        assertEquals(candidates(a),candidates(b))
        assertEquals(1000,candidates(a).size)
        assertEquals(1000,candidates(a).map { it.substringBeforeLast('.') }.distinct().size)
        assertTrue((field(report(a),"Attempts") as Int)<=30000)
        assertEquals("4294967296",field(report(a),"EligibleCount"))
    }
    @Test fun sourceRoundRobinUsesCanonicalIpOrderBeforeBoundedRotation() {
        val result=sample("192.0.2.16/30\n192.0.2.0/30",8)
        assertEquals(listOf("192.0.2.0","192.0.2.16","192.0.2.1","192.0.2.17","192.0.2.2","192.0.2.18","192.0.2.3","192.0.2.19"),candidates(result).toList())
        assertEquals(candidates(result),candidates(sample("192.0.2.0/30\n192.0.2.16/30",8)))
    }
    @Test fun finiteBroadPoolAcrossStratumBoundaryExhausts() {
        val result=sample("192.0.2.0/27",100)
        assertEquals(32,candidates(result).size)
        assertEquals("POOL_EXHAUSTED",field(report(result),"Status").toString())
        assertEquals("32",field(report(result),"EligibleCount"))
    }
    @Test fun malformedIntervalsAreRejected() {
        // Parser supplies valid ranges; hostile programmatic ranges must not escape the same bounds.
        val type=runCatching { Class.forName("com.cfst.app.speedtest.CandidateSampler") }.getOrNull()
        assertNotNull("N3 bounded sampler is absent",type)
        try {
            val sampler=type!!.getConstructor(List::class.java,Int::class.javaPrimitiveType,Long::class.javaPrimitiveType).newInstance(listOf(IpRangeParser.IpRange(java.math.BigInteger.valueOf(-1),java.math.BigInteger.TEN)),10,0L)
            sampler.javaClass.getMethod("sample").invoke(sampler)
            fail("negative IP rejected")
        } catch(e:java.lang.reflect.InvocationTargetException) { assertTrue(e.cause is IllegalArgumentException) }
    }
}
