package com.cfst.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cfst.app.model.SpeedTestResult
import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import com.cfst.app.viewmodel.SpeedTestViewModel
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ReviewRegressionTest {
    @Test fun historySavePreservesTerminalStopReason() {
        val vm = SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        val resultField = SpeedTestViewModel::class.java.getDeclaredField("_results").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        val results = resultField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<SpeedTestResult>>
        results.value=listOf(SpeedTestResult("192.0.2.1",1,1,0.0,1.0,0.0,"N/A"))
        val statusField = SpeedTestViewModel::class.java.getDeclaredField("_statusMessage").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        val status = statusField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<String>
        status.value="network lost，保留 1 个结果"
        vm.saveCurrentResultsToHistory()
        assertEquals("network lost，保留 1 个结果",vm.statusMessage.value)
        assertEquals(1,vm.historyRecords.value.size)
    }
    @Test fun csvWritesFrozenSnapshotOffMainThread() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = SpeedTestViewModel(app)
        val uri=android.net.Uri.parse("content://test/export")
        var writeThread: Thread?=null
        val bytes=java.io.ByteArrayOutputStream()
        val output=object: java.io.OutputStream() {
            override fun write(value:Int) { writeThread=Thread.currentThread(); bytes.write(value) }
        }
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerOutputStream(uri,output)
        val caller=Thread.currentThread()
        vm.saveCsv(uri,"frozen snapshot")
        assertEquals("frozen snapshot",bytes.toString("UTF-8"))
        assertNotEquals(caller,writeThread)
    }
    @Test @Config(shadows=[NullExportResolver::class]) fun nullCsvStreamIsFailure() = runBlocking {
        val vm = SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        try { vm.saveCsv(android.net.Uri.parse("content://missing/export"), "frozen"); fail("null stream must fail") }
        catch (_: java.io.IOException) { }
    }
    @Test fun oldListResponseCannotReplaceNewImport() {
        val vm = SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        val request = vm.captureListRequest()
        vm.loadIpRanges("192.0.2.1")
        assertFalse(vm.acceptListResponse(request,"192.0.2.2"))
        assertEquals(listOf("192.0.2.1"),vm.availableIpSegments.value)
    }
    @Test fun oldIpv4ResponseCannotOverwriteAfterFamilySwitch() {
        val vm = SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.updateUseIpv6(false)
        val request = vm.captureListRequest()
        vm.updateUseIpv6(true); vm.loadIpRanges("2001:db8::1")
        assertFalse(vm.acceptListResponse(request,"192.0.2.2"))
        assertEquals(listOf("2001:db8::1"),vm.availableIpSegments.value)
    }
    @Test fun cancelledTcpDoesNotReportUnattemptedFailures() = runBlocking {
        val token = RunCancellation(); token.cancel("stop")
        val result = TcpPingTest.ping("127.0.0.1", 9, 20, 50, token)
        assertEquals(0, result.sent)
        assertEquals(0.0, result.packetLoss, 0.0)
    }
    @Test fun lostIdleNetworkCannotBeginFromStaleSnapshot() {
        val guard = NetworkIdentityGuard {}
        guard.update("123:Wi-Fi"); guard.lost("123"); guard.update("123:Wi-Fi")
        assertFalse(guard.begin())
    }
    @Test fun rankingHasStableIpTieBreak() {
        val a = SpeedTestResult("192.0.2.1", 1, 1, 0.0, 1.0, 2.0, "N/A")
        val b = a.copy(ipAddress="192.0.2.2")
        assertEquals(listOf(a,b), SpeedTestResult.rank(listOf(b,a), false))
        assertTrue(a < b)
    }
    @Test fun csvProtectsWhitespaceFormula() {
        val r = SpeedTestResult("192.0.2.1",1,1,0.0,1.0,0.0," \t=1+1")
        assertTrue(r.toCsvString().contains("' \t=1+1"))
    }
    @Test fun selectedSegmentTrimPersists() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("local_settings",0).edit().clear().commit()
        val vm = SpeedTestViewModel(app)
        vm.loadIpRanges("192.0.2.1\n192.0.2.2"); vm.toggleSpecifiedIpSegment("192.0.2.2")
        vm.loadIpRanges("192.0.2.1")
        assertTrue(app.getSharedPreferences("local_settings",0).getStringSet("selectedSegments",emptySet())!!.isEmpty())
    }
    @Test fun oversizedCustomListRejectedAtomically() {
        val vm = SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.loadIpRanges("192.0.2.1")
        vm.loadIpRanges("192.0.2.2\n".repeat(10001))
        assertEquals(listOf("192.0.2.1"), vm.availableIpSegments.value)
    }
    private suspend fun fetch(vm: SpeedTestViewModel, client: OkHttpClient, url: String): String? {
        val method = SpeedTestViewModel::class.java.getDeclaredMethod("fetchContent", OkHttpClient::class.java, String::class.java, kotlin.coroutines.Continuation::class.java)
        method.isAccessible = true
        return kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
            method.invoke(vm, client, url, continuation)
        }
    }
    @Test fun listBodyBounded() = runBlocking {
        val vm = SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("x".repeat(1024*1024+1), 8192))
            val result = fetch(vm, OkHttpClient(), server.url("/").toString())
            assertNull(result)
        }
    }
    @Test fun listBodyDeadlineIncludesSlowBody() = runBlocking {
        val vm = SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("192.0.2.1").setBodyDelay(1,TimeUnit.SECONDS))
            val client = OkHttpClient.Builder().callTimeout(100,TimeUnit.MILLISECONDS).build()
            val started = System.nanoTime()
            assertNull(fetch(vm,client,server.url("/").toString()))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<800)
        }
    }
    @Test fun listCancellationClosesSlowBodyAndPropagates() = runBlocking {
        val vm = SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("192.0.2.1").setBodyDelay(4,TimeUnit.SECONDS))
            var returned = false
            val job = launch(Dispatchers.Default) { fetch(vm,OkHttpClient(),server.url("/").toString()); returned=true }
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))
            val start=System.nanoTime(); job.cancel(); job.join()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000)
            assertFalse(returned)
        }
    }
    @Test fun listNonSuccessIsNotImported() = runBlocking {
        val vm = SpeedTestViewModel(ApplicationProvider.getApplicationContext<Application>())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("192.0.2.1"))
            assertNull(fetch(vm,OkHttpClient(),server.url("/").toString()))
        }
    }
}
