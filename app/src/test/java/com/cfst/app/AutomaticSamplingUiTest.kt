package com.cfst.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.settings.AppStateStore
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class AutomaticSamplingUiTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private fun finish(vm:SpeedTestViewModel) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(vm.isTesting.value && System.nanoTime()<end) { Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5) }
        assertFalse(vm.statusMessage.value,vm.isTesting.value)
    }
    @Test fun newCandidateDoesNotReuseAlreadyDeliveredVersion() {
        val build=File("build.gradle").readText()
        assertTrue(build.contains("versionCode 114"));assertTrue(build.contains("versionName \"1.2.2\""))
        assertTrue(File("src/main/java/com/cfst/app/ui/settings/AdvancedSettingsScreen.kt").readText().contains("CFST Local 1.2.2"))
    }
    @Test fun allUserScreensHideInternalSeedAndModeControls() {
        // Static wiring guard only; does not claim Compose touch/visibility acceptance.
        val files=File("src/main/java/com/cfst/app/ui").walkTopDown().filter { it.isFile && it.extension=="kt" }.toList()
        assertTrue(files.isNotEmpty())
        files.forEach { assertFalse("internal sampling parameter exposed in ${it.name}",it.readText().contains("seed",ignoreCase=true)) }
    }
    @Test fun migrationNoticeNeverOffersFixedSamplingMode() {
        for(name in listOf("test/TestScreen.kt","settings/AdvancedSettingsScreen.kt")) {
            val s=File("src/main/java/com/cfst/app/ui/$name").readText()
            assertFalse(s.contains("固定复现"));assertFalse(s.contains("可切回"));assertFalse(s.contains("高级设置切回"))
            assertTrue(s.contains("每轮自动采样"))
        }
    }
    @Test fun oldFixedSettingAndRestoredBackupCannotDisableFreshSeedsOrRewriteHistory() {
        val store=AppStateStore.get(app)
        assertTrue(store.edit().putBoolean("randomizeSampleSeed",false).putLong("sampleSeed",Long.MIN_VALUE).commit())
        var calls=0
        fun vm()=SpeedTestViewModel(app) { (++calls).toLong()*65536 }
        var vm=vm()
        ServerSocket(0,16,InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout=3000
            assertTrue(vm.saveDraft(vm.beginDraft().copy(port=server.localPort.toString(),enableDownload=false,repetitions="1",candidates="1")))
            vm.loadIpRanges("127.0.0.1")
            vm.savedTestConfig();vm.beginDraft();vm.cancelDraft();assertEquals(0,calls)
            vm.startTest();finish(vm);server.accept().close()
            assertEquals(1,calls);assertEquals(65536L,vm.runRecords.value.single().config!!.sampleSeed)
            val original=Gson().toJson(vm.runRecords.value.single());val parent=vm.runRecords.value.single().runId
            val backup=vm.exportAppState();vm=vm();assertTrue(vm.restoreAppState(backup))
            assertEquals(original,Gson().toJson(vm.runRecords.value.single()))
            vm.startTest();finish(vm);server.accept().close()
            assertEquals(2,calls);assertEquals(131072L,vm.runRecords.value.first().config!!.sampleSeed)
            assertTrue(vm.confirmSingleIpRetest(parent,"127.0.0.1"));finish(vm);server.accept().close()
            assertEquals(3,calls);assertEquals(196608L,vm.runRecords.value.first().config!!.sampleSeed)
            assertEquals("127.0.0.1",vm.runRecords.value.first().results.single().ipAddress)
            assertEquals(original,Gson().toJson(vm.runRecords.value.last()))
            val final=vm.exportAppState();assertTrue(vm.restoreAppState(final));assertEquals(final,vm.exportAppState())
            assertEquals(Long.MIN_VALUE,store.getLong("sampleSeed",0L));assertFalse(store.getBoolean("randomizeSampleSeed",true))
        }
    }
}
