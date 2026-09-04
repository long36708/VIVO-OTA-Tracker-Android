package io.github.long36708.updater.vivo

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.long36708.updater.AndroidAppContext
import io.github.long36708.updater.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VivoOtaViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(VivoOtaUiState())
    val uiState: StateFlow<VivoOtaUiState> = _uiState.asStateFlow()

    private val client: VivoOtaClient
    private val prefs: android.content.SharedPreferences

    init {
        val ctx = AndroidAppContext.getApplicationContext()
            ?: throw IllegalStateException("AndroidAppContext not initialized")
        prefs = ctx.getSharedPreferences("vivo_ota", Context.MODE_PRIVATE)
        VivoDeviceDatabase.load(ctx)
        client = VivoOtaClient(ctx)
        loadHistory()
        initCrypto()
        applyDefaultSelection()
    }

    private fun applyDefaultSelection() {
        val defaultSeries = "X 系列"
        val defaultModel = "vivo X200 Pro mini"
        val devices = VivoDeviceDatabase.devicesOf(defaultSeries)
        val index = devices.indexOfFirst { it.model == defaultModel }.coerceAtLeast(0)
        val device = devices.getOrNull(index) ?: devices.firstOrNull() ?: return
        val detectedType = detectDeviceType(defaultSeries)
        _uiState.update {
            it.copy(
                selectedSeries = defaultSeries,
                selectedModelIndex = index,
                selectedModel = device.model,
                selectedCodename = device.codename,
                selectedModelSwVer = device.model_sw_ver,
                deviceType = detectedType,
                androidVersion = 15,
                isCustomAndroidVersion = false
            )
        }
    }

    private fun initCrypto() {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                client.initCrypto()
            } catch (e: Exception) {
                Log.e("VivoOtaViewModel", "Crypto init failed", e)
                false
            }
            _uiState.update { it.copy(cryptoReady = ok) }
        }
    }

    fun selectSeries(series: String) {
        val devices = VivoDeviceDatabase.devicesOf(series)
        val first = devices.firstOrNull()
        val detectedType = detectDeviceType(series)
        _uiState.update {
            it.copy(
                selectedSeries = series,
                selectedModelIndex = 0,
                selectedModel = first?.model ?: "",
                selectedCodename = first?.codename ?: "",
                selectedModelSwVer = first?.model_sw_ver ?: "",
                deviceType = detectedType
            )
        }
    }

    fun selectDevice(index: Int) {
        val devices = VivoDeviceDatabase.devicesOf(_uiState.value.selectedSeries)
        val device = devices.getOrNull(index) ?: return
        _uiState.update {
            it.copy(
                selectedModelIndex = index,
                selectedModel = device.model,
                selectedCodename = device.codename,
                selectedModelSwVer = device.model_sw_ver
            )
        }
    }

    fun updateSoftwareVersion(v: String) {
        val majorVersion = if (v.contains('.')) {
            v.substringBefore('.').toIntOrNull()
        } else {
            v.toIntOrNull()?.takeIf { it in 13..16 }
        }
        if (majorVersion != null && majorVersion > 0) {
            if (majorVersion in 13..16) {
                _uiState.update { it.copy(softwareVersion = v, androidVersion = majorVersion, isCustomAndroidVersion = false) }
            } else {
                _uiState.update { it.copy(softwareVersion = v, androidVersion = majorVersion, isCustomAndroidVersion = true, customAndroidVersion = majorVersion.toString()) }
            }
        } else {
            _uiState.update { it.copy(softwareVersion = v) }
        }
    }

    fun updateAndroidVersion(v: Int) {
        _uiState.update { it.copy(androidVersion = v, isCustomAndroidVersion = false) }
    }

    fun selectCustomAndroidVersion() {
        _uiState.update { it.copy(isCustomAndroidVersion = true) }
    }

    fun updateCustomAndroidVersion(v: String) {
        val num = v.filter { it.isDigit() }
        val ver = num.toIntOrNull() ?: 0
        _uiState.update { it.copy(customAndroidVersion = num, androidVersion = if (ver > 0) ver else it.androidVersion, isCustomAndroidVersion = true) }
    }

    fun updateSn(v: String) { _uiState.update { it.copy(sn = v) } }
    fun updateQueryChannel(channel: String) {
        _uiState.update {
            // 尝鲜 / 公测 / 内测通道仅支持增量包，强制锁定为增量
            if (channel != "NORMAL") {
                it.copy(queryChannel = channel, isFullPackage = false)
            } else {
                it.copy(queryChannel = channel)
            }
        }
    }

    fun updateQueryDomain(domain: String) {
        if (domain !in listOf("CN", "GLOBAL")) return
        _uiState.update { it.copy(queryDomain = domain) }
    }

    fun updateDeviceType(type: String) { _uiState.update { it.copy(deviceType = type) } }
    fun togglePackageType() {
        _uiState.update {
            // 尝鲜 / 公测 / 内测通道下禁止切换包类型，始终保持增量
            if (it.queryChannel != "NORMAL") it else it.copy(isFullPackage = !it.isFullPackage)
        }
    }
    fun toggleManualMode() { _uiState.update { it.copy(manualMode = !it.manualMode) } }
    fun updateManualCodename(v: String) { _uiState.update { it.copy(manualCodename = v) } }
    fun updateManualModelSwVer(v: String) { _uiState.update { it.copy(manualModelSwVer = v) } }
    fun updateManualModelName(v: String) { _uiState.update { it.copy(manualModelName = v) } }
    fun clearToast() { _uiState.update { it.copy(toastMessage = null) } }

    fun deleteHistoryEntry(timestamp: Long) {
        val updated = _uiState.value.history.filterNot { it.timestamp == timestamp }
        _uiState.update { it.copy(history = updated) }
        saveHistory(updated)
    }

    fun toggleHistorySelectionMode() {
        _uiState.update { it.copy(historySelectionMode = !it.historySelectionMode, selectedHistory = emptySet()) }
    }

    fun toggleHistorySelection(timestamp: Long) {
        _uiState.update {
            val newSet = if (timestamp in it.selectedHistory) it.selectedHistory - timestamp else it.selectedHistory + timestamp
            it.copy(selectedHistory = newSet)
        }
    }

    fun selectAllHistory() {
        val all = _uiState.value.history.map { it.timestamp }.toSet()
        _uiState.update { it.copy(selectedHistory = all) }
    }

    fun deleteSelectedHistory() {
        val selected = _uiState.value.selectedHistory
        val updated = _uiState.value.history.filterNot { it.timestamp in selected }
        _uiState.update { it.copy(history = updated, selectedHistory = emptySet(), historySelectionMode = false) }
        saveHistory(updated)
    }

    fun isAllHistorySelected(): Boolean {
        return _uiState.value.history.isNotEmpty() &&
            _uiState.value.history.all { it.timestamp in _uiState.value.selectedHistory }
    }

    private fun fetchChangelog(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val content = client.fetchChangelog(url)
            val ctx = AndroidAppContext.getApplicationContext()!!
            _uiState.update { it.copy(changelogContent = content ?: ctx.getString(R.string.no_changelog)) }
        }
    }

    /**
     * 从历史记录按需读取更新日志：纯读取，不写入全局 changelogContent，
     * 由独立的日志页自行管理加载状态，避免与查询结果的版本号混淆。
     */
    suspend fun getChangelog(url: String): String? = withContext(Dispatchers.IO) {
        client.fetchChangelog(url)
    }


    private fun detectDeviceType(series: String): String {
        return if (series.contains("平板") || series.contains("穿戴")) "tablet" else "phone"
    }

    fun query() {
        val state = _uiState.value
        if (!state.cryptoReady) return

        val codename: String
        val modelSwVer: String
        val modelName: String

        if (state.manualMode) {
            codename = state.manualCodename.trim()
            modelSwVer = state.manualModelSwVer.trim()
            modelName = state.manualModelName.trim().ifEmpty { codename }
            if (codename.isEmpty()) return
        } else {
            codename = state.selectedCodename
            modelSwVer = state.selectedModelSwVer
            modelName = state.selectedModel
            if (codename.isEmpty()) return
        }
        if (state.softwareVersion.isEmpty()) return

        _uiState.update { it.copy(isLoading = true, error = null, result = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = client.query(
                    codename = codename,
                    modelSwVer = modelSwVer,
                    swVersion = state.softwareVersion,
                    androidVersion = state.androidVersion,
                    isPhone = state.deviceType == "phone",
                    isFull = state.isFullPackage,
                    sn = state.sn,
                    channel = VivoOtaClient.QueryChannel.valueOf(state.queryChannel),
                    domain = VivoOtaClient.Domain.valueOf(state.queryDomain)
                )

                val hasUpdate = result.updateVersion.isNotEmpty() &&
                        result.updateVersion != "(Not found)" &&
                        result.filename.isNotEmpty() &&
                        result.filename != "(Not found)"

                if (hasUpdate) {
                    val ctx = AndroidAppContext.getApplicationContext()!!
                    addToHistory(
                        model = modelName,
                        codename = codename,
                        swVersion = state.softwareVersion,
                        result = result,
                        querySoftwareVersion = state.softwareVersion,
                        manualMode = state.manualMode,
                        manualCodename = state.manualCodename,
                        manualModelSwVer = state.manualModelSwVer,
                        manualModelName = state.manualModelName,
                        androidVersion = state.androidVersion,
                        deviceType = state.deviceType,
                        isFullPackage = state.isFullPackage,
                        queryChannel = state.queryChannel,
                        queryDomain = state.queryDomain,
                        changelogUrl = result.changelogUrl
                    )
                    _uiState.update {
                        it.copy(isLoading = false, result = result, toastMessage = ctx.getString(R.string.toast_success))
                    }
                    if (result.changelogUrl.isNotEmpty() && result.changelogUrl != "(Not found)") {
                        _uiState.update { it.copy(changelogContent = "loading") }
                        fetchChangelog(result.changelogUrl)
                    }
                } else {
                    val ctx = AndroidAppContext.getApplicationContext()!!
                    _uiState.update {
                        it.copy(isLoading = false, result = result, toastMessage = ctx.getString(R.string.toast_no_update))
                    }
                }
            } catch (e: Exception) {
                Log.e("VivoOtaViewModel", "Query failed", e)
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun addToHistory(
        model: String,
        codename: String,
        swVersion: String,
        result: VivoOtaResult,
        querySoftwareVersion: String,
        manualMode: Boolean,
        manualCodename: String,
        manualModelSwVer: String,
        manualModelName: String,
        androidVersion: Int,
        deviceType: String,
        isFullPackage: Boolean,
        queryChannel: String,
        queryDomain: String,
        changelogUrl: String
    ) {
        val now = System.currentTimeMillis()
        val entry = QueryHistoryEntry(
            timestamp = now,
            model = model,
            codename = codename,
            swVersion = swVersion,
            resultVersion = result.updateVersion,
            fileSize = result.fileSizeMb,
            downloadUrl = result.downloadUrl,
            channel = result.channel,
            querySoftwareVersion = querySoftwareVersion,
            manualMode = manualMode,
            manualCodename = manualCodename,
            manualModelSwVer = manualModelSwVer,
            manualModelName = manualModelName,
            androidVersion = androidVersion,
            deviceType = deviceType,
            isFullPackage = isFullPackage,
            queryChannel = queryChannel,
            queryDomain = queryDomain,
            changelogUrl = changelogUrl
        )
        val current = _uiState.value.history.toMutableList()
        // 去重：以"查询条件签名"为键（而非 downloadUrl，后者常含动态签名/时间戳参数，每次都不同）。
        val sig = querySignature(entry)
        val dupIndex = current.indexOfFirst { querySignature(it) == sig }
        if (dupIndex >= 0) {
            current[dupIndex] = entry
        } else {
            current.add(entry)
        }
        // 按查询时间降序排序（最新在前），并截断到最多 20 条。
        val updated = current.sortedByDescending { it.timestamp }.take(20)
        _uiState.update { it.copy(history = updated) }
        saveHistory(updated)
    }

    /**
     * 查询条件签名：把能唯一确定"这次查的是什么"的输入字段归一化成一个字符串。
     * 排除 timestamp / downloadUrl / 结果字段（这些每次都可能变化，不能作为判重依据）。
     * 相同查询条件必然生成相同签名，从而正确去重。
     */
    private fun querySignature(e: QueryHistoryEntry): String =
        listOf(
            e.manualMode,
            e.manualCodename.trim(),
            e.manualModelSwVer.trim(),
            e.manualModelName.trim(),
            e.querySoftwareVersion.trim(),
            e.androidVersion,
            e.deviceType.trim(),
            e.isFullPackage,
            e.queryChannel.trim(),
            e.queryDomain.trim()
        ).joinToString("|")

    fun clearHistory() {
        _uiState.update { it.copy(history = emptyList()) }
        saveHistory(emptyList())
    }

    /**
     * 一键回填：把历史记录中的查询条件写回表单状态。
     * 仅填充表单，不自动发起查询，由用户自行点击查询按钮。
     */
    fun fillHistoryBack(entry: QueryHistoryEntry) {
        _uiState.update {
            it.copy(
                manualMode = entry.manualMode,
                manualCodename = entry.manualCodename,
                manualModelSwVer = entry.manualModelSwVer,
                manualModelName = entry.manualModelName,
                softwareVersion = entry.querySoftwareVersion,
                androidVersion = entry.androidVersion,
                deviceType = entry.deviceType,
                isFullPackage = entry.isFullPackage,
                queryChannel = entry.queryChannel,
                queryDomain = entry.queryDomain
            )
        }
    }

    private fun loadHistory() {
        try {
            val json = prefs.getString("history", "") ?: ""
            if (json.isEmpty()) return
            val arr = JSONArray(json)
            val list = mutableListOf<QueryHistoryEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    QueryHistoryEntry(
                        timestamp = o.getLong("timestamp"),
                        model = o.getString("model"),
                        codename = o.getString("codename"),
                        swVersion = o.getString("swVersion"),
                        resultVersion = o.optString("resultVersion", ""),
                        fileSize = o.optString("fileSize", ""),
                        downloadUrl = o.optString("downloadUrl", ""),
                        channel = o.optString("channel", "NORMAL"),
                        querySoftwareVersion = o.optString("querySoftwareVersion", ""),
                        manualMode = o.optBoolean("manualMode", false),
                        manualCodename = o.optString("manualCodename", ""),
                        manualModelSwVer = o.optString("manualModelSwVer", ""),
                        manualModelName = o.optString("manualModelName", ""),
                        androidVersion = o.optInt("androidVersion", 15),
                        deviceType = o.optString("deviceType", "phone"),
                        isFullPackage = o.optBoolean("isFullPackage", true),
                        queryChannel = o.optString("queryChannel", "NORMAL"),
                        queryDomain = o.optString("queryDomain", "CN"),
                        changelogUrl = o.optString("changelogUrl", "")
                    )
                )
            }
            // 兜底：按查询时间降序排序（最新在前），兼容早期未排序的存储数据。
            val sorted = list.sortedByDescending { it.timestamp }
            _uiState.update { it.copy(history = sorted) }
        } catch (e: Exception) {
            Log.w("VivoOtaViewModel", "Failed to load history", e)
        }
    }

    private fun saveHistory(list: List<QueryHistoryEntry>) {
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(JSONObject().apply {
                    put("timestamp", e.timestamp)
                    put("model", e.model)
                    put("codename", e.codename)
                    put("swVersion", e.swVersion)
                    put("resultVersion", e.resultVersion)
                    put("fileSize", e.fileSize)
                    put("downloadUrl", e.downloadUrl)
                    put("channel", e.channel)
                    put("querySoftwareVersion", e.querySoftwareVersion)
                    put("manualMode", e.manualMode)
                    put("manualCodename", e.manualCodename)
                    put("manualModelSwVer", e.manualModelSwVer)
                    put("manualModelName", e.manualModelName)
                    put("androidVersion", e.androidVersion)
                    put("deviceType", e.deviceType)
                    put("isFullPackage", e.isFullPackage)
                    put("queryChannel", e.queryChannel)
                    put("queryDomain", e.queryDomain)
                    put("changelogUrl", e.changelogUrl)
                })
            }
            prefs.edit().putString("history", arr.toString()).apply()
        } catch (e: Exception) {
            Log.w("VivoOtaViewModel", "Failed to save history", e)
        }
    }

    fun formatTime(ts: Long): String {
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
