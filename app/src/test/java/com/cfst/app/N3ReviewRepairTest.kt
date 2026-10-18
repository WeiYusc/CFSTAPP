package com.cfst.app

import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch

class N3ReviewRepairTest {
    private fun stoppedThenExpired(reason:String) {
        val clock=N3DeadlineTest.Clock()
        val token=RunCancellation()
        token.bindDeadline(clock.now+1,clock)
        token.cancel(reason); clock.now+=2
        val result=DownloadTransport().measure("127.0.0.1","http://localhost/",80,1000,ByteBudget(100),token,true)
        assertEquals(reason,token.reason)
        assertEquals(DownloadTermination.valueOf(reason),result.termination)
        assertEquals(DownloadError.CANCELLED,result.error)
        assertFalse(result.recommended)
    }
    @Test fun userStopWinsOverLaterExpiryAtMeasureEntry() = stoppedThenExpired("USER_STOP")
    @Test fun networkLossWinsOverLaterExpiryAtMeasureEntry() = stoppedThenExpired("NETWORK_LOST")
    private fun maximumSources(cap:Int) {
        val text=(0 until 10000).joinToString("\n") { IpRangeParser.longToIp(0x0a000000L+512L*it) }
        val ranges=IpRangeParser.parseIpFile(text)
        assertEquals(10000,ranges.size)
        val start=System.nanoTime()
        val result=CandidateSampler(ranges,cap,0).sample()
        val elapsed=(System.nanoTime()-start)/1000000
        println("N3 maximum legal literal sources cap=$cap elapsedMs=$elapsed")
        assertEquals(cap,result.candidates.size)
        assertEquals("10000",result.report.eligibleCount)
        assertEquals((10000-cap).toString(),result.report.priorityNotCovered)
        assertTrue("10k preparation must finish within finite host gate (2000ms), actual=$elapsed",elapsed<2000)
    }
    @Test fun maximumLegalSourcesAtCapOneAreBounded() = maximumSources(1)
    @Test fun maximumLegalSourcesAtCapTenThousandAreBounded() = maximumSources(10000)

