package com.cfst.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cfst.app.viewmodel.SpeedTestViewModel
import com.cfst.app.settings.SettingsDraft

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AdvancedSettingsScreen(vm:SpeedTestViewModel,onDone:()->Unit) {
    var draft by remember { mutableStateOf(vm.beginDraft()) }; val status by vm.statusMessage.collectAsState()
    val pending by vm.draftPending.collectAsState(); val running by vm.isTesting.collectAsState()
    val migrationPending by vm.samplingMigrationNoticePending.collectAsState()
    var expandedProbe by rememberSaveable { mutableStateOf(true) }
    var expandedResource by rememberSaveable { mutableStateOf(false) }
    var expandedFilter by rememberSaveable { mutableStateOf(false) }
    var expandedEndpoint by rememberSaveable { mutableStateOf(false) }
    fun change(next:SettingsDraft) { draft=next;vm.updateDraft(next) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(pending) "配置草稿 · 尚未保存" else "查看已保存配置 · 未修改不阻断测速",style=MaterialTheme.typography.titleMedium)
            Text(status.take(512),style=MaterialTheme.typography.bodySmall)
            if(migrationPending) Card { Column(Modifier.padding(16.dp)) {
                Text("默认已改每轮探索；原固定seed保留，可切回固定复现。")
                TextButton(onClick={vm.acknowledgeSamplingMigration()}) { Text("知道了") }
            } }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={expandedProbe=!expandedProbe}) { Text("探测 ${if(expandedProbe) "收起" else "展开"}") }
                Text("候选 ${draft.candidates} · 重复 ${draft.repetitions} · 并发 ${draft.concurrency} · 总时限 ${draft.runTimeSeconds}s")
                if(expandedProbe) {
                    DraftInput(draft.candidates,{change(draft.copy(candidates=it))},"候选数 1..10000","candidates-input",integerError(draft.candidates,1..10000),enabled=!running)
                    DraftInput(draft.repetitions,{change(draft.copy(repetitions=it))},"同候选串行重复 1..20","repetitions-input",integerError(draft.repetitions,1..20),enabled=!running)
                    Row(Modifier.fillMaxWidth()) { for(n in listOf(1,4,8,16)) FilterChip(selected=draft.concurrency==n.toString(),onClick={change(draft.copy(concurrency=n.toString()))},enabled=!running,label={Text("$n")},modifier=Modifier.weight(1f)) }
                    Text("初筛并发 ${draft.concurrency}",style=MaterialTheme.typography.bodySmall)
                }
            } }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={expandedResource=!expandedResource}) { Text("资源 ${if(expandedResource) "收起" else "展开"}") }
                Text("下载 ${if(draft.enableDownload) "开启" else "关闭"} · 预算 ${draft.budgetMb} MB")
                if(expandedResource) {
                    DraftInput(draft.runTimeSeconds,{change(draft.copy(runTimeSeconds=it))},"标准run总秒数 30..600","run-deadline-input",integerError(draft.runTimeSeconds,30..600),enabled=!running)
                    Row { Text("每轮探索（非密码学随机seed）",Modifier.weight(1f));Switch(draft.randomizeSampleSeed,{change(draft.copy(randomizeSampleSeed=it))},enabled=!running) }
                    DraftInput(draft.sampleSeed,{change(draft.copy(sampleSeed=it))},"固定复现seed（64位整数）","sample-seed-input",if(draft.sampleSeed.toLongOrNull()==null) "须为64位整数" else null,enabled=!running && !draft.randomizeSampleSeed)
                    Text("探索seed只在确认启动时生成；固定seed仅同算法版本复现。")
                    Text("IPv4 literal/小段优先、去重/24分层；IPv6仅实际literal或≤256成员段，巨大前缀不制造节点。",style=MaterialTheme.typography.bodySmall)
                    Text("下载始终串行",style=MaterialTheme.typography.bodySmall)
                    Row { Text("启用下载",Modifier.weight(1f)); Switch(draft.enableDownload,{change(draft.copy(enableDownload=it))},enabled=!running) }
                    DraftInput(draft.downloads,{change(draft.copy(downloads=it))},"下载候选尝试上限 1..100","downloads-input",integerError(draft.downloads,1..100),enabled=!running)
                    DraftInput(draft.seconds,{change(draft.copy(seconds=it))},"每候选秒 1..60","seconds-input",integerError(draft.seconds,1..60),enabled=!running)
                    DraftInput(draft.budgetMb,{change(draft.copy(budgetMb=it,budgetDirty=true))},"下载预算 MB（十进制，最多两位小数）","budget-input",if(draft.budgetDirty) runCatching { com.cfst.app.utils.DataUnits.parseBudgetMb(draft.budgetMb);null }.getOrElse { "预算须为有效 MB，最多两位小数" } else null,KeyboardType.Decimal,!running)
                    Text("未编辑预算保留精确原bytes，不将显示四舍五入写回。",style=MaterialTheme.typography.bodySmall)
                }
            } }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={expandedFilter=!expandedFilter}) { Text("筛选 ${if(expandedFilter) "收起" else "展开"}") }
                Text("最低速度 ${draft.speedMb} MB/s · 最大延迟 ${draft.latency} ms")
                if(expandedFilter) {
                    DraftInput(draft.speedMb,{change(draft.copy(speedMb=it,speedDirty=true))},"最低速度 MB/s，0不限","speed-input",decimalError(draft.speedMb,1000000.0),KeyboardType.Decimal,!running)
                    DraftInput(draft.latency,{change(draft.copy(latency=it))},"最大延迟 ms，0不限","latency-input",decimalError(draft.latency,9999.0),KeyboardType.Decimal,!running)
                }
            } }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={expandedEndpoint=!expandedEndpoint}) { Text("安全端点 ${if(expandedEndpoint) "收起" else "展开"}") }
                Text("端口 ${draft.port} · 系统 CA / SNI")
                if(expandedEndpoint) {
                    DraftInput(draft.url,{change(draft.copy(url=it))},"HTTPS 下载资源 URL","url-input",urlError(draft.url),KeyboardType.Uri,!running)
                    DraftInput(draft.port,{change(draft.copy(port=it))},"目标端口（覆盖URL端口）","port-input",integerError(draft.port,1..65535),enabled=!running)
                    DraftInput(draft.mirror,{change(draft.copy(mirror=it))},"仅列表更新镜像 URL","mirror-input",if(draft.mirror.isEmpty()) null else urlError(draft.mirror),KeyboardType.Uri,!running)
                    Row { Text("使用列表镜像",Modifier.weight(1f)); Switch(draft.useMirror,{change(draft.copy(useMirror=it))},enabled=!running) }
                }
            } }
            Text("帮助 / 关于 · CFST Local 1.2.1",style=MaterialTheme.typography.titleMedium)
            Text("literal DNS、实际Peer/Port核验、NO_PROXY、禁止重定向。吞吐含连接/TLS总耗时；不是峰值或运营商wire-byte账单。",style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }
        Surface(shadowElevation=4.dp) { Column(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
            Button(onClick={if(vm.saveDraft(draft)) onDone()},enabled=!running,modifier=Modifier.fillMaxWidth().testTag("settings-save")) { Text("保存设置") }
            TextButton(onClick={vm.cancelDraft();onDone()},modifier=Modifier.fillMaxWidth()) { Text("取消草稿") }
        } }
    }
}
private fun integerError(text:String,range:IntRange):String?=if(text.toIntOrNull() in range) null else "请输入 ${range.first}..${range.last} 整数"
private fun decimalError(text:String,max:Double):String? {
    val value=text.toDoubleOrNull()
    return if(value!=null && value.isFinite() && value in 0.0..max) null else "请输入 0..$max 有限数字"
}
private fun urlError(text:String):String? {
    val uri=runCatching { java.net.URI(text) }.getOrNull()
    return if(text.length<=4096 && uri?.scheme=="https" && !uri.host.isNullOrEmpty() && uri.userInfo==null && uri.fragment==null) null else "需要安全 HTTPS URL，无身份信息或 fragment"
}
@Composable private fun DraftInput(value:String,onChange:(String)->Unit,label:String,tag:String,error:String?,type:KeyboardType=KeyboardType.Number,enabled:Boolean=true) {
    OutlinedTextField(value,onChange,label={Text(label)},isError=error!=null,supportingText={if(error!=null) Text(error)},keyboardOptions=KeyboardOptions(keyboardType=type,imeAction=ImeAction.Next),enabled=enabled,singleLine=true,modifier=Modifier.fillMaxWidth().testTag(tag))
}
