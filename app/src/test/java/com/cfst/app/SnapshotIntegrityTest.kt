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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SnapshotIntegrityTest {
    @Test fun oversizedOrInvalidHistoryRestoreCannotPartiallyCommit() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        val payload=vm.exportAppState(); val store=AppStateStore(app)
        val root=com.google.gson.JsonParser.parseString(payload).asJsonObject
        val fields=root["fields"].asJsonObject
        fields["testCount"].asJsonObject.addProperty("value",17)
        fields["history_records"].asJsonObject.addProperty("value","not json")
        assertFalse(vm.restoreAppState(root.toString()))
        assertEquals(30,AppStateStore(app).getInt("testCount",0))
    }
    @Test fun cacheInvalidRawIsBackedUpAndBuiltinRecoveryNotRepeated() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("ip_cache",0).edit().putString("ipv4_file_content","bad!").commit()
        val first=SpeedTestViewModel(app)
        assertFalse(first.availableIpSegments.value.isEmpty())
        val store=AppStateStore(app)
        assertNotEquals("bad!",store.getString("ipv4_file_content",null))
        assertTrue(store.all.values.any { it is String && it.contains("bad!") })
    }
    @Test fun independentRealOwnersDoNotOverwriteOtherCommittedFields() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val first=AppStateStore(app); val second=AppStateStore(app)
        first.edit().putInt("testCount",17).commit()
        second.edit().putInt("pingCount",7).commit()
        val restored=AppStateStore(app)
        assertEquals(17,restored.getInt("testCount",0)); assertEquals(7,restored.getInt("pingCount",0))
    }
}
