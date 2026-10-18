package com.cfst.app

import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OptimizationContractTest {
    @Test fun emptyAndCommentsCannotReplaceList() {
        for (input in listOf("", " \n", "# comment\n")) {
            try { IpRangeParser.parseIpFile(input); fail("empty list accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun physicalLineBytesAreBoundedEvenComments() {
        try { IpRangeParser.parseIpFile("#" + "x".repeat(4096) + "\n192.0.2.1"); fail("oversized line accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun nulIsRejectedEvenInComments() {
        try { IpRangeParser.parseIpFile("#\u0000\n192.0.2.1"); fail("NUL accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun filteredDownloadsAreRetainedRaw() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("x".repeat(1000)))
            var final = emptyList<com.cfst.app.model.SpeedTestResult>()
            SpeedTestEngine().startTest(IpRangeParser.parseIpFile("127.0.0.1"), SpeedTestEngine.TestConfig(testCount=1,pingCount=1,port=server.port,downloadUrl=server.url("/").toString(),allowHttp=true,byteBudget=1000,speedLimit=1e9), {}, {}, { final=it })
            assertEquals("filtered is not deleted",1,final.size)
        }
    }
    @Test fun failedProbesAreRetainedRaw() = runBlocking {
        java.net.ServerSocket(0).use { socket ->
            val port=socket.localPort; socket.close()
            var final = emptyList<com.cfst.app.model.SpeedTestResult>()
            SpeedTestEngine().startTest(IpRangeParser.parseIpFile("127.0.0.1"),SpeedTestEngine.TestConfig(testCount=1,pingCount=1,port=port,downloadTest=false),{},{},{final=it})
            assertEquals(1,final.size)
        }
    }
    @Test fun completingCallbackCannotReserveBeforeCleanup() = runBlocking {
        val engine=SpeedTestEngine()
        var premature: RunCancellation?=null
        engine.startTest(IpRangeParser.parseIpFile("127.0.0.1"),SpeedTestEngine.TestConfig(testCount=1,pingCount=1,downloadTest=false),{},{},{premature=engine.prepareRun()})
        assertNull("completion still owns resources",premature)
        assertNotNull(engine.prepareRun())
    }
}
