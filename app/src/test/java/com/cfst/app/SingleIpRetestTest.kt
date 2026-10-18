package com.cfst.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.documents.DocumentOperationOwner
import com.cfst.app.model.*
import com.cfst.app.settings.*
import com.cfst.app.speedtest.*
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class SingleIpRetestTest {
    private val gson=Gson()
    private fun vm()=SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>()) { 88L }.also { idle() }
    private fun idle()=Shadows.shadowOf(Looper.getMainLooper()).idle()
    private fun confirm(vm:SpeedTestViewModel,parent:String,ip:String):Boolean {
        val method=vm.javaClass.methods.firstOrNull { it.name=="confirmSingleIpRetest" && it.parameterCount==2 }
        assertNotNull("explicit single-IP retest owner API missing",method)
        return method!!.invoke(vm,parent,ip) as Boolean
    }
    private fun parent(vm:SpeedTestViewModel,ips:List<String> = listOf("127.0.0.1")):RunRecord {
        val id=java.util.UUID.randomUUID().toString(); val ledger=RunLedger(id,ips.size,ips.toSet())
        ips.forEach { ledger.accept(MeasurementEvent(EventIdentity(id,it,"probe:$it",MeasurementStage.PROBE,1),probe=ProbeMeasurement(1,1,1.0))) }
        val r=ledger.record(SpeedTestEngine.TestConfig(testCount=ips.size,downloadTest=false),ips.map { SpeedTestResult(it,1,1,0.0,1.0,0.0,"N/A",probe=ProbeMeasurement(1,1,1.0)) },1,null)
        val persist=vm.javaClass.declaredMethods.first { it.name.startsWith("persistCompletedRun") }.apply { isAccessible=true }
        assertEquals(true,persist.invoke(vm,r)); return r
    }
    private fun engine(vm:SpeedTestViewModel)=vm.javaClass.getDeclaredField("engine").apply { isAccessible=true }.get(vm) as SpeedTestEngine
    private fun await(vm:SpeedTestViewModel,condition:()->Boolean={ !vm.isTesting.value }) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(!condition() && System.nanoTime()<end) { idle(); Thread.sleep(10) }
        idle(); assertTrue("real owner did not finish: ${vm.statusMessage.value}",condition())
    }
    private fun saved(vm:SpeedTestViewModel,port:Int,budget:Long=8192,download:Boolean=true) {
        assertTrue(vm.saveDraft(vm.beginDraft().copy(candidates="7",repetitions="1",concurrency="2",downloads="3",seconds="2",port=port.toString(),url="https://localhost/payload",originalBudget=budget,enableDownload=download,latency="9999",speedMb="0",runTimeSeconds="45",sampleSeed="77",randomizeSampleSeed=false)))
    }
    private fun parentId(r:RunRecord):String? {
        val f=r.javaClass.declaredFields.firstOrNull { it.name=="parentRunId" }
        assertNotNull("nullable durable parentRunId missing",f); f!!.isAccessible=true; return f.get(r) as String?
    }
    private fun withTls(block:(MockWebServer)->Unit) {
        val cert=okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls=okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(cert).build()
        val app=ApplicationProvider.getApplicationContext<Application>()
        val trust=java.io.File(app.cacheDir,"n4-trust.jks")
        val store=java.security.KeyStore.getInstance("JKS").apply { load(null,null); setCertificateEntry("fixture",cert.certificate) }
        trust.outputStream().use { store.store(it,"fixture".toCharArray()) }
        val names=listOf("javax.net.ssl.trustStore","javax.net.ssl.trustStorePassword","javax.net.ssl.trustStoreType")
        val previous=names.associateWith { System.getProperty(it) }
        System.setProperty(names[0],trust.absolutePath);System.setProperty(names[1],"fixture");System.setProperty(names[2],"JKS")
        try { MockWebServer().use { it.useHttps(tls.sslSocketFactory(),false);it.start();block(it) } }
        finally { previous.forEach { (k,v)->if(v==null) System.clearProperty(k) else System.setProperty(k,v) };trust.delete() }
    }
    @Test fun explicitRetestUsesCurrentSavedConfigIndependentBudgetAndDurableParentWithoutChangingOriginal()=withTls { server ->
        val vm=vm(); saved(vm,server.port);vm.loadIpRanges("127.0.0.1")
        server.enqueue(MockResponse().setBody("x".repeat(16384)))
        vm.startTest();await(vm)
        val original=vm.runRecords.value.single(); val before=gson.toJson(original)
        assertEquals(8192L,original.summary.bodyBytesConsumed)
        assertEquals("127.0.0.1",original.results.single().observedPeer)
        assertTrue(original.results.single().download!!.safetyVerified)
        assertNull(parentId(original))
        saved(vm,server.port,12345)
        vm.loadIpRanges("2001:db8::1") // wrong live family/list must not control literal retest
        val payload=vm.exportAppState(); assertEquals(1,server.requestCount) // no automatic retest
        server.enqueue(MockResponse().setBody("y".repeat(20000)))
        assertTrue(confirm(vm,original.runId,"127.0.0.1"))
        assertTrue(vm.isTesting.value);assertEquals(0,vm.progress.value.total)
        val owner=engine(vm).runOwner!!; assertNotEquals(original.runId,owner.id)
        assertEquals(1,owner.config!!.testCount);assertEquals(1,owner.config!!.downloadTestCount)
        assertEquals(12345L,owner.config!!.byteBudget);assertEquals(2000L,owner.config!!.downloadDuration)
        assertEquals(45,owner.config!!.runTimeSeconds);assertEquals(88L,owner.config!!.sampleSeed)
        assertEquals(9999.0,owner.config!!.latencyLimit,0.0);assertFalse(owner.config!!.allowHttp)
        val savedFields=gson.toJsonTree(original.config).asJsonObject
        val childFields=gson.toJsonTree(owner.config).asJsonObject
        savedFields.addProperty("byteBudget",12345L);savedFields.addProperty("testCount",1);savedFields.addProperty("downloadTestCount",1)
        assertEquals("only counts and deliberately edited saved budget change",savedFields,childFields)
        await(vm)
        assertEquals(2,server.requestCount)
        val child=vm.runRecords.value.first();assertEquals(original.runId,parentId(child))
        assertEquals(12345L,child.summary.bodyBytesConsumed);assertEquals(1,child.summary.generatedUnique)
        assertEquals(1,child.summary.downloadsStarted);assertEquals("127.0.0.1",child.results.single().ipAddress)
        assertEquals(SampleQuality.SHORT,child.results.single().download!!.sampleQuality)
        assertFalse(child.results.single().download!!.recommended)
        assertTrue(child.events.all { it.identity.runId==child.runId && it.identity.candidateId=="127.0.0.1" })
        assertTrue(original.events.map { it.identity }.toSet().intersect(child.events.map { it.identity }.toSet()).isEmpty())
        assertEquals(before,gson.toJson(vm.runRecords.value.last()))
        val export=vm.exportAppState();assertNotEquals(payload,export)
        println("N4_REAL_TLS_PARENT="+gson.toJson(original))
        println("N4_REAL_TLS_CHILD="+gson.toJson(child))
        println("N4_REAL_TLS_REQUESTS="+server.requestCount)
        assertEquals(export,vm().exportAppState())
        assertTrue(vm.restoreAppState(export));assertEquals(export,vm.exportAppState())
        assertEquals(original.runId,parentId(vm().runRecords.value.first()))
        assertTrue(vm.deleteHistoryIdentity(original.runId));assertEquals(child.runId,vm.runRecords.value.single().runId)
        assertTrue(vm.restoreAppState(vm.exportAppState()));assertEquals(original.runId,parentId(vm().runRecords.value.single()))
        assertFalse("deleted parent cannot start another retest",confirm(vm,original.runId,"127.0.0.1"))
    }
    @Test fun runningDraftPastePreviewAndDocumentSlotRejectSecondRunWithReason() {
        val vm=vm();val p=parent(vm)
        vm.updateDraft(vm.beginDraft().copy(candidates="9"));assertFalse(confirm(vm,p.runId,"127.0.0.1"));assertTrue(vm.statusMessage.value.contains("草稿"));vm.cancelDraft()
        vm.editPaste("192.0.2.3");assertFalse(confirm(vm,p.runId,"127.0.0.1"));assertTrue(vm.statusMessage.value.contains("粘贴"));vm.cancelImport()
        vm.previewPaste("192.0.2.3");assertFalse(confirm(vm,p.runId,"127.0.0.1"));assertTrue(vm.statusMessage.value.contains("预览"));vm.cancelImport()
        val owner=DocumentOperationOwner.get(ApplicationProvider.getApplicationContext());val id=owner.picker(vm.sourceGeneration)!!
        assertFalse(confirm(vm,p.runId,"127.0.0.1"));assertTrue(vm.statusMessage.value.contains("文档"));owner.cancelPicker(id)
        java.net.ServerSocket(0).use { socket ->
            saved(vm,socket.localPort,download=false)
            assertTrue(confirm(vm,p.runId,"127.0.0.1"));val run=engine(vm).runOwner!!.id
            assertFalse(confirm(vm,p.runId,"127.0.0.1"));assertTrue(vm.statusMessage.value.contains("运行"));assertEquals(run,engine(vm).runOwner!!.id)
            vm.stopTest();await(vm)
        }
    }
    @Test fun nonexistentUnownedAndMaliciousTargetsFailClosedBeforeReservation() {
        val vm=vm();val p=parent(vm);val before=vm.exportAppState()
        for((id,ip) in listOf("legacy:0" to "127.0.0.1",java.util.UUID.randomUUID().toString() to "127.0.0.1",p.runId to "127.0.0.2",p.runId to "127.0.0.1/32",p.runId to "localhost",p.runId to " 127.0.0.1",p.runId to "127.0.0.1\n127.0.0.2")) {
            assertFalse("$id/$ip",confirm(vm,id,ip));assertFalse(vm.isTesting.value);assertNull(engine(vm).runOwner)
        }
        assertEquals(before,vm.exportAppState())
    }
    @Test fun stopRetainsRealPartialBytesAsNonRecommendationAndOldParentUnchanged()=withTls { server ->
        val vm=vm();val p=parent(vm);val old=gson.toJson(p);saved(vm,server.port,100000)
        server.enqueue(MockResponse().setBody("x".repeat(200000)).throttleBody(4096,1,TimeUnit.SECONDS))
        assertTrue(confirm(vm,p.runId,"127.0.0.1"))
        await(vm) { (engine(vm).currentRecord?.summary?.bodyBytesConsumed ?: 0)>0 }
        vm.stopTest();await(vm)
        val child=vm.runRecords.value.first();assertTrue(child.summary.bodyBytesConsumed>0)
        assertEquals(DownloadTermination.USER_STOP,child.results.single().download!!.termination)
        assertFalse(child.results.single().download!!.recommended);assertEquals(old,gson.toJson(vm.runRecords.value.last()))
        assertEquals(vm.exportAppState(),vm().exportAppState())
    }
    @Test fun realRetestNetworkLossClosesCallAndPersistsPartialLedger()=withTls { server ->
        val vm=vm();val p=parent(vm);saved(vm,server.port,100000)
        server.enqueue(MockResponse().setBody("z".repeat(200000)).throttleBody(4096,1,TimeUnit.SECONDS))
        assertTrue(confirm(vm,p.runId,"127.0.0.1"))
        await(vm) { (engine(vm).currentRecord?.summary?.bodyBytesConsumed ?: 0)>0 }
        val callback=vm.javaClass.getDeclaredField("networkCallback").apply { isAccessible=true }.get(vm) as android.net.ConnectivityManager.NetworkCallback
        val manager=ApplicationProvider.getApplicationContext<Application>().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        callback.onLost(manager.activeNetwork!!)
        await(vm)
        val child=vm.runRecords.value.first();assertEquals(p.runId,parentId(child))
        assertTrue(child.summary.bodyBytesConsumed>0);assertEquals(DownloadTermination.NETWORK_LOST,child.results.single().download!!.termination)
        assertFalse(child.results.single().download!!.recommended);assertEquals(RunOwner.State.RELEASED,engine(vm).runOwner!!.state)
        assertEquals(vm.exportAppState(),vm().exportAppState())
    }
    @Test fun strictParentShapeSelfUuidSingleCandidateAndRetainedMembershipAreValidatedOnRestore() {
        val vm=vm();val p=parent(vm);val before=vm.exportAppState()
        val list=JsonParser.parseString(gson.toJson(listOf(p))).asJsonArray
        list[0].asJsonObject.addProperty("parentRunId",p.runId)
        assertTrue("self parent must fail closed",runCatching { SnapshotRecords.runs(list.toString()) }.isFailure)
        list[0].asJsonObject.addProperty("parentRunId","1-1-1-1-1")
        assertTrue(runCatching { SnapshotRecords.runs(list.toString()) }.isFailure)
        list[0].asJsonObject.addProperty("parentRunId",7)
        assertTrue(runCatching { SnapshotRecords.runs(list.toString()) }.isFailure)
        val root=JsonParser.parseString(before).asJsonObject
        root["fields"].asJsonObject["run_records"].asJsonObject.addProperty("value",list.toString())
        assertFalse(vm.restoreAppState(root.toString()));assertEquals(before,vm.exportAppState())
        val multi=parent(vm,listOf("127.0.0.1","127.0.0.2"));val array=JsonParser.parseString(gson.toJson(listOf(multi))).asJsonArray
        array[0].asJsonObject.addProperty("parentRunId",p.runId)
        assertTrue(runCatching { SnapshotRecords.runs(array.toString()) }.isFailure)
        val foreign=parent(vm,listOf("127.0.0.9"));val pair=JsonParser.parseString(gson.toJson(listOf(foreign,p))).asJsonArray
        pair[0].asJsonObject.addProperty("parentRunId",p.runId)
        pair[0].asJsonObject["config"].asJsonObject.addProperty("downloadTestCount",1)
        assertTrue(runCatching { SnapshotRecords.runs(pair.toString()) }.isFailure)
        pair[0].asJsonObject.addProperty("parentRunId",java.util.UUID.randomUUID().toString())
        assertEquals("absent legitimately removed parent is allowed",2,SnapshotRecords.runs(pair.toString()).size)
    }
    @Test fun legacyMissingAndExplicitNullParentNormalizeWithoutGsonUnsafeDefaultAssumption() {
        val vm=vm();val p=parent(vm);val node=JsonParser.parseString(gson.toJson(listOf(p))).asJsonArray
        node[0].asJsonObject.remove("parentRunId")
        assertNull(parentId(SnapshotRecords.runs(node.toString()).single()))
        node[0].asJsonObject.add("parentRunId",com.google.gson.JsonNull.INSTANCE)
        assertNull(parentId(SnapshotRecords.runs(node.toString()).single()))
    }
    @Test fun retestParentPresentationSubscribesToActualDurableHistoryFlow() {
        val source=java.io.File("src/main/java/com/cfst/app/ui/test/TestScreen.kt").readText()
        assertTrue("retest parent provenance must observe durable history changes",source.contains("vm.runRecords.collectAsState"))
        assertFalse("unobserved history read inside composition",source.contains("vm.runRecords.value"))
    }
    @Test fun detailsExposeOptionalExplicitConfirmationAndSavedSummaryOnBothActualScreens() {
        val root=java.io.File("src/main/java/com/cfst/app/ui")
        val cards=root.resolve("components/RunCards.kt").readText()
        assertTrue("optional retest callback missing",cards.contains("onRetest"))
        assertTrue(cards.contains("确认单 IP 复测"));assertTrue(cards.contains("独立新预算"));assertTrue(cards.contains("原结果保留"))
        assertTrue(cards.contains("runTimeSeconds"));assertTrue(cards.contains("byteBudget"));assertTrue(cards.contains("downloadDuration"))
        for(file in listOf("test/TestScreen.kt","history/HistoryScreen.kt")) {
            val text=root.resolve(file).readText();assertTrue(file,text.contains("confirmSingleIpRetest"));assertTrue(file,text.contains("savedTestConfig"))
        }
    }
    @Test fun currentResultParentLinkObservesRunRecordsInsteadOfUnsubscribedCompositionValue() {
        val text=java.io.File("src/main/java/com/cfst/app/ui/test/TestScreen.kt").readText()
        assertTrue("parent links must observe durable run changes",text.contains("vm.runRecords.collectAsState()"))
        assertFalse(text.contains("vm.runRecords.value"))
    }
}
