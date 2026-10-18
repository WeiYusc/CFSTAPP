package com.cfst.app

import com.cfst.app.speedtest.*
import com.cfst.app.model.*
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

class OwnerRegressionTest {
    @Test fun cancelledOwnerCleansItsWorkersBeforeNextReservation() = runBlocking {
        val engine=SpeedTestEngine(); val token=engine.prepareRun()!!
        engine.stopTest("user")
        engine.startTest(com.cfst.app.utils.IpRangeParser.parseIpFile("127.0.0.1"),SpeedTestEngine.TestConfig(testCount=1,downloadTest=false),{},{},{},reserved=token)
        val getter=engine.javaClass.methods.firstOrNull { it.name=="getRunOwner" }
        assertNotNull("RunOwner not installed",getter)
        val owner=getter!!.invoke(engine)
        assertEquals("RELEASED",owner.javaClass.getMethod("getState").invoke(owner).toString())
        assertNotNull(engine.prepareRun())
    }
    @Test fun partialReadTimeoutIsNotNormalDeadline() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(100000)).throttleBody(8192,4,TimeUnit.SECONDS))
            val result=DownloadTransport().measure("127.0.0.1",server.url("/").toString(),server.port,5000,ByteBudget(100000),RunCancellation(),true)
            assertTrue(result.bytes>0)
            assertEquals(DownloadTermination.READ_TIMEOUT,result.termination)
            assertFalse(result.transportValid)
        }
    }
    @Test fun ledgerDedupesEventsAndKeepsAllAttempts() {
        val ledger=RunLedger("run",50,(1..50).map { "ip$it" }.toSet())
        for(n in 1..50) ledger.accept(MeasurementEvent(EventIdentity("run","ip$n","p$n",MeasurementStage.PROBE,1),probe=ProbeMeasurement(1,if(n<=16) 1 else 0,if(n<=16) 1.0 else null)))
        for(n in 1..10) {
            val event=MeasurementEvent(EventIdentity("run","ip$n","d$n",MeasurementStage.DOWNLOAD,1),download=DownloadMeasurement(1000,1000000000,"ip$n",200,DownloadError.BUDGET,DownloadTermination.BYTE_LIMIT,true),byteDelta=1000)
            ledger.accept(event); assertFalse(ledger.accept(event))
        }
        val record=ledger.record(SpeedTestEngine.TestConfig(),emptyList(),1,null)
        assertEquals(50,record.summary.generatedUnique); assertEquals(16,record.summary.probePassed)
        assertEquals(10,record.summary.downloadsStarted); assertEquals(6,record.summary.probePassedNotDownloaded)
        assertEquals(10000L,record.summary.bodyBytesConsumed); assertEquals(0,record.summary.recommended)
    }
}
