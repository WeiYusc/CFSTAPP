package com.cfst.app.model

import java.util.Date

/**
 * 测速结果数据类
 */
data class SpeedTestResult(
    val ipAddress: String,          // IP地址
    val packetsSent: Int,           // 发送的包数量
    val packetsReceived: Int,       // 接收的包数量
    val packetLoss: Double,         // 丢包率 (0.00 - 100.00)
    val avgLatency: Double,         // 平均延迟 (ms)
    val downloadSpeed: Double,      // 下载速度 (MB/s)
    val regionCode: String,         // 地区码
    val testTime: Long = System.currentTimeMillis(),
    val downloadedBytes: Long = 0,
    val observedPeer: String = "",
    val downloadError: String = "NOT_TESTED",
    val httpStatus: Int = 0,
    val probe: ProbeMeasurement? = null,
    val download: com.cfst.app.speedtest.DownloadMeasurement? = null
) : Comparable<SpeedTestResult> {
    
    override fun compareTo(other: SpeedTestResult): Int {
        // 先按丢包率升序，再按延迟升序，最后按下载速度降序
        val lossCompare = this.packetLoss.compareTo(other.packetLoss)
        if (lossCompare != 0) return lossCompare
        
        val latencyCompare = this.avgLatency.compareTo(other.avgLatency)
        if (latencyCompare != 0) return latencyCompare
        
        val speedCompare = other.downloadSpeed.compareTo(this.downloadSpeed)
        return if (speedCompare != 0) speedCompare else ipAddress.compareTo(other.ipAddress)
    }

    fun getTestTimeFormatted(): String {
        val date = Date(this.testTime)
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(date)
    }
    
    fun toCsvString(): String {
        fun escape(value: String): String {
            val safe = if (value.trimStart().startsWith("=") || value.trimStart().startsWith("+") || value.trimStart().startsWith("-") || value.trimStart().startsWith("@")) "'$value" else value
            return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${safe.replace("\"", "\"\"")}\"" else safe
        }
        fun number(value: Double) = String.format(java.util.Locale.US, "%.2f", value)
        return listOf(ipAddress, packetsSent.toString(), packetsReceived.toString(), number(packetLoss), if(packetsReceived>0) number(avgLatency) else "UNKNOWN", number((download?.bytesPerSecond ?: downloadSpeed*1048576)/1000000), regionCode, getTestTimeFormatted(), downloadedBytes.toString(), observedPeer, downloadError, httpStatus.toString(), if(download==null) "1" else "2", download?.elapsedNanos?.toString() ?: "UNKNOWN", download?.termination?.name ?: "LEGACY", download?.sampleQuality?.name ?: "UNKNOWN", download?.transportValid?.toString() ?: "UNKNOWN", download?.filterPassed?.toString() ?: "UNKNOWN").joinToString(",") { escape(it) }
    }
    
    companion object {
        fun rank(results: List<SpeedTestResult>, byLatency: Boolean): List<SpeedTestResult> = results.sortedWith(
            (if (byLatency) compareBy<SpeedTestResult> { it.packetLoss }.thenBy { if(it.packetsReceived>0) it.avgLatency else Double.POSITIVE_INFINITY }
            else compareBy<SpeedTestResult> { it.packetLoss }.thenByDescending { it.downloadSpeed }.thenBy { it.avgLatency })
        .thenBy { it.ipAddress })
        fun getCsvHeader(): String {
            return "IP 地址,已发送,已接收,丢包率,平均延迟,下载速度(MB/s),地区码,测试时间,响应体字节,实际Peer,下载状态,HTTP状态,schemaVersion,elapsedNanos,termination,sampleQuality,transportValid,filterPassed"
        }
    }
}
