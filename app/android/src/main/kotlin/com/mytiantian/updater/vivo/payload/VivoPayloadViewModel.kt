package com.mytiantian.updater.vivo.payload

import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VivoPayloadViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PayloadDumperUiState())
    val uiState: StateFlow<PayloadDumperUiState> = _uiState

    private val _toastEvent = MutableSharedFlow<PayloadToast>(extraBufferCapacity = 20)
    val toastEvent = _toastEvent.asSharedFlow()

    private var currentPayload: Payload? = null

    /** 输入框中的链接文本，供界面双向绑定。 */
    fun updateInputUrl(url: String) {
        _uiState.value = _uiState.value.copy(inputUrl = url)
    }

    /** 在输入框中手动提交链接并解析。 */
    fun submitUrl() {
        val target = _uiState.value.inputUrl.trim()
        if (target.isEmpty()) return
        // 把输入框内容设为解析目标，并清掉上一次的结果，确保用新地址解析。
        _uiState.value = _uiState.value.copy(
            pathOrUrl = target,
            parseRequestId = _uiState.value.parseRequestId + 1,
            isParsing = false,
            error = null,
            archiveInfo = null,
            partitions = emptyList(),
            filteredPartitions = emptyList(),
            selectedPartitions = emptySet(),
            selectedPartition = null
        )
    }

    /** 从查询结果直接带入下载链接，界面打开后自动解析。 */
    fun parseFromUrl(url: String) {
        val target = url.trim()
        if (target.isEmpty()) return
        if (_uiState.value.pathOrUrl == target && _uiState.value.partitions.isNotEmpty()) return
        _uiState.value = _uiState.value.copy(
            pathOrUrl = target,
            inputUrl = target,
            parseRequestId = _uiState.value.parseRequestId + 1,
            isParsing = false,
            error = null,
            archiveInfo = null,
            partitions = emptyList(),
            filteredPartitions = emptyList(),
            selectedPartitions = emptySet(),
            selectedPartition = null
        )
    }

    fun selectPartition(partitionName: String) {
        val partition = _uiState.value.partitions.find { it.partitionName == partitionName }
        _uiState.value = _uiState.value.copy(selectedPartition = partition)
    }

    fun clearSelectedPartition() {
        _uiState.value = _uiState.value.copy(selectedPartition = null)
    }

    fun toggleSelection(partitionName: String) {
        val current = _uiState.value.selectedPartitions
        _uiState.value = _uiState.value.copy(
            selectedPartitions = if (current.contains(partitionName)) {
                current - partitionName
            } else {
                current + partitionName
            }
        )
    }

    fun selectAll() {
        _uiState.value = _uiState.value.copy(
            selectedPartitions = _uiState.value.partitions.map { it.partitionName }.toSet()
        )
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedPartitions = emptySet())
    }

    fun isAllSelected(): Boolean {
        val state = _uiState.value
        return state.partitions.isNotEmpty() &&
            state.selectedPartitions.size == state.partitions.size
    }

    fun parsePayload(url: String) {
        val target = url.trim()
        viewModelScope.launch {
            if (!target.startsWith("http://") && !target.startsWith("https://")) {
                _uiState.value = _uiState.value.copy(
                    isParsing = false,
                    error = "Only online URL is supported"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                pathOrUrl = target,
                inputUrl = target,
                isParsing = true,
                error = null,
                archiveInfo = null,
                partitions = emptyList(),
                filteredPartitions = emptyList(),
                selectedPartitions = emptySet(),
                selectedPartition = null
            )
            try {
                Log.i("VivoPayload", "parseFromUrl: start, target=$target")
                VivoPayloadHttpUtil.init(target)
                Log.i("VivoPayload", "parseFromUrl: http init done, fileLength=${VivoPayloadHttpUtil.length()}, fileName=${VivoPayloadHttpUtil.getFileName()}")
                val payloadOffset = PayloadUtil.getPayloadOffset(target)
                Log.i("VivoPayload", "parseFromUrl: payloadOffset=$payloadOffset")
                val payload = PayloadUtil.initPayload(
                    VivoPayloadHttpUtil.getFileName(),
                    VivoPayloadHttpUtil,
                    payloadOffset
                ).copy(sourcePath = target)

                currentPayload = payload
                Log.i("VivoPayload", "parseFromUrl: initPayload done, manifest partitions=${payload.deltaArchiveManifest.partitionsList.size}, blockSize=${payload.deltaArchiveManifest.blockSize}")
                val partitionList = PayloadUtil.getPartitionInfoList(payload)
                Log.i("VivoPayload", "parseFromUrl: getPartitionInfoList done, size=${partitionList.size}")
                val manifest = payload.deltaArchiveManifest
                val archiveInfo = ArchiveInfo(
                    fileName = payload.fileName,
                    fileSize = payload.archiveSize,
                    securityPatchLevel = manifest.securityPatchLevel,
                    buildDate = formatTimestamp(manifest.maxTimestamp),
                    blockSize = manifest.blockSize,
                    partialUpdate = manifest.partialUpdate,
                    minorVersion = manifest.minorVersion
                )

                _uiState.value = _uiState.value.copy(
                    archiveInfo = archiveInfo,
                    partitions = partitionList,
                    filteredPartitions = partitionList,
                    isParsing = false
                )
            } catch (e: Exception) {
                val msg = mapErrorMessage(e.message)
                Log.e("VivoPayload", "parseFromUrl FAILED: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isParsing = false,
                    error = msg
                )
                _toastEvent.emit(PayloadToast.Error(msg))
            }
        }
    }

    /**
     * 把底层解析异常映射为用户友好的提示文案。
     * 解析层用特定标记文案区分错误类型，这里转成易懂的中文说明。
     */
    private fun mapErrorMessage(raw: String?): String {
        return when (raw) {
            "NOT_A_PAYLOAD_ZIP" ->
                "这不是 A/B 增量包（payload.bin 不存在）。该 OTA 包可能是 recovery 全量包，无法用此工具解析。"
            "NOT_A_VALID_ZIP" ->
                "链接指向的文件不是有效的 OTA zip，请检查链接或文件是否完整。"
            else -> raw ?: "解析失败，请重试"
        }
    }

    fun extractPartition(partitionInfo: PartitionInfo) {
        val payload = currentPayload ?: return
        viewModelScope.launch {
            val success = doExtract(payload, partitionInfo)
            if (success) {
                _toastEvent.emit(PayloadToast.ExtractSuccess(partitionInfo.partitionName))
            } else {
                _toastEvent.emit(PayloadToast.ExtractFailed(partitionInfo.partitionName))
            }
        }
    }

    fun extractSelectedPartitions() {
        val payload = currentPayload ?: return
        val selected = _uiState.value.selectedPartitions
            .mapNotNull { name -> _uiState.value.partitions.find { it.partitionName == name } }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExtracting = true)
            var success = 0
            var fail = 0
            for (partition in selected) {
                val ok = doExtract(payload, partition)
                if (ok) success++ else fail++
                if (ok) {
                    _toastEvent.emit(PayloadToast.ExtractSuccess(partition.partitionName))
                } else {
                    _toastEvent.emit(PayloadToast.ExtractFailed(partition.partitionName))
                }
            }
            _uiState.value = _uiState.value.copy(
                isExtracting = false,
                selectedPartitions = emptySet()
            )
            _toastEvent.emit(PayloadToast.BatchComplete(success, fail))
        }
    }

    private suspend fun doExtract(payload: Payload, partitionInfo: PartitionInfo): Boolean {
        markDownloading(partitionInfo.partitionName, true)
        try {
            val safeName = payload.fileName
                .substringBefore('?')
                .removeSuffix(".zip")
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .ifBlank { "payload" }
            val outputDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VivoOtaTracker/$safeName"
            ).absolutePath

            val partitionUpdate = payload.deltaArchiveManifest.partitionsList.find {
                it.partitionName == partitionInfo.partitionName
            } ?: throw RuntimeException("Partition not found")

            PayloadUtil.extractPartition(
                partitionUpdate,
                VivoPayloadHttpUtil,
                outputDir,
                payload
            ) { progress ->
                updateProgress(partitionInfo.partitionName, progress)
            }
            markDownloading(partitionInfo.partitionName, false)
            return true
        } catch (e: Exception) {
            markDownloading(partitionInfo.partitionName, false)
            return false
        }
    }

    private fun markDownloading(partitionName: String, downloading: Boolean) {
        _uiState.value = _uiState.value.copy(
            partitions = _uiState.value.partitions.map {
                if (it.partitionName == partitionName) {
                    it.copy(isDownloading = downloading, progress = 0f)
                } else it
            }
        )
        updateFilteredPartitions()
    }

    private fun updateProgress(partitionName: String, progress: Long) {
        _uiState.value = _uiState.value.copy(
            partitions = _uiState.value.partitions.map {
                if (it.partitionName == partitionName) {
                    it.copy(progress = progress.toFloat())
                } else it
            }
        )
        updateFilteredPartitions()
    }

    private fun updateFilteredPartitions() {
        _uiState.value = _uiState.value.copy(
            filteredPartitions = _uiState.value.partitions
        )
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(timestamp * 1000L))
        }.getOrDefault("")
    }
}
