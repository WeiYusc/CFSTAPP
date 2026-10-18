package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.settings.AppStateStore
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DurableRecordTest {
    @Test fun failedProbeRecordSerializesWithoutInventingZeroDelay() = runBlocking {
        java.net.ServerSocket(0).use { socket ->
            val port=socket.localPort; socket.close(); val engine=SpeedTestEngine()
            engine.startTest(IpRangeParser.parseIpFile("127.0.0.1"),SpeedTestEngine.TestConfig(testCount=1,pingCount=1,port=port,downloadTest=false),{},{},{})
            assertNull(engine.lastRecord!!.results.single().probe!!.delayMs)
            val json=com.google.gson.Gson().toJson(engine.lastRecord)
            assertFalse(json.contains("NaN"))
        }
    }
    @Test fun corruptTypedSnapshotFailsClosedPreservingAuthority() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val store=AppStateStore(app)
        val payload=store.exportState().replace("\"value\":30","\"value\":10001")
        java.io.File(app.filesDir,"app-state.json").writeText(payload)
        try { SpeedTestViewModel(app); fail("corrupt authority must never fall back") } catch(_:IllegalStateException) {}
        assertEquals(payload,java.io.File(app.filesDir,"app-state.json").readText())
    }
    @Test fun restoreRejectsWrongPrimitiveBeforeAnyWrite() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val store=AppStateStore(app)
        val payload=store.exportState().replace("\"type\":\"boolean\",\"value\":true","\"type\":\"boolean\",\"value\":\"true\"")
        val original=store.exportState()
        try { store.restoreState(payload); fail("string coerced into boolean") } catch(_:IllegalArgumentException) { }
        assertEquals(original,store.exportState())
    }
}
