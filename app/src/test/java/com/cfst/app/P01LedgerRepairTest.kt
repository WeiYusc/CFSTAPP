package com.cfst.app

import com.cfst.app.model.*
import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class P01LedgerRepairTest {
    @Test fun bodyProgressIsEphemeralButAttemptTotalsAndReplayIdentitySurvive() {
        val ledger=RunLedger("r",1,setOf("127.0.0.1"))
        val id=EventIdentity("r","127.0.0.1","d",MeasurementStage.DOWNLOAD,0)
        ledger.accept(MeasurementEvent(id))
        val chunks=38400 // a real 300 MiB consumption representation, not a larger cap
        repeat(chunks) { n ->
            val e=MeasurementEvent(id.copy(sequence=n+1L),byteDelta=8192)
            assertTrue(ledger.accept(e)); assertFalse(ledger.accept(e))
        }
        val before=ledger.record(SpeedTestEngine.TestConfig(),emptyList(),1,null)
        assertTrue("raw retained per-chunk events are unbounded",before.events.size<=2)
        ledger.accept(MeasurementEvent(id.copy(sequence=chunks+1L),download=DownloadMeasurement(bytes=chunks*8192L)))
        val record=ledger.record(SpeedTestEngine.TestConfig(),emptyList(),1,null)
        assertEquals(chunks*8192L,record.summary.bodyBytesConsumed)
        assertEquals(chunks*8192L,record.events.sumOf { it.byteDelta })
        assertEquals(1,record.events.count { it.download!=null })
    }
    @Test fun terminalBeforeCollectorStopIsNotReclassifiedByGlobalToken()= runBlocking {
        ServerSocket(0).use { server ->
            val engine=SpeedTestEngine(); val collectorEntered=CountDownLatch(1); val release=CountDownLatch(1)
            val connected=CountDownLatch(2)
            TcpPingTest.connectionObserver={ connected.countDown() }
            val job=launch(Dispatchers.IO) {
                engine.startTest(IpRangeParser.parseIpFile("127.0.0.1\n127.0.0.2"),SpeedTestEngine.TestConfig(testCount=2,pingCount=1,port=server.localPort,downloadTest=false,maxConcurrentPings=2),{}, {
                    collectorEntered.countDown(); release.await(5,TimeUnit.SECONDS)
                },{})
            }
            try {
                assertTrue(collectorEntered.await(5,TimeUnit.SECONDS)); assertTrue(connected.await(5,TimeUnit.SECONDS))
                Thread.sleep(100) // workers have queued terminal results; collector remains blocked
                engine.stopTest(); release.countDown(); job.join()
                assertEquals(2,engine.lastRecord!!.summary.probePassed)
                assertEquals(0,engine.lastRecord!!.summary.probeCancelled)
            } finally { release.countDown(); TcpPingTest.connectionObserver=null; job.cancelAndJoin() }
        }
    }
    @Test fun stopAfterFinalHandshakeDoesNotCancelFinishedProbeOrFailureDenominator()=runBlocking {
        ServerSocket(0).use { server ->
            val token=RunCancellation()
            TcpPingTest.connectionObserver={ token.cancel("USER_STOP") }
            try {
                val measured=TcpPingTest.ping("127.0.0.1",server.localPort,1,1000,token)
                assertEquals(1,measured.received); assertEquals(0,measured.cancelledAttempts)
                assertFalse("finished handshake is not cancelled by collector/observer delay",measured.cancelled)
            } finally { TcpPingTest.connectionObserver=null }
        }
    }
    @Test fun liveEngineSnapshotsNeverCopyDurableEventHistory()=runBlocking {
        ServerSocket(0).use { server ->
            val engine=SpeedTestEngine()
            engine.startTest(IpRangeParser.parseIpFile("127.0.0.1"),SpeedTestEngine.TestConfig(testCount=1,pingCount=1,port=server.localPort,downloadTest=false),{}, {
                assertTrue("live UI copies raw event list",engine.currentRecord!!.events.isEmpty())
            },{})
            assertEquals(2,engine.lastRecord!!.events.size)
        }
    }
    @Test fun downloadProgressCountsFinishedAttemptsOnly()=runBlocking {
        val server=okhttp3.mockwebserver.MockWebServer(); server.start()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("x".repeat(65536)))
            val progress=mutableListOf<SpeedTestEngine.TestProgress>(); val engine=SpeedTestEngine()
            engine.startTest(IpRangeParser.parseIpFile("127.0.0.1"),SpeedTestEngine.TestConfig(testCount=1,pingCount=1,port=server.port,downloadUrl=server.url("/").toString(),allowHttp=true,byteBudget=65536),{progress.add(it)},{},{})
            val download=progress.filter { it.status.contains("下载") }
            assertEquals(0,download.first().current)
            assertEquals(1,download.last().current)
        } finally { server.shutdown() }
    }
}
