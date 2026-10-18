package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.model.*
import com.cfst.app.speedtest.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PresentationRegressionTest {
    @Test fun legacyShortAndPartialCannotBeBestDownload() {
        val vm=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        val field=vm.javaClass.getDeclaredField("_results").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST") val flow=field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<SpeedTestResult>>
        val legacy=SpeedTestResult("192.0.2.1",1,1,0.0,1.0,100.0,"N/A")
        flow.value=listOf(legacy)
        assertNull("legacy/short not best",vm.getBestIp())
    }
    @Test fun csvNewSchemaHasExactBytesDurationAndQuality() {
        val measurement=DownloadMeasurement(65536,1000000000,"192.0.2.1",200,DownloadError.BUDGET,DownloadTermination.BYTE_LIMIT,true)
        val result=SpeedTestResult("192.0.2.1",1,1,0.0,1.0,measurement.speed,"N/A",downloadedBytes=65536,download=measurement)
        assertTrue(SpeedTestResult.getCsvHeader().contains("schemaVersion"))
        assertTrue(result.toCsvString().contains("QUALIFIED"))
        assertTrue(result.toCsvString().contains("1000000000"))
    }
    @Test fun stateExportIncludesHistoryAndCanRestoreThroughViewModel() {
        val vm=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.loadIpRanges("192.0.2.1")
        val export=vm.javaClass.methods.firstOrNull { it.name=="exportAppState" }
        assertNotNull(export)
        val payload=export!!.invoke(vm) as String
        vm.loadIpRanges("192.0.2.2")
        val restore=vm.javaClass.methods.firstOrNull { it.name=="restoreAppState" }
        assertNotNull(restore); assertEquals(true,restore!!.invoke(vm,payload))
        assertEquals(listOf("192.0.2.1"),vm.availableIpSegments.value)
    }
}
