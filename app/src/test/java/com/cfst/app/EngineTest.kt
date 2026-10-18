package com.cfst.app

import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import com.cfst.app.model.SpeedTestResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class EngineTest {
    @Test fun stopBeforeCoroutineStartCancelsReservedRun() = runBlocking {
        val engine = SpeedTestEngine()
        val token = engine.prepareRun()!!
        engine.stopTest("foreground lost")
        var probes = 0
        engine.startTest(IpRangeParser.parseIpFile("127.0.0.1"), SpeedTestEngine.TestConfig(testCount=1,downloadTest=false), { probes++ }, {}, {}, reserved=token)
        assertEquals(0,probes)
        assertFalse(engine.isRunning())
        assertEquals("foreground lost",engine.stopReason)
    }
    @Test fun stopDuringNetworkHandshakeCannotReachRequest() {
        MockWebServer().use { server ->
            server.start()
            val engine = SpeedTestEngine()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            var completed = false
            val worker = Thread { runBlocking {
                engine.startTest(IpRangeParser.parseIpFile("127.0.0.1"), SpeedTestEngine.TestConfig(testCount=1, pingCount=1, port=server.port, downloadUrl=server.url("/").toString(), downloadDuration=100, allowHttp=true), {}, {}, { completed=true }, beforeNetwork = { entered.countDown(); release.await(2,TimeUnit.SECONDS); true })
            } }
            worker.start()
            try {
                assertTrue("handshake must execute before any probe", entered.await(2,TimeUnit.SECONDS))
                engine.stopTest("network lost during begin")
            } finally { release.countDown(); worker.join(3000) }
            assertFalse(worker.isAlive)
            assertTrue(completed)
            assertEquals(0,server.requestCount)
            assertEquals("network lost during begin",engine.stopReason)
        }
    }
    @Test fun invalidNetworkBeforeProbeCannotReachRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("x"))
            var events=0
            SpeedTestEngine().startTest(IpRangeParser.parseIpFile("127.0.0.1"),SpeedTestEngine.TestConfig(testCount=1,pingCount=1,port=server.port,downloadUrl=server.url("/").toString(),downloadDuration=100,allowHttp=true),{ events++ },{}, {}, beforeNetwork={false})
            assertEquals(0,events)
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun invalidConfigDoesNotStartNetwork() = runBlocking {
        val engine = SpeedTestEngine()
        var completed = false
        try {
            engine.startTest(IpRangeParser.parseIpFile("127.0.0.1"), SpeedTestEngine.TestConfig(downloadUrl="http://unsafe.example/"), {}, {}, { completed=true })
            fail("invalid URL must fail before ping")
        } catch (_: IllegalArgumentException) { }
        assertFalse(engine.isRunning())
        assertFalse(completed)
    }
    @Test fun speedLimitRetainsRawWithFilterFailed() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("x".repeat(1000)))
            var final = listOf<SpeedTestResult>()
            SpeedTestEngine().startTest(IpRangeParser.parseIpFile("127.0.0.1"), SpeedTestEngine.TestConfig(testCount=1, pingCount=1, port=server.port, downloadDuration=500, downloadTestCount=1, downloadUrl=server.url("/").toString(), byteBudget=1000, allowHttp=true, speedLimit=1e9), {}, {}, { final=it })
            assertEquals(1,final.size)
            assertFalse(final.single().download!!.filterPassed)
            assertFalse(final.single().download!!.recommended)
        }
    }
    @Test fun downloadsAreSerialWithEqualCandidateBudgets() = runBlocking {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("0.0.0.0"), 0)
            repeat(2) { server.enqueue(MockResponse().setBody("x".repeat(10000))) }
            val measured = mutableListOf<SpeedTestResult>()
            var final = listOf<SpeedTestResult>()
            SpeedTestEngine().startTest(IpRangeParser.parseIpFile("127.0.0.1\n127.0.0.2"), SpeedTestEngine.TestConfig(testCount=2, pingCount=1, port=server.port, downloadDuration=500, downloadTestCount=2, downloadUrl="http://candidate.test/payload", byteBudget=2000, allowHttp=true, maxConcurrentDownloads=5), {}, {
                if (it.downloadedBytes > 0) {
                    measured.add(it)
                    assertEquals(measured.size, server.requestCount)
                }
            }, { final=it })
            assertEquals(2, measured.size)
            assertEquals(listOf(1000L, 1000L), measured.map { it.downloadedBytes })
            assertEquals(2000L, final.sumOf { it.downloadedBytes })
            assertTrue(measured.all { it.ipAddress == it.observedPeer })
        }
    }
    @Test fun coroutineCancellationClosesBlockedCall() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val engine = SpeedTestEngine()
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                engine.startTest(IpRangeParser.parseIpFile("127.0.0.1"), SpeedTestEngine.TestConfig(testCount=1, pingCount=1, port=server.port, downloadUrl=server.url("/").toString(), downloadDuration=10000, allowHttp=true), {}, {}, {})
            }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            job.cancel()
            kotlinx.coroutines.withTimeout(1800) { job.join() }
            assertFalse(engine.isRunning())
        }
    }
    @Test fun pingOnlyDoesNotDownloadEvenWithoutPingOnlyFlag() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val results = mutableListOf<SpeedTestResult>()
            SpeedTestEngine().startTest(IpRangeParser.parseIpFile("127.0.0.1/32"), SpeedTestEngine.TestConfig(testCount=1, pingCount=1, port=server.port, downloadTest=false), {}, { results.add(it) }, {})
            assertEquals(1, results.size)
            assertEquals(0.0, results[0].downloadSpeed, 0.0)
        }
    }
    @Test fun streamsResultBeforeNextDownloadAndUsesConfiguredEndpoint() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("x".repeat(10000)))
            var completed = false
            val results = mutableListOf<SpeedTestResult>()
            SpeedTestEngine().startTest(IpRangeParser.parseIpFile("127.0.0.1/32"), SpeedTestEngine.TestConfig(testCount=1, pingCount=1, port=server.port, downloadDuration=500, downloadTestCount=1, downloadUrl=server.url("/chosen").toString(), byteBudget=1000, allowHttp=true), {}, { assertFalse(completed); results.add(it) }, { completed=true })
            assertEquals(2, results.size) // preliminary ping, then measured update
            assertTrue(results.last().downloadSpeed > 0)
            assertEquals("/chosen", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        }
    }
    @Test fun defaultDownloadRankingIsSerialAndDataSaving() {
        val config = SpeedTestEngine.TestConfig()
        assertEquals(1, config.maxConcurrentDownloads)
        assertTrue(config.downloadTestCount <= 3)
        assertTrue(config.downloadDuration <= 3000)
        assertTrue(config.byteBudget <= 3 * 1024 * 1024)
    }
    @Test fun stopInterruptsRealBlockedDownloadAndRetainsPingResult() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val engine = SpeedTestEngine()
            val final = mutableListOf<SpeedTestResult>()
            val worker = Thread { runBlocking { engine.startTest(IpRangeParser.parseIpFile("127.0.0.1/32"), SpeedTestEngine.TestConfig(testCount=1, pingCount=1, port=server.port, downloadUrl=server.url("/").toString(), downloadDuration=10000, allowHttp=true), {}, {}, { final.addAll(it) }) } }
            worker.start(); assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); engine.stopTest(); worker.join(1800)
            assertFalse(worker.isAlive)
            assertEquals(1, final.size)
        }
    }
}
