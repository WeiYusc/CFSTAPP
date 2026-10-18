package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.documents.DocumentOperationOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SourceGenerationTest {
    @Test fun previewCannotBeRebasedAfterSourceChanges() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        vm.loadIpRanges("192.0.2.1")
        val get=vm.javaClass.methods.firstOrNull { it.name=="getSourceGeneration" }
        assertNotNull(get)
        val old=get!!.invoke(vm) as Long
        vm.loadIpRanges("192.0.2.2")
        val preview=vm.javaClass.methods.firstOrNull { it.name=="previewDocument" }
        assertNotNull(preview); preview!!.invoke(vm,"192.0.2.3".toByteArray(),old)
        assertNull(vm.importPreview.value)
        assertEquals(listOf("192.0.2.2"),vm.availableIpSegments.value)
    }
    @Test fun retainedDraftDoesNotLoseEditsOnDestinationReentry() {
        val vm=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        val edited=vm.beginDraft().copy(candidates="71",budgetMb="9.31",budgetDirty=true)
        val update=vm.javaClass.methods.firstOrNull { it.name=="updateDraft" }
        assertNotNull(update); update!!.invoke(vm,edited)
        assertEquals(edited,vm.beginDraft())
        vm.cancelDraft(); assertNotEquals(edited,vm.beginDraft())
    }
    @Test fun pickerCancelCleansActualRetrySnapshot() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val id=owner.prepareExport(0,"source")!!
        val dir=java.io.File(app.filesDir,"document-operations")
        assertTrue(java.io.File(dir,"$id.source").exists())
        owner.cancelPicker(id)
        assertFalse(java.io.File(dir,"$id.source").exists())
    }
}
