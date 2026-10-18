package com.cfst.app.ui.test

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.ui.components.ResultCard
import com.cfst.app.ui.presentation.presentResult
import com.cfst.app.utils.DataUnits

@Composable fun TestActionBar(vm:SpeedTestViewModel,onResolve:()->Unit) {
    val running by vm.isTesting.collectAsState(); val draft by vm.draftPending.collectAsState()
    val paste by vm.pastePending.collectAsState(); val preview by vm.importPreview.collectAsState()
    val family by vm.useIpv6.collectAsState(); val source by vm.currentListContent.collectAsState()
    val operation by com.cfst.app.documents.DocumentOperationOwner.get(androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application).state.collectAsState()
    val stopping by vm.isStopping.collectAsState()
    val block=operation.let { vm.startBlockReason() }
    Surface(shadowElevation=4.dp) { Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)) {
        if(!running && block!=null) {
            Text(block,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("start-block"))
            Row {
                TextButton(onClick=onResolve) { Text(if(draft) "继续编辑配置" else "去处理阻断") }
                if(draft) TextButton(onClick=vm::cancelDraft) { Text("放弃未保存配置") }
                else if(paste || preview!=null) TextButton(onClick=vm::cancelImport) { Text("取消待确认列表") }
            }
        }
        Button(onClick={if(running) vm.stopTest() else vm.startTest()},enabled=if(running) !stopping else block==null,modifier=Modifier.fillMaxWidth().testTag("primary-run-action")) {
            Text(if(stopping && running) "正在收尾，请等待" else if(running) "停止" else "开始测速")
        }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TestScreen(vm:SpeedTestViewModel,onExport:()->Unit) {
    val running by vm.isTesting.collectAsState(); val results by vm.results.collectAsState()
    val network by vm.networkLabel.collectAsState(); val source by vm.sourceLabel.collectAsState()
    val family by vm.useIpv6.collectAsState(); val budget by vm.budgetBytes.collectAsState()
    val deadline by vm.runTimeSeconds.collectAsState(); val seed by vm.sampleSeed.collectAsState()
    val sampling by vm.samplingNotice.collectAsState()
    val randomizeSeed by vm.randomizeSampleSeed.collectAsState()
    val migrationPending by vm.samplingMigrationNoticePending.collectAsState()
    val count by vm.testCount.collectAsState(); val downloads by vm.downloadTestCount.collectAsState()
    val concurrency by vm.maxConcurrentPings.collectAsState(); val pending by vm.draftPending.collectAsState()
    val progress by vm.progress.collectAsState(); val status by vm.statusMessage.collectAsState()
    val summary by vm.runSummary.collectAsState(); val downloadEnabled by vm.resultDownloadEnabled.collectAsState()
    val resultConfig by vm.resultConfig.collectAsState()
    val runs by vm.runRecords.collectAsState()
    var bucket by rememberSaveable { mutableStateOf("优选") }; var details by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val quick by vm.editableDraft.collectAsState()
    val groups=results.groupBy { presentResult(it,resultConfig).bucket }
    val best=vm.getBestIp()
    LazyColumn(Modifier.fillMaxSize().testTag("test-scroll"),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        item { Text("$network · ${if(family) "IPv6" else "IPv4"} · $source",style=MaterialTheme.typography.bodyMedium) }
        item { Text("结果反映当前路由；直连优选前关闭VPN/代理。应用层NO_PROXY不绕过系统VPN/TUN，未识别不等于不存在。",style=MaterialTheme.typography.bodySmall)
            if(network.endsWith(":VPN")) Text("已识别VPN：当前结果可能反映代理路径，可继续测试。",color=MaterialTheme.colorScheme.error)
        }
        if(migrationPending) item { Card { Column(Modifier.padding(16.dp)) {
            Text("默认已改每轮探索；原固定seed保留，可在高级设置切回。")
            TextButton(onClick={vm.acknowledgeSamplingMigration()}) { Text("知道了") }
        } } }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(running) progress.status.ifBlank { "正在准备候选" } else if(best!=null) "最佳合格结果" else "准备测速",style=MaterialTheme.typography.titleMedium)
            Text(best?.let { if(downloadEnabled) presentResult(it,resultConfig).speed else presentResult(it,resultConfig).latency } ?: "—",style=MaterialTheme.typography.displaySmall)
            if(best!=null) Text(best.ipAddress)
            else Text(if(groups["短样本"].orEmpty().isNotEmpty()) "样本较短，暂无已验证优选；可增加预算后重新测速" else "尚无合格测量，不显示零速度")
            Text("已下载 ${DataUnits.formatBytes(summary?.bodyBytesConsumed ?: 0)}",style=MaterialTheme.typography.bodyMedium)
            if(running) {
                Text(progress.status.ifBlank { "准备阶段" }+if(progress.total>0) " · ${progress.current}/${progress.total}" else "")
                if(progress.total>0) LinearProgressIndicator(progress=(progress.current.toFloat()/progress.total).coerceIn(0f,1f),modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } } }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("标准模式 · 配置",style=MaterialTheme.typography.titleMedium)
            Text("候选 ≤ $count · 下载 ≤ $downloads · 初筛并发 $concurrency · ${DataUnits.formatBytes(budget)} · 总时限 ${deadline}s · seed $seed")
            Text(if(randomizeSeed) "新运行：探索seed启动时生成" else "新运行：固定seed $seed")
            resultConfig?.sampleSeed?.let { Text("当前结果原运行有效seed $it") }
            Row(Modifier.fillMaxWidth()) { for(label in listOf("省流","标准","深入")) TextButton(enabled=!running,onClick={vm.presetDraft(label);editing=true},modifier=Modifier.weight(1f)) { Text(label) } }
            OutlinedButton(enabled=!running,onClick={vm.beginDraft();editing=true}) { Text(if(pending) "继续编辑快捷草稿" else "编辑候选/下载/预算") }
            if(editing) quick?.let { d ->
                fun change(next:com.cfst.app.settings.SettingsDraft) { vm.updateDraft(next) }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val fields:@Composable ()->Unit={
                        OutlinedTextField(d.candidates,{change(d.copy(candidates=it))},enabled=!running,label={Text("候选上限")},modifier=Modifier.fillMaxWidth().testTag("quick-candidates"))
                        OutlinedTextField(d.downloads,{change(d.copy(downloads=it))},enabled=!running,label={Text("下载候选上限")},modifier=Modifier.fillMaxWidth().testTag("quick-downloads"))
                    }
                    if(maxWidth>=480.dp) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Column(Modifier.weight(1f)) { fields() }; OutlinedTextField(d.budgetMb,{change(d.copy(budgetMb=it,budgetDirty=true))},enabled=!running,label={Text("预算 MB")},modifier=Modifier.weight(1f).testTag("quick-budget")) }
                    else Column { fields(); OutlinedTextField(d.budgetMb,{change(d.copy(budgetMb=it,budgetDirty=true))},enabled=!running,label={Text("预算 MB")},modifier=Modifier.fillMaxWidth().testTag("quick-budget")) }
                }
                Text("预设/修改仅为草稿，确认后一次保存",style=MaterialTheme.typography.bodySmall)
                Button(enabled=!running,modifier=Modifier.testTag("quick-save"),onClick={if(vm.saveDraft(d)) editing=false}) { Text("确认快捷设置") }
                TextButton(onClick={vm.cancelDraft();editing=false}) { Text("取消快捷草稿") }
            }
        } } }
        item { if(sampling.isNotBlank()) Text(sampling,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("sampling-notice")) }
        item { if(status.isNotBlank()) Text(status.take(2048),style=MaterialTheme.typography.bodySmall) }
        item { TextButton(onClick={details=!details}) { Text(if(details) "收起计数与说明" else "计数与测量说明") }
            if(details) Column {
                Text("原始结果 ${results.size}；分类互斥，异常包括失败/取消/未达标/未知旧记录。")
                summary?.let { s -> Text("计划 ${s.plannedCandidates} / 实际生成 ${s.generatedUnique} · 探测完成 ${s.probesCompleted}（通过 ${s.probePassed} / 失败 ${s.probeFailed} / 取消 ${s.probeCancelled} / 未启 ${s.probeNotStarted}）\n下载已尝试 ${s.downloadsStarted} / 完成 ${s.downloadsCompleted}：成功 ${s.downloadSucceeded}（含短样本）、失败 ${s.downloadFailed}、取消 ${s.downloadCancelled}；通过未下载 ${s.probePassedNotDownloaded}") }
                Text("预算是上限，不保证用尽。只计消费响应体，TLS/协议开销额外。短样本不代表网络失败，失败/取消部分数据不推荐。")
            }
        }
        item { Column { for(row in listOf(listOf("优选","短样本"),listOf("仅延迟","异常/未达标"))) Row(Modifier.fillMaxWidth()) { row.forEach { label -> FilterChip(selected=bucket==label,onClick={bucket=label},label={Text("$label ${groups[label].orEmpty().size}")},modifier=Modifier.weight(1f).testTag("bucket-$label")) } } } }
        val visible=com.cfst.app.model.SpeedTestResult.rank(groups[bucket].orEmpty(),!downloadEnabled)
        if(visible.isEmpty()) item { Text("此分类暂无结果",style=MaterialTheme.typography.bodyMedium) }
        items(visible,key={it.ipAddress}) { result ->
            val parent=vm.currentResultRunId
            ResultCard(result,resultConfig,onRetest=parent?.let { { vm.confirmSingleIpRetest(it,result.ipAddress); Unit } },savedConfig=vm.savedTestConfig(),parentRunId=runs.firstOrNull { it.runId==parent }?.parentRunId,randomizeSeed=randomizeSeed) { vm.copyIp(result.ipAddress) }
        }
        item { OutlinedButton(onClick=onExport,enabled=!running && results.isNotEmpty(),modifier=Modifier.fillMaxWidth()) { Text("导出 CSV") } }
    }
}