    @Test fun delayedAttemptTimerStillClassifiesRunLimitWithPartialBody() {
        val clock=N3DeadlineTest.Clock()
        val token=RunCancellation();token.bindDeadline(clock.now+100_000_000,clock)
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start();server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("x".repeat(100000)))
            val result=DownloadTransport(clock).measure("127.0.0.1",server.url("/").toString(),server.port,10000,ByteBudget(200000),token,true) { clock.now+=200_000_000 }
            assertEquals(1,server.requestCount)
            assertTrue(result.bytes>0)
            assertEquals("RUN_TIME_LIMIT",token.reason)
            assertEquals(DownloadTermination.RUN_TIME_LIMIT,result.termination)
            assertEquals(DownloadError.CANCELLED,result.error)
            assertFalse(result.recommended)
            assertTrue(clock.tasks.single().joined)
        }
    }
    private fun stopDuringPreparation(cause:String) = kotlinx.coroutines.runBlocking {
        val clock=N3DeadlineTest.Clock(); val engine=SpeedTestEngine(clock)
        val ranges=IpRangeParser.parseIpFile((0 until 10000).joinToString("\n") { IpRangeParser.longToIp(0x0a000000L+512L*it) })
        val config=SpeedTestEngine.TestConfig(testCount=1,pingCount=1,pingTimeout=1,downloadTest=false,runTimeSeconds=30)
        val token=engine.reserve(config,ranges)!!
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val visits=java.util.concurrent.atomic.AtomicInteger();val network=java.util.concurrent.atomic.AtomicInteger()
        var completed=false
        CandidateSampler.preparationObserver={ if(visits.incrementAndGet()==128) { entered.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS)) } }
        TcpPingTest.connectionObserver={ network.incrementAndGet() }
        val job=launch(kotlinx.coroutines.Dispatchers.Default) {
            engine.startTest(ranges,config,{},{},{ assertTrue(clock.tasks.single().joined);completed=true },reserved=token)
        }
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            when(cause) {
                "USER_STOP" -> engine.stopTest(cause)
                "RUN_TIME_LIMIT" -> clock.now=clock.tasks.single().end // scheduler deliberately undispatched
                else -> job.cancel()
            }
            release.countDown();job.join()
            assertTrue("preparation must stop at the next cooperative boundary, visits=${visits.get()}",visits.get()<=129)
            assertEquals(0,network.get());assertTrue(completed)
            assertEquals(cause,engine.lastRecord!!.summary.stoppedReason)
            assertEquals(0,engine.lastRecord!!.summary.generatedUnique)
            assertEquals(RunOwner.State.RELEASED,engine.runOwner!!.state);assertFalse(engine.isRunning())
            val next=engine.reserve(config,ranges)!!;assertFalse(next.cancelled);engine.releaseRun(next)
        } finally { release.countDown();job.cancel();job.join();CandidateSampler.preparationObserver=null;TcpPingTest.connectionObserver=null;engine.stopTest() }
    }
    @Test fun userStopDuringActualEnginePreparationReleasesOwner() = stopDuringPreparation("USER_STOP")
    @Test fun deadlineDuringActualEnginePreparationReleasesOwner() = stopDuringPreparation("RUN_TIME_LIMIT")
    @Test fun coroutineCancelDuringActualEnginePreparationReleasesOwner() = stopDuringPreparation("OWNER_CANCELLED")

    @Test fun delayedLocalTimerIsOrdinaryTimeLimitNotRunCancellation() {
        val clock=N3DeadlineTest.Clock();val token=RunCancellation()
        token.bindDeadline(clock.now+30_000_000_000,clock)
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start();server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("x".repeat(100000)))
            val result=DownloadTransport(clock).measure("127.0.0.1",server.url("/").toString(),server.port,100,ByteBudget(200000),token,true) { clock.now+=200_000_000 }
            assertTrue(result.bytes>0);assertEquals(DownloadTermination.TIME_LIMIT,result.termination)
            assertNull(token.reason);assertTrue(result.transportValid)
        }
    }
    @Test fun parentFirstReasonWinsWhenLocalTimerHasAlreadyFired() {
        val clock=N3DeadlineTest.Clock();val token=RunCancellation()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start();server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("x".repeat(100000)))
            val result=DownloadTransport(clock).measure("127.0.0.1",server.url("/").toString(),server.port,100,ByteBudget(200000),token,true) { clock.tasks.single().action();token.cancel("NETWORK_LOST") }
            assertEquals(DownloadTermination.NETWORK_LOST,result.termination)
            assertEquals(DownloadError.CANCELLED,result.error);assertFalse(result.recommended)
        }
    }
    @Test fun cancelledEntryWithInvalidTargetStillHonorsParentFirstReason() {
        val token=RunCancellation();token.cancel("USER_STOP")
        assertEquals(DownloadTermination.USER_STOP,DownloadTransport().measure("bad","bad",0,100,ByteBudget(10),token).termination)
    }
    @Test fun unevenOverlappingSmallSourcesKeepCanonicalRoundRobinThroughRotation() {
        val ranges=IpRangeParser.parseIpFile("192.0.2.0/30\n192.0.2.2/31\n192.0.2.8/31\n192.0.2.16/29\n192.0.2.17")
        val full=CandidateSampler(ranges,30,0).sample().candidates.toList()
        assertEquals(listOf("192.0.2.17","192.0.2.0","192.0.2.8","192.0.2.16","192.0.2.1","192.0.2.9","192.0.2.18","192.0.2.2","192.0.2.19","192.0.2.3","192.0.2.20","192.0.2.21","192.0.2.22","192.0.2.23"),full)
        for(seed in listOf(-1L,0L,4L,10L,Long.MIN_VALUE)) {
            val small=CandidateSampler(ranges,6,seed).sample()
            val pool=full.drop(1);val offset=Math.floorMod(seed,pool.size.toLong()).toInt()
            assertEquals(listOf(full.first())+(0 until 5).map { pool[(offset+it)%pool.size] },small.candidates.toList())
        }
    }
    @Test fun disjointBroadStrataAndFiniteIpv6OverlapStayExact() {
        val result=CandidateSampler(IpRangeParser.parseIpFile("192.0.2.0/27\n192.0.3.224/27"),100,17).sample()
        assertEquals(64,result.candidates.size);assertEquals("64",result.report.eligibleCount)
        assertTrue(result.candidates.all { it.startsWith("192.0.2.") || it.startsWith("192.0.3.") })
        assertEquals(256,CandidateSampler(IpRangeParser.parseIpFile("2001:db8::/120\n2001:db8::80/121\n2001:db8::1"),300,0).sample().candidates.size)
    }
}
