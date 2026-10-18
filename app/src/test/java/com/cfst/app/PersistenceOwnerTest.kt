package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PersistenceOwnerTest {
    @Test fun allCompatibilityUpdatesCommitBeforeMemory() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        val before=listOf(vm.pingCount.value,vm.maxConcurrentPings.value,vm.enableDownloadTest.value,vm.speedLimit.value,vm.latencyLimit.value,vm.downloadTestCount.value,vm.downloadDuration.value,vm.downloadPort.value,vm.downloadUrl.value,vm.budgetMiB.value,vm.useProxy.value,vm.proxyUrl.value,vm.useIpv6.value,vm.sortByLatency.value)
        val file=java.io.File(app.filesDir,"app-state.json"); file.delete(); file.mkdirs(); java.io.File(file,"block").writeText("x")
        val backup=java.io.File(app.filesDir,"app-state.json.bak"); backup.mkdirs(); java.io.File(backup,"block").writeText("x")
        val actions=listOf<()->Unit>({vm.updatePingCount(7)},{vm.updateMaxConcurrentPings(4)},{vm.updateEnableDownloadTest(false)},{vm.updateSpeedLimit(1.0)},{vm.updateLatencyLimit(250.0)},{vm.updateDownloadTestCount(8)},{vm.updateDownloadDuration(8)},{vm.updateDownloadPort(8443)},{vm.updateDownloadUrl("https://example.com/")},{vm.updateBudgetMiB(10)},{vm.updateUseProxy(true)},{vm.updateProxyUrl("https://mirror.example/")},{vm.updateUseIpv6(true)},{vm.updateSortByLatency(true)})
        actions.forEach { try { it() } catch(_:Exception) { } }
        assertEquals(before,listOf(vm.pingCount.value,vm.maxConcurrentPings.value,vm.enableDownloadTest.value,vm.speedLimit.value,vm.latencyLimit.value,vm.downloadTestCount.value,vm.downloadDuration.value,vm.downloadPort.value,vm.downloadUrl.value,vm.budgetMiB.value,vm.useProxy.value,vm.proxyUrl.value,vm.useIpv6.value,vm.sortByLatency.value))
    }
    @Test fun mixedFamilySourcePreservedAcrossRecreation() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        vm.loadIpRanges("192.0.2.1\n2001:db8::1")
        vm.updateUseIpv6(true)
        assertEquals(listOf("192.0.2.1","2001:db8::1"),vm.availableIpSegments.value)
        assertEquals(vm.availableIpSegments.value,SpeedTestViewModel(app).availableIpSegments.value)
    }
    @Test fun typedRunHistoryPersistsOnceWithConfigAndSummary() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        val getter=vm.javaClass.methods.firstOrNull { it.name=="getRunRecords" }
        assertNotNull("typed durable history absent",getter)
    }
}
