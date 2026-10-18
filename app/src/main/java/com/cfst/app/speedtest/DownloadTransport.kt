package com.cfst.app.speedtest

import com.cfst.app.utils.IpRangeParser
import okhttp3.*
import okio.buffer
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Classified termination, not a zero-speed success. */
enum class DownloadError { NONE, CONFIG, HTTP, REDIRECT, TLS, PEER, TIMEOUT, IO, CANCELLED, BUDGET }
enum class DownloadTermination { BODY_COMPLETE, TIME_LIMIT, BYTE_LIMIT, READ_TIMEOUT, CONNECT_TIMEOUT, TLS_FAILURE, HTTP_REJECTED, REDIRECT_REJECTED, PEER_MISMATCH, IO_FAILURE, USER_STOP, NETWORK_LOST, OWNER_CANCELLED, RUN_TIME_LIMIT }
enum class SampleQuality { UNKNOWN, SHORT, QUALIFIED }
data class DownloadMeasurement(val bytes: Long = 0, val elapsedNanos: Long = 1, val peer: String = "", val status: Int = 0, val error: DownloadError = DownloadError.NONE,
    val termination: DownloadTermination = when(error) {
        DownloadError.NONE -> DownloadTermination.BODY_COMPLETE
        DownloadError.BUDGET -> DownloadTermination.BYTE_LIMIT
        DownloadError.TIMEOUT -> DownloadTermination.READ_TIMEOUT
        DownloadError.TLS -> DownloadTermination.TLS_FAILURE
        DownloadError.HTTP -> DownloadTermination.HTTP_REJECTED
        DownloadError.REDIRECT -> DownloadTermination.REDIRECT_REJECTED
        DownloadError.PEER -> DownloadTermination.PEER_MISMATCH
        DownloadError.CANCELLED -> DownloadTermination.OWNER_CANCELLED
        else -> DownloadTermination.IO_FAILURE
    }, val safetyVerified: Boolean = false, val filterPassed: Boolean = true) {
    val bytesPerSecond: Double get() = bytes * 1e9 / elapsedNanos.coerceAtLeast(1)
    /** Compatibility API in MiB/s; new storage/UI uses bytesPerSecond. */
    val speed: Double get() = bytesPerSecond / 1048576
    val transportValid: Boolean get() = safetyVerified && bytes > 0 && termination in setOf(DownloadTermination.BODY_COMPLETE, DownloadTermination.TIME_LIMIT, DownloadTermination.BYTE_LIMIT)
    val sampleQuality: SampleQuality get() = if (elapsedNanos >= 1000000000 && bytes >= 65536) SampleQuality.QUALIFIED else SampleQuality.SHORT
    val recommended: Boolean get() = transportValid && sampleQuality == SampleQuality.QUALIFIED && filterPassed
}
/** Actual consumed response-body bytes; headers/TLS/socket buffering are additional. */
class ByteBudget(val limit: Long) {
    init { require(limit > 0) }
    var used: Long = 0; private set
    val remaining: Long get() = limit - used
    fun consume(bytes: Int) { require(bytes >= 0 && bytes <= remaining); used += bytes }
}
/** Closing registration is race-safe even when stop precedes connect/execute. */
class RunCancellation {
    private val closers = mutableSetOf<() -> Unit>()
    @Volatile private var deadlineNanos:Long?=null
    private var clock:RunDeadlineClock=MonotonicRunClock
    fun bindDeadline(deadline:Long,clock:RunDeadlineClock) { this.clock=clock; deadlineNanos=deadline }
    fun remainingMillis(local:Long):Long {
        val end=deadlineNanos ?: return local
        val left=end-clock.nanoTime()
        if(left<=0) { cancel("RUN_TIME_LIMIT"); return 0 }
        return minOf(local,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(left).coerceAtLeast(1))
    }
    @Volatile var reason: String? = null; private set
    val cancelled: Boolean get() = reason != null
    fun cancel(reason:DownloadTermination) = cancel(reason.name)
    @Synchronized fun register(close: () -> Unit): () -> Unit {
        if (cancelled) close() else closers.add(close)
        return { synchronized(this) { closers.remove(close) }; Unit }
    }
    @Synchronized fun cancel(reason: String) {
        if (cancelled) return
        this.reason = reason
        closers.toList().forEach { runCatching { it() } }
        closers.clear()
    }
}
class PeerMismatch : IOException("Connected peer does not match candidate")
private class CollectorFailure(val original:Throwable):RuntimeException(original)
private fun cancelTermination(reason:String)=when {
    reason=="BYTE_LIMIT" -> DownloadTermination.BYTE_LIMIT
    reason=="TIME_LIMIT" -> DownloadTermination.TIME_LIMIT
    reason=="RUN_TIME_LIMIT" -> DownloadTermination.RUN_TIME_LIMIT
    reason=="NETWORK_LOST" || reason.contains("network",true) || reason.contains("网络") -> DownloadTermination.NETWORK_LOST
    reason.contains("coroutine",true) || reason=="OWNER_CANCELLED" -> DownloadTermination.OWNER_CANCELLED
    else -> DownloadTermination.USER_STOP
}
class DownloadTransport(private val attemptClock:RunDeadlineClock=MonotonicRunClock) {
    fun measure(ip: String, url: String, port: Int, durationMs: Long, budget: ByteBudget, cancellation: RunCancellation, allowHttp: Boolean = false, onBytes:(Long)->Unit={}): DownloadMeasurement {
        val start = attemptClock.nanoTime()
        var bytes = 0L
        var peer = ""
        var status = 0
        var error = DownloadError.NONE
        var safetyVerified = false
        var connecting = true
        fun cancelledMeasurement()=DownloadMeasurement(error=DownloadError.CANCELLED,termination=cancelTermination(cancellation.reason ?: "OWNER_CANCELLED"))
        cancellation.remainingMillis(durationMs)
        if(cancellation.cancelled) return cancelledMeasurement()
        val address = try { IpRangeParser.literalAddress(ip) } catch (_: IllegalArgumentException) { return DownloadMeasurement(error = DownloadError.CONFIG) }
        val target = try {
            require(port in 1..65535 && durationMs in 1..60000)
            url.toHttpUrl().also { require((it.isHttps || allowHttp) && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null) }.newBuilder().port(port).build()
        } catch (_: IllegalArgumentException) { return DownloadMeasurement(error = DownloadError.CONFIG) }
        val remaining=cancellation.remainingMillis(durationMs)
        if(remaining<=0 || cancellation.cancelled) return cancelledMeasurement()
        val deadline = start + TimeUnit.MILLISECONDS.toNanos(remaining)
        val attemptToken = RunCancellation()
        val unregisterParent = cancellation.register { attemptToken.cancel(cancellation.reason ?: "OWNER_CANCELLED") }
        val runBounded=remaining<durationMs
        fun expireAttempt() { if(runBounded) cancellation.cancel("RUN_TIME_LIMIT") else attemptToken.cancel("TIME_LIMIT") }
        val deadlineTask = attemptClock.schedule((deadline - attemptClock.nanoTime()).coerceAtLeast(0)) { expireAttempt() }
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .dns(object : Dns { override fun lookup(hostname: String): List<java.net.InetAddress> = if (hostname == target.host) listOf(address) else throw IOException("Unexpected DNS host") })
            .connectTimeout(remaining.coerceAtMost(3000), TimeUnit.MILLISECONDS)
            .readTimeout(remaining.coerceAtMost(1500), TimeUnit.MILLISECONDS)
            .eventListener(object : EventListener() {
                override fun connectionAcquired(call: Call, connection: Connection) { connecting = false }
            })
            .addNetworkInterceptor { chain ->
                val socket = chain.connection()?.socket() ?: throw PeerMismatch()
                if (!socket.inetAddress.address.contentEquals(address.address) || socket.port != port) throw PeerMismatch()
                peer = socket.inetAddress.hostAddress.orEmpty()
                val response = chain.proceed(chain.request())
                val body = response.body
                if (body == null || !response.isSuccessful || (response.header("Content-Encoding") ?: "identity") != "identity") response
                else {
                    safetyVerified = true
                    val bounded = object : okio.ForwardingSource(body.source()) {
                        override fun read(sink: okio.Buffer, byteCount: Long): Long {
                            if (budget.remaining <= 0) return -1
                            val count = super.read(sink, minOf(byteCount, budget.remaining))
                            if (count > 0) { budget.consume(count.toInt()); bytes += count; try { onBytes(count) } catch(e:Throwable) { throw CollectorFailure(e) } }
                            return count
                        }
                    }
                    val source = bounded.buffer()
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun source() = source
                    }).build()
                }
            }.build()
        try {
            while (!attemptToken.cancelled && budget.remaining > 0 && attemptClock.nanoTime() < deadline) {
                val remainingNanos = deadline - attemptClock.nanoTime()
                if (remainingNanos <= 0) break
                val call = client.newCall(Request.Builder().url(target)
                    .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache")
                    .header("Connection", "close").build())
                connecting = true
                val unregister = attemptToken.register { call.cancel() }
                try {
                    call.execute().use { response ->
                        status = response.code
                        if (status in 300..399) { error = DownloadError.REDIRECT; return@use }
                        if (!response.isSuccessful || (response.header("Content-Encoding") ?: "identity") != "identity") { error = DownloadError.HTTP; return@use }
                        val source = response.body?.byteStream() ?: throw IOException("Empty response body")
                        val buffer = ByteArray(8192)
                        var requestBytes = 0L
                        while (!attemptToken.cancelled && budget.remaining > 0 && attemptClock.nanoTime() < deadline) {
                            val n = source.read(buffer, 0, minOf(buffer.size.toLong(), budget.remaining).toInt())
                            if (n < 0) break
                            if (n == 0) continue
                            requestBytes += n
                        }
                        if (requestBytes == 0L && !attemptToken.cancelled && budget.remaining > 0) error = DownloadError.IO
                    }
                } finally { unregister() }
                if (error != DownloadError.NONE) break
            }
            if (attemptToken.reason == "TIME_LIMIT") error = DownloadError.TIMEOUT
            else if (attemptToken.cancelled) error = DownloadError.CANCELLED
            else if (error == DownloadError.NONE && budget.remaining == 0L) error = DownloadError.BUDGET
            else if (error == DownloadError.NONE && attemptClock.nanoTime() >= deadline) {
                expireAttempt()
                error = if(cancellation.cancelled) DownloadError.CANCELLED else DownloadError.TIMEOUT
            }
        } catch (e: Exception) {
            if(e is CollectorFailure) { cancellation.cancel(DownloadTermination.OWNER_CANCELLED); throw e.original }
            error = when {
                attemptToken.reason == "TIME_LIMIT" -> DownloadError.TIMEOUT
                attemptToken.cancelled -> DownloadError.CANCELLED
                e is PeerMismatch -> DownloadError.PEER
                e is SSLException -> DownloadError.TLS
                e is InterruptedIOException -> DownloadError.TIMEOUT
                else -> DownloadError.IO
            }
        } finally {
            deadlineTask.cancelAndJoin()
            unregisterParent()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
        if(runBounded && attemptClock.nanoTime()>=deadline) { cancellation.cancel("RUN_TIME_LIMIT"); error=DownloadError.CANCELLED }
        val reason = cancellation.reason ?: attemptToken.reason
        if(cancellation.cancelled) error=DownloadError.CANCELLED
        val termination = when {
            reason == "RUN_TIME_LIMIT" -> DownloadTermination.RUN_TIME_LIMIT
            reason == "TIME_LIMIT" -> DownloadTermination.TIME_LIMIT
            reason == "BYTE_LIMIT" -> DownloadTermination.BYTE_LIMIT
            reason != null -> cancelTermination(reason)
            error == DownloadError.TIMEOUT -> if (connecting) DownloadTermination.CONNECT_TIMEOUT else DownloadTermination.READ_TIMEOUT
            else -> DownloadMeasurement(error = error).termination
        }
        return DownloadMeasurement(bytes, attemptClock.nanoTime() - start, peer, status, error, termination, safetyVerified)
    }
}
