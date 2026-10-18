package com.cfst.app.speedtest

import com.cfst.app.model.SpeedTestResult
import com.cfst.app.model.*
import com.cfst.app.utils.IpRangeParser
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicBoolean

/** Fair serial baseline. Settings are immutable for a run. */
class SpeedTestEngine(private val deadlineClock:RunDeadlineClock=MonotonicRunClock) {
    @Volatile var lastRecord: RunRecord? = null; private set
    @Volatile var samplingReport:CandidateSampler.Report?=null; private set
    @Volatile var currentRecord:RunRecord?=null; private set
    @Volatile var runOwner: RunOwner? = null; private set
    data class TestConfig(
        val testCount: Int = 30, val pingCount: Int = 3, val pingTimeout: Int = 1000,
        val downloadTest: Boolean = true, val downloadDuration: Long = 3000,
        val speedLimit: Double = 0.0, val latencyLimit: Double = 0.0, val port: Int = 443,
        val pingOnly: Boolean = false, val downloadTestCount: Int = 3, val pingFirst: Boolean = true,
        val maxConcurrentDownloads: Int = 1, val maxConcurrentPings: Int = 8,
        val downloadUrl: String = "https://speed.cloudflare.com/__down?bytes=1048576",
        val byteBudget: Long = 3 * 1024 * 1024, val allowHttp: Boolean = false,
        val runTimeSeconds:Int = 120, val sampleSeed:Long = 0
    )
    data class TestProgress(val current: Int, val total: Int, val currentIp: String, val status: String)
    private val runLock = Any()
    private val running = AtomicBoolean(false)
    fun prepareRun(): RunCancellation? = synchronized(runLock) {
        if (running.get()) null else RunCancellation().also { cancellation = it; runOwner=RunOwner(java.util.UUID.randomUUID().toString(),it,deadlineClock); running.set(true) }
    }
    @Volatile private var cancellation = RunCancellation()
    fun reserve(config:TestConfig,list:List<IpRangeParser.IpRange>,parentRunId:String?=null,confirmedLiteral:String?=null):RunCancellation? = synchronized(runLock) {
        require(config.runTimeSeconds in 30..600)
        val token=prepareRun() ?: return null
        try { runOwner!!.bind(config,list,parentRunId,confirmedLiteral) }
        catch(e:Exception) { releaseRun(token);throw e }
        token
    }
    val stopReason: String? get() = cancellation.reason
    suspend fun startTest(ipRanges: List<IpRangeParser.IpRange>, config: TestConfig, onProgress: (TestProgress) -> Unit, onResult: (SpeedTestResult) -> Unit, onComplete: (List<SpeedTestResult>) -> Unit, beforeNetwork: () -> Boolean = { true }, reserved: RunCancellation? = null) {
        try {
        require(config.testCount in 1..10000 && config.pingCount in 1..20 && config.pingTimeout in 1..10000 && config.maxConcurrentPings in 1..16)
        require(config.runTimeSeconds in 30..600)
        require(config.port in 1..65535 && config.downloadDuration in 1..60000 && config.downloadTestCount in 1..100)
        require(config.byteBudget in 1..(1024L * 1024 * 1024) && config.speedLimit.isFinite() && config.speedLimit >= 0 && config.latencyLimit.isFinite() && config.latencyLimit >= 0)
        if (config.downloadTest && !config.pingOnly) {
            val url = config.downloadUrl.toHttpUrl()
            require((url.isHttps || config.allowHttp) && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "HTTPS download URL required" }
        }
        if(reserved!=null) {
            val bound=synchronized(runLock) { runOwner?.takeIf { it.token===reserved } }
            require(bound!=null && (bound.config==null || (bound.config==config && bound.list==ipRanges))) { "Reserved run snapshot differs" }
        }
        } catch(e:IllegalArgumentException) { if(reserved!=null) releaseRun(reserved); throw e }
        val token = reserved ?: prepareRun() ?: return
        val owner = synchronized(runLock) { runOwner?.takeIf { it.token===token && running.get() } } ?: return
        if(owner.config==null) owner.bind(config,ipRanges)
        samplingReport=null
        owner.running()
        val results = linkedMapOf<String, SpeedTestResult>()
        val started = owner.startedNanos
        val runId = owner.id
        var ledger = RunLedger(runId, config.testCount, emptySet(),owner.parentRunId)
        try {
            if (!beforeNetwork()) token.cancel("no available default network")
            if (token.cancelled) return
            coroutineScope {
                val ownerJob = currentCoroutineContext()[Job]!!
                val watcher = launch(Dispatchers.IO) {
                    try { awaitCancellation() } finally { if (!ownerJob.isActive) token.cancel(DownloadTermination.OWNER_CANCELLED) }
                }
                try { withContext(Dispatchers.IO) {
                    val preparationContext=currentCoroutineContext()
                    val sampled=try { CandidateSampler(ipRanges,config.testCount,config.sampleSeed).sampleChecked {
                        if(!preparationContext.isActive) { token.cancel("OWNER_CANCELLED");preparationContext.ensureActive() }
                        token.remainingMillis(Long.MAX_VALUE)
                        if(token.cancelled) throw CancellationException("sampling stopped")
                    } } catch(e:CancellationException) {
                        if(!preparationContext.isActive) throw e
                        return@withContext
                    }
                    // Sampling still owns preparation cancellation/reporting. Only an explicitly
                    // bound, validated retest keeps the exact text the user confirmed.
                    val candidates=owner.confirmedLiteral?.let { linkedSetOf(it) } ?: sampled.candidates
                    samplingReport=sampled.report
                    val pingResults = mutableListOf<SpeedTestResult>()
                    ledger = RunLedger(runId, config.testCount, candidates,owner.parentRunId)
                    coroutineScope {
                        val queue = kotlinx.coroutines.channels.Channel<String>(minOf(config.testCount,config.maxConcurrentPings * 2))
                        val events = kotlinx.coroutines.channels.Channel<Pair<String, TcpPingTest.PingResult?>>(minOf(config.testCount,config.maxConcurrentPings * 2))
                        val producer = launch {
                            try { for (ip in candidates) { if (token.cancelled) break; queue.send(ip) } }
                            finally { queue.close() }
                        }
                        val workers = List(config.maxConcurrentPings) {
                            launch(Dispatchers.IO) {
                                for (ip in queue) {
                                    if (token.cancelled) break
                                    events.send(ip to null)
                                    events.send(ip to TcpPingTest.ping(ip, config.port, config.pingCount, config.pingTimeout, token))
                                }
                            }
                        }
                        val closer = launch { workers.joinAll(); events.close() }
                        var completed = 0
                        try {
                            for ((ip,ping) in events) {
                                if(ping==null) {
                                    ledger.accept(MeasurementEvent(EventIdentity(runId,ip,"probe:$ip",MeasurementStage.PROBE,0)))
                                    currentRecord=ledger.record(config,results.values.toList(),(deadlineClock.nanoTime()-started).coerceAtLeast(0),token.reason,includeEvents=false)
                                    continue
                                }
                                val probe = ProbeMeasurement(ping.sent, ping.received, if (ping.received == 0) null else ping.avgLatency, ping.cancelled,ping.cancelledAttempts)
                                val result = SpeedTestResult(ip,ping.sent,ping.received,ping.packetLoss,if (ping.received == 0) 0.0 else ping.avgLatency,0.0,"N/A",probe=probe)
                                ledger.accept(MeasurementEvent(EventIdentity(runId,ip,"probe:$ip",MeasurementStage.PROBE,1),probe=probe))
                                if (probe.passed && (config.latencyLimit == 0.0 || ping.avgLatency <= config.latencyLimit)) pingResults.add(result)
                                results[ip] = result
                                currentRecord=ledger.record(config,results.values.toList(),(deadlineClock.nanoTime()-started).coerceAtLeast(0),token.reason,includeEvents=false)
                                onResult(result)
                                onProgress(TestProgress(++completed,candidates.size,ip,"TCP 连接测速"))
                            }
                            producer.join(); workers.joinAll(); closer.join()
                        } catch (e: Throwable) {
                            token.cancel("OWNER_CANCELLED")
                            throw e
                        } finally {
                            withContext(NonCancellable) {
                                producer.cancelAndJoin(); workers.forEach { it.cancel() }; workers.joinAll(); closer.cancelAndJoin()
                                queue.cancel(); events.cancel()
                            }
                        }
                    }
                    if (config.downloadTest && !config.pingOnly) {
                        val selected = pingResults.sorted().take(config.downloadTestCount)
                        val perCandidate = config.byteBudget / selected.size.coerceAtLeast(1)
                        for ((index, result) in selected.withIndex()) {
                            if (token.cancelled || perCandidate < 1) break
                            onProgress(TestProgress(index, selected.size, result.ipAddress, "串行下载测速"))
                            ledger.accept(MeasurementEvent(EventIdentity(runId,result.ipAddress,"download:$index",MeasurementStage.DOWNLOAD,0)))
                            currentRecord=ledger.record(config,results.values.toList(),(deadlineClock.nanoTime()-started).coerceAtLeast(0),token.reason,includeEvents=false)
                            var sequence=0L
                            val measured = DownloadTransport().measure(result.ipAddress, config.downloadUrl, config.port, config.downloadDuration, ByteBudget(perCandidate), token, config.allowHttp) { delta ->
                                ledger.accept(MeasurementEvent(EventIdentity(runId,result.ipAddress,"download:$index",MeasurementStage.DOWNLOAD,++sequence),byteDelta=delta))
                                currentRecord=ledger.record(config,results.values.toList(),(deadlineClock.nanoTime()-started).coerceAtLeast(0),token.reason,includeEvents=false)
                                onProgress(TestProgress(index,selected.size,result.ipAddress,"串行下载测速"))
                            }.let { it.copy(filterPassed = (config.speedLimit == 0.0 || it.speed >= config.speedLimit) && (config.latencyLimit == 0.0 || result.avgLatency <= config.latencyLimit)) }
                            ledger.accept(MeasurementEvent(EventIdentity(runId,result.ipAddress,"download:$index",MeasurementStage.DOWNLOAD,++sequence),download=measured))
                            val updated = result.copy(downloadSpeed = measured.speed, downloadedBytes = measured.bytes, observedPeer = measured.peer, downloadError = measured.error.name, httpStatus = measured.status, download = measured)
                            results[result.ipAddress] = updated; onResult(updated)
                            currentRecord=ledger.record(config,results.values.toList(),(deadlineClock.nanoTime()-started).coerceAtLeast(0),token.reason,includeEvents=false)
                            onProgress(TestProgress(index+1,selected.size,result.ipAddress,"串行下载测速"))
                        }
                    }
                } } finally { withContext(NonCancellable) { watcher.cancelAndJoin() } }
            }
        } finally {
            try {
                owner.finishDeadline()
                owner.terminal()
                if(token.cancelled) ledger.closeCancelled()
                lastRecord = ledger.record(config, results.values.toList(), (deadlineClock.nanoTime()-started).coerceAtLeast(0), token.reason)
                currentRecord=lastRecord
                onComplete(SpeedTestResult.rank(results.values.toList(), false))
            } finally {
                owner.finishDeadline()
                synchronized(runLock) { if (cancellation === token) { owner.release(); running.set(false) } }
            }
        }
    }
    fun stopTest(reason: String = "user stopped") { synchronized(runLock) { runOwner?.stop(reason) ?: cancellation.cancel(reason) } }
    fun releaseRun(token: RunCancellation) = synchronized(runLock) { if (cancellation === token && runOwner?.entered==false) { runOwner?.finishDeadline(); runOwner?.terminal(); runOwner?.release(); running.set(false) } }
    fun isRunning() = running.get()
}
