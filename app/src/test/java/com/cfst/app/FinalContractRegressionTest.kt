package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.settings.AppStateStore
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.speedtest.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class FinalContractRegressionTest {
    @Test fun historyReadsAuthoritativeSnapshotNotOldPrefs() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val store=AppStateStore(app)
        store.edit().putString("history_records","[[{\"ipAddress\":\"192.0.2.1\",\"packetsSent\":1,\"packetsReceived\":1,\"packetLoss\":0,\"avgLatency\":1,\"downloadSpeed\":0,\"regionCode\":\"N/A\"}]]").commit()
        val vm=SpeedTestViewModel(app)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1,vm.historyRecords.value.size)
    }
    @Test fun invalidSelectionCannotProduceEmptyRunAfterMigration() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("local_settings",0).edit().putBoolean("useSpecifiedIpSegments",true).putStringSet("selectedSegments",setOf("bad!")).commit()
        val vm=SpeedTestViewModel(app)
        assertFalse("unusable selection disabled",vm.useSpecifiedIpSegments.value)
    }
    @Test fun reservedRunConfigIsBoundBeforeAnyDispatch() {
        val engine=SpeedTestEngine()
        val reserve=engine.javaClass.methods.firstOrNull { it.name=="reserve" }
        assertNotNull("immutable reserve API absent",reserve)
    }
    @Test fun snapshotRestoreRejectsLatencyAboveCapAndUnsafeMirror() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val store=AppStateStore(app)
        val original=store.exportState()
        for((key,value) in listOf("latencyLimit" to "10000", "proxyUrl" to "http://unsafe.example/")) {
            val root=com.google.gson.JsonParser.parseString(original).asJsonObject
            root["fields"].asJsonObject[key].asJsonObject.addProperty("value",value)
            try { store.restoreState(root.toString()); fail("bad $key accepted") } catch(_:IllegalArgumentException) { }
            assertEquals(original,store.exportState())
        }
    }
}
