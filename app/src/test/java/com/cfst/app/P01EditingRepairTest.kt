package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.settings.AppStateStore
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class P01EditingRepairTest {
    @Test fun unconfirmedPasteDraftBlocksStartAndCanBeCancelledFromAnyPage() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        vm.loadIpRanges("127.0.0.1")
        val edit=vm.javaClass.methods.firstOrNull { it.name=="editPaste" }; assertNotNull("paste edit must be authoritative",edit)
        edit!!.invoke(vm,"127.0.0.2"); vm.startTest(); assertFalse(vm.isTesting.value)
        vm.cancelImport(); assertNull(vm.importPreview.value)
        val pending=vm.javaClass.methods.first { it.name=="getPastePending" }.invoke(vm) as kotlinx.coroutines.flow.StateFlow<*>
        assertEquals(false,pending.value)
    }
    @Test fun allSelectionActionsUseFamilyAndInvalidatePendingGenerationAtomically() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        vm.loadIpRanges("192.0.2.1\n2001:db8::1")
        val request=vm.captureListRequest(); vm.selectAllSpecifiedIpSegments()
        assertEquals(setOf("192.0.2.1"),vm.selectedSpecifiedIpSegments.value)
        assertFalse(vm.acceptListResponse(request,"192.0.2.99"))
        vm.updateUseSpecifiedIpSegments(true); vm.clearSpecifiedIpSegments()
        assertFalse(vm.useSpecifiedIpSegments.value)
    }
    @Test fun selectedSegmentsWrongFamilyCannotPublishAndFailedSaveIsAtomic() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        vm.loadIpRanges("192.0.2.1\n2001:db8::1")
        vm.toggleSpecifiedIpSegment("2001:db8::1")
        assertTrue("wrong family selection must be rejected",vm.selectedSpecifiedIpSegments.value.isEmpty())
        vm.toggleSpecifiedIpSegment("192.0.2.1"); vm.updateUseSpecifiedIpSegments(true)
        val generation=vm.sourceGeneration
        val request=vm.captureListRequest()
        vm.toggleSpecifiedIpSegment("192.0.2.1")
        assertTrue(vm.sourceGeneration>generation)
        assertFalse(vm.acceptListResponse(request,"192.0.2.99"))
        assertFalse(vm.useSpecifiedIpSegments.value)
        for(name in listOf("app-state.json","app-state.json.bak")) File(app.filesDir,name).apply { delete();mkdirs();resolve("block").writeText("block") }
        runCatching { vm.toggleSpecifiedIpSegment("192.0.2.1") }
        assertTrue(vm.selectedSpecifiedIpSegments.value.isEmpty())
    }
    @Test fun damagedAuthorityCannotBeOverwrittenByExistingStoreEditor() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val store=AppStateStore(app)
        val file=File(app.filesDir,"app-state.json"); file.writeText("broken authority")
        assertFalse(store.edit().putInt("testCount",99).commit())
        assertEquals("broken authority",file.readText())
    }
    @Test fun quickPresetIsDraftOnlyThenOneSaveFreezesRealRunConfig() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        val method=vm.javaClass.methods.firstOrNull { it.name=="presetDraft" }; assertNotNull("home preset draft owner missing",method)
        val before=AppStateStore.get(app).exportState()
        val draft=method!!.invoke(vm,"省流") as com.cfst.app.settings.SettingsDraft
        assertEquals(before,AppStateStore.get(app).exportState())
        assertEquals("10",draft.candidates); assertEquals("1",draft.downloads); assertEquals("3",draft.budgetMb)
        assertTrue(vm.saveDraft(draft)); assertEquals(3000000L,vm.budgetBytes.value)
        vm.loadIpRanges("127.0.0.1"); vm.startTest()
        val engine=vm.javaClass.getDeclaredField("engine").apply { isAccessible=true }.get(vm) as com.cfst.app.speedtest.SpeedTestEngine
        assertEquals(10,engine.runOwner!!.config!!.testCount); assertEquals(3000000L,engine.runOwner!!.config!!.byteBudget)
        vm.stopTest()
    }
}
