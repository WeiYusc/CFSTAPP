package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.documents.DocumentOperationOwner
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.settings.AppStateStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class StateRestoreBoundaryTest {
    @Test fun failedStateCommitDoesNotDiscardPreviewAndGenerationRestores() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        vm.loadIpRanges("192.0.2.1"); val payload=vm.exportAppState(); val generation=vm.sourceGeneration
        vm.loadIpRanges("192.0.2.2"); assertTrue(vm.restoreAppState(payload))
        assertEquals(generation,vm.sourceGeneration)
        assertEquals(vm.sourceGeneration,SpeedTestViewModel(app).sourceGeneration)
    }
    @Test fun authoritativeStateRejectsMismatchingSpeedMirrors() {
        val store=AppStateStore(ApplicationProvider.getApplicationContext<Application>())
        val root=com.google.gson.JsonParser.parseString(store.exportState()).asJsonObject
        root["fields"].asJsonObject["minBytesPerSecond"].asJsonObject.addProperty("value",999999.0)
        try { store.restoreState(root.toString()); fail("contradictory state accepted") } catch(_:IllegalArgumentException) {}
    }
    @Test fun recoveredStateImportCarriesKindWithoutBundle() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val id=owner.picker(0)!!
        val uri=android.net.Uri.parse("content://state-recovery")
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStream(uri,java.io.ByteArrayInputStream(AppStateStore(app).exportState().toByteArray()))
        owner.readState(id,uri)
        val deadline=System.nanoTime()+2000000000
        while(owner.state.value?.stage==DocumentOperationOwner.Stage.READING && System.nanoTime()<deadline) Thread.sleep(10)
        assertEquals("STATE",owner.state.value!!.kind)
    }
}
