package com.cfst.app.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cfst.app.model.SpeedTestResult
import com.cfst.app.speedtest.SpeedTestEngine
import com.cfst.app.utils.IpRangeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 测速 ViewModel
 */
class SpeedTestViewModel internal constructor(private val application: Application,private val seedSource:()->Long,deadlineClock:com.cfst.app.speedtest.RunDeadlineClock) : ViewModel() {
    internal constructor(application:Application,seedSource:()->Long):this(application,seedSource,com.cfst.app.speedtest.MonotonicRunClock)
    constructor(application:Application):this(application,java.util.Random()::nextLong,com.cfst.app.speedtest.MonotonicRunClock)
    
    // 测速引擎
    private val engine = SpeedTestEngine(deadlineClock)
    private val settings: android.content.SharedPreferences = com.cfst.app.settings.AppStateStore.get(application)

    private val _runTimeSeconds=MutableStateFlow(settings.getInt("runTimeSeconds",120))
    val runTimeSeconds=_runTimeSeconds.asStateFlow()
    private val _sampleSeed=MutableStateFlow(settings.getLong("sampleSeed",0L))
    val sampleSeed=_sampleSeed.asStateFlow()
    private val _randomizeSampleSeed=MutableStateFlow(settings.getBoolean("randomizeSampleSeed",true))
    val randomizeSampleSeed=_randomizeSampleSeed.asStateFlow()
    private val _samplingMigrationNoticePending=MutableStateFlow(settings.getBoolean("samplingMigrationNoticePending",false))
    val samplingMigrationNoticePending=_samplingMigrationNoticePending.asStateFlow()
    fun acknowledgeSamplingMigration():Boolean {
        if(!settings.edit().putBoolean("samplingMigrationNoticePending",false).commit()) { _statusMessage.value="确认保存失败，请重试";return false }
        _samplingMigrationNoticePending.value=false;return true
    }
    private val _samplingNotice=MutableStateFlow("")
    val samplingNotice=_samplingNotice.asStateFlow()
    private val _downloadUrl = MutableStateFlow(settings.getString("downloadUrl", SpeedTestEngine.TestConfig().downloadUrl)!!)
    val downloadUrl = _downloadUrl.asStateFlow()
    private val _downloadPort = MutableStateFlow(settings.getInt("downloadPort", 443))
    val downloadPort = _downloadPort.asStateFlow()
    private val _budgetMiB = MutableStateFlow(settings.getInt("budgetMiB", 3))
    val budgetMiB = _budgetMiB.asStateFlow()
    private val _budgetBytes = MutableStateFlow(settings.getLong("budgetBytes",_budgetMiB.value*1048576L))
    val budgetBytes = _budgetBytes.asStateFlow()
    fun updateDownloadUrl(value: String) { check(settings.edit().putString("downloadUrl", value).commit()) { "保存失败" }; _downloadUrl.value = value }
    fun updateDownloadPort(value: Int) { check(settings.edit().putInt("downloadPort", value).commit()) { "保存失败" }; _downloadPort.value = value }
    fun updateBudgetMiB(value: Int) { check(settings.edit().putInt("budgetMiB", value).commit()) { "保存失败" }; _budgetMiB.value = value }

    // 内置 IPv4 IP 范围
    private val builtInIpv4Ranges = """173.245.48.0/20
103.21.244.0/22
103.22.200.0/22
103.31.4.0/22
141.101.64.0/18
108.162.192.0/18
190.93.240.0/20
188.114.96.0/20
197.234.240.0/22
198.41.128.0/17
162.158.0.0/15
104.16.0.0/12
172.64.0.0/17
172.64.128.0/18
172.64.192.0/19
172.64.224.0/22
172.64.229.0/24
172.64.230.0/23
172.64.232.0/21
172.64.240.0/21
172.64.248.0/21
172.65.0.0/16
172.66.0.0/16
172.67.0.0/16
131.0.72.0/22""".trimIndent()
    
    // 内置 IPv6 IP 范围
    private val builtInIpv6Ranges = """2400:cb00:2049::/48
2400:cb00:f00e::/48
2606:4700::/32
2606:4700:10::/48
2606:4700:130::/48
2606:4700:3000::/48
2606:4700:3001::/48
2606:4700:3002::/48
2606:4700:3003::/48
2606:4700:3004::/48
2606:4700:3005::/48
2606:4700:3006::/48
2606:4700:3007::/48
2606:4700:3008::/48
2606:4700:3009::/48
2606:4700:3010::/48
2606:4700:3011::/48
2606:4700:3012::/48
2606:4700:3013::/48
2606:4700:3014::/48
2606:4700:3015::/48
2606:4700:3016::/48
2606:4700:3017::/48
2606:4700:3018::/48
2606:4700:3019::/48
2606:4700:3020::/48
2606:4700:3021::/48
2606:4700:3022::/48
2606:4700:3023::/48
2606:4700:3024::/48
2606:4700:3025::/48
2606:4700:3026::/48
2606:4700:3027::/48
2606:4700:3028::/48
2606:4700:3029::/48
2606:4700:3030::/48
2606:4700:3031::/48
2606:4700:3032::/48
2606:4700:3033::/48
2606:4700:3034::/48
2606:4700:3035::/48
2606:4700:3036::/48
2606:4700:3037::/48
2606:4700:3038::/48
2606:4700:3039::/48
2606:4700:a0::/48
2606:4700:a1::/48
2606:4700:a8::/48
2606:4700:a9::/48
2606:4700:a::/48
2606:4700:b::/48
2606:4700:c::/48
2606:4700:d0::/48
2606:4700:d1::/48
2606:4700:d::/48
2606:4700:e0::/48
2606:4700:e1::/48
2606:4700:e2::/48
2606:4700:e3::/48
2606:4700:e4::/48
2606:4700:e5::/48
2606:4700:e6::/48
2606:4700:e7::/48
2606:4700:e::/48
2606:4700:f1::/48
2606:4700:f2::/48
2606:4700:f3::/48
2606:4700:f4::/48
2606:4700:f5::/48
2606:4700:f::/48
2803:f800:50::/48
2803:f800:51::/48
2a06:98c1:3100::/48
2a06:98c1:3101::/48
2a06:98c1:3102::/48
2a06:98c1:3103::/48
2a06:98c1:3104::/48
2a06:98c1:3105::/48
2a06:98c1:3106::/48
2a06:98c1:3107::/48
2a06:98c1:3108::/48
2a06:98c1:3109::/48
2a06:98c1:310a::/48
2a06:98c1:310b::/48
2a06:98c1:310c::/48
2a06:98c1:310d::/48
2a06:98c1:310e::/48
2a06:98c1:310f::/48
2a06:98c1:3120::/48
2a06:98c1:3121::/48
2a06:98c1:3122::/48
2a06:98c1:3123::/48
2a06:98c1:3200::/48
2a06:98c1:50::/48
2a06:98c1:51::/48
2a06:98c1:54::/48
2a06:98c1:58::/48""".trimIndent()
    
