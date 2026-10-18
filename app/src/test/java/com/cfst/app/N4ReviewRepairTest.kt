package com.cfst.app

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class) @Config(sdk=[28])
class N4ReviewRepairTest {
    private val gson=Gson()
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private fun idle()=Shadows.shadowOf(Looper.getMainLooper()).idle()
    private fun vm()=SpeedTestViewModel(app).also { idle() }
    private fun await(vm:SpeedTestViewModel,condition:()->Boolean={ !vm.isTesting.value }) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(!condition() && System.nanoTime()<end) { idle();Thread.sleep(10) }
        idle();assertTrue("owner did not finish: ${vm.statusMessage.value}",condition())
    }
    private fun engine(vm:SpeedTestViewModel)=vm.javaClass.getDeclaredField("engine").apply { isAccessible=true }.get(vm) as SpeedTestEngine
    private fun record(ip:String="::1",id:String=UUID.randomUUID().toString(),parent:String?=null):RunRecord {
        val probe=ProbeMeasurement(1,1,1.0)
        val ledger=RunLedger(id,1,setOf(ip),parent)
        ledger.accept(MeasurementEvent(EventIdentity(id,ip,"probe:$ip",MeasurementStage.PROBE,1),probe=probe))
        return ledger.record(SpeedTestEngine.TestConfig(testCount=1,downloadTestCount=1,downloadTest=false),listOf(SpeedTestResult(ip,1,1,0.0,1.0,0.0,"N/A",probe=probe)),1,null)
    }
    private fun payload(vm:SpeedTestViewModel,records:List<RunRecord>):String {
        val root=JsonParser.parseString(vm.exportAppState()).asJsonObject
        val fields=root["fields"].asJsonObject
        for((key,value) in mapOf("run_records" to records,"history_records" to records.map { it.results },"history_run_ids" to records.map { it.runId })) {
            val field=com.google.gson.JsonObject();field.addProperty("type","string");field.addProperty("value",gson.toJson(value));fields.add(key,field)
        }
        return root.toString()
    }
    private fun rejectWithoutMutation(records:List<RunRecord>) {
        val vm=vm();val seed=record("127.0.0.1")
        assertTrue(vm.restoreAppState(payload(vm,listOf(seed))))
        val candidate=payload(vm,records)
        val state=File(app.filesDir,"app-state.json");val bytes=state.readBytes()
        val before=AppStateStore.get(app).exportState();val history=gson.toJson(vm.runRecords.value);val ids=vm.historyIdentities()
        val parserRejected=runCatching { SnapshotRecords.runs(gson.toJson(records)) }.isFailure
        val storeRejected=runCatching { AppStateStore.get(app).restoreState(candidate) }.getOrDefault(false).not()
        val vmRejected=!vm.restoreAppState(candidate)
        assertTrue("store must reject before durable write",storeRejected)
        assertTrue("VM must reject malformed provenance",vmRejected)
        assertArrayEquals("durable bytes must not change",bytes,state.readBytes())
        assertEquals(before,AppStateStore.get(app).exportState());assertEquals(history,gson.toJson(vm.runRecords.value));assertEquals(ids,vm.historyIdentities())
        assertTrue("SnapshotRecords must reject",parserRejected)
    }
    @Test fun mutualParentCycleRestoreRejectsWithoutByteMutation() {
        val a=record();val b=record();rejectWithoutMutation(listOf(a.copy(parentRunId=b.runId),b.copy(parentRunId=a.runId)))
    }
    @Test fun longerRetainedParentCycleRestoreRejectsWithoutByteMutation() {
        val records=List(100) { record() }
        rejectWithoutMutation(records.mapIndexed { i,r -> r.copy(parentRunId=records[(i+1)%records.size].runId) })
    }
    @Test fun uppercaseUuidSelfAliasRestoreRejectsWithoutByteMutation() {
        val id="abcdefab-abcd-abcd-abcd-abcdefabcdef"
        rejectWithoutMutation(listOf(record(id=id.uppercase(),parent=id)))
    }
    @Test fun duplicateUuidAliasesRestoreRejectWithoutByteMutation() {
        val id="abcdefab-abcd-abcd-abcd-abcdefabcdef"
        rejectWithoutMutation(listOf(record(id=id),record(id=id.uppercase())))
    }
    @Test fun abbreviatedUuidRestoreRejectsWithoutByteMutation() {
        rejectWithoutMutation(listOf(record(id="1-1-1-1-1")))
    }
    @Test fun legalGenerationsMissingDeletedAndCountFifoParentsRemainRestorable() {
        val vm=vm();val a=record();val b=record(parent=a.runId);val c=record(parent=b.runId)
        assertTrue(vm.restoreAppState(payload(vm,listOf(c,b,a))))
        assertEquals(listOf(c,b,a),vm().runRecords.value)
        assertTrue(vm.deleteHistoryIdentity(a.runId))
        assertTrue(vm.restoreAppState(vm.exportAppState()));assertEquals(listOf(c,b),vm().runRecords.value)
        val missing=record(parent=UUID.randomUUID().toString())
        assertTrue(vm.restoreAppState(payload(vm,listOf(missing))))
        // Parent is oldest and child newest: adding the 101st row evicts only the parent.
        val retained=listOf(b)+List(98) { record() }+listOf(a)
        assertTrue(vm.restoreAppState(payload(vm,retained)))
        val next=record();assertTrue(vm.persistCompletedRun(next))
        assertEquals(100,vm.runRecords.value.size);assertFalse(vm.runRecords.value.any { it.runId==a.runId })
        assertEquals(a.runId,vm.runRecords.value.first { it.runId==b.runId }.parentRunId)
        assertTrue(vm.restoreAppState(vm.exportAppState()));assertEquals(vm.runRecords.value,vm().runRecords.value)
    }
    @Test fun byteFifoEvictsWholeLargeParentWithoutInvalidatingRetainedChild() {
        val vm=vm()
        fun large():RunRecord {
            val id=UUID.randomUUID().toString();val ips=listOf("::1")+(1 until 10000).map { "10.${(it shr 16) and 255}.${(it shr 8) and 255}.${it and 255}" }
            val probe=ProbeMeasurement(1,1,1.0);val ledger=RunLedger(id,10000,ips.toSet())
            ips.forEach { ledger.accept(MeasurementEvent(EventIdentity(id,it,"probe:$it",MeasurementStage.PROBE,1),probe=probe)) }
            return ledger.record(SpeedTestEngine.TestConfig(testCount=10000,downloadTest=false),ips.map { SpeedTestResult(it,1,1,0.0,1.0,0.0,"N/A",probe=probe) },1,null)
        }
        val parent=large();assertTrue(vm.persistCompletedRun(parent))
        val child=record(parent=parent.runId);assertTrue(vm.persistCompletedRun(child))
        var additions=0
        while(vm.runRecords.value.any { it.runId==parent.runId } && additions<5) { assertTrue(vm.persistCompletedRun(large()));additions++ }
        assertFalse("byte FIFO must evict large oldest parent",vm.runRecords.value.any { it.runId==parent.runId })
        assertTrue(vm.runRecords.value.any { it.runId==child.runId && it.parentRunId==parent.runId })
        assertTrue(vm.runRecords.value.size<100)
        val retainedBytes=gson.toJson(vm.historyRecords.value).toByteArray().size.toLong()+gson.toJson(vm.runRecords.value).toByteArray().size
        assertTrue(retainedBytes<=16*1024*1024)
        assertTrue(vm.runRecords.value.filter { it.runId!=child.runId }.all { it.results.size==10000 && it.events.size==10000 })
        val export=vm.exportAppState();assertTrue(vm.restoreAppState(export));assertEquals(export,vm().exportAppState())
        println("N4_REPAIR_BYTE_FIFO="+gson.toJson(mapOf("retainedBytes" to retainedBytes,"count" to vm.runRecords.value.size,"largeAdditions" to additions,"childRunId" to child.runId,"missingParentRunId" to parent.runId)))
    }
    private fun saved(vm:SpeedTestViewModel,port:Int,download:Boolean) {
        assertTrue(vm.saveDraft(vm.beginDraft().copy(candidates="7",repetitions="1",concurrency="2",downloads="3",seconds="2",port=port.toString(),url="https://localhost/payload",originalBudget=8192,enableDownload=download,latency="9999",speedMb="0",runTimeSeconds="45",sampleSeed="77")))
    }
    private fun assertDurableLiteral(vm:SpeedTestViewModel,parent:RunRecord,literal:String,before:String):RunRecord {
        val child=vm.runRecords.value.first()
        assertNotEquals("child must be durably saved",parent.runId,child.runId)
        assertEquals(parent.runId,child.parentRunId);assertEquals(literal,child.results.single().ipAddress)
        assertTrue(child.events.isNotEmpty());assertTrue(child.events.all { it.identity.candidateId==literal && it.identity.runId==child.runId })
        assertEquals(before,gson.toJson(vm.runRecords.value.last()))
        val export=vm.exportAppState();assertEquals(export,vm().exportAppState())
        assertTrue(vm.restoreAppState(export));assertEquals(export,vm.exportAppState())
        assertEquals(child,vm().runRecords.value.first());assertTrue(vm.exportHistoryIdentity(child.runId).contains(literal))
        println("N4_REPAIR_LITERAL="+gson.toJson(mapOf("literal" to literal,"parent" to parent,"child" to child)))
        return child
    }
    @Test fun compressedIpv6RealTlsRetestKeepsConfirmedLiteralThroughDurableRoundTrip() {
        val cert=okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls=okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust=File(app.cacheDir,"n4-repair-trust.jks")
        val store=java.security.KeyStore.getInstance("JKS").apply { load(null,null);setCertificateEntry("fixture",cert.certificate) }
        trust.outputStream().use { store.store(it,"fixture".toCharArray()) }
        val names=listOf("javax.net.ssl.trustStore","javax.net.ssl.trustStorePassword","javax.net.ssl.trustStoreType")
        val previous=names.associateWith { System.getProperty(it) }
        System.setProperty(names[0],trust.absolutePath);System.setProperty(names[1],"fixture");System.setProperty(names[2],"JKS")
        try {
            MockWebServer().use { server ->
                server.useHttps(tls.sslSocketFactory(),false);server.start(InetAddress.getByName("::1"),0)
                val vm=vm();val parent=record();assertTrue(vm.restoreAppState(payload(vm,listOf(parent))))
                saved(vm,server.port,true);val before=gson.toJson(vm.runRecords.value.single())
                server.enqueue(MockResponse().setBody("x".repeat(16384)))
                assertTrue(vm.confirmSingleIpRetest(parent.runId,"::1"));await(vm)
                val child=assertDurableLiteral(vm,parent,"::1",before)
                assertEquals(1,server.requestCount);assertEquals(8192L,child.summary.bodyBytesConsumed)
                assertTrue(child.results.single().download!!.safetyVerified)
                assertArrayEquals(InetAddress.getByName("::1").address,InetAddress.getByName(child.results.single().observedPeer).address)
                println("N4_REPAIR_IPV6_TLS_REQUESTS="+server.requestCount)
            }
        } finally { previous.forEach { (k,v)->if(v==null) System.clearProperty(k) else System.setProperty(k,v) };trust.delete() }
    }
    @Test fun compressedIpv6RealProbeWithoutDownloadKeepsLiteralThroughDurableRoundTrip() {
        ServerSocket(0,8,InetAddress.getByName("::1")).use { socket ->
            val vm=vm();val parent=record();assertTrue(vm.restoreAppState(payload(vm,listOf(parent))))
            saved(vm,socket.localPort,false);val before=gson.toJson(parent)
            assertTrue(vm.confirmSingleIpRetest(parent.runId,"::1"));await(vm)
            val child=assertDurableLiteral(vm,parent,"::1",before)
            assertTrue(child.results.single().probe!!.passed);assertEquals(0,child.summary.downloadsStarted)
            socket.accept().use { assertTrue(it.isConnected) }
        }
    }
    @Test fun uppercaseCompressedIpv6FailedConnectionStillPersistsExactConfirmedLiteral() {
        val vm=vm();val literal="2001:DB8::A";val parent=record(literal)
        assertTrue(vm.restoreAppState(payload(vm,listOf(parent))));saved(vm,1,false)
        val before=gson.toJson(parent);assertTrue(vm.confirmSingleIpRetest(parent.runId,literal));await(vm)
        val child=assertDurableLiteral(vm,parent,literal,before)
        assertFalse(child.results.single().probe!!.passed);assertEquals(1,child.summary.probeFailed)
    }
    @Test fun completionCommitFailureIsExplicitTerminalErrorAndDoesNotExposeUndurableParentId() {
        ServerSocket(0,8,InetAddress.getByName("::1")).use { socket ->
            val vm=vm();val parent=record();assertTrue(vm.restoreAppState(payload(vm,listOf(parent))))
            saved(vm,socket.localPort,false)
            val state=File(app.filesDir,"app-state.json");val bytes=state.readBytes()
            val before=AppStateStore.get(app).exportState();val ids=vm.historyIdentities()
            // Block actual store IO while preserving the complete previous authority directory.
            val directory=app.filesDir;val moved=File(directory.parentFile,directory.name+"-n4-repair-held")
            assertTrue(directory.renameTo(moved));assertTrue(directory.createNewFile())
            try {
                assertTrue(vm.confirmSingleIpRetest(parent.runId,"::1"));await(vm)
                assertTrue("explicit terminal persistence error required: ${vm.statusMessage.value}",vm.statusMessage.value.contains("测速结果保存失败"))
                assertNull("undurable child must not be advertised as a retained current parent",vm.currentResultRunId)
                assertEquals(listOf(parent),vm.runRecords.value);assertEquals(ids,vm.historyIdentities())
                assertEquals(RunOwner.State.RELEASED,engine(vm).runOwner!!.state);assertFalse(vm.isTesting.value)
            } finally { assertTrue(directory.delete());assertTrue(moved.renameTo(directory)) }
            assertArrayEquals(bytes,state.readBytes());assertEquals(before,AppStateStore.get(app).exportState())
            assertEquals(listOf(parent),vm().runRecords.value)
        }
    }
}
