package com.cfst.app.speedtest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.round

/**
 * TCP Ping 测试类
 * 参考 Windows 版 cfst.exe 的测速逻辑优化
 */
class TcpPingTest {
    
    /**
     * Ping 测试结果
     */
    data class PingResult(
        val sent: Int,           // 发送的包数
        val received: Int,       // 接收的包数
        val packetLoss: Double,  // 丢包率 (0.00 - 100.00)
        val avgLatency: Double,  // 平均延迟 (ms)
        val minLatency: Double,  // 最小延迟 (ms)
        val maxLatency: Double,   // 最大延迟 (ms)
        val cancelledAttempts: Int = 0,
        val cancelled: Boolean = false
    )
    
    companion object {
        @Volatile var connectionObserver:((Socket)->Unit)?=null
        private const val DEFAULT_PORT = 443
        private const val DEFAULT_TIMEOUT_MS = 1000
        
        /**
         * 执行 TCP Ping 测试（挂起函数）
         * @param ip 目标 IP 地址
         * @param port 目标端口，默认 443
         * @param count 测试次数，默认 4 次
         * @param timeoutMs 超时时间 (毫秒)，默认 1000ms
         * @return PingResult 对象
         */
        suspend fun ping(
            ip: String,
            port: Int = DEFAULT_PORT,
            count: Int = 4,
            timeoutMs: Int = DEFAULT_TIMEOUT_MS,
            cancellation: RunCancellation = RunCancellation()
        ): PingResult = withContext(Dispatchers.IO) {
            val latencies = mutableListOf<Double>()
            var attempted = 0
            var cancelledAttempts = 0
            
            repeat(count) {
                if (cancellation.cancelled) return@repeat
                val remaining=cancellation.remainingMillis(timeoutMs.toLong()).toInt()
                if(remaining<=0) return@repeat
                attempted++
                val latency = singleTcpPing(ip, port, remaining, cancellation)
                if (cancellation.cancelled && latency == null) { cancelledAttempts++; return@repeat }
                if (latency != null) {
                    latencies.add(latency)
                }
            }
            
            val received = latencies.size
            val completed = attempted - cancelledAttempts
            val packetLoss = if (completed > 0) {
                round(((completed - received).toDouble() / completed) * 10000) / 100
            } else {
                0.0
            }
            
            val avgLatency = if (received > 0) {
                round((latencies.sum() / received) * 100) / 100
            } else {
                0.0
            }
            
            val minLatency = if (latencies.isNotEmpty()) {
                round(latencies.min() * 100) / 100
            } else {
                0.0
            }
            
            val maxLatency = if (latencies.isNotEmpty()) {
                round(latencies.max() * 100) / 100
            } else {
                0.0
            }
            
            PingResult(
                sent = attempted,
                received = received,
                packetLoss = packetLoss,
                avgLatency = avgLatency,
                minLatency = minLatency,
                maxLatency = maxLatency,
                cancelledAttempts = cancelledAttempts,
                cancelled = cancelledAttempts>0 || (cancellation.cancelled && attempted<count)
            )
        }
        
        /**
         * 执行单次 TCP Ping
         * @return 延迟 (ms)，如果失败返回 null
         */
        private fun singleTcpPing(ip: String, port: Int, timeoutMs: Int, cancellation: RunCancellation): Double? {
            return try {
                val socket = Socket()
                val unregister = cancellation.register { socket.close() }
                val startTime = System.nanoTime()
                
                try {
                    socket.connect(
                        InetSocketAddress(com.cfst.app.utils.IpRangeParser.literalAddress(ip), port),
                        timeoutMs
                    )
                    val endTime = System.nanoTime()
                    val latencyMs = (endTime - startTime) / 1_000_000.0
                    connectionObserver?.invoke(socket)
                    
                    // 限制最大延迟显示
                    val roundedLatency = round(latencyMs * 100) / 100
                    if (roundedLatency > 9999) null else roundedLatency
                } finally {
                    unregister()
                    try {
                        socket.close()
                    } catch (e: Exception) {
                        // 忽略关闭错误
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
