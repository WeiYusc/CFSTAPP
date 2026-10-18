package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.model.*
import com.cfst.app.settings.AppStateStore
import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import com.cfst.app.viewmodel.SpeedTestViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class IntegratedBoundaryTest {
    @Test fun latencyTieUsesIpNotUnmeasuredOrLegacySpeed() {
        fun row(ip:String,speed:Double)=SpeedTestResult(ip,1,1,0.0,10.0,speed,"N/A")
        assertEquals("192.0.2.1",SpeedTestResult.rank(listOf(row("192.0.2.2",100.0),row("192.0.2.1",1.0)),true).first().ipAddress)
    }
    @Test fun invalidReservedConfigurationReleasesOwner() = runBlocking {
        val engine=SpeedTestEngine(); val token=engine.prepareRun()!!
        try { engine.startTest(emptyList(),SpeedTestEngine.TestConfig(testCount=0),{},{},{},reserved=token); fail() } catch(_:IllegalArgumentException) {}
        assertFalse(engine.isRunning())
    }
    @Test fun reserveFreezesConfigAndRejectsDifferentExecutionArguments() = runBlocking {
        val engine=SpeedTestEngine(); val config=SpeedTestEngine.TestConfig(downloadTest=false)
        val list=IpRangeParser.parseIpFile("127.0.0.1"); val token=engine.reserve(config,list)!!
        var network=false
        try { engine.startTest(list,config.copy(testCount=1),{},{},{},{network=true;true},token); fail("different config accepted") } catch(_:IllegalArgumentException) {}
        assertFalse(network); assertFalse(engine.isRunning())
    }
    @Test fun stateRestoreRejectsWrongTypedListsAndSelectionAndNonfiniteAuthoritativeSpeed() {
        val store=AppStateStore(ApplicationProvider.getApplicationContext<Application>())
        val original=store.exportState()
        for((key,type,value) in listOf(Triple("ipv4_file_content","int","7"),Triple("selectedSegments","string","bad"),Triple("minBytesPerSecond","double","1e309"),Triple("run_records","string","[{\"schemaVersion\":2}]"))) {
            val root=com.google.gson.JsonParser.parseString(original).asJsonObject
            val n=com.google.gson.JsonObject(); n.addProperty("type",type)
            if(type=="string") n.addProperty("value",value) else n.add("value",com.google.gson.JsonParser.parseString(value))
            root["fields"].asJsonObject.add(key,n)
            try { store.restoreState(root.toString()); fail("accepted $key") } catch(_:IllegalArgumentException) {}
            assertEquals(original,store.exportState())
        }
    }
    @Test fun restoreBuiltInGenerationSurvivesRecreatedOwnerAndInvalidatesOldPreview() {
        val app=ApplicationProvider.getApplicationContext<Application>(); val vm=SpeedTestViewModel(app)
        vm.loadIpRanges("192.0.2.1"); val old=vm.sourceGeneration; vm.restoreBuiltIn()
        val recreated=SpeedTestViewModel(app)
        assertEquals(vm.sourceGeneration,recreated.sourceGeneration)
        recreated.previewDocument("192.0.2.2".toByteArray(),old); assertNull(recreated.importPreview.value)
    }
}
