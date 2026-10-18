package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.viewmodel.SpeedTestViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SettingsTest {
    @Test fun selectedSegmentsAndCustomListPersist() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("local_settings", 0).edit().clear().commit()
        val first = SpeedTestViewModel(app)
        first.loadIpRanges("192.0.2.1\n192.0.2.2"); first.toggleSpecifiedIpSegment("192.0.2.2"); first.updateUseSpecifiedIpSegments(true)
        val second = SpeedTestViewModel(app)
        second.loadLocalIpRanges()
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), second.availableIpSegments.value)
        assertEquals(setOf("192.0.2.2"), second.selectedSpecifiedIpSegments.value)
    }
    @Test fun malformedStoredNumbersDoNotCrashStartup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("local_settings", 0).edit().putString("speedLimit", "garbage").putString("latencyLimit", "garbage").commit()
        val vm = SpeedTestViewModel(app)
        assertEquals(0.0, vm.speedLimit.value, 0.0)
        assertEquals(0.0, vm.latencyLimit.value, 0.0)
    }
    @Test fun remainingSettingsPersist() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("local_settings", 0).edit().clear().commit()
        val first = SpeedTestViewModel(app)
        first.updateSpeedLimit(1.2); first.updateLatencyLimit(250.0); first.updateUseIpv6(true)
        first.updateSortByLatency(true); first.updateProxyUrl("https://mirror.example/"); first.updateUseProxy(true)
        val second = SpeedTestViewModel(app)
        assertEquals(1.2, second.speedLimit.value, 0.0); assertEquals(250.0, second.latencyLimit.value, 0.0)
        assertTrue(second.useIpv6.value); assertTrue(second.sortByLatency.value); assertTrue(second.useProxy.value)
        assertEquals("https://mirror.example/", second.proxyUrl.value)
    }
    @Test fun csvExportWritesActualFile() {
        val vm = SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        val file = java.io.File.createTempFile("cfst-export-", ".csv")
        try {
            file.outputStream().use { vm.writeCsv(it) }
            assertTrue(file.readText().contains("响应体字节"))
            assertTrue(file.length() > 0)
        } finally { file.delete() }
    }
    @Test fun settingsPersistAcrossRealViewModelRecreation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("local_settings", 0).edit().clear().commit()
        val first = SpeedTestViewModel(app)
        first.updateTestCount(17); first.updateDownloadDuration(2); first.updateEnableDownloadTest(false)
        val second = SpeedTestViewModel(app)
        assertEquals(17, second.testCount.value)
        assertEquals(2, second.downloadDuration.value)
        assertFalse(second.enableDownloadTest.value)
    }
    @Test fun defaultSettingsAreLowData() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("local_settings", 0).edit().clear().commit()
        val vm = SpeedTestViewModel(app)
        assertTrue(vm.downloadTestCount.value <= 3)
        assertTrue(vm.downloadDuration.value <= 3)
    }
    @Test fun rejectsInvalidCustomFileWithoutReplacingCurrentList() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = SpeedTestViewModel(app)
        vm.loadIpRanges("127.0.0.1")
        vm.loadIpRanges("127.0.0.1\ninvalid")
        assertTrue(vm.statusMessage.value.contains("Invalid"))
        assertEquals(listOf("127.0.0.1"), vm.availableIpSegments.value)
    }
}
