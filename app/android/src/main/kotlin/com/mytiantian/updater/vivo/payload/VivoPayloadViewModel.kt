package com.mytiantian.updater.vivo.payload

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytiantian.updater.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // ===== zip 容器内文件浏览（ADR-001 / L0）=====

    private val _zipState = MutableStateFlow(ZipBrowserState())
    val zipState: StateFlow<ZipBrowserState> = _zipState

    /**
     * 独立于 toastEvent 的消息通道。
     * 不新增 PayloadToast 分支——Screen 里对它的 when 是穷尽匹配，加分支会编译失败。
     */
    private val _zipMessage = MutableSharedFlow<String>(extraBufferCapacity = 20)
    val zipMessage = _zipMessage.asSharedFlow()

    /**
     * 串行化全部 zip 侧 HTTP 操作。
     * VivoPayloadHttpUtil 是单例且只有一个 position 游标，并发读写会互相踩踏（技术债 4）。
     */
    private val zipMutex = Mutex()

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

    // ===== zip 容器内文件浏览（ADR-001 / L0 层）=====

    /** 展开「包内文件」时按需加载；已加载过则不再发请求。 */
    fun loadZipEntries(context: Context) {
        viewModelScope.launch {
            zipMutex.withLock {
                if (_zipState.value.entries.isNotEmpty()) return@withLock
                _zipState.value = _zipState.value.copy(isLoading = true, error = null)
                try {
                    val entries = VivoZipBrowser.listZipEntries(VivoPayloadHttpUtil)
                    _zipState.value = _zipState.value.copy(
                        entries = entries,
                        visibleEntries = entries,
                        isLoading = false
                    )
                } catch (e: Exception) {
                    _zipState.value = _zipState.value.copy(
                        isLoading = false,
                        error = context.getString(mapZipErrorRes(e.message))
                    )
                }
            }
        }
    }

    fun toggleZipExpanded(context: Context) {
        val expanded = !_zipState.value.expanded
        _zipState.value = _zipState.value.copy(expanded = expanded)
        if (expanded) loadZipEntries(context)
    }

    fun updateZipSearch(query: String) {
        val all = _zipState.value.entries
        val q = query.trim()
        _zipState.value = _zipState.value.copy(
            searchQuery = query,
            visibleEntries = if (q.isEmpty()) {
                all
            } else {
                all.filter { it.name.contains(q, ignoreCase = true) }
            }
        )
    }

    /**
     * 预览文本条目。
     *
     * @param loadFull false 时只取前 8KB——DEFLATE 场景实际流量约 2~4KB，
     *                 因为 zip 每条目独立压缩，读满即可掐断连接（ADR-001 D5）；
     *                 true 时取完整内容，仍受 10MB 红线约束。
     */
    fun previewEntry(context: Context, entry: ZipEntryInfo, loadFull: Boolean = false) {
        viewModelScope.launch {
            zipMutex.withLock {
                _zipState.value = _zipState.value.copy(
                    previewEntry = entry,
                    isPreviewLoading = true,
                    previewError = null,
                    preview = if (loadFull) _zipState.value.preview else null
                )
                try {
                    val maxOutput = if (loadFull) {
                        ZipEntryInfo.DOWNLOAD_LIMIT_BYTES
                    } else {
                        ZipEntryInfo.PREVIEW_BYTES.toLong()
                    }
                    val maxInput = if (loadFull) {
                        Long.MAX_VALUE
                    } else {
                        VivoZipBrowser.PREVIEW_INPUT_BYTES.toLong()
                    }
                    val data = VivoZipBrowser.readEntryBytes(
                        VivoPayloadHttpUtil, entry, maxOutput, maxInput
                    )
                    // ADR-001 D6：白名单只决定「是否给入口」，能否预览由内容嗅探拍板。
                    // 这样无扩展名的 updater-script 能进，而 .pb 会被 NUL 拦截。
                    if (!VivoZipBrowser.looksLikeText(data.bytes)) {
                        _zipState.value = _zipState.value.copy(
                            isPreviewLoading = false,
                            previewError = context.getString(R.string.zip_preview_not_text)
                        )
                        return@withLock
                    }
                    val (text, encoding) = VivoZipBrowser.decodeText(data.bytes)
                    val truncated = !data.complete
                    _zipState.value = _zipState.value.copy(
                        isPreviewLoading = false,
                        preview = TextPreview(
                            text = text,
                            encoding = encoding,
                            bytesLoaded = data.bytes.size.toLong(),
                            totalSize = entry.uncompressedSize,
                            truncated = truncated,
                            // 截断时复制的是「已加载部分」，仍允许（ADR-001 D9）
                            canCopyAll = text.toByteArray().size <= ZipEntryInfo.CLIPBOARD_LIMIT_BYTES
                        )
                    )
                } catch (e: Exception) {
                    _zipState.value = _zipState.value.copy(
                        isPreviewLoading = false,
                        previewError = context.getString(mapZipErrorRes(e.message))
                    )
                }
            }
        }
    }

    fun dismissPreview() {
        _zipState.value = _zipState.value.copy(
            previewEntry = null,
            preview = null,
            previewError = null,
            isPreviewLoading = false
        )
    }

    /** ADR-001 D3/D12：≤10MB 才可下载，落盘后校验 CRC32 佐证流式 Range 拼接未错位。 */
    fun downloadEntry(context: Context, entry: ZipEntryInfo) {
        viewModelScope.launch {
            zipMutex.withLock {
                if (!entry.canDownload) {
                    _zipMessage.emit(context.getString(R.string.zip_download_too_large))
                    return@withLock
                }
                _zipState.value = _zipState.value.copy(downloadingEntryName = entry.name)
                val displayName = sanitizeFileName(entry.name.substringAfterLast('/'))
                try {
                    val data = VivoZipBrowser.readEntryBytes(
                        VivoPayloadHttpUtil, entry, ZipEntryInfo.DOWNLOAD_LIMIT_BYTES
                    )
                    // 产出被 10MB 上限截断 ⇒ 实际大小与 header 声明不符，按 zip bomb 处理。
                    // header 里的 uncompressedSize 是可伪造的，不能只信它。
                    if (!data.complete) {
                        _zipState.value = _zipState.value.copy(downloadingEntryName = null)
                        _zipMessage.emit(context.getString(R.string.zip_download_incomplete))
                        return@withLock
                    }
                    val crcOk = VivoZipBrowser.crc32(data.bytes) == entry.crc32
                    val saved = saveEntryToDownloads(context, displayName, data.bytes)
                    _zipState.value = _zipState.value.copy(downloadingEntryName = null)
                    _zipMessage.emit(
                        if (!saved) {
                            context.getString(R.string.zip_download_failed, displayName)
                        } else if (crcOk) {
                            context.getString(R.string.zip_download_success, displayName)
                        } else {
                            context.getString(R.string.zip_download_crc_mismatch, displayName)
                        }
                    )
                } catch (e: Exception) {
                    _zipState.value = _zipState.value.copy(downloadingEntryName = null)
                    _zipMessage.emit(context.getString(mapZipErrorRes(e.message)))
                }
            }
        }
    }

    /**
     * ADR-001 D4：Android 10+ 走 MediaStore.Downloads（零权限且用户可见），
     * 低版本回退 App 私有目录。文件均 ≤10MB，整块读入内存后一次性写出即可。
     */
    private fun saveEntryToDownloads(context: Context, displayName: String, data: ByteArray): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return false
                resolver.openOutputStream(uri)?.use { it.write(data) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "VivoOtaTracker"
                )
                if (!dir.exists()) dir.mkdirs()
                File(dir, displayName).writeBytes(data)
                true
            }
        }.getOrDefault(false)
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "entry" }
    }

    private fun mapZipErrorRes(raw: String?): Int {
        return when (raw) {
            "NOT_A_VALID_ZIP" -> R.string.zip_err_not_valid_zip
            "BAD_LOCAL_HEADER" -> R.string.zip_err_bad_local_header
            "BAD_DEFLATE_DATA" -> R.string.zip_err_bad_deflate
            "UNSUPPORTED_ENTRY_METHOD" -> R.string.zip_err_unsupported_method
            else -> R.string.zip_err_generic
        }
    }
}
