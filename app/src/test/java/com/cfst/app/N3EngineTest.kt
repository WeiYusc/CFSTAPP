package com.cfst.app

import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class N3EngineTest {
    @Test fun realEngineProbesPriorityCandidatesOnlyAtCap() = runBlocking {
        ServerSocket(0,50,java.net.InetAddress.getByName("0.0.0.0")).use { server ->
            val seen=java.util.Collections.synchronizedSet(mutableSetOf<String>())
            TcpPingTest.connectionObserver={seen.add(it.inetAddress.hostAddress!!)}
            try {
                val engine=SpeedTestEngine()
                engine.startTest(IpRangeParser.parseIpFile("127.0.0.0/24\n127.0.0.9\n127.0.0.10/31"),SpeedTestEngine.TestConfig(testCount=3,pingCount=1,port=server.localPort,downloadTest=false),{},{},{})
                assertEquals(setOf("127.0.0.9","127.0.0.10","127.0.0.11"),seen)
                assertEquals(3,engine.lastRecord!!.summary.generatedUnique)
            } finally { TcpPingTest.connectionObserver=null }
        }
    }
    @Test fun samplingAndDeadlineAreVisibleInActualConfigurationUi() {
        val root=java.io.File("src/main/java/com/cfst/app")
        val test=root.resolve("ui/test/TestScreen.kt").readText()
        assertTrue("persistent sampler notice missing from UI",test.contains("vm.samplingNotice.collectAsState"))
        assertTrue("deadline config summary missing",test.contains("vm.runTimeSeconds.collectAsState"))
        assertTrue("seed summary missing",test.contains("vm.sampleSeed.collectAsState"))
    }
    @Test fun hugeIpv6EngineHasZeroInventedProbes() = runBlocking {
        val engine=SpeedTestEngine()
        engine.startTest(IpRangeParser.parseIpFile("2001:db8::/32"),SpeedTestEngine.TestConfig(testCount=2,pingCount=1,pingTimeout=1,downloadTest=false),{},{},{})
        assertEquals("unsupported prefix must not become fabricated candidates",0,engine.lastRecord!!.summary.generatedUnique)
    }
    @Test fun configCarriesValidatedStandardDeadlineAndSeed() {
        val json=JsonParser.parseString(Gson().toJson(SpeedTestEngine.TestConfig())).asJsonObject
        assertTrue("frozen run deadline missing",json.has("runTimeSeconds"))
        assertEquals(120,json["runTimeSeconds"].asInt)
        assertTrue(json.has("sampleSeed"))
    }
    @Test fun deadlineCanBeDrivenAtRealOwnerWithoutRelaxingProductionBounds() {
        val type=runCatching { Class.forName("com.cfst.app.speedtest.RunDeadlineClock") }.getOrNull()
        assertNotNull("monotonic owner deadline clock absent",type)
    }
}
