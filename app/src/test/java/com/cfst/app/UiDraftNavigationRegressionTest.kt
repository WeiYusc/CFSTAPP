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
class UiDraftNavigationRegressionTest {
    private fun vm()=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
    @Test fun openingUntouchedSettingsDoesNotBlockStartOrWriteAuthority() {
        val vm=vm(); val store=AppStateStore.get(ApplicationProvider.getApplicationContext())
        val before=store.exportState(); val draft=vm.beginDraft()
        assertFalse("opening settings is not an edit",vm.draftPending.value)
        vm.updateDraft(draft)
        assertFalse("unchanged recomposition is not an edit",vm.draftPending.value)
        assertEquals(before,store.exportState())
    }
    @Test fun revertingOrdinaryEditClearsDirtyWithoutRoundingOriginalBytes() {
        val vm=vm(); val original=vm.beginDraft()
        vm.updateDraft(original.copy(candidates="71")); assertTrue(vm.draftPending.value)
        vm.updateDraft(original); assertFalse(vm.draftPending.value)
        assertEquals(original,vm.beginDraft())
    }
    @Test fun invalidSaveRetainsActualInputAndBlocksRealStart() {
        val vm=vm(); val invalid=vm.beginDraft().copy(candidates="")
        assertFalse(vm.saveDraft(invalid)); assertEquals(invalid,vm.beginDraft())
        assertTrue(vm.draftPending.value); vm.startTest(); assertFalse(vm.isTesting.value)
        vm.cancelDraft(); assertFalse(vm.draftPending.value)
    }
    @Test fun restoreClearsStalePasteOwnerSoRestoredStateCanBeUsed() {
        val vm=vm(); val payload=vm.exportAppState()
        vm.editPaste("192.0.2.99"); assertTrue(vm.pastePending.value)
        assertTrue(vm.restoreAppState(payload))
        assertFalse("restored state may not remain blocked by replaced paste",vm.pastePending.value)
        assertNull(vm.pasteText.value);assertNull(vm.importPreview.value)
    }
    @Test fun failedDurableSaveRetainsDraftAcrossPageResume() {
        val vm=vm(); val draft=vm.beginDraft().copy(candidates="71"); vm.updateDraft(draft)
        val app=ApplicationProvider.getApplicationContext<Application>()
        for(name in listOf("app-state.json","app-state.json.bak")) File(app.filesDir,name).apply { delete();mkdirs();resolve("block").writeText("x") }
        assertFalse(vm.saveDraft(draft)); assertEquals(draft,vm.beginDraft()); assertTrue(vm.draftPending.value)
        assertNotEquals(71,vm.testCount.value)
    }
}
