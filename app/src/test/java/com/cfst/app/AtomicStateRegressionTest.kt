package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.settings.AppStateStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AtomicStateRegressionTest {
    @Test fun failedSaveCannotChangeRealInMemorySettings() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val vm=SpeedTestViewModel(app)
        val original=vm.testCount.value
        val file=java.io.File(app.filesDir,"app-state.json")
        file.delete(); file.mkdirs(); java.io.File(file,"block").writeText("x")
        val backup=java.io.File(app.filesDir,"app-state.json.bak")
        backup.mkdirs(); java.io.File(backup,"block").writeText("x")
        try { vm.updateTestCount(99) } catch(_:Exception) { }
        assertEquals("memory only after durable commit",original,vm.testCount.value)
    }
    @Test fun restoreIsTypedAtomicAndIncludesListsAndSettings() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val store=AppStateStore(app)
        store.edit().putInt("testCount",17).putString("ipv4_file_content","192.0.2.1").commit()
        val export=store.javaClass.methods.firstOrNull { it.name=="exportState" }
        assertNotNull("full-state schema export missing",export)
        val payload=export!!.invoke(store) as String
        store.edit().putInt("testCount",30).commit()
        val restore=store.javaClass.methods.firstOrNull { it.name=="restoreState" }
        assertNotNull(restore); assertEquals(true,restore!!.invoke(store,payload))
        assertEquals(17,store.getInt("testCount",0))
        assertEquals("192.0.2.1",store.getString("ipv4_file_content",null))
        val invalid=payload.replace("\"value\":17","\"value\":10001")
        try { restore.invoke(store,invalid) } catch(_:java.lang.reflect.InvocationTargetException) { }
        assertEquals(17,store.getInt("testCount",0))
    }
    @Test fun draftValidationRejectsStartUntilCancel() {
        val vm=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        val draft=vm.beginDraft().copy(candidates="",budgetMb="999999999999999",budgetDirty=true)
        assertFalse(vm.saveDraft(draft)); vm.startTest()
        assertFalse(vm.isTesting.value); assertTrue(vm.statusMessage.value.contains("草稿"))
        vm.cancelDraft(); assertFalse(vm.draftPending.value)
    }
}
