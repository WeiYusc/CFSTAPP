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
class StateSnapshotMigrationTest {
    @Test fun allLegacyWrongTypesAreSafeAtRealStartup() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("local_settings",0).edit().putString("testCount","bad").putString("pingCount","bad").putInt("downloadUrl",3).putString("enableDownloadTest","bad").commit()
        val vm=SpeedTestViewModel(app)
        assertEquals(30,vm.testCount.value)
        assertEquals(3,vm.pingCount.value)
        assertTrue(vm.downloadUrl.value.startsWith("https://"))
    }
    @Test fun settingsAndListUseOneSnapshotAndLegacyIsReadOnly() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val prefs=app.getSharedPreferences("local_settings",0)
        prefs.edit().clear().putInt("testCount",30).commit()
        val vm=SpeedTestViewModel(app)
        vm.updateTestCount(17); vm.loadIpRanges("192.0.2.1")
        assertEquals("legacy prefs are backup",30,prefs.getInt("testCount",0))
        assertTrue(java.io.File(app.filesDir,"app-state.json").exists())
        val restored=SpeedTestViewModel(app)
        assertEquals(17,restored.testCount.value)
        assertEquals(listOf("192.0.2.1"),restored.availableIpSegments.value)
    }
    @Test fun emptyImportDoesNotChangeActualViewModel() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val vm=SpeedTestViewModel(app); vm.loadIpRanges("192.0.2.1"); vm.loadIpRanges("# only comments")
        assertEquals(listOf("192.0.2.1"),vm.availableIpSegments.value)
        assertTrue(vm.statusMessage.value.contains("empty"))
    }
}
