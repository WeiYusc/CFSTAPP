package com.cfst.app.ui.presentation

import com.cfst.app.model.SpeedTestResult
import com.cfst.app.speedtest.*
import com.cfst.app.utils.DataUnits
import java.util.Locale

data class ResultPresentation(val bucket:String,val status:String,val speed:String,val latency:String,val tcpFailure:String,val bytes:String)
fun presentResult(result:SpeedTestResult,config:SpeedTestEngine.TestConfig?=null):ResultPresentation {
    val p=result.probe; val d=result.download
    val legacy=p==null && d==null
    val tcpOnly=config!=null && (!config.downloadTest || config.pingOnly)
    val tcpEligible=tcpOnly && p!=null && !p.cancelled && p.received>0 && p.delayMs?.let { it.isFinite() && it>=0 && (config!!.latencyLimit==0.0 || it<=config.latencyLimit) }==true
    val bucket=when {
        legacy -> "异常/未达标"
        d==null && tcpEligible -> "优选"
        d?.recommended==true -> "优选"
        d!=null && d.transportValid && d.filterPassed && d.sampleQuality==SampleQuality.SHORT -> "短样本"
        d==null && tcpOnly && p?.passed==true -> "异常/未达标"
        d==null && p?.passed==true -> "仅延迟"
        else -> "异常/未达标"
    }
    val status=when {
        legacy -> "旧记录 · 质量未知"
        d==null && p?.cancelled==true -> "探测已取消"
        d==null && p?.passed!=true -> "探测未成功"
        d==null && tcpEligible -> "已验证延迟优选 · 未下载"
        d==null && tcpOnly -> "延迟筛选未达标 · 未下载"
        d==null -> if(config==null) "仅延迟 · 配置未知" else "仅延迟 · 未下载"
        d.recommended -> "已验证优选"
        !d.transportValid -> terminationLabel(d.termination)+if(d.bytes>0) " · 部分数据保留" else ""
        !d.filterPassed -> "筛选未达标"
        else -> "样本较短 · 非默认推荐"
    }
    val speed=when {
        d!=null -> DataUnits.formatSpeed(d.bytesPerSecond)
        legacy && result.downloadSpeed>0 -> DataUnits.formatSpeed(result.downloadSpeed*1048576)+" · 旧值"
        legacy -> "速度未知"
        else -> "未测速度"
    }
    val delay=p?.delayMs ?: result.avgLatency.takeIf { legacy && result.packetsReceived>0 }
    val loss=if(p!=null) p.loss else result.packetLoss.takeIf { result.packetsSent>0 }
    return ResultPresentation(bucket,status,speed,delay?.let { String.format(Locale.US,"%.2f ms",it) } ?: "未知",loss?.let { String.format(Locale.US,"%.1f%%",it) } ?: "未知",DataUnits.formatBytes(result.downloadedBytes))
}
fun terminationLabel(t:DownloadTermination):String=when(t) {
    DownloadTermination.BODY_COMPLETE -> "响应体完成"
    DownloadTermination.TIME_LIMIT -> "正常时限结束"
    DownloadTermination.BYTE_LIMIT -> "预算上限结束"
    DownloadTermination.READ_TIMEOUT -> "读取超时"
    DownloadTermination.CONNECT_TIMEOUT -> "连接超时"
    DownloadTermination.TLS_FAILURE -> "TLS 验证失败"
    DownloadTermination.HTTP_REJECTED -> "HTTP 状态拒绝"
    DownloadTermination.REDIRECT_REJECTED -> "重定向已拒绝"
    DownloadTermination.PEER_MISMATCH -> "实际端点不匹配"
    DownloadTermination.IO_FAILURE -> "传输失败"
    DownloadTermination.USER_STOP -> "用户停止"
    DownloadTermination.NETWORK_LOST -> "网络丢失"
    DownloadTermination.OWNER_CANCELLED -> "运行已取消"
    DownloadTermination.RUN_TIME_LIMIT -> "总运行时限结束"
}
