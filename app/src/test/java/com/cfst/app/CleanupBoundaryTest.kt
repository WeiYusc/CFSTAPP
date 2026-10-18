package com.cfst.app

import com.cfst.app.speedtest.*
import com.cfst.app.model.*
import com.cfst.app.utils.IpRangeParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class CleanupBoundaryTest {
    @Test fun stoppedBeforeLaunchReservationCanReleaseWithoutWorker() {
        val engine=SpeedTestEngine(); val token=engine.prepareRun()!!
        engine.stopTest(); engine.releaseRun(token)
        assertFalse(engine.isRunning()); assertNotNull(engine.prepareRun())
    }
    @Test fun liveDownloadCallbackFailureClosesResourcesAndTerminatesAccounting() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(100000)))
            val engine=SpeedTestEngine(); var failure=false
            try {
                engine.startTest(IpRangeParser.parseIpFile("127.0.0.1"),SpeedTestEngine.TestConfig(testCount=1,pingCount=1,downloadUrl=server.url("/").toString(),port=server.port,allowHttp=true,byteBudget=50000),{
                    if(engine.currentRecord?.summary?.bodyBytesConsumed?:0L>0) throw IllegalStateException("collector rejected")
                },{},{})
            } catch(e:IllegalStateException) { failure=true }
            assertTrue("collector exception must not be swallowed as ordinary transport IO",failure)
            assertFalse(engine.isRunning())
            val s=engine.lastRecord!!.summary
            assertEquals(0,s.downloadActive); assertEquals(s.downloadsStarted,s.downloadsCompleted); assertEquals(1,s.downloadCancelled)
        }
    }
}