    private val _currentListContent = MutableStateFlow("")
    val currentListContent = _currentListContent.asStateFlow()
    private val _sourceLabel = MutableStateFlow("内置列表")
    val sourceLabel = _sourceLabel.asStateFlow()
    data class ImportPreview(val content:String,val generation:Long,val entries:Int,val ipv4:Int,val ipv6:Int)
    private val _importPreview = MutableStateFlow<ImportPreview?>(null)
    val importPreview = _importPreview.asStateFlow()
    private val _pastePending=MutableStateFlow(false)
    val pastePending=_pastePending.asStateFlow()
    private val _pasteText=MutableStateFlow<String?>(null)
    val pasteText=_pasteText.asStateFlow()
    fun editPaste(text:String) { _pasteText.value=text; _pastePending.value=text!=_currentListContent.value; _importPreview.value=null }
    private val _draftPending = MutableStateFlow(false)
    val draftPending = _draftPending.asStateFlow()
    private val _editableDraft=MutableStateFlow<com.cfst.app.settings.SettingsDraft?>(null)
    val editableDraft=_editableDraft.asStateFlow()
    private var retainedDraft:com.cfst.app.settings.SettingsDraft?
        get()=_editableDraft.value
        set(value) { _editableDraft.value=value }
    private var draftBaseline:com.cfst.app.settings.SettingsDraft?=null
    fun updateDraft(draft:com.cfst.app.settings.SettingsDraft) {
        if(_isTesting.value) return
        if(draftBaseline==null) beginDraft()
        retainedDraft=draft; _draftPending.value=draft!=draftBaseline
    }
    val sourceGeneration:Long get()=ipGeneration
    fun previewDocument(bytes:ByteArray,generation:Long) {
        if(generation!=ipGeneration) { _statusMessage.value="来源已改变，请重新选择文件"; _importPreview.value=null; return }
        previewBytes(bytes)
    }
    fun previewPaste(content:String) = previewBytes(content.toByteArray(Charsets.UTF_8))
    fun previewBytes(bytes:ByteArray) {
        try {
            require(bytes.size<=1048576) { "列表超过1 MiB" }
            val content=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val ranges=IpRangeParser.parseIpFile(content)
            _importPreview.value=ImportPreview(content,ipGeneration,ranges.size,ranges.count { !it.isIpv6 },ranges.count { it.isIpv6 })
            _statusMessage.value="预览：${ranges.size} 个条目/网段，请确认应用"
        } catch (_:java.nio.charset.CharacterCodingException) { _statusMessage.value="UTF-8 编码无效，列表未改变"; _importPreview.value=null }
        catch (e:Exception) { _statusMessage.value=e.message.orEmpty().take(2048); _importPreview.value=null }
    }
    fun confirmImport():Boolean {
        val preview=_importPreview.value ?: return false
        if (preview.generation!=ipGeneration) { _importPreview.value=null; _statusMessage.value="来源已改变，预览失效，请重新选择"; return false }
        val before=ipGeneration
        loadIpRanges(preview.content)
        val applied=ipGeneration!=before
        if(applied) { _importPreview.value=null; _pastePending.value=false; _pasteText.value=null }
        return applied
    }
    fun cancelImport() { _importPreview.value=null; _pastePending.value=false; _pasteText.value=null; _statusMessage.value="已取消导入，当前列表未改变" }
    fun presetDraft(kind:String):com.cfst.app.settings.SettingsDraft {
        val d=beginDraft()
        val next=when(kind) {
            "省流" -> d.copy(candidates="10",downloads="1",budgetMb="3",budgetDirty=true,seconds="3")
            "标准" -> d.copy(candidates="30",downloads="3",budgetMb="30",budgetDirty=true,seconds="3")
            "深入" -> d.copy(candidates="300",downloads="10",budgetMb="300",budgetDirty=true,seconds="5")
            else -> d
        }
        updateDraft(next); return next
    }
    fun restoreBuiltIn() {
        val content=getDefaultIpContent(); val parsed=IpRangeParser.parseIpFile(content)
        if(!settings.edit().putString(getCurrentIpCacheKey(),content).putString("source","内置列表").putStringSet("selectedSegments",emptySet()).putBoolean("useSpecifiedIpSegments",false).putLong("sourceGeneration",ipGeneration+1).commit()) { _statusMessage.value="恢复失败，当前列表未改变"; return }
        ipGeneration++; ipRanges=parsed; _availableIpSegments.value=extractIpSegments(content); _currentListContent.value=content
        com.cfst.app.documents.DocumentOperationOwner.get(application).invalidateImport(ipGeneration); _importPreview.value=null
        _sourceLabel.value="内置列表"; _selectedSpecifiedIpSegments.value=emptySet(); _useSpecifiedIpSegments.value=false
        _statusMessage.value="已恢复内置列表"
    }
    fun reportDocumentStatus(text:String) { _statusMessage.value=text }
    fun beginDraft():com.cfst.app.settings.SettingsDraft {
        retainedDraft?.let { return it }
        return com.cfst.app.settings.SettingsDraft(_testCount.value.toString(),_pingCount.value.toString(),_maxConcurrentPings.value.toString(),_downloadTestCount.value.toString(),_downloadDuration.value.toString(),_downloadPort.value.toString(),_downloadUrl.value,com.cfst.app.utils.DataUnits.budgetEditor(_budgetBytes.value),(_speedLimit.value*1048576/1000000).toString(),_latencyLimit.value.toString(),_enableDownloadTest.value,_budgetBytes.value,_speedLimit.value*1048576,mirror=_proxyUrl.value,useMirror=_useProxy.value,runTimeSeconds=_runTimeSeconds.value.toString(),sampleSeed=_sampleSeed.value.toString(),randomizeSampleSeed=_randomizeSampleSeed.value).also { retainedDraft=it; draftBaseline=it }
    }
    fun cancelDraft() { retainedDraft=null; draftBaseline=null; _draftPending.value=false; _statusMessage.value="已取消草稿，设置未修改" }
    fun saveDraft(draft:com.cfst.app.settings.SettingsDraft):Boolean {
        if(_isTesting.value) { _statusMessage.value="运行中不能保存设置"; return false }
        if(retainedDraft==null) { _statusMessage.value="草稿已结束，请重新编辑"; return false }
        updateDraft(draft)
        return try {
            val fields=draft.validate()
            val editor=settings.edit()
            fields.forEach { (k,v) -> when(v) { is Int -> editor.putInt(k,v); is Long -> editor.putLong(k,v); is Boolean -> editor.putBoolean(k,v); is String -> editor.putString(k,v); is Double -> (settings as com.cfst.app.settings.AppStateStore).putDouble(editor,k,v) } }
            if(draft.randomizeSampleSeed!=draftBaseline?.randomizeSampleSeed || draft.sampleSeed!=draftBaseline?.sampleSeed)
                editor.putBoolean("samplingMigrationNoticePending",false)
            check(editor.commit()) { "保存失败，草稿保留" }
            _randomizeSampleSeed.value=fields["randomizeSampleSeed"] as Boolean
            _samplingMigrationNoticePending.value=settings.getBoolean("samplingMigrationNoticePending",false)
            _runTimeSeconds.value=fields["runTimeSeconds"] as Int; _sampleSeed.value=fields["sampleSeed"] as Long
            _testCount.value=fields["testCount"] as Int; _pingCount.value=fields["pingCount"] as Int
            _maxConcurrentPings.value=fields["maxConcurrentPings"] as Int; _downloadTestCount.value=fields["downloadTestCount"] as Int
            _downloadDuration.value=fields["downloadDuration"] as Int; _downloadPort.value=fields["downloadPort"] as Int
            _downloadUrl.value=fields["downloadUrl"] as String; _budgetBytes.value=fields["budgetBytes"] as Long
            _speedLimit.value=(fields["speedLimit"] as String).toDouble(); _latencyLimit.value=(fields["latencyLimit"] as String).toDouble()
            _enableDownloadTest.value=fields["enableDownloadTest"] as Boolean; _proxyUrl.value=fields["proxyUrl"] as String; _useProxy.value=fields["useProxy"] as Boolean
            retainedDraft=null; draftBaseline=null; _draftPending.value=false; _statusMessage.value="设置已保存"; true
        } catch (e:Exception) { _statusMessage.value=e.message.orEmpty(); false }
    }
    // UI 状态
    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()
    private val _isStopping=MutableStateFlow(false)
    val isStopping=_isStopping.asStateFlow()
    
    private val _progress = MutableStateFlow(SpeedTestEngine.TestProgress(0, 100, "", ""))
    val progress: StateFlow<SpeedTestEngine.TestProgress> = _progress.asStateFlow()
    
    private val _results = MutableStateFlow<List<SpeedTestResult>>(emptyList())
    val results: StateFlow<List<SpeedTestResult>> = _results.asStateFlow()
    private val _resultDownloadEnabled=MutableStateFlow(true)
    val resultDownloadEnabled=_resultDownloadEnabled.asStateFlow()
    private val _resultConfig=MutableStateFlow<SpeedTestEngine.TestConfig?>(null)
    val resultConfig=_resultConfig.asStateFlow()
    
