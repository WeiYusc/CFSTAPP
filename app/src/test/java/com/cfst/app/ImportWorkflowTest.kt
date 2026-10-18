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
class ImportWorkflowTest {
    private fun call(vm:SpeedTestViewModel,name:String,vararg args:Any):Any? {
        val method=vm.javaClass.methods.firstOrNull { it.name==name && it.parameterCount==args.size }
        assertNotNull("real owner missing $name",method)
        return method!!.invoke(vm,*args)
    }
    @Test fun pasteRequiresPreviewThenExplicitConfirm() {
        val vm=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.loadIpRanges("192.0.2.1")
        call(vm,"previewPaste","192.0.2.2\n2001:db8::1")
        assertEquals(listOf("192.0.2.1"),vm.availableIpSegments.value)
        call(vm,"confirmImport")
        assertEquals(listOf("192.0.2.2","2001:db8::1"),vm.availableIpSegments.value)
    }
    @Test fun stalePreviewCannotReplaceNewSource() {
        val vm=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.loadIpRanges("192.0.2.1"); call(vm,"previewPaste","192.0.2.2")
        vm.loadIpRanges("192.0.2.3"); call(vm,"confirmImport")
        assertEquals(listOf("192.0.2.3"),vm.availableIpSegments.value)
    }
    @Test fun previewRejectsBadUtf8AndDoesNotApply() {
        val vm=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.loadIpRanges("192.0.2.1")
        call(vm,"previewBytes",byteArrayOf(0xc3.toByte(),0x28))
        assertEquals(listOf("192.0.2.1"),vm.availableIpSegments.value)
        assertTrue(vm.statusMessage.value.contains("UTF"))
    }
    @Test fun draftCancelDoesNotWriteAndDirtyFalsePreservesBudget() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("local_settings",0).edit().putInt("budgetMiB",300).commit()
        val vm=SpeedTestViewModel(app)
        val draft=call(vm,"beginDraft")!!
        call(vm,"cancelDraft")
        assertEquals(300,vm.budgetMiB.value)
        call(vm,"beginDraft")
        val save=vm.javaClass.methods.firstOrNull { it.name=="saveDraft" }
        assertNotNull(save); assertEquals(true,save!!.invoke(vm,draft))
        val getter=vm.javaClass.getMethod("getBudgetBytes").invoke(vm) as kotlinx.coroutines.flow.StateFlow<*>
        assertEquals(314572800L,getter.value)
    }
}
