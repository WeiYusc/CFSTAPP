package com.cfst.app

import com.cfst.app.speedtest.CandidateSampler
import com.cfst.app.utils.IpRangeParser
import org.junit.Assert.*
import org.junit.Test

class SamplingRandomizationRepairTest {
    private fun sample(text:String,cap:Int,seed:Long)=CandidateSampler(IpRangeParser.parseIpFile(text),cap,seed).sample()
    @Test fun versionTwoFrozenOrderedGolden() {
        assertEquals(2,CandidateSampler.ALGORITHM_VERSION)
        val expected=mapOf(
            0L to "54.183 127.0 200.109 17.146 90.122 163.177 236.199 53.140",
            65536L to "154.220 105.141 56.199 7.234 214.187 165.143 116.148 67.12",
            Long.MIN_VALUE to "169.249 62.240 211.127 104.222 253.71 146.147 39.146 188.152",
            Long.MAX_VALUE to "59.37 180.63 45.238 166.236 31.227 152.211 17.6 138.72"
        )
        expected.forEach { (seed,suffixes) -> assertEquals(suffixes.split(' ').map { "104.20.$it" },sample("104.20.0.0/16",8,seed).candidates.toList()) }
    }
    @Test fun defaultSeedDoesNotForceDiagonalEndpoints() {
        val rows=sample("104.20.0.0/16\n162.159.0.0/16",512,0).candidates
        assertEquals(512,rows.size)
        val diagonal=rows.count { val p=it.split('.');p[2]==p[3] }
        assertTrue("Endpoint correlation: $diagonal / 512",diagonal<=25)
    }
    @Test fun fixedSeedCorpusDoesNotForceXorEndpoints() {
        for(seed in listOf(0L,1L,256L,65536L,Long.MIN_VALUE,Long.MAX_VALUE)) {
            val rows=sample("104.20.0.0/16",256,seed).candidates
            val correlated=rows.count { val p=it.split('.'); p[3].toInt()==(p[2].toInt() xor (seed.toInt() and 255)) }
            assertTrue("seed $seed has $correlated XOR-related endpoints",correlated<=20)
        }
    }
    @Test fun highSeedBitsAffectOrderedCandidates() {
        val base=sample("104.20.0.0/16",256,0).candidates.toList()
        assertNotEquals(base,sample("104.20.0.0/16",256,65536).candidates.toList())
        assertNotEquals(base,sample("104.20.0.0/16",256,1L shl 48).candidates.toList())
    }
    @Test fun fixedCorpusUsesBroadTailDistribution() {
        val tails=IntArray(256)
        for(seed in 0L until 64L) sample("104.20.0.0/16",256,seed).candidates.forEach { tails[it.substringAfterLast('.').toInt()]++ }
        assertTrue(tails.count { it>0 }>=240)
        assertTrue(tails.max()<256)
    }
    @Test fun sameSeedAndCanonicalUnionReproduceOrder() {
        val a=sample("104.20.0.0/16\n104.20.0.0/17",500,-17).candidates.toList()
        assertEquals(a,sample("104.20.0.0/16",500,-17).candidates.toList())
        assertEquals(a,sample("104.20.0.0/16",500,-17).candidates.toList())
    }
    @Test fun completeBlockPermutationExhaustsWithoutDuplicates() {
        for(seed in listOf(0L,1L,Long.MIN_VALUE,Long.MAX_VALUE)) {
            val s=sample("192.0.2.0/24",1000,seed)
            assertEquals((0..255).map { "192.0.2.$it" }.toSet(),s.candidates)
            assertEquals(CandidateSampler.Status.POOL_EXHAUSTED,s.report.status)
        }
    }
    @Test fun partialBlockAndPriorityHoleRemainExact() {
        val s=sample("192.0.2.128/25\n192.0.2.200",256,123)
        assertEquals((128..255).map { "192.0.2.$it" }.toSet(),s.candidates)
        assertEquals("192.0.2.200",s.candidates.first())
    }
    @Test fun firstBroadPassIsStratifiedAndBounded() {
        val s=sample("0.0.0.0/0",10000,Long.MIN_VALUE)
        assertEquals(10000,s.candidates.size)
        assertEquals(10000,s.candidates.map { it.substringBeforeLast('.') }.distinct().size)
        assertTrue(s.report.attempts<=300000)
    }
}
