package com.cfst.app.ui.history

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.ui.components.ResultCard
import com.cfst.app.ui.presentation.presentResult
import com.cfst.app.utils.DataUnits
import com.cfst.app.documents.DocumentOperationOwner

@Composable fun HistoryScreen(vm:SpeedTestViewModel,onStateExport:()->Unit,onStateRestore:()->Unit,onRetry:()->Unit,onExport:(String)->Unit) {
    val records by vm.historyRecords.collectAsState(); val status by vm.statusMessage.collectAsState(); val runs by vm.runRecords.collectAsState()
    val running by vm.isTesting.collectAsState()

    val owner=DocumentOperationOwner.get(LocalContext.current.applicationContext as Application); val operation by owner.state.collectAsState()
    val available=operation.let { !running && !owner.blocksStart() }
    val identities=vm.historyIdentities()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }; var clear by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("备份与恢复",style=MaterialTheme.typography.titleMedium)
            Text("完整状态包含设置、来源、选择和历史；结果 CSV 仅为测量数据。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=onStateExport,enabled=available) { Text("导出完整状态") }
            TextButton(onClick=onStateRestore,enabled=available) { Text("恢复完整状态") }
            if(!available) Text("运行或文档操作未结束，不能开启另一操作",style=MaterialTheme.typography.bodySmall)
        } } }
        item { Text("${records.size} 次记录",style=MaterialTheme.typography.titleMedium)
            Text("旧记录/配置未知；不认领未知归属，不伪造新推荐。",style=MaterialTheme.typography.bodySmall)
            if(status.isNotBlank()) Text(status.take(2048),style=MaterialTheme.typography.bodySmall)
            if(records.isNotEmpty()) TextButton(onClick={clear=true},enabled=available) { Text("清空历史") }
        }
        if(records.isEmpty()) item { Text("暂无历史。完成的运行在原始账本保存成功后出现在这里。") }
        records.forEachIndexed { index,record ->
            val id=identities.getOrNull(index) ?: return@forEachIndexed
            val run=runs.firstOrNull { it.runId==id }
            val config=run?.config
            item(key=id) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(if(run==null) "旧记录 · 归属未确定" else "原始运行",style=MaterialTheme.typography.titleMedium)
                run?.parentRunId?.let { Text("单 IP 复测 · 来源运行 $it",style=MaterialTheme.typography.bodySmall) }
                Text(record.firstOrNull()?.getTestTimeFormatted()?.let { "结果测量时间 $it" } ?: "时间未知",style=MaterialTheme.typography.bodySmall)
                Text("${record.size} 条原始结果 · 优选 ${record.count { presentResult(it,config).bucket=="优选" }} · 短样本 ${record.count { presentResult(it,config).bucket=="短样本" }}")
                Text("响应体 ${DataUnits.formatBytes(run?.summary?.bodyBytesConsumed ?: record.sumOf { it.downloadedBytes })}")
                Text(run?.config?.let { "候选 ${it.testCount} / 初筛并发 ${it.maxConcurrentPings} / 下载 ≤ ${it.downloadTestCount}" } ?: "配置未知",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={expanded=if(expanded==id) null else id}) { Text(if(expanded==id) "收起记录详情" else "查看记录详情") }
                if(expanded==id) {
                    Text(id,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={onExport(id)},enabled=available) { Text("导出该记录 CSV") }
                    TextButton(onClick={deleteId=id},enabled=available) { Text("删除记录") }
                    run?.let { Text("探测通过 ${it.summary.probePassed} / 失败 ${it.summary.probeFailed} / 取消 ${it.summary.probeCancelled} · 下载成功 ${it.summary.downloadSucceeded} / 失败 ${it.summary.downloadFailed} / 取消 ${it.summary.downloadCancelled} · 原始事件 ${it.events.size}",style=MaterialTheme.typography.bodySmall) }
                }
            } } }
            if(expanded==id) record.forEachIndexed { row,result -> item(key="$id:$row") {
                ResultCard(result,config,onRetest=run?.let { { vm.confirmSingleIpRetest(it.runId,result.ipAddress); Unit } },savedConfig=vm.savedTestConfig(),parentRunId=run?.parentRunId) { vm.copyIp(result.ipAddress) }
            } }
        }
    }
    if(deleteId!=null) AlertDialog(onDismissRequest={deleteId=null},title={Text("删除这次记录？")},text={Text("记录身份：$deleteId\n删除原始记录及对应账本，不可撤销。其他记录不受影响。")},confirmButton={TextButton(onClick={if(available) vm.deleteHistoryIdentity(deleteId!!);deleteId=null}) { Text("确认删除记录") }},dismissButton={TextButton(onClick={deleteId=null}) { Text("取消删除") }})
    if(clear) AlertDialog(onDismissRequest={clear=false},title={Text("清空全部历史？")},text={Text("所有历史与原始运行账本将删除，不可撤销。请先导出完整状态备份。")},confirmButton={TextButton(onClick={if(available) vm.clearAllHistoryRecords();clear=false}) { Text("确认清空历史") }},dismissButton={TextButton(onClick={clear=false}) { Text("取消清空") }})
}
