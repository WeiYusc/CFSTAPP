package com.cfst.app.settings

import com.cfst.app.model.*
import com.cfst.app.speedtest.*
import com.cfst.app.utils.IpRangeParser
import com.google.gson.*
import java.lang.reflect.*

/** One validator for restore and reopen. Gson is only called after shape/range validation. */
object SnapshotRecords {
    private val gson=Gson()
    private val nullable=setOf("config","probe","download","delayMs","stoppedReason","parentRunId")
    private fun shape(node:JsonElement,type:Type) {
        if(type is ParameterizedType) {
            require(node.isJsonArray)
            node.asJsonArray.forEach { shape(it,type.actualTypeArguments[0]) }; return
        }
        val c=type as Class<*>
        require(!node.isJsonNull)
        when {
            c==String::class.java -> require(node.isJsonPrimitive && node.asJsonPrimitive.isString)
            c==Boolean::class.javaPrimitiveType || c==Boolean::class.javaObjectType -> require(node.isJsonPrimitive && node.asJsonPrimitive.isBoolean)
            c==Int::class.javaPrimitiveType || c==Long::class.javaPrimitiveType -> {
                require(node.isJsonPrimitive && node.asJsonPrimitive.isNumber)
                val n=node.asBigDecimal.toBigIntegerExact()
                val lo=if(c==Int::class.javaPrimitiveType) Int.MIN_VALUE.toLong() else Long.MIN_VALUE
                val hi=if(c==Int::class.javaPrimitiveType) Int.MAX_VALUE.toLong() else Long.MAX_VALUE
                require(n>=java.math.BigInteger.valueOf(lo) && n<=java.math.BigInteger.valueOf(hi))
            }
            c==Double::class.javaPrimitiveType || c==Double::class.javaObjectType -> require(node.isJsonPrimitive && node.asJsonPrimitive.isNumber && node.asDouble.isFinite())
            c.isEnum -> require(node.isJsonPrimitive && node.asJsonPrimitive.isString && c.enumConstants.any { (it as Enum<*>).name==node.asString })
            else -> {
                require(node.isJsonObject)
                c.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.forEach { f ->
                    val value=node.asJsonObject[f.name]
                    if(f.name in nullable && (value==null || value.isJsonNull)) return@forEach
                    require(value!=null && !value.isJsonNull) { "Missing/null ${c.simpleName}.${f.name}" }
                    shape(value,f.genericType)
                }
            }
        }
    }
    fun result(node:JsonElement):SpeedTestResult {
        require(node.isJsonObject); val r=node.asJsonObject
        // Pre-v2 compatibility is explicit, never Kotlin defaults bypassed by Gson Unsafe.
        val legacy=!r.has("observedPeer") && !r.has("downloadError")
        if(legacy) {
            val copy=r.deepCopy()
            copy.addProperty("observedPeer","UNKNOWN"); copy.addProperty("downloadError","UNKNOWN")
            if(!copy.has("downloadedBytes")) copy.addProperty("downloadedBytes",0L)
            if(!copy.has("httpStatus")) copy.addProperty("httpStatus",0)
            if(!copy.has("testTime")) copy.addProperty("testTime",0L)
            shape(copy,SpeedTestResult::class.java)
            return validateResult(gson.fromJson(copy,SpeedTestResult::class.java))
        }
        shape(r,SpeedTestResult::class.java)
        return validateResult(gson.fromJson(r,SpeedTestResult::class.java))
    }
    private fun validateProbe(p:ProbeMeasurement) {
        require(p.attempted>=0 && p.received in 0..p.attempted && p.cancelledConnectAttempts in 0..(p.attempted-p.received))
        require(p.delayMs==null || p.delayMs.isFinite() && p.delayMs>=0)
    }
    private fun validateDownload(d:DownloadMeasurement) {
        require(d.bytes>=0 && d.elapsedNanos>0 && d.status in 0..599)
        require(d.peer.isEmpty() || IpRangeParser.literalBytes(d.peer)!=null)
    }
    private fun validateResult(r:SpeedTestResult):SpeedTestResult {
        require(IpRangeParser.literalBytes(r.ipAddress)!=null)
        require(r.packetsSent>=0 && r.packetsReceived in 0..r.packetsSent && r.packetLoss in 0.0..100.0 && r.avgLatency>=0 && r.downloadSpeed>=0 && r.downloadedBytes>=0 && r.testTime>=0 && r.httpStatus in 0..599)
        r.probe?.let(::validateProbe); r.download?.let(::validateDownload); return r
    }
    fun histories(text:String):List<List<SpeedTestResult>> {
        val root=JsonParser.parseString(text); require(root.isJsonArray && root.asJsonArray.size()<=100)
        return root.asJsonArray.map { row -> require(row.isJsonArray && row.asJsonArray.size()<=10000); row.asJsonArray.map(::result) }
    }
    fun runs(text:String):List<RunRecord> {
        val root=JsonParser.parseString(text); require(root.isJsonArray && root.asJsonArray.size()<=100)
        val runIds=mutableSetOf<String>()
        val runs=root.asJsonArray.map { node ->
            val normalized=node.deepCopy()
            if(!normalized.asJsonObject.has("parentRunId")) normalized.asJsonObject.add("parentRunId",JsonNull.INSTANCE)
            normalized.asJsonObject["config"]?.takeIf { !it.isJsonNull }?.asJsonObject?.let { config ->
                if(!config.has("runTimeSeconds")) config.addProperty("runTimeSeconds",120)
                if(!config.has("sampleSeed")) config.addProperty("sampleSeed",0L)
            }
            shape(normalized,RunRecord::class.java)
            val r=gson.fromJson(normalized,RunRecord::class.java)
            require(java.util.UUID.fromString(r.runId).toString()==r.runId) { "Noncanonical run UUID" }
            require(r.schemaVersion==2 && runIds.add(r.runId))
            r.parentRunId?.let { parent ->
                require(java.util.UUID.fromString(parent).toString()==parent && parent!=r.runId)
                require(r.config!=null && r.config.testCount==1 && r.config.downloadTestCount==1)
                require(r.summary.plannedCandidates==1 && r.summary.generatedUnique<=1 && r.summary.downloadsStarted<=1)
                require((r.events.map { it.identity.candidateId }+r.results.map { it.ipAddress }).distinct().size<=1)
            }
            require(r.events.size<=40200 && r.results.size<=10000)
            r.config?.let { c ->
                require(c.runTimeSeconds in 30..600)
                require(c.testCount in 1..10000 && c.pingCount in 1..20 && c.pingTimeout in 1..10000 && c.maxConcurrentPings in 1..16 && c.maxConcurrentDownloads==1)
                require(c.downloadTestCount in 1..100 && c.downloadDuration in 1..60000 && c.port in 1..65535 && c.byteBudget in 1..1073741824L)
                require(c.speedLimit in 0.0..(1e12/1048576) && c.latencyLimit in 0.0..9999.0 && !c.allowHttp)
                val uri=java.net.URI(c.downloadUrl); require(c.downloadUrl.length<=4096 && uri.scheme=="https" && !uri.host.isNullOrEmpty() && uri.userInfo==null && uri.fragment==null)
            }
            val s=r.summary
            val counts=listOf(s.plannedCandidates,s.generatedUnique,s.probeNotStarted,s.probeActive,s.probePassed,s.probeFailed,s.probeCancelled,s.downloadsStarted,s.downloadActive,s.downloadSucceeded,s.downloadFailed,s.downloadCancelled,s.uniqueDownloadCandidates,s.probePassedNotDownloaded,s.recommended)
            require(counts.all { it in 0..10000 } && s.plannedCandidates in 1..10000 && s.generatedUnique<=s.plannedCandidates)
            require(s.generatedUnique.toLong()==s.probeNotStarted.toLong()+s.probeActive+s.probePassed+s.probeFailed+s.probeCancelled)
            require(s.downloadsStarted.toLong()==s.downloadActive.toLong()+s.downloadSucceeded+s.downloadFailed+s.downloadCancelled)
            require(s.uniqueDownloadCandidates<=s.downloadsStarted && s.recommended<=s.downloadSucceeded && s.bodyBytesConsumed>=0 && s.elapsedNanos>=0)
            require(r.config==null || s.bodyBytesConsumed<=r.config.byteBudget)
            val identities=mutableSetOf<EventIdentity>(); var body=0L
            r.events.forEach { e ->
                require(e.identity.runId==r.runId && IpRangeParser.literalBytes(e.identity.candidateId)!=null && e.identity.attemptId.isNotBlank() && e.identity.sequence>=0 && e.byteDelta>=0 && identities.add(e.identity))
                require(!(e.probe!=null && e.download!=null))
                e.probe?.let { require(e.identity.stage==MeasurementStage.PROBE); validateProbe(it) }
                e.download?.let { require(e.identity.stage==MeasurementStage.DOWNLOAD); validateDownload(it) }
                body=Math.addExact(body,e.byteDelta)
            }
            require(body==s.bodyBytesConsumed)
            val terminalProbes=r.events.filter { it.probe!=null }.map { it.probe!! }
            require(r.events.filter { it.probe!=null || it.download!=null }.map { it.identity.attemptId to it.identity.stage }.distinct().size==r.events.count { it.probe!=null || it.download!=null })
            val startedProbeIds=r.events.filter { it.identity.stage==MeasurementStage.PROBE }.map { it.identity.candidateId }.toSet()
            val startedDownloadIds=r.events.filter { it.identity.stage==MeasurementStage.DOWNLOAD }.map { it.identity.attemptId }.toSet()
            val downloadCandidates=r.events.filter { it.identity.stage==MeasurementStage.DOWNLOAD }.map { it.identity.candidateId }.toSet()
            require(s.probeActive==startedProbeIds.size-terminalProbes.size && s.probeNotStarted==s.generatedUnique-startedProbeIds.size)
            require(s.downloadsStarted==startedDownloadIds.size && s.uniqueDownloadCandidates==downloadCandidates.size)
            require(s.probePassedNotDownloaded==(s.probePassed-downloadCandidates.size).coerceAtLeast(0))
            require(terminalProbes.count { it.passed }==s.probePassed && terminalProbes.count { it.cancelled }==s.probeCancelled && terminalProbes.count { !it.passed && !it.cancelled }==s.probeFailed)
            val terminalDownloads=r.events.mapNotNull { it.download }
            val cancelledDownloads=terminalDownloads.count { it.termination in setOf(DownloadTermination.USER_STOP,DownloadTermination.NETWORK_LOST,DownloadTermination.OWNER_CANCELLED,DownloadTermination.RUN_TIME_LIMIT) }
            require(terminalDownloads.count { it.transportValid }==s.downloadSucceeded && cancelledDownloads==s.downloadCancelled && terminalDownloads.size-s.downloadSucceeded-cancelledDownloads==s.downloadFailed)
            require(terminalDownloads.count { it.recommended }==s.recommended)
            r.events.groupBy { it.identity.attemptId to it.identity.stage }.values.forEach { attempt ->
                require(attempt.map { it.identity.candidateId }.distinct().size==1)
                attempt.lastOrNull { it.download!=null }?.download?.let { d ->
                    require(d.bytes==attempt.fold(0L) { total,e -> Math.addExact(total,e.byteDelta) }) { "Attempt body contradiction" }
                }
            }
            r.results.forEach(::validateResult)
            require(r.results.map { it.ipAddress }.distinct().size==r.results.size)
            val latestProbes=r.events.filter { it.probe!=null }.associate { it.identity.candidateId to it.probe }
            val latestDownloads=r.events.filter { it.download!=null }.associate { it.identity.candidateId to it.download }
            r.results.forEach { result ->
                val p=latestProbes[result.ipAddress]
                val d=latestDownloads[result.ipAddress]
                require(result.probe==null || result.probe==p) { "Result probe contradiction" }
                if(p!=null) require(result.packetsSent==p.attempted && result.packetsReceived==p.received && result.packetLoss==(p.loss ?: 100.0) && result.avgLatency==(p.delayMs ?: 0.0))
                require(result.download==d) { "Result must reflect its last terminal download" }
                if(d!=null) require(result.downloadSpeed==d.speed && result.downloadedBytes==d.bytes && result.observedPeer==d.peer && result.httpStatus==d.status && result.downloadError==d.error.name) { "Result download contradiction" }
                else require(result.downloadedBytes==0L && result.downloadSpeed==0.0 && result.httpStatus==0 && result.observedPeer in setOf("","UNKNOWN") && result.downloadError in setOf("NOT_TESTED","UNKNOWN"))
            }
            r
        }
        val byId=runs.associateBy { it.runId }
        runs.forEach { r ->
            r.parentRunId?.let { parentId -> byId[parentId]?.let { parent ->
                val members=parent.results.map { it.ipAddress }.toSet()
                require(r.results.all { it.ipAddress in members } && r.events.all { it.identity.candidateId in members }) { "Retest target not in retained parent results" }
            } }
        }
        // At most 100 retained vertices. Missing/deleted/FIFO parents end a path.
        // Iterative path colouring avoids recursion and visits each retained edge once.
        val finished=mutableSetOf<String>()
        runs.forEach { start ->
            val path=mutableSetOf<String>()
            var id:String?=start.runId
            while(id!=null && id in byId && id !in finished) {
                require(path.add(id)) { "Retest parent cycle" }
                id=byId.getValue(id).parentRunId
            }
            finished.addAll(path)
        }
        return runs
    }
}
