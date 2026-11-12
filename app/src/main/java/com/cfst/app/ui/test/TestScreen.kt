package com.cfst.app.ui.test

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.ui.components.EmptyState
import com.cfst.app.ui.components.InfoChip
import com.cfst.app.ui.components.ResultCard
import com.cfst.app.ui.components.StatusPill
import com.cfst.app.ui.presentation.presentResult
import com.cfst.app.utils.DataUnits

@Composable fun TestActionBar(vm:SpeedTestViewModel,onResolve:()->Unit) {
    val running by vm.isTesting.collectAsState(); val draft by vm.draftPending.collectAsState()
    val paste by vm.pastePending.collectAsState(); val preview by vm.importPreview.collectAsState()
    val family by vm.useIpv6.collectAsState(); val source by vm.currentListContent.collectAsState()
    val operation by com.cfst.app.documents.DocumentOperationOwner.get(androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application).state.collectAsState()
    val stopping by vm.isStopping.collectAsState()
    val block=operation.let { vm.startBlockReason() }
    Surface(tonalElevation=3.dp) { Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)) {
        if(!running && block!=null) {
            Text(block,style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("start-block"))
            Row {
                TextButton(onClick=onResolve) { Text(if(draft) "继续编辑配置" else "去处理阻断") }
                if(draft) TextButton(onClick=vm::cancelDraft) { Text("放弃未保存配置") }
                else if(paste || preview!=null) TextButton(onClick=vm::cancelImport) { Text("取消待确认列表") }
            }
        }
        Button(onClick={if(running) vm.stopTest() else vm.startTest()},enabled=if(running) !stopping else block==null,modifier=Modifier.fillMaxWidth().testTag("primary-run-action")) {
            Icon(if(running) Icons.Filled.Stop else Icons.Filled.PlayArrow,null,modifier=Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(if(stopping && running) "正在收尾，请等待" else if(running) "停止" else "开始测速")
        }
    } }
}

