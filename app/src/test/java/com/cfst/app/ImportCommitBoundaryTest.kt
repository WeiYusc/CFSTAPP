package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.documents.DocumentOperationOwner
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ImportCommitBoundaryTest {
    @Test fun failedApplyKeepsPreviewAvailableAndReturnsFalse() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        vm.previewPaste("192.0.2.1")
        val original=vm.currentListContent.value
        for(name in listOf("app-state.json","app-state.json.bak")) File(app.filesDir,name).apply { delete(); mkdirs(); resolve("block").writeText("block") }
        assertEquals(false,vm.javaClass.getMethod("confirmImport").invoke(vm))
        assertNotNull(vm.importPreview.value); assertEquals(original,vm.currentListContent.value)
    }
    @Test fun familyGenerationSurvivesRecreation() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        vm.loadIpRanges("192.0.2.1\n2001:db8::1"); vm.updateUseIpv6(true)
        assertEquals(vm.sourceGeneration,SpeedTestViewModel(app).sourceGeneration)
    }
    @Test fun cancelledRetryCleansOriginalFrozenSource() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val owner=DocumentOperationOwner.get(app)
        val id=owner.prepareExport(0,"frozen")!!
        val field=owner.javaClass.getDeclaredField("_state").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST") val state=field.get(owner) as kotlinx.coroutines.flow.MutableStateFlow<DocumentOperationOwner.Operation?>
        state.value=state.value!!.copy(stage=DocumentOperationOwner.Stage.FAILED)
        val retry=owner.retryExport(id)!!; owner.cancelPicker(retry)
        assertFalse(File(app.filesDir,"document-operations/$id.source").exists())
    }
}
