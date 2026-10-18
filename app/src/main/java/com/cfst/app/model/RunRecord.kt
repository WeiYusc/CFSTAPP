package com.cfst.app.model

import com.cfst.app.speedtest.DownloadMeasurement
import com.cfst.app.speedtest.RunCancellation
import com.cfst.app.speedtest.SpeedTestEngine

/** Null delay means no successful handshake; cancelled connects are not ordinary failures. */
data class ProbeMeasurement(val attempted: Int, val received: Int, val delayMs: Double?, val cancelled: Boolean = false, val cancelledConnectAttempts:Int=0) {
    val loss: Double? get() { val completed=attempted-cancelledConnectAttempts; return if(completed==0) null else (completed-received)*100.0/completed }
    val passed: Boolean get() = received > 0 && !cancelled
}
enum class MeasurementStage { PROBE, DOWNLOAD }
data class EventIdentity(val runId: String, val candidateId: String, val attemptId: String, val stage: MeasurementStage, val sequence: Long)
data class MeasurementEvent(val identity: EventIdentity, val probe: ProbeMeasurement? = null, val download: DownloadMeasurement? = null, val byteDelta: Long = 0)
data class RunSummary(
    val plannedCandidates: Int, val generatedUnique: Int, val probeNotStarted: Int, val probeActive: Int,
    val probePassed: Int, val probeFailed: Int, val probeCancelled: Int,
    val downloadsStarted: Int, val downloadActive: Int, val downloadSucceeded: Int, val downloadFailed: Int, val downloadCancelled: Int,
    val uniqueDownloadCandidates: Int, val probePassedNotDownloaded: Int, val recommended: Int,
    val bodyBytesConsumed: Long, val elapsedNanos: Long, val stoppedReason: String?
) {
    val probesStarted get() = probeActive + probePassed + probeFailed + probeCancelled
    val probesCompleted get() = probePassed + probeFailed
    val downloadsCompleted get() = downloadSucceeded + downloadFailed + downloadCancelled
}
data class RunRecord(val runId: String, val config: SpeedTestEngine.TestConfig?, val summary: RunSummary, val events: List<MeasurementEvent>, val results: List<SpeedTestResult>, val schemaVersion: Int = 2, val parentRunId:String? = null)

/** Single collector owns this reducer. Replayed events cannot double-charge bytes. */
class RunLedger(val runId: String, private val planned: Int, private val candidates: Set<String>, private val parentRunId:String? = null) {
    // Per-attempt monotonic watermark bounds memory independently of body chunk count.
    // Collector sequences are ordered; replay/late identities never double-charge.
    private val seen = mutableMapOf<Pair<String,MeasurementStage>,Long>()
    private val attemptBytes = mutableMapOf<String,Long>()
    private val terminalAttempts=mutableSetOf<Pair<String,MeasurementStage>>()
    private val startedProbes=mutableSetOf<String>()
    private val startedDownloads=mutableSetOf<String>()
    private val downloadCandidateByAttempt=mutableMapOf<String,String>()
    private val raw = mutableListOf<MeasurementEvent>()
    private val probes = linkedMapOf<String, ProbeMeasurement>()
    private val downloads = linkedMapOf<String, DownloadMeasurement>()
    private val downloadedCandidates = mutableSetOf<String>()
    private var bytes = 0L
    fun accept(event: MeasurementEvent): Boolean {
        if (event.identity.runId != runId || event.identity.candidateId !in candidates) return false
        val key=event.identity.attemptId to event.identity.stage
        if(event.identity.sequence <= (seen[key] ?: -1L)) return false
        require(event.byteDelta>=0 && event.identity.sequence>=0)
        seen[key]=event.identity.sequence
        if(event.identity.attemptId to event.identity.stage in terminalAttempts) return false
        if((event.probe!=null || event.download!=null) && !terminalAttempts.add(event.identity.attemptId to event.identity.stage)) return false
        if(event.identity.stage==MeasurementStage.PROBE) startedProbes.add(event.identity.candidateId)
        else { startedDownloads.add(event.identity.attemptId); downloadedCandidates.add(event.identity.candidateId); downloadCandidateByAttempt[event.identity.attemptId]=event.identity.candidateId }
        require(event.byteDelta >= 0)
        bytes = Math.addExact(bytes, event.byteDelta)
        if(event.identity.stage==MeasurementStage.DOWNLOAD) attemptBytes[event.identity.attemptId]=Math.addExact(attemptBytes[event.identity.attemptId] ?: 0,event.byteDelta)
        // Body progress is ephemeral; terminal attempts retain full failed/cancelled byte ledgers.
        if(event.download!=null) raw.add(event.copy(byteDelta=attemptBytes.getValue(event.identity.attemptId)))
        else if(event.byteDelta==0L) raw.add(event)
        event.probe?.let { probes[event.identity.candidateId] = it }
        event.download?.let { downloads[event.identity.attemptId] = it; downloadedCandidates.add(event.identity.candidateId) }
        return true
    }
    fun record(config: SpeedTestEngine.TestConfig, results: List<SpeedTestResult>, elapsed: Long, reason: String?, includeEvents:Boolean=true): RunRecord {
        val passed = probes.values.count { it.passed }
        val cancelled = probes.values.count { it.cancelled }
        val failed = probes.size - passed - cancelled
        val cancelledDownloads = downloads.values.count { it.termination in setOf(com.cfst.app.speedtest.DownloadTermination.USER_STOP, com.cfst.app.speedtest.DownloadTermination.NETWORK_LOST, com.cfst.app.speedtest.DownloadTermination.OWNER_CANCELLED, com.cfst.app.speedtest.DownloadTermination.RUN_TIME_LIMIT) }
        val succeeded = downloads.values.count { it.transportValid }
        return RunRecord(runId, config, RunSummary(planned,candidates.size,candidates.size-startedProbes.size,startedProbes.size-probes.size,passed,failed,cancelled,startedDownloads.size,startedDownloads.size-downloads.size,succeeded,downloads.size-succeeded-cancelledDownloads,cancelledDownloads,downloadedCandidates.size,(passed-downloadedCandidates.size).coerceAtLeast(0),downloads.values.count { it.recommended },bytes,elapsed,reason),if(includeEvents) raw.toList() else emptyList(),results.toList(),parentRunId=parentRunId)
    }
    fun closeCancelled() {
        for(ip in startedProbes.toList()) if(ip !in probes) accept(MeasurementEvent(EventIdentity(runId,ip,"probe:$ip",MeasurementStage.PROBE,Long.MAX_VALUE),probe=ProbeMeasurement(0,0,null,true)))
        for(attempt in startedDownloads.toList()) if(attempt !in downloads) {
            val ip=downloadCandidateByAttempt.getValue(attempt)
            val consumed=attemptBytes[attempt] ?: 0L
            accept(MeasurementEvent(EventIdentity(runId,ip,attempt,MeasurementStage.DOWNLOAD,Long.MAX_VALUE),download=DownloadMeasurement(bytes=consumed,error=com.cfst.app.speedtest.DownloadError.CANCELLED)))
        }
    }
}