private fun presetIcon(label:String)=when(label) { "省流"->Icons.Outlined.Savings;"标准"->Icons.Outlined.Speed;else->Icons.Outlined.RocketLaunch }

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class,ExperimentalFoundationApi::class)
@Composable fun TestScreen(vm:SpeedTestViewModel,onExport:()->Unit) {
    val running by vm.isTesting.collectAsState(); val results by vm.results.collectAsState()
    val network by vm.networkLabel.collectAsState(); val source by vm.sourceLabel.collectAsState()
    val family by vm.useIpv6.collectAsState(); val budget by vm.budgetBytes.collectAsState()
    val deadline by vm.runTimeSeconds.collectAsState()
    val sampling by vm.samplingNotice.collectAsState()

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
    val bestP=best?.let { presentResult(it,resultConfig) }
    val heroValue=if(best!=null && bestP!=null) (if(downloadEnabled) bestP.speed else bestP.latency) else "—"
    val parent=vm.currentResultRunId
    LazyColumn(Modifier.fillMaxSize().testTag("test-scroll"),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(if(running) progress.status.ifBlank { "正在准备候选" } else if(best!=null) "最佳合格结果" else "准备测速",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                if(bestP!=null) StatusPill(bestP.bucket)
            }
            AnimatedContent(targetState=heroValue,label="hero-value") { v -> Text(v,style=MaterialTheme.typography.displaySmall) }
            if(best!=null) Text(best.ipAddress,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            else Text(if(groups["短样本"].orEmpty().isNotEmpty()) "样本较短，暂无已验证优选；可增加预算后重新测速" else "尚无合格测量，不显示零速度",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                InfoChip(network,Icons.Outlined.Wifi); InfoChip(if(family) "IPv6" else "IPv4",if(family) Icons.Outlined.Public else Icons.Outlined.Link); InfoChip(source)
            }
            Text("已下载 ${DataUnits.formatBytes(summary?.bodyBytesConsumed ?: 0)}",style=MaterialTheme.typography.bodyMedium)
            if(running) {
                Text(progress.status.ifBlank { "准备阶段" }+if(progress.total>0) " · ${progress.current}/${progress.total}" else "",style=MaterialTheme.typography.bodySmall)
                if(progress.total>0) { val target=(progress.current.toFloat()/progress.total).coerceIn(0f,1f); val animated by animateFloatAsState(targetValue=target,label="run-progress"); LinearProgressIndicator(progress=animated,modifier=Modifier.fillMaxWidth()) }
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } } }
        item { Surface(color=MaterialTheme.colorScheme.secondaryContainer,contentColor=MaterialTheme.colorScheme.onSecondaryContainer,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal=12.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Info,null,modifier=Modifier.size(16.dp))
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text("结果反映当前路由；直连优选前关闭VPN/代理。应用层NO_PROXY不绕过系统VPN/TUN，未识别不等于不存在。",style=MaterialTheme.typography.bodySmall)
                    if(network.endsWith(":VPN")) Text("已识别VPN：当前结果可能反映代理路径，可继续测试。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                }
            }
        } }
        if(migrationPending) item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.NewReleases,null,modifier=Modifier.size(18.dp),tint=MaterialTheme.colorScheme.primary)
                Text("采样已内置",style=MaterialTheme.typography.titleSmall)
            }
            Text("每轮自动采样，无需配置内部参数；已有历史结果保留。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={vm.acknowledgeSamplingMigration()}) { Text("知道了") }
        } } }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("标准模式 · 配置",style=MaterialTheme.typography.titleMedium)
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                InfoChip("候选 ≤ $count"); InfoChip("下载 ≤ $downloads"); InfoChip("初筛并发 $concurrency"); InfoChip(DataUnits.formatBytes(budget)); InfoChip("总时限 ${deadline}s")
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val fontScale=androidx.compose.ui.platform.LocalDensity.current.fontScale
                val compact=maxWidth<400.dp
                val stacked=maxWidth< (240f*fontScale).dp
                val preset:@Composable (String,Modifier)->Unit={ label,modifier ->
                    OutlinedButton(onClick={vm.presetDraft(label);editing=true},enabled=!running,
                        contentPadding=if(compact) PaddingValues(horizontal=8.dp,vertical=8.dp) else ButtonDefaults.ContentPadding,
                        modifier=modifier.heightIn(min=48.dp)) {
                        if(!compact) { Icon(presetIcon(label),null,modifier=Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                        Text(label)
                    }
                }
                if(stacked) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("省流","标准","深入").forEach { preset(it,Modifier.fillMaxWidth()) }
                } else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("省流","标准","深入").forEach { preset(it,Modifier.weight(1f)) }
                }
            }
            OutlinedButton(enabled=!running,onClick={vm.beginDraft();editing=true},modifier=Modifier.fillMaxWidth()) { Text(if(pending) "继续编辑快捷草稿" else "编辑候选/下载/预算") }
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
        item { Column(Modifier.animateContentSize()) {
            TextButton(onClick={details=!details}) {
                Icon(Icons.Outlined.ExpandMore,null,modifier=Modifier.size(18.dp).rotate(if(details) 180f else 0f)); Spacer(Modifier.width(6.dp))
                Text(if(details) "收起计数与说明" else "计数与测量说明")
            }
            if(details) Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("原始结果 ${results.size}；分类互斥，异常包括失败/取消/未达标/未知旧记录。",style=MaterialTheme.typography.bodySmall)
                summary?.let { s -> Text("计划 ${s.plannedCandidates} / 实际生成 ${s.generatedUnique} · 探测完成 ${s.probesCompleted}（通过 ${s.probePassed} / 失败 ${s.probeFailed} / 取消 ${s.probeCancelled} / 未启 ${s.probeNotStarted}）\n下载已尝试 ${s.downloadsStarted} / 完成 ${s.downloadsCompleted}：成功 ${s.downloadSucceeded}（含短样本）、失败 ${s.downloadFailed}、取消 ${s.downloadCancelled}；通过未下载 ${s.probePassedNotDownloaded}",style=MaterialTheme.typography.bodySmall) }
                Text("预算是上限，不保证用尽。只计消费响应体，TLS/协议开销额外。短样本不代表网络失败，失败/取消部分数据不推荐。",style=MaterialTheme.typography.bodySmall)
            }
        } }
        item { Column { for(row in listOf(listOf("优选","短样本"),listOf("仅延迟","异常/未达标"))) Row(Modifier.fillMaxWidth()) { row.forEach { label -> FilterChip(selected=bucket==label,onClick={bucket=label},label={Text("$label ${groups[label].orEmpty().size}")},modifier=Modifier.weight(1f).testTag("bucket-$label")) } } } }
        val visible=com.cfst.app.model.SpeedTestResult.rank(groups[bucket].orEmpty(),!downloadEnabled)
        if(visible.isEmpty()) item { EmptyState(Icons.Outlined.Insights,"此分类暂无结果","切换分类或完成一次测速后，结果会显示在这里。") }
        itemsIndexed(visible,key={_,r->r.ipAddress}) { index,result ->
            Box(Modifier.fillMaxWidth().animateItemPlacement()) {
                ResultCard(result,resultConfig,onRetest=parent?.let { { vm.confirmSingleIpRetest(it,result.ipAddress); Unit } },savedConfig=vm.savedTestConfig(),parentRunId=runs.firstOrNull { it.runId==parent }?.parentRunId,rank=index) { vm.copyIp(result.ipAddress) }
            }
        }
        item { OutlinedButton(onClick=onExport,enabled=!running && results.isNotEmpty(),modifier=Modifier.fillMaxWidth()) { Text("导出 CSV") } }
    }
}
