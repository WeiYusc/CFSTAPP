package com.cfst.app

import com.cfst.app.model.*
import com.cfst.app.speedtest.*
import org.junit.Assert.*
import org.junit.Test

class UiPresentationContractTest {
    private fun present(r:SpeedTestResult,download:Boolean=true):Any {
        val type=try { Class.forName("com.cfst.app.ui.presentation.ResultPresentationKt") } catch(_:ClassNotFoundException) { fail("typed result presentation missing"); throw AssertionError() }
        return type.getMethod("presentResult",SpeedTestResult::class.java,SpeedTestEngine.TestConfig::class.java).invoke(null,r,SpeedTestEngine.TestConfig(downloadTest=download))!!
    }
    private fun field(p:Any,name:String):Any=p.javaClass.getMethod("get$name").invoke(p)!!
    private fun row(d:DownloadMeasurement?=null,p:ProbeMeasurement?=ProbeMeasurement(1,1,1.25))=SpeedTestResult("192.0.2.1",1,p?.received ?: 1,0.0,1.25,d?.speed ?: 0.0,"N/A",downloadedBytes=d?.bytes ?: 0,probe=p,download=d)
    @Test fun newUnmeasuredSpeedIsUnknownNotZero() { val p=present(row()); assertEquals("仅延迟",field(p,"Bucket")); assertEquals("未测速度",field(p,"Speed")) }
    @Test fun noSuccessfulHandshakeHasUnknownLatency() { assertEquals("未知",field(present(row(p=ProbeMeasurement(1,0,null))),"Latency")) }
    @Test fun legacyZeroIsNotFabricatedNewMeasurement() { val p=present(row(p=null)); assertEquals("异常/未达标",field(p,"Bucket")); assertTrue(field(p,"Status").toString().contains("旧记录")); assertTrue(field(p,"Speed").toString().contains("未知")) }
    @Test fun normalShortSampleIsNotDiagnosticFailure() { val p=present(row(DownloadMeasurement(1000,1000000,safetyVerified=true))); assertEquals("短样本",field(p,"Bucket")) }
    @Test fun failedPartialAndCancelledPartialRemainDiagnosticWithBytes() {
        for(e in listOf(DownloadError.IO,DownloadError.CANCELLED)) { val p=present(row(DownloadMeasurement(65536,1000000000,error=e,safetyVerified=true))); assertEquals("异常/未达标",field(p,"Bucket")); assertTrue(field(p,"Bytes").toString().contains("65.54")) }
    }
    @Test fun filteredShortIsNotOfferedAsNormalShort() { assertEquals("异常/未达标",field(present(row(DownloadMeasurement(1000,1000000,safetyVerified=true,filterPassed=false))),"Bucket")) }
    @Test fun latencyModeUsesValidProbeForPreferredBucket() { assertEquals("优选",field(present(row(),false),"Bucket")) }
    @Test fun qualifiedRecommendationIsPreferred() { assertEquals("优选",field(present(row(DownloadMeasurement(65536,1000000000,safetyVerified=true))),"Bucket")) }
    @Test fun cancelledProbeLossDoesNotInventZeroFailureRate() { assertEquals("未知",field(present(row(p=ProbeMeasurement(1,0,null,true,1))),"TcpFailure")) }
}
