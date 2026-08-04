package com.mytiantian.updater.vivo

data class VivoOtaResult(
    val updateVersion: String = "",
    val filename: String = "",
    val fileSizeBytes: String = "",
    val fileSizeMb: String = "",
    val downloadUrl: String = "",
    val changelogUrl: String = "",
    val securityPatch: String = "",
    val updateDate: String = "",
    val md5: String = "",
    val channel: String = "NORMAL",
    val rawResponse: String = ""
)

data class QueryHistoryEntry(
    val timestamp: Long,
    val model: String,
    val codename: String,
    val swVersion: String,
    val resultVersion: String,
    val fileSize: String,
    val downloadUrl: String,
    val channel: String = "NORMAL"
)

data class VivoOtaUiState(
    val selectedSeries: String = "X 系列",
    val selectedModelIndex: Int = 0,
    val selectedModel: String = "",
    val selectedCodename: String = "",
    val selectedModelSwVer: String = "",
    val deviceType: String = "phone",
    val softwareVersion: String = "15.0.33.7.W10",
    val androidVersion: Int = 15,
    val isCustomAndroidVersion: Boolean = false,
    val customAndroidVersion: String = "",
    val sn: String = "A0000000000000A",
    val isFullPackage: Boolean = true,
    val queryChannel: String = "NORMAL",
    val queryDomain: String = "CN",
    val isLoading: Boolean = false,
    val result: VivoOtaResult? = null,
    val error: String? = null,
    val cryptoReady: Boolean = false,
    val manualMode: Boolean = false,
    val manualCodename: String = "",
    val manualModelSwVer: String = "",
    val manualModelName: String = "",
    val history: List<QueryHistoryEntry> = emptyList(),
    val historySelectionMode: Boolean = false,
    val selectedHistory: Set<Long> = emptySet(),
    val toastMessage: String? = null,
    val changelogContent: String? = null
)
