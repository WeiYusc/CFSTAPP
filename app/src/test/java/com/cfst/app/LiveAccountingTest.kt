package com.cfst.app

import com.cfst.app.model.*
import com.cfst.app.speedtest.*
import org.junit.Assert.*
import org.junit.Test

class LiveAccountingTest {
    @Test fun activeProbeAndDownloadCountersAreConserved() {
        val ledger=RunLedger("r",1,setOf("ip"))
        ledger.accept(MeasurementEvent(EventIdentity("r","ip","p",MeasurementStage.PROBE,0)))
        val active=ledger.record(SpeedTestEngine.TestConfig(),emptyList(),1,null).summary
        assertEquals(1,active.probeActive); assertEquals(1,active.probesStarted); assertEquals(0,active.probeNotStarted)
        ledger.accept(MeasurementEvent(EventIdentity("r","ip","p",MeasurementStage.PROBE,1),probe=ProbeMeasurement(1,1,1.0)))
        ledger.accept(MeasurementEvent(EventIdentity("r","ip","d",MeasurementStage.DOWNLOAD,0)))
        val download=ledger.record(SpeedTestEngine.TestConfig(),emptyList(),1,null).summary
        assertEquals(1,download.downloadActive); assertEquals(1,download.downloadsStarted)
        assertEquals(download.downloadsStarted,download.downloadActive+download.downloadSucceeded+download.downloadFailed+download.downloadCancelled)
        assertEquals(download.generatedUnique,download.probeNotStarted+download.probeActive+download.probePassed+download.probeFailed+download.probeCancelled)
    }
    @Test fun lateByteAfterTerminalRejectedButAllIndependentAttemptsCharged() {
        val ledger=RunLedger("r",1,setOf("ip"))
        val end=MeasurementEvent(EventIdentity("r","ip","d1",MeasurementStage.DOWNLOAD,2),download=DownloadMeasurement(5,1,"ip",200,DownloadError.BUDGET,DownloadTermination.BYTE_LIMIT,true),byteDelta=5)
        ledger.accept(end)
        assertFalse(ledger.accept(MeasurementEvent(end.identity.copy(sequence=3),byteDelta=100)))
        ledger.accept(end.copy(identity=end.identity.copy(attemptId="d2"),byteDelta=7))
        assertEquals(12L,ledger.record(SpeedTestEngine.TestConfig(),emptyList(),1,null).summary.bodyBytesConsumed)
    }
}
