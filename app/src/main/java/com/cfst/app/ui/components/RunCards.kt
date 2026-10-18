package com.cfst.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cfst.app.model.SpeedTestResult
import com.cfst.app.ui.presentation.presentResult
import com.cfst.app.ui.presentation.terminationLabel
import com.cfst.app.utils.DataUnits

@Composable fun ResultCard(result:SpeedTestResult,config:com.cfst.app.speedtest.SpeedTestEngine.TestConfig?=null,onRetest:(()->Unit)?=null,savedConfig:com.cfst.app.speedtest.SpeedTestEngine.TestConfig?=null,parentRunId:String?=null,onCopy:()->Unit) {
    val p=presentResult(result,config)
    var detail by remember { mutableStateOf(false) }
    var confirmRetest by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(result.ipAddress,style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
            IconButton(onClick=onCopy,modifier=Modifier.testTag("copy-${result.ipAddress}")) { Icon(Icons.Outlined.ContentCopy,contentDescription="复制 IP ${result.ipAddress}") }
        }
        Text(p.status,style=MaterialTheme.typography.labelLarge,color=if(p.bucket=="异常/未达标") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Text(p.speed,style=MaterialTheme.typography.headlineSmall)
        Text("TCP 延迟 ${p.latency} · TCP 失败率 ${p.tcpFailure}",style=MaterialTheme.typography.bodyMedium)
        Text("响应体 ${p.bytes}",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={detail=true},modifier=Modifier.testTag("detail-${result.ipAddress}")) { Text("测量详情") }
    } }
    if(detail) AlertDialog(onDismissRequest={detail=false},title={Text("测量详情")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(result.ipAddress); Text(p.status)
            Text("TCP 连接尝试 ${result.probe?.attempted ?: result.packetsSent} / 成功 ${result.probe?.received ?: result.packetsReceived}；不是 ICMP 丢包")
            Text("测量时间 ${result.getTestTimeFormatted()}")
            val d=result.download
            if(d!=null) {
                Text("${terminationLabel(d.termination)} · ${d.termination.name}")
                Text("样本 ${d.sampleQuality} · ${(d.elapsedNanos/1000000)} ms · ${DataUnits.formatBytes(d.bytes)}")
                Text("实际 Peer ${d.peer.ifBlank { "未知" }} · HTTP ${d.status.takeIf { it>0 } ?: "未知"}")
                Text("安全核验 ${d.safetyVerified} · 筛选通过 ${d.filterPassed}")
            } else Text(if(config==null) "未测下载；旧记录的配置与质量未知。" else "未测下载；按原运行延迟阈值解释。")
            config?.let { Text("原运行延迟阈值 ${it.latencyLimit}ms（0 不限）") }
            Text("速度含连接/TLS总耗时，不是峰值；仅计消费响应体，协议开销额外。",style=MaterialTheme.typography.bodySmall)
            parentRunId?.let { Text("复测来源运行 $it",style=MaterialTheme.typography.bodySmall) }
            if(onRetest!=null && savedConfig!=null) {
                Text("单 IP 复测使用当前已保存配置，不推断旧配置；独立新预算，原结果保留。")
                RetestConfigSummary(savedConfig)
                TextButton(onClick={confirmRetest=true},modifier=Modifier.testTag("retest-${result.ipAddress}")) { Text("单 IP 复测…") }
            }
        }
    },confirmButton={TextButton(onClick=onCopy) { Text("复制 IP") }},dismissButton={TextButton(onClick={detail=false}) { Text("关闭详情") }})
    if(confirmRetest && onRetest!=null && savedConfig!=null) AlertDialog(onDismissRequest={confirmRetest=false},title={Text("确认单 IP 复测")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("目标 ${result.ipAddress} · 候选 1 / 下载 ≤ 1")
            RetestConfigSummary(savedConfig)
            Text("独立新预算，原结果保留。只有确认后才会开始新运行；不会自动复测。")
        }
    },confirmButton={TextButton(onClick={confirmRetest=false;detail=false;onRetest()}) { Text("确认并开始新运行") }},dismissButton={TextButton(onClick={confirmRetest=false}) { Text("取消复测") }})
}

@Composable private fun RetestConfigSummary(config:com.cfst.app.speedtest.SpeedTestEngine.TestConfig) {
    Text("当前已保存：${if(config.downloadTest) "TCP + 下载" else "仅 TCP"} · 响应体预算 ${DataUnits.formatBytes(config.byteBudget)} · 单次下载 ${config.downloadDuration/1000}s · 总时限 ${config.runTimeSeconds}s")
    Text("TCP 重复 ${config.pingCount} / 超时 ${config.pingTimeout}ms / 并发 ${config.maxConcurrentPings} · 端口 ${config.port}")
    Text("仅复测已确认的单个 IP，目标不变。")
    Text("HTTPS ${config.downloadUrl} · 筛选 ≥ ${DataUnits.formatBytes((config.speedLimit*1048576).toLong())}/s / 延迟 ≤ ${config.latencyLimit}ms（0 不限制）")
    Text("串行下载；TLS / Peer 核验，不跟随重定向；应用层不使用HTTP代理，仍受系统VPN/当前路由影响；TLS/Peer核验不证明直连。失败或取消部分样本不推荐。",style=MaterialTheme.typography.bodySmall)
}
