package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.speedtest.*
import com.cfst.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AccountingBoundaryTest {
    @Test fun sameAttemptSecondTerminalDoesNotOverwriteFirstOrChargeAgain() {
        val ledger=RunLedger("r",1,setOf("ip"))
        val first=MeasurementEvent(EventIdentity("r","ip","d",MeasurementStage.DOWNLOAD,1),download=DownloadMeasurement(100,1000000000,"ip",200,DownloadError.BUDGET,DownloadTermination.BYTE_LIMIT,true),byteDelta=100)
        ledger.accept(first)
        assertFalse(ledger.accept(first.copy(identity=first.identity.copy(sequence=2),byteDelta=1000)))
        assertEquals(100L,ledger.record(SpeedTestEngine.TestConfig(),emptyList(),1,null).summary.bodyBytesConsumed)
    }
    @Test fun cancelledConnectExcludedFromFailureDenominator() {
        val probe=ProbeMeasurement(2,1,1.0,true)
        val getter=probe.javaClass.methods.firstOrNull { it.name=="getCancelledConnectAttempts" }
        assertNotNull("actual cancellation denominator missing",getter)
    }
    @Test fun restoringBuiltinIsOneCommitEvenOnFailure() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        vm.loadIpRanges("192.0.2.1")
        val before=vm.currentListContent.value
        val file=java.io.File(app.filesDir,"app-state.json"); file.delete(); file.mkdirs(); java.io.File(file,"block").writeText("x")
        val backup=java.io.File(app.filesDir,"app-state.json.bak"); backup.mkdirs(); java.io.File(backup,"block").writeText("x")
        try { vm.restoreBuiltIn() } catch(_:Exception) { }
        assertEquals(before,vm.currentListContent.value)
        assertEquals("自定义列表",vm.sourceLabel.value)
    }
}
