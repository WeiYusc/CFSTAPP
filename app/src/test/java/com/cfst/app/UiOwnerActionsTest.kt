package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.documents.DocumentOperationOwner
import com.cfst.app.model.SpeedTestResult
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows
import android.os.Looper

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class UiOwnerActionsTest {
    private fun vm()=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>()).also { Shadows.shadowOf(Looper.getMainLooper()).idle() }
    private fun call(vm:SpeedTestViewModel,name:String,vararg args:Any):Any? {
        val method=vm.javaClass.methods.firstOrNull { it.name==name && it.parameterCount==args.size }
        assertNotNull("real VM action missing: $name",method); return method!!.invoke(vm,*args)
    }
    @Suppress("UNCHECKED_CAST") private fun ids(vm:SpeedTestViewModel)=call(vm,"historyIdentities") as List<String>
    @Suppress("UNCHECKED_CAST") private fun save(vm:SpeedTestViewModel,ip:String) {
        (vm.javaClass.getDeclaredField("_results").apply { isAccessible=true }.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<SpeedTestResult>>).value=listOf(SpeedTestResult(ip,1,1,0.0,1.0,0.0,"legacy"))
        vm.saveCurrentResultsToHistory()
    }
    @Test fun deletingCapturedIdentityAfterNewRunCannotDeleteShiftedRow() {
        val vm=vm(); save(vm,"192.0.2.1"); val id=ids(vm).single(); save(vm,"192.0.2.2")
        assertEquals(true,call(vm,"deleteHistoryIdentity",id))
        assertEquals("192.0.2.2",vm.historyRecords.value.single().single().ipAddress)
        assertEquals(false,call(vm,"deleteHistoryIdentity",id)); assertEquals(1,vm.historyRecords.value.size)
        val reopened=vm(); assertEquals(ids(vm),ids(reopened)); assertEquals(vm.exportAppState(),reopened.exportAppState())
    }
    @Test fun exportingCapturedIdentityUsesSameRecordAfterIndexShift() {
        val vm=vm(); save(vm,"192.0.2.1"); val id=ids(vm).single(); save(vm,"192.0.2.2")
        val csv=call(vm,"exportHistoryIdentity",id) as String
        assertTrue(csv.contains("192.0.2.1")); assertFalse(csv.contains("192.0.2.2"))
    }
    @Test fun startBlockExplainsDraftPastePreviewAndRealDocumentSlot() {
        val vm=vm(); assertNull(call(vm,"startBlockReason"))
        vm.updateDraft(vm.beginDraft().copy(candidates="71")); assertTrue(call(vm,"startBlockReason").toString().contains("配置")); vm.cancelDraft()
        vm.editPaste("192.0.2.99"); assertTrue(call(vm,"startBlockReason").toString().contains("粘贴")); vm.cancelImport()
        vm.previewPaste("192.0.2.99"); assertTrue(call(vm,"startBlockReason").toString().contains("预览")); vm.cancelImport()
        val owner=DocumentOperationOwner.get(ApplicationProvider.getApplicationContext()); val id=owner.picker(vm.sourceGeneration)!!
        assertTrue(call(vm,"startBlockReason").toString().contains("文档")); vm.startTest(); assertFalse(vm.isTesting.value)
        owner.cancelPicker(id); assertNull(call(vm,"startBlockReason"))
    }
    @Test fun wrongFamilyCandidateListHasActionableBlockBeforeOwnerReservation() {
        val vm=vm(); vm.loadIpRanges("2001:db8::1")
        assertTrue(call(vm,"startBlockReason").toString().contains("family")); vm.startTest(); assertFalse(vm.isTesting.value)
    }
}