    private val _statusMessage = MutableStateFlow((settings as com.cfst.app.settings.AppStateStore).warning)
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    
    // 配置状态
    private val _testCount = MutableStateFlow(settings.getInt("testCount", 30))
    val testCount: StateFlow<Int> = _testCount.asStateFlow()
    
    private val _pingCount = MutableStateFlow(settings.getInt("pingCount", 3))
    val pingCount: StateFlow<Int> = _pingCount.asStateFlow()

    private val _maxConcurrentPings = MutableStateFlow(settings.getInt("maxConcurrentPings", 8))
    val maxConcurrentPings: StateFlow<Int> = _maxConcurrentPings.asStateFlow()
    
    private val _enableDownloadTest = MutableStateFlow(settings.getBoolean("enableDownloadTest", true))
    val enableDownloadTest: StateFlow<Boolean> = _enableDownloadTest.asStateFlow()
    
    private val _speedLimit = MutableStateFlow((settings.getString("speedLimit", "0.0")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0))
    val speedLimit: StateFlow<Double> = _speedLimit.asStateFlow()
    
    private val _latencyLimit = MutableStateFlow((settings.getString("latencyLimit", "0.0")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0))
    val latencyLimit: StateFlow<Double> = _latencyLimit.asStateFlow()
    
    // 新增配置状态
    private val _downloadTestCount = MutableStateFlow(settings.getInt("downloadTestCount", 3))
    val downloadTestCount: StateFlow<Int> = _downloadTestCount.asStateFlow()

    private val _downloadDuration = MutableStateFlow(settings.getInt("downloadDuration", 3))
    val downloadDuration: StateFlow<Int> = _downloadDuration.asStateFlow()

    private val _useProxy = MutableStateFlow(settings.getBoolean("useProxy", false))
    val useProxy: StateFlow<Boolean> = _useProxy.asStateFlow()
    
    private val _proxyUrl = MutableStateFlow(settings.getString("proxyUrl", "")!!)
    val proxyUrl: StateFlow<String> = _proxyUrl.asStateFlow()
    
    private val _useIpv6 = MutableStateFlow(settings.getBoolean("useIpv6", false))
    val useIpv6: StateFlow<Boolean> = _useIpv6.asStateFlow()

    private val _useSpecifiedIpSegments = MutableStateFlow(settings.getBoolean("useSpecifiedIpSegments", false))
    val useSpecifiedIpSegments: StateFlow<Boolean> = _useSpecifiedIpSegments.asStateFlow()

    private val _availableIpSegments = MutableStateFlow<List<String>>(emptyList())
    val availableIpSegments: StateFlow<List<String>> = _availableIpSegments.asStateFlow()

    private val _selectedSpecifiedIpSegments = MutableStateFlow<Set<String>>(settings.getStringSet("selectedSegments", emptySet())!!.toSet())
    val selectedSpecifiedIpSegments: StateFlow<Set<String>> = _selectedSpecifiedIpSegments.asStateFlow()
    
    // 新增配置状态：结果排序方式
    private val _sortByLatency = MutableStateFlow(settings.getBoolean("sortByLatency", false))
    val sortByLatency: StateFlow<Boolean> = _sortByLatency.asStateFlow()
    
    // IP 范围 - 默认使用内置 IPv4
    private var ipRanges: List<IpRangeParser.IpRange> = IpRangeParser.parseIpFile(builtInIpv4Ranges)
    
    // 历史记录相关
    private val _historyRecords = MutableStateFlow<List<List<SpeedTestResult>>>(emptyList())
    val historyRecords: StateFlow<List<List<SpeedTestResult>>> = _historyRecords.asStateFlow()
    private val _runRecords=MutableStateFlow<List<com.cfst.app.model.RunRecord>>(emptyList())
    val runRecords=_runRecords.asStateFlow()
    private var historyRunIds:List<String> = emptyList()
    private val _runSummary=MutableStateFlow<com.cfst.app.model.RunSummary?>(null)
    val runSummary=_runSummary.asStateFlow()
    
    // GitHub 原始 URL
    private val githubRawUrl = "https://raw.githubusercontent.com/XIU2/CloudflareSpeedTest/master/"

    // 镜像网站前缀列表（用于拼接在 GitHub URL 前面）
    // 注意：这些镜像可能会失效，如果失效请手动更新或使用自定义镜像
    private val mirrorPrefixes = listOf(
        // 原来的 4 个
        "https://ghps.cc/",
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.vip/",
        // 新增的 4 个
        "https://ghproxy.cc/",
        "https://ghproxy.net/",
        "https://ghproxy.click/"
    )
    
