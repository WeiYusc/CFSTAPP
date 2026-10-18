package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import com.cfst.app.settings.AppStateStore
import com.cfst.app.viewmodel.SpeedTestViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ReleaseSafetyTest {
    @Test fun reserveIsReservedUntilCoroutineRunsAndReleaseCleansInvalidConfig() {
        val engine=SpeedTestEngine()
        val token=engine.reserve(SpeedTestEngine.TestConfig(downloadTest=false),IpRangeParser.parseIpFile("127.0.0.1"))!!
        assertEquals(RunOwner.State.RESERVED,engine.runOwner!!.state)
        engine.releaseRun(token)
        assertFalse(engine.isRunning())
    }
    @Test fun restoreRejectsMalformedNestedHistoryBeforeStateWrite() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val store=AppStateStore(app)
        val root=com.google.gson.JsonParser.parseString(store.exportState()).asJsonObject
        val node=com.google.gson.JsonObject(); node.addProperty("type","string"); node.addProperty("value","[[{\"ipAddress\":true}]]")
        root["fields"].asJsonObject.add("history_records",node)
        try { store.restoreState(root.toString()); fail("malformed history accepted") } catch(_:IllegalArgumentException) { }
        assertFalse(store.contains("history_records"))
    }
    @Test fun saveDraftWritesAuthoritativeSpeedAsNumberNotString() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        assertTrue(vm.saveDraft(vm.beginDraft().copy(speedMb="1.25",speedDirty=true)))
        val store=AppStateStore(app)
        assertTrue(store.all["minBytesPerSecond"] is Number)
        assertEquals(1250000.0,(store.all["minBytesPerSecond"] as Number).toDouble(),0.0)
    }
}
