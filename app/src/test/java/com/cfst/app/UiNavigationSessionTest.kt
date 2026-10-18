package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.viewmodel.SpeedTestViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class UiNavigationSessionTest {
    private fun session(vm:SpeedTestViewModel):Any {
        val type=try { Class.forName("com.cfst.app.ui.presentation.NavigationSession") } catch(_:ClassNotFoundException) { fail("draft navigation session missing"); throw AssertionError() }
        return type.getConstructor(SpeedTestViewModel::class.java,String::class.java).newInstance(vm,"设置")
    }
    private fun invoke(s:Any,name:String,vararg args:Any)=s.javaClass.methods.first { it.name==name && it.parameterCount==args.size }.invoke(s,*args)
    private fun vm()=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
    @Test fun untouchedSettingsLeavesWithoutConfirmationOrDirtyBlock() {
        val vm=vm(); vm.beginDraft(); val s=session(vm); invoke(s,"navigate","测速")
        assertEquals("测速",invoke(s,"getPage")); assertNull(invoke(s,"getPendingPage")); assertFalse(vm.draftPending.value)
    }
    @Test fun dirtyLeaveStayDiscardAndSaveUseActualVmOwner() {
        val vm=vm(); vm.updateDraft(vm.beginDraft().copy(candidates="71")); val s=session(vm)
        invoke(s,"navigate","列表"); assertEquals("设置",invoke(s,"getPage")); assertEquals("列表",invoke(s,"getPendingPage"))
        invoke(s,"stay"); assertEquals("设置",invoke(s,"getPage")); assertTrue(vm.draftPending.value)
        invoke(s,"navigate","测速"); assertEquals(true,invoke(s,"saveAndLeave")); assertEquals(71,vm.testCount.value); assertEquals("测速",invoke(s,"getPage"))
        vm.updateDraft(vm.beginDraft().copy(candidates="99")); invoke(s,"navigate","历史"); invoke(s,"discardAndLeave")
        assertEquals("历史",invoke(s,"getPage")); assertEquals(71,vm.testCount.value); assertFalse(vm.draftPending.value)
    }
    @Test fun resumeDirtyEditorDoesNotOfferSaveBeforeReachingInput() {
        val vm=vm(); vm.updateDraft(vm.beginDraft().copy(candidates="71"))
        val type=Class.forName("com.cfst.app.ui.presentation.NavigationSession")
        val s=type.getConstructor(SpeedTestViewModel::class.java,String::class.java).newInstance(vm,"测速")
        val method=type.methods.firstOrNull { it.name=="resumeEditor" }
        assertNotNull("resuming the dirty editor must bypass leave confirmation",method)
        method!!.invoke(s)
        assertEquals("设置",invoke(s,"getPage")); assertNull(invoke(s,"getPendingPage")); assertEquals("71",vm.beginDraft().candidates)
    }
    @Test fun invalidSaveDoesNotNavigateAndRetainsInput() {
        val vm=vm(); vm.updateDraft(vm.beginDraft().copy(candidates="")); val s=session(vm); invoke(s,"navigate","测速")
        assertEquals(false,invoke(s,"saveAndLeave")); assertEquals("设置",invoke(s,"getPage")); assertEquals("",vm.beginDraft().candidates); assertTrue(vm.draftPending.value)
    }
}
