package com.cfst.app

import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class RunAccountingTest {
    @Test fun enginePublishesTypedRecordWithFailedProbe() = runBlocking {
        java.net.ServerSocket(0).use { socket ->
            val port=socket.localPort; socket.close()
            val engine=SpeedTestEngine()
            engine.startTest(IpRangeParser.parseIpFile("127.0.0.1"),SpeedTestEngine.TestConfig(testCount=1,pingCount=1,port=port,downloadTest=false),{},{},{})
            val getter=engine.javaClass.methods.firstOrNull { it.name=="getLastRecord" }
            assertNotNull("production engine needs raw RunRecord",getter)
            val record=getter!!.invoke(engine)!!
            val summary=record.javaClass.getMethod("getSummary").invoke(record)
            assertEquals(1,summary.javaClass.getMethod("getGeneratedUnique").invoke(summary))
            assertEquals(1,summary.javaClass.getMethod("getProbeFailed").invoke(summary))
        }
    }
    @Test fun typedDownloadIsAttachedToActualResult() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(10000)))
            var result: com.cfst.app.model.SpeedTestResult?=null
            SpeedTestEngine().startTest(IpRangeParser.parseIpFile("127.0.0.1"),SpeedTestEngine.TestConfig(testCount=1,pingCount=1,port=server.port,downloadUrl=server.url("/").toString(),allowHttp=true,byteBudget=1000),{},{},{result=it.single()})
            val getter=result!!.javaClass.methods.firstOrNull { it.name=="getDownload" }
            assertNotNull("typed download is not wired",getter)
            val download=getter!!.invoke(result)
            assertNotNull(download)
            assertEquals(false,download.javaClass.getMethod("getRecommended").invoke(download))
        }
    }
}