    companion object {
        // 本地存储 IP 和版本信息的键
        private const val IP_FILE_KEY = "ip_file_content"
        private const val IP_VERSION_KEY = "ip_version_info"
        private const val IP_UPDATE_TIME_KEY = "ip_update_time"
        private const val IPV4_FILE_KEY = "ipv4_file_content"
        private const val IPV6_FILE_KEY = "ipv6_file_content"
        private const val IPV4_UPDATE_TIME_KEY = "ipv4_update_time"
        private const val IPV6_UPDATE_TIME_KEY = "ipv6_update_time"
        private const val CLOUDFLARE_IPV4_URL = "https://www.cloudflare.com/ips-v4"
        private const val CLOUDFLARE_IPV6_URL = "https://www.cloudflare.com/ips-v6"
        // 历史记录相关键
        private const val HISTORY_RECORDS_KEY = "history_records"
        
        /**
         * ViewModel Factory
         */
        fun createFactory(application: Application): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(SpeedTestViewModel::class.java)) {
                        return SpeedTestViewModel(application) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class")
                }
            }
        }
    }
    
    private val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    private val networkGuard = com.cfst.app.speedtest.NetworkIdentityGuard { engine.stopTest(it) }
    private val _networkLabel = MutableStateFlow("无网络")
    val networkLabel = _networkLabel.asStateFlow()
    private fun refreshNetwork() {
        val network = connectivity.activeNetwork
        publishNetwork(network, network?.let { connectivity.getNetworkCapabilities(it) })
    }
    private fun publishNetwork(network: android.net.Network?, caps: android.net.NetworkCapabilities?, available: Boolean = false) {
        val kind = when {
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "蜂窝"
            network != null -> "其他"
            else -> "无网络"
        }
        val identity = network?.let { "$it:$kind" }
        _networkLabel.value = identity ?: "无网络"
        if (available && identity != null) networkGuard.available(identity) else networkGuard.update(identity)
    }
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { publishNetwork(network, connectivity.getNetworkCapabilities(network), true) }
        override fun onLost(network: android.net.Network) { networkGuard.lost(network.toString()); _networkLabel.value = "网络丢失：$network" }
        override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { publishNetwork(network, caps) }
    }
    override fun onCleared() {
        engine.stopTest("页面销毁")
        connectivity.unregisterNetworkCallback(networkCallback)
        super.onCleared()
    }
    init {
        refreshNetwork()
        connectivity.registerDefaultNetworkCallback(networkCallback)
        // 延迟初始化，避免在构造函数中访问 SharedPreferences
        viewModelScope.launch {
            // 应用启动时加载本地 IP 列表
            loadLocalIpRanges()
            // 应用启动时加载历史记录
            loadHistoryRecords()
            
            // 监听排序方式变化，重新排序当前结果
            _sortByLatency.collect { sortByLatency ->
                val currentResults = _results.value
                if (currentResults.isNotEmpty()) {
                    val sortedResults = if (sortByLatency) {
                        SpeedTestResult.rank(currentResults, true)
                    } else {
                        SpeedTestResult.rank(currentResults, false)
                    }
                    _results.value = sortedResults
                }
            }
        }
    }
    
    /**
     * 更新测试数量
     */
    fun updateTestCount(count: Int) {
        require(count in 1..10000)
        check(settings.edit().putInt("testCount",count).commit()) { "保存失败" }
        _testCount.value=count
    }
    
    /**
     * 更新 Ping 次数
     */
    fun updatePingCount(count: Int) {
        check(settings.edit().putInt("pingCount", count).commit()) { "保存失败" }
        _pingCount.value = count
    }

    fun updateMaxConcurrentPings(count: Int) {
        check(settings.edit().putInt("maxConcurrentPings", count).commit()) { "保存失败" }
        _maxConcurrentPings.value = count
    }
    
    /**
     * 更新是否启用下载测速
     */
    fun updateEnableDownloadTest(enabled: Boolean) {
        check(settings.edit().putBoolean("enableDownloadTest", enabled).commit()) { "保存失败" }
        _enableDownloadTest.value = enabled
    }
    
    /**
     * 更新速度下限
     */
    fun updateSpeedLimit(limit: Double) {
        val editor=settings.edit().putString("speedLimit",limit.toString())
        (settings as com.cfst.app.settings.AppStateStore).putDouble(editor,"minBytesPerSecond",limit*1048576)
        check(editor.commit()) { "保存失败" }
        _speedLimit.value = limit
    }
    
    /**
     * 更新延迟上限
     */
    fun updateLatencyLimit(limit: Double) {
        check(settings.edit().putString("latencyLimit", limit.toString()).commit()) { "保存失败" }
        _latencyLimit.value = limit
    }
    
    /**
     * 更新下载测速 IP 数量
     */
    fun updateDownloadTestCount(count: Int) {
        check(settings.edit().putInt("downloadTestCount", count).commit()) { "保存失败" }
        _downloadTestCount.value = count
    }

    /**
     * 更新下载测试时长 (秒)
     */
    fun updateDownloadDuration(seconds: Int) {
        check(settings.edit().putInt("downloadDuration", seconds).commit()) { "保存失败" }
        _downloadDuration.value = seconds
    }

    /**
     * 更新是否使用代理
     */
    fun updateUseProxy(use: Boolean) {
        check(settings.edit().putBoolean("useProxy", use).commit()) { "保存失败" }
        _useProxy.value = use
    }
    
    /**
     * 更新代理 URL
     */
    fun updateProxyUrl(url: String) {
        check(settings.edit().putString("proxyUrl", url).commit()) { "保存失败" }
        _proxyUrl.value = url
    }
    
    /**
     * 加载自定义 IP 文件内容
     */
    private var ipGeneration = settings.getLong("sourceGeneration",0L)
    internal fun captureListRequest(): Triple<Long, String, String> = Triple(++ipGeneration, getCurrentIpCacheKey(), getCurrentUpdateTimeKey())
    internal fun acceptListResponse(request: Triple<Long, String, String>, content: String): Boolean {
        if (request.first != ipGeneration || request.second != getCurrentIpCacheKey()) return false
        loadIpRanges(content)
        return currentListContent.value == content
    }
    fun loadIpRanges(content: String) {
        val parsedRanges = try { IpRangeParser.parseIpFile(content) } catch (e: IllegalArgumentException) { _statusMessage.value = e.message.orEmpty(); return }
        val segments = extractIpSegments(content)
        val selected = _selectedSpecifiedIpSegments.value.intersect(segments.toSet())
        val enabled=_useSpecifiedIpSegments.value && selected.isNotEmpty()
        if (!settings.edit().putString(getCurrentIpCacheKey(),content).putString("source","自定义列表").putStringSet("selectedSegments",selected).putBoolean("useSpecifiedIpSegments",enabled).putLong("sourceGeneration",ipGeneration+1).commit()) {
            _statusMessage.value="保存失败，列表未修改"; return
        }
        ipGeneration++
        ipRanges = parsedRanges
        _availableIpSegments.value = segments
        _selectedSpecifiedIpSegments.value = selected
        _useSpecifiedIpSegments.value = enabled
        _currentListContent.value=content
        _sourceLabel.value="自定义列表"
        _statusMessage.value = "已加载 ${ipRanges.size} 个 IP 段"
    }

    fun updateUseSpecifiedIpSegments(enabled: Boolean) {
        require(!enabled || _selectedSpecifiedIpSegments.value.isNotEmpty()) { "请先选择当前family网段" }
        check(settings.edit().putBoolean("useSpecifiedIpSegments", enabled).putLong("sourceGeneration",ipGeneration+1).commit()) { "保存失败" }
        ipGeneration++; com.cfst.app.documents.DocumentOperationOwner.get(application).invalidateImport(ipGeneration)
        _useSpecifiedIpSegments.value = enabled
    }

    fun toggleSpecifiedIpSegment(segment: String) {
        if(segment !in _availableIpSegments.value || IpRangeParser.parseCidr(segment)?.isIpv6!=_useIpv6.value) { _statusMessage.value="只能选择当前family的有效条目"; return }
        val updated = _selectedSpecifiedIpSegments.value.toMutableSet()
        if (!updated.add(segment)) {
            updated.remove(segment)
        }
        val enabled=_useSpecifiedIpSegments.value && updated.isNotEmpty()
        if(!settings.edit().putStringSet("selectedSegments",updated).putBoolean("useSpecifiedIpSegments",enabled).putLong("sourceGeneration",ipGeneration+1).commit()) { _statusMessage.value="选择保存失败，未修改"; return }
        _selectedSpecifiedIpSegments.value = updated; _useSpecifiedIpSegments.value=enabled
        ipGeneration++; com.cfst.app.documents.DocumentOperationOwner.get(application).invalidateImport(ipGeneration)
    }

    fun clearSpecifiedIpSegments() {
        commitSelection(emptySet())
    }

    fun selectAllSpecifiedIpSegments() {
        commitSelection(_availableIpSegments.value.filter { IpRangeParser.parseCidr(it)?.isIpv6==_useIpv6.value }.toSet())
    }
    private fun commitSelection(next:Set<String>) {
        val enabled=_useSpecifiedIpSegments.value && next.isNotEmpty()
        if(!settings.edit().putStringSet("selectedSegments",next).putBoolean("useSpecifiedIpSegments",enabled).putLong("sourceGeneration",ipGeneration+1).commit()) { _statusMessage.value="选择保存失败，未修改"; return }
        _selectedSpecifiedIpSegments.value=next; _useSpecifiedIpSegments.value=enabled
        ipGeneration++; com.cfst.app.documents.DocumentOperationOwner.get(application).invalidateImport(ipGeneration)
    }
    
    /**
     * 加载历史记录
     */
    private fun loadHistoryRecords() {
        viewModelScope.launch {
            try {
                val snapshot=settings.getString(HISTORY_RECORDS_KEY,null)
                if(snapshot!=null) {
                    val type=com.google.gson.reflect.TypeToken.getParameterized(List::class.java,com.google.gson.reflect.TypeToken.getParameterized(List::class.java,SpeedTestResult::class.java).type).type
                    _historyRecords.value=com.cfst.app.settings.SnapshotRecords.histories(snapshot)
                    val runType=com.google.gson.reflect.TypeToken.getParameterized(List::class.java,com.cfst.app.model.RunRecord::class.java).type
                    _runRecords.value=com.cfst.app.settings.SnapshotRecords.runs(settings.getString("run_records","[]")!!)
                    historyRunIds=readHistoryIds(_historyRecords.value.size)
                    return@launch
                }
                val sharedPreferences = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val historyJson = sharedPreferences.getString(HISTORY_RECORDS_KEY, "[]") ?: "[]"
                
                val gson = com.google.gson.Gson()
                val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, String::class.java).type
                val jsonStringList = gson.fromJson<List<String>>(historyJson, type)
                
                val historyRecords = jsonStringList.mapNotNull { jsonStr ->
                    try {
                        val resultType = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, SpeedTestResult::class.java).type
                        com.cfst.app.settings.SnapshotRecords.histories("[$jsonStr]").single()
                    } catch (e: Exception) {
                        null
                    }
                }
                _historyRecords.value = historyRecords
                historyRunIds=historyRecords.map { "legacy:${java.util.UUID.randomUUID()}" }
            } catch (e: Exception) {
                _historyRecords.value = emptyList()
            }
        }
    }

    /**
     * 保存历史记录到本地存储
     */
    private fun readHistoryIds(size:Int):List<String> {
        val text=settings.getString("history_run_ids",null)
        return if(text==null) {
            val remaining=_runRecords.value.toMutableList()
            val rowCounts=_historyRecords.value.groupingBy { it.size to it.toSet() }.eachCount()
            val ids=_historyRecords.value.map { row ->
                // Only a one-row/one-run match has known ownership. Duplicate old rows
                // remain unowned even when just one matching raw run exists.
                val matches=remaining.filter { it.results.toSet()==row.toSet() && it.results.size==row.size }
                if(matches.size==1 && rowCounts[row.size to row.toSet()]==1) matches.single().also { remaining.remove(it) }.runId else "legacy:${java.util.UUID.randomUUID()}"
            }
            var rows=if(ids.size+remaining.size<=100) _historyRecords.value+remaining.map { it.results } else remaining.map { it.results }+_historyRecords.value
            var names=if(ids.size+remaining.size<=100) ids+remaining.map { it.runId } else remaining.map { it.runId }+ids
            var runs=_runRecords.value
            val gson=com.google.gson.Gson()
            val total=rows.size
            while(rows.size>100 || rows.size>1 && (gson.toJson(rows).toByteArray().size.toLong()+gson.toJson(runs).toByteArray().size)>16*1024*1024) {
                rows=rows.dropLast(1); names=names.dropLast(1); runs=runs.filter { it.runId in names }
            }
            check(commitHistory(rows,names,runs)) { "旧历史归属迁移保存失败，原文件保留" }
            if(remaining.isNotEmpty()) _statusMessage.value="旧记录/归属未确定；未映射原始运行已独立保留，可分别删除"+(if(rows.size<total) "；100条/16 MiB整记录FIFO已移除超限旧行" else "")
            names
        }
        else com.google.gson.JsonParser.parseString(text).asJsonArray.map { it.asString }.also { require(it.size==size && it.distinct().size==size) }
    }
    fun historyLabel(index:Int):String {
        val id=historyRunIds.getOrNull(index) ?: return "旧记录/归属未确定"
        return if(id.startsWith("legacy:")) "旧记录/归属未确定 · 配置未知" else "原始运行 $id"
    }
    fun historyIdentities():List<String> = historyRunIds.toList()
    fun deleteHistoryIdentity(id:String):Boolean {
        val index=historyRunIds.indexOf(id)
        if(index<0) { _statusMessage.value="该记录已不存在，未删除其他记录"; return false }
        deleteHistoryRecord(index)
        return id !in historyRunIds
    }
    fun exportHistoryIdentity(id:String):String {
        val index=historyRunIds.indexOf(id)
        require(index>=0) { "该记录已不存在" }
        return exportHistory(index)
    }
    private fun commitHistory(next:List<List<SpeedTestResult>>,ids:List<String>,runs:List<com.cfst.app.model.RunRecord>):Boolean {
        val gson=com.google.gson.Gson()
        val committed=try { settings.edit().putString(HISTORY_RECORDS_KEY,gson.toJson(next)).putString("history_run_ids",gson.toJson(ids)).putString("run_records",gson.toJson(runs)).commit() } catch(_:Exception) { false }
        if(committed) { _historyRecords.value=next; historyRunIds=ids; _runRecords.value=runs } else _statusMessage.value="保存历史记录失败，历史未改变"
        return committed
    }
    internal fun persistCompletedRun(record:com.cfst.app.model.RunRecord):Boolean {
        if(record.runId in historyRunIds) return true
        var rows=(listOf(record.results)+_historyRecords.value).take(100)
        var ids=(listOf(record.runId)+historyRunIds).take(100)
        var runs=(listOf(record)+_runRecords.value).filter { it.runId in ids }
        val gson=com.google.gson.Gson()
        // Explicit whole-record FIFO byte retention: never truncate a retained attempt.
        while(rows.size>1 && (gson.toJson(rows).toByteArray().size.toLong()+gson.toJson(runs).toByteArray().size)>16*1024*1024) {
            rows=rows.dropLast(1); ids=ids.dropLast(1); runs=runs.filter { it.runId in ids }
        }
        val previousSize=_historyRecords.value.size
        val retained=commitHistory(rows,ids,runs)
        if(retained && rows.size<1+previousSize) _statusMessage.value="历史按100条/16 MiB整记录FIFO保留；超限旧记录已移除，不截断原始attempt"
        return retained
    }

    /**
     * 保存当前测速结果到历史记录
     */
    fun saveCurrentResultsToHistory() {
        val currentResults = _results.value
        if (currentResults.isNotEmpty()) {
            val newHistory = _historyRecords.value.toMutableList()
            newHistory.add(0, currentResults)
            if (newHistory.size > 100) {
                newHistory.removeLast()
            }
            val ids=(listOf("legacy:${java.util.UUID.randomUUID()}")+historyRunIds).take(100)
            commitHistory(newHistory,ids,_runRecords.value.filter { it.runId in ids })
        }
    }

    /**
     * 删除指定索引的历史记录
     */
    fun deleteHistoryRecord(index: Int) {
        if (index >= 0 && index < _historyRecords.value.size) {
            val newHistory = _historyRecords.value.toMutableList()
            newHistory.removeAt(index)
            val deleted=historyRunIds[index]; val ids=historyRunIds.filterIndexed { i,_ -> i!=index }
            if(commitHistory(newHistory,ids,_runRecords.value.filter { it.runId!=deleted })) _statusMessage.value = "已删除历史记录，剩余 ${_historyRecords.value.size} 条记录"
        }
    }

    /**
     * 清空所有历史记录
     */
    fun clearAllHistoryRecords() {
        if(commitHistory(emptyList(),emptyList(),emptyList())) _statusMessage.value = "已清空所有历史记录"
    }

    /**
     * 获取最新一次的历史记录
     */
    fun getLatestHistoryRecord(): List<SpeedTestResult>? {
        return _historyRecords.value.firstOrNull()
    }
    
    /**
     * 更新是否使用 IPv6
     */
    fun updateUseIpv6(use: Boolean) {
        val mixed=ipRanges.any { it.isIpv6 } && ipRanges.any { !it.isIpv6 }
        val editor=settings.edit().putBoolean("useIpv6",use).putBoolean("useSpecifiedIpSegments",false).putStringSet("selectedSegments",emptySet()).putLong("sourceGeneration",ipGeneration+1)
        if(mixed) editor.putString(if(use) IPV6_FILE_KEY else IPV4_FILE_KEY,_currentListContent.value)
        check(editor.commit()) { "保存失败" }
        ipGeneration++
        _useIpv6.value = use
        com.cfst.app.documents.DocumentOperationOwner.get(application).invalidateImport(ipGeneration); _importPreview.value=null
        _useSpecifiedIpSegments.value = false
        _selectedSpecifiedIpSegments.value = emptySet()
        if(!mixed) loadLocalIpRanges()
    }

    /**
     * 获取镜像网站列表（返回完整 URL）
     */
    fun getMirrorSites(): List<String> {
        return mirrorPrefixes.map { ensureEndsWithSlash(it) + githubRawUrl }
    }

    /**
     * 更新结果排序方式
     */
    fun updateSortByLatency(sortByLatency: Boolean) {
        check(settings.edit().putBoolean("sortByLatency", sortByLatency).commit()) { "保存失败" }
        _sortByLatency.value = sortByLatency
    }

    private fun extractIpSegments(content: String): List<String> {
        return content.replace("\uFEFF", "")
            .lines()
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun trimSelectedSpecifiedIpSegments() {
        val available = _availableIpSegments.value.toSet()
        _selectedSpecifiedIpSegments.value = _selectedSpecifiedIpSegments.value.filterTo(linkedSetOf()) {
            it in available
        }
        settings.edit().putStringSet("selectedSegments", _selectedSpecifiedIpSegments.value).apply()
    }

    private fun getDefaultIpContent(): String {
        return if (_useIpv6.value) builtInIpv6Ranges else builtInIpv4Ranges
    }

    private fun getCurrentIpCacheKey(): String {
        return if (_useIpv6.value) IPV6_FILE_KEY else IPV4_FILE_KEY
    }

    private fun getCurrentUpdateTimeKey(): String {
        return if (_useIpv6.value) IPV6_UPDATE_TIME_KEY else IPV4_UPDATE_TIME_KEY
    }

    private fun applyIpContent(content: String) {
        ipRanges = IpRangeParser.parseIpFile(content)
        _availableIpSegments.value = extractIpSegments(content)
        _currentListContent.value=content
        _sourceLabel.value=settings.getString("source","内置列表") ?: "内置列表"
        trimSelectedSpecifiedIpSegments()
    }
    
    /**
     * 从本地存储获取 IP 范围
     */
    fun loadLocalIpRanges() {
        try {
            val sharedPreferences = settings
            val updateTime = sharedPreferences.getString(getCurrentUpdateTimeKey(), "") ?:
                sharedPreferences.getString(IP_UPDATE_TIME_KEY, "") ?: ""
            val defaultContent = getDefaultIpContent()
            val localContent = sharedPreferences.getString(getCurrentIpCacheKey(), null)
                ?: sharedPreferences.getString(IP_FILE_KEY, defaultContent)
                ?: defaultContent

            applyIpContent(localContent)

            _statusMessage.value = if (updateTime.isNotEmpty()) {
                "已加载本地 ${if (_useIpv6.value) "IPv6" else "IPv4"} 列表 (更新于：$updateTime)"
            } else {
                "已加载默认 ${if (_useIpv6.value) "IPv6" else "IPv4"} 列表 ${ipRanges.size} 个 IP 段"
            }
        } catch (e: Exception) {
            applyIpContent(getDefaultIpContent())
            _statusMessage.value = "加载 ${if (_useIpv6.value) "IPv6" else "IPv4"} 列表失败，使用默认列表 ${ipRanges.size} 个 IP 段"
        }
    }

    fun fetchCloudflareIpRanges() {
        val request = captureListRequest()
        val ipv6 = _useIpv6.value
        viewModelScope.launch {
            try {
                val ipVersion = if (ipv6) "IPv6" else "IPv4"
                val requestUrl = if (ipv6) CLOUDFLARE_IPV6_URL else CLOUDFLARE_IPV4_URL
                _statusMessage.value = "正在从 Cloudflare 获取最新 $ipVersion 地址..."

                val client = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val content = fetchContent(client, requestUrl)
                val normalizedContent = content
                    ?.trim()
                    ?.split(Regex("\\s+"))
                    ?.filter { it.isNotBlank() }
                    ?.joinToString("\n")
                    .orEmpty()
                if (request.first != ipGeneration) return@launch
                val parsedRanges = IpRangeParser.parseIpFile(normalizedContent)

                if (normalizedContent.isBlank() || parsedRanges.isEmpty()) {
                    val msg = "从 Cloudflare 获取 $ipVersion 地址失败：返回内容无效"
                    _statusMessage.value = msg
                    Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (!acceptListResponse(request, normalizedContent)) return@launch
                val updateTime = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm",
                    java.util.Locale.getDefault()
                ).format(java.util.Date())

                val sharedPreferences = settings
                with(sharedPreferences.edit()) {
                    putString(request.second, normalizedContent)
                    putString(request.third, updateTime)
                    apply()
                }

                val msg = "已从 Cloudflare 获取 ${parsedRanges.size} 个 $ipVersion IP 段"
                _statusMessage.value = msg
                Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                if (request.first != ipGeneration) return@launch
                val msg = "从 Cloudflare 获取最新 IP 失败：${e.message}"
                _statusMessage.value = msg
                Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 从 GitHub 获取最新的 IP 地址并存储到本地
     */
    fun fetchGithubIpRanges() {
        val request = captureListRequest()
        val ipv6 = _useIpv6.value
        val proxyEnabled = _useProxy.value
        val proxyUrl = _proxyUrl.value
        viewModelScope.launch {
            try {
                _statusMessage.value = "正在从 GitHub 获取最新 IP 地址..."

                val clientBuilder = OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                
                // 注意：这里的代理设置是为 HTTP 代理准备的
                // 但镜像网址本身是 HTTPS，所以代理设置可能不适用
                if (proxyUrl.isNotEmpty() && proxyEnabled) {
                    try {
                        val proxyParts = proxyUrl.split(":")
                        if (proxyParts.size >= 2) {
                            val proxyHost = proxyParts[0].removePrefix("http://").removePrefix("https://")
                            val proxyPort = proxyParts[1].toIntOrNull() ?: 8080
                            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
                            clientBuilder.proxy(proxy)
                        }
                    } catch (e: Exception) {
                        // 忽略代理设置错误，继续使用直连
                    }
                }
                val client = clientBuilder.build()

                val fileName = if (ipv6) "ipv6.txt" else "ip.txt"
                var githubContent: String? = null
                var lastError: String? = null

                val urlsToTry = mutableListOf<String>()

                if (proxyEnabled && proxyUrl.isNotBlank()) {
                    buildMirrorRequestUrl(proxyUrl, fileName)?.let { customUrl ->
                        urlsToTry.add(customUrl)
                        _statusMessage.value = "尝试从自定义镜像获取：$customUrl"
                    }
                }

                mirrorPrefixes.mapNotNull { buildMirrorRequestUrl(it, fileName) }
                    .forEach { urlsToTry.add(it) }

                urlsToTry.add("${githubRawUrl}${fileName}")

                val uniqueUrls = urlsToTry.filter { it.isNotBlank() }.distinct()

                // 依次尝试每个 URL
                for (url in uniqueUrls) {
                    try {
                        val content = fetchContent(client, url)
                        if (!content.isNullOrEmpty()) {
                            val trimmedContent = content.trim()
                            if (trimmedContent.startsWith("<!DOCTYPE", ignoreCase = true) ||
                                trimmedContent.startsWith("<html", ignoreCase = true) ||
                                trimmedContent.startsWith("<!--")) {
                                lastError = "镜像返回 HTML 页面，可能访问受限"
                                continue
                            }

                            // 直接解析检查是否包含真正的 IP
                            val parsedRanges = IpRangeParser.parseIpFile(content)
                            if (parsedRanges.isNotEmpty()) {
                                githubContent = content
                                break
                            } else {
                                lastError = "返回内容格式不正确，并未解析到有效 IP 列表"
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) {
                        lastError = "请求失败：${e.message}"
                    }
                }

                if (request.first != ipGeneration) return@launch
                if (!githubContent.isNullOrEmpty()) {
                    val resolvedContent = githubContent ?: ""
                    val sharedPreferences = settings
                    // 根据 IPv4/IPv6 选择对应的本地缓存内容进行比较
                    val localContent = sharedPreferences.getString(getCurrentIpCacheKey(), null)
                        ?: sharedPreferences.getString(IP_FILE_KEY, getDefaultIpContent())
                        ?: getDefaultIpContent()

                    // 解析 GitHub 获取的内容
                    val parsedRanges = IpRangeParser.parseIpFile(resolvedContent)

                    if (parsedRanges.isNotEmpty()) {
                        // 解析成功，检查是否需要更新
                        if (resolvedContent.trim() != localContent.trim()) {
                            if (!acceptListResponse(request, resolvedContent)) return@launch

                            val updateTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                            val msg = "已从 GitHub 获取 ${parsedRanges.size} 个 IP 段，已保存到本地"
                            _statusMessage.value = msg
                            Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()

                            with(sharedPreferences.edit()) {
                                putString(request.second, resolvedContent)
                                putString(request.third, updateTime)
                                apply()
                            }
                        } else {
                            val msg = "IP 列表已是最新版本，无需更新"
                            _statusMessage.value = msg
                            Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // 解析失败，说明获取的内容格式不正确
                        val msg = "从 GitHub 获取 IP 地址失败：内容格式不正确（非 CIDR 格式）"
                        _statusMessage.value = msg
                        Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val msg = "从 GitHub 获取 IP 地址失败：${lastError ?: "无法访问任何源"}"
                    _statusMessage.value = "$msg\n建议：1.检查网络 2.切换镜像 3.使用自定义镜像"
                    Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                if (request.first != ipGeneration) return@launch
                val msg = "从 GitHub 获取 IP 地址失败：${e.message}"
                _statusMessage.value = msg
                Toast.makeText(application, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 获取内容的辅助函数
     */
    private suspend fun fetchContent(client: OkHttpClient, url: String): String? = kotlinx.coroutines.coroutineScope {
        val call = client.newCall(Request.Builder().url(url).build())
        if (call.timeout().timeoutNanos() == 0L) call.timeout().timeout(15, java.util.concurrent.TimeUnit.SECONDS)
        val owner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
        val watcher = launch(Dispatchers.IO) {
            try { kotlinx.coroutines.awaitCancellation() } finally { if (!owner.isActive) call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body ?: return@withContext null
                    if (body.contentLength() > 1024 * 1024) return@withContext null
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    body.byteStream().use { input ->
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (bytes.size() + n > 1024 * 1024) return@withContext null
                            bytes.write(buffer, 0, n)
                        }
                    }
                    bytes.toString("UTF-8")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: java.io.IOException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            null
        } finally { watcher.cancel(); withContext(kotlinx.coroutines.NonCancellable) { watcher.join() } }
    }

    private fun ensureEndsWithSlash(value: String): String {
        var normalized = value.trim()
        if (normalized.isEmpty()) return ""
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://" + normalized.removePrefix("//")
        }
        normalized = normalized.replace("\\", "/")
        return if (normalized.endsWith("/")) normalized else "$normalized/"
    }

    private fun buildMirrorRequestUrl(prefix: String, fileName: String): String? {
        if (prefix.isBlank()) return null
        val normalized = ensureEndsWithSlash(prefix)
        return if (normalized.contains("raw.githubusercontent.com")) {
            normalized + fileName
        } else {
            normalized + githubRawUrl + fileName
        }
    }
    
    /**
     * 开始测速
     */
    private fun ownerStartBlockReason():String? = when {
        _isTesting.value || engine.isRunning() -> "运行尚未结束，请等待原运行收尾"
        _draftPending.value -> "存在未保存配置草稿，请继续编辑或放弃"
        _pastePending.value -> "粘贴尚未确认，请返回列表预览或取消"
        _importPreview.value!=null -> "导入预览待确认，请返回列表应用或取消"
        com.cfst.app.documents.DocumentOperationOwner.get(application).blocksStart() -> "文档操作占用槽位，请完成操作；隔离时等待旧IO退出"
        else -> null
    }
    fun startBlockReason():String? = ownerStartBlockReason() ?: when {
        ipRanges.isEmpty() -> "请先加载 IP 段文件"
        _useSpecifiedIpSegments.value && _selectedSpecifiedIpSegments.value.isEmpty() -> "请先选择至少一个指定 IP 段"
        (if(_useSpecifiedIpSegments.value) IpRangeParser.parseIpFile(_selectedSpecifiedIpSegments.value.joinToString("\n")) else ipRanges).none { it.isIpv6==_useIpv6.value } -> "当前family没有候选，请切换family或导入列表"
        else -> null
    }
    fun startTest() {
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) return
        if (_isTesting.value) return
        startBlockReason()?.let { _statusMessage.value=it; return }
        if (ipRanges.isEmpty()) {
            _statusMessage.value = "请先加载 IP 段文件"
            return
        }

        val activeIpRanges = if (_useSpecifiedIpSegments.value) {
            val selectedSegments = _availableIpSegments.value.filter { it in _selectedSpecifiedIpSegments.value }
            if (selectedSegments.isEmpty()) {
                _statusMessage.value = "请先选择至少一个指定 IP 段"
                return
            }
            IpRangeParser.parseIpFile(selectedSegments.joinToString("\n"))
        } else {
            ipRanges
        }

        if (activeIpRanges.isEmpty()) {
            _statusMessage.value = "当前选择的 IP 段无效，请重新选择"
            return
        }

        val familyRanges=activeIpRanges.filter { it.isIpv6==_useIpv6.value }
        if(familyRanges.isEmpty()) { _statusMessage.value="当前family没有候选，请切换family或导入列表"; return }
        startRun(familyRanges.toList(),savedTestConfig())
    }

    /** Current saved settings, never an inferred historical configuration. */
    fun savedTestConfig() = SpeedTestEngine.TestConfig(
                testCount = _testCount.value,
                pingCount = _pingCount.value,
                downloadTest = _enableDownloadTest.value,
                downloadDuration = _downloadDuration.value * 1000L,  // 秒转毫秒
                speedLimit = _speedLimit.value,
                latencyLimit = _latencyLimit.value,
                downloadTestCount = _downloadTestCount.value,
                pingFirst = true,
                maxConcurrentDownloads = 1,
                downloadUrl = _downloadUrl.value,
                port = _downloadPort.value,
                byteBudget = _budgetBytes.value,
                maxConcurrentPings = _maxConcurrentPings.value,
                runTimeSeconds = _runTimeSeconds.value, sampleSeed = _sampleSeed.value
            )
    val currentResultRunId:String? get() = engine.lastRecord?.takeIf { record -> !_isTesting.value && record.results.isNotEmpty() && _runRecords.value.any { it.runId==record.runId } }?.runId

    /** Called only by an explicit confirmation. Resolve stable ownership before reserving. */
    fun confirmSingleIpRetest(parentRunId:String,literal:String):Boolean {
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) return false
        ownerStartBlockReason()?.let { _statusMessage.value=it; return false }
        val validId=runCatching { java.util.UUID.fromString(parentRunId).toString()==parentRunId }.getOrDefault(false)
        val parent=_runRecords.value.firstOrNull { it.runId==parentRunId }
        if(!validId || IpRangeParser.literalBytes(literal)==null || parent==null || parent.results.none { it.ipAddress==literal }) {
            _statusMessage.value="无法复测：原运行或实际结果中的单 IP 目标不存在，旧记录归属未知时不可复测"
            return false
        }
        val target=IpRangeParser.parseIpFile(literal)
        if(target.size!=1) { _statusMessage.value="无法复测：需要单个 literal IP"; return false }
        return startRun(target,savedTestConfig().copy(testCount=1,downloadTestCount=1),parentRunId,literal)
    }

    private fun startRun(familyRanges:List<IpRangeParser.IpRange>,savedConfig:SpeedTestEngine.TestConfig,parentRunId:String?=null,confirmedLiteral:String?=null):Boolean {
        val config:SpeedTestEngine.TestConfig
        val reserved:com.cfst.app.speedtest.RunCancellation
        try {
            config=if(_randomizeSampleSeed.value) savedConfig.copy(sampleSeed=seedSource()) else savedConfig
            reserved=engine.reserve(config,familyRanges,parentRunId,confirmedLiteral) ?: run { _statusMessage.value="运行尚未结束，请等待原运行收尾";return false }
        } catch(e:Exception) { _statusMessage.value="启动失败：${e.message}";return false }
        _resultDownloadEnabled.value=config.downloadTest
        _resultConfig.value=config
        val runId=engine.runOwner!!.id
        _isStopping.value=false
        _samplingNotice.value=""
        _progress.value=SpeedTestEngine.TestProgress(0,0,"","准备候选")
        _runSummary.value=null
        _results.value=emptyList()
        _isTesting.value=true
        viewModelScope.launch {
            _results.value=emptyList()
            _statusMessage.value="开始测速..."

            val currentResults = mutableListOf<SpeedTestResult>()

            try {
                engine.startTest(
                    ipRanges = familyRanges,
                    config = config,
                    reserved = reserved,
                    beforeNetwork = { refreshNetwork(); networkGuard.begin(runId) },
                    onProgress = onProgress@ { progress ->
                        if(engine.runOwner?.token!==reserved || engine.runOwner?.id!=runId) return@onProgress
                        _runSummary.value=engine.currentRecord?.summary
                        engine.samplingReport?.let { _samplingNotice.value="${it.status} · 优先未覆盖 ${it.priorityNotCovered} · 受限来源 ${it.limitedSources}" }
                        _progress.value = progress
                        _statusMessage.value = "${progress.status} ${progress.currentIp}"
                    },
                    onResult = onResult@ { result ->
                        if(engine.runOwner?.token!==reserved || engine.runOwner?.id!=runId) return@onResult
                        _runSummary.value=engine.currentRecord?.summary
                        currentResults.removeAll { it.ipAddress == result.ipAddress }; currentResults.add(result)
                        _results.value = if (_sortByLatency.value) {
                            SpeedTestResult.rank(currentResults, true)
                        } else {
                            SpeedTestResult.rank(currentResults, false)
                        }
                    },
                    onComplete = onComplete@ { finalResults ->
                        if(engine.runOwner?.token!==reserved || engine.runOwner?.id!=runId) return@onComplete
                        engine.samplingReport?.let { _samplingNotice.value="${it.status} · 优先未覆盖 ${it.priorityNotCovered} · 受限来源 ${it.limitedSources}" }
                        _runSummary.value=engine.lastRecord?.summary
                        val sortedResults = if (_sortByLatency.value) {
                            SpeedTestResult.rank(finalResults, true)
                        } else {
                            SpeedTestResult.rank(finalResults, false)
                        }
                        _results.value = sortedResults
                        networkGuard.end(runId)
                        _statusMessage.value = if (finalResults.isNotEmpty()) {
                            "${engine.stopReason ?: "测速完成"}，保留 ${finalResults.size} 个原始结果；已下载 ${com.cfst.app.utils.DataUnits.formatBytes(engine.lastRecord?.summary?.bodyBytesConsumed ?: 0)}（额外协议开销不计）"
                        } else {
                            "${engine.stopReason ?: "测速完成"}，未找到可用 IP"
                        }
                        val persisted=engine.lastRecord?.let(::persistCompletedRun)==true
                        if(!persisted) _statusMessage.value="测速结果保存失败；本次结果仅在内存，旧历史和索引未改变，请先导出当前结果"
                    }
                )
            } catch (e: java.io.IOException) {
                _isTesting.value = false
                _statusMessage.value = "测速失败：${e.message}"
            } catch (e: Exception) {
                networkGuard.end(runId)
                _isTesting.value = false
                _statusMessage.value = "测速失败：${e.message}"
            } finally {
                engine.releaseRun(reserved)
                if(engine.runOwner?.token===reserved) {
                    networkGuard.end(engine.runOwner!!.id)
                    _isTesting.value=false
                    _isStopping.value=false
                }
            }
        }
        return true
    }
    
    /**
     * 停止测速
     */
    fun stopTest(reason: String = "用户停止") {
        if (!_isTesting.value) return
        _isStopping.value=true
        engine.stopTest(reason)
        _statusMessage.value = "正在停止测速：$reason"
    }
    
    /**
     * 复制 IP 到剪贴板
     */
    fun copyIp(ip: String) {
        val clipboard = application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("IP Address", ip)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(application, "已复制：$ip", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 导出结果为 CSV
     */
    internal suspend fun saveCsv(uri: android.net.Uri, snapshot: String) = withContext(Dispatchers.IO) {
        val output = application.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("无法打开 CSV 输出流")
        output.use { it.write(snapshot.toByteArray(Charsets.UTF_8)); it.flush() }
    }
    fun writeCsv(output: java.io.OutputStream) { output.write(exportResults().toByteArray(Charsets.UTF_8)); output.flush() }
    fun exportResults(): String {
        val builder = StringBuilder()
        builder.appendLine(SpeedTestResult.getCsvHeader())
        _results.value.forEach { result ->
            builder.appendLine(result.toCsvString())
        }
        return builder.toString()
    }
    fun exportHistory(index:Int):String {
        val rows=_historyRecords.value.getOrNull(index) ?: throw IllegalArgumentException("历史记录不存在")
        return SpeedTestResult.getCsvHeader()+"\n"+rows.joinToString("\n") { it.toCsvString() }+"\n"
    }
    
    /**
     * 获取最佳 IP
     */
    fun getBestIp(): SpeedTestResult? {
        val eligible=_results.value.filter { com.cfst.app.ui.presentation.presentResult(it,_resultConfig.value).bucket=="优选" }
        return SpeedTestResult.rank(eligible,!_resultDownloadEnabled.value).firstOrNull()
    }
    fun exportAppState():String {
        check(commitHistory(_historyRecords.value,historyRunIds,_runRecords.value)) { "无法保存历史快照" }
        return (settings as com.cfst.app.settings.AppStateStore).exportState()
    }
    fun restoreAppState(payload:String):Boolean {
        return try {
            check(!_isTesting.value) { "运行中不能恢复" }
            if(!(settings as com.cfst.app.settings.AppStateStore).restoreState(payload)) return false
            _runTimeSeconds.value=settings.getInt("runTimeSeconds",120); _sampleSeed.value=settings.getLong("sampleSeed",0L)
            _randomizeSampleSeed.value=settings.getBoolean("randomizeSampleSeed",true)
            _samplingMigrationNoticePending.value=settings.getBoolean("samplingMigrationNoticePending",false)
            _testCount.value=settings.getInt("testCount",30); _pingCount.value=settings.getInt("pingCount",3)
            _maxConcurrentPings.value=settings.getInt("maxConcurrentPings",8); _budgetBytes.value=settings.getLong("budgetBytes",3145728)
            _downloadTestCount.value=settings.getInt("downloadTestCount",3); _downloadDuration.value=settings.getInt("downloadDuration",3)
            _downloadPort.value=settings.getInt("downloadPort",443); _downloadUrl.value=settings.getString("downloadUrl",SpeedTestEngine.TestConfig().downloadUrl)!!
            _speedLimit.value=settings.getString("speedLimit","0")!!.toDouble(); _latencyLimit.value=settings.getString("latencyLimit","0")!!.toDouble()
            _useIpv6.value=settings.getBoolean("useIpv6",false); _enableDownloadTest.value=settings.getBoolean("enableDownloadTest",true)
            _selectedSpecifiedIpSegments.value=settings.getStringSet("selectedSegments",mutableSetOf())!!.toSet()
            _useSpecifiedIpSegments.value=settings.getBoolean("useSpecifiedIpSegments",false)
            _proxyUrl.value=settings.getString("proxyUrl","")!!; _useProxy.value=settings.getBoolean("useProxy",false)
            _sortByLatency.value=settings.getBoolean("sortByLatency",false)
            ipGeneration=settings.getLong("sourceGeneration",0L)
            _importPreview.value=null; _pastePending.value=false; _pasteText.value=null; retainedDraft=null; draftBaseline=null; _draftPending.value=false
            loadLocalIpRanges()
            val type=com.google.gson.reflect.TypeToken.getParameterized(List::class.java,com.google.gson.reflect.TypeToken.getParameterized(List::class.java,SpeedTestResult::class.java).type).type
            _historyRecords.value=com.cfst.app.settings.SnapshotRecords.histories(settings.getString("history_records","[]")!!)
            val runType=com.google.gson.reflect.TypeToken.getParameterized(List::class.java,com.cfst.app.model.RunRecord::class.java).type
            _runRecords.value=com.cfst.app.settings.SnapshotRecords.runs(settings.getString("run_records","[]")!!)
            historyRunIds=readHistoryIds(_historyRecords.value.size)
            _statusMessage.value="状态已恢复；旧APK降级需单独处理数据，不保证直接覆盖安装"; true
        } catch(e:Exception) { _statusMessage.value="恢复失败：${e.message}"; false }
    }
}
