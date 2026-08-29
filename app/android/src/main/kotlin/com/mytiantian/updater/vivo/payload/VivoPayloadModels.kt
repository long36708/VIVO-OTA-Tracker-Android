package com.mytiantian.updater.vivo.payload

import chromeos_update_engine.UpdateMetadata

data class Payload(
    val fileName: String,
    val header: PayloadHeader,
    val deltaArchiveManifest: UpdateMetadata.DeltaArchiveManifest,
    val dataOffset: Long,
    val blockSize: Int,
    val archiveSize: Long,
    val isPath: Boolean,
    val sourcePath: String = ""
)

data class PayloadHeader(
    val fileFormatVersion: Long,
    val manifestSize: Long,
    val metadataSignatureSize: Int,
)

data class PartitionInfo(
    val partitionName: String,
    val size: Long,
    val rawSize: Long,
    val sha256: String,
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
)

data class FileInfo(
    val offset: Long,
    val size: Long
)

data class ArchiveInfo(
    val fileName: String,
    val fileSize: Long,
    val securityPatchLevel: String,
    val buildDate: String = "",
    val blockSize: Int = 0,
    val partialUpdate: Boolean = false,
    val minorVersion: Int = 0
)

data class PayloadDumperUiState(
    val pathOrUrl: String = "",
    val archiveInfo: ArchiveInfo? = null,
    val partitions: List<PartitionInfo> = emptyList(),
    val filteredPartitions: List<PartitionInfo> = emptyList(),
    val selectedPartitions: Set<String> = emptySet(),
    val searchQuery: String = "",
    val isParsing: Boolean = false,
    val isExtracting: Boolean = false,
    val error: String? = null,
    val selectedPartition: PartitionInfo? = null
)

sealed class PayloadToast {
    data class ExtractSuccess(val partitionName: String) : PayloadToast()
    data class ExtractFailed(val partitionName: String) : PayloadToast()
    data class BatchComplete(val success: Int, val fail: Int) : PayloadToast()
}

// ===== zip 容器内文件浏览（ADR-001，L0 层）=====
// 层级定义见 docs/GLOSSARY.md：「列条目」(L0)、「提取」(L1)、「浏览」(L2) 三者不可混用。
// 注意：PayloadToast 的 when 在 Screen 里是穷尽匹配，zip 浏览的消息走独立的
// zipMessage 通道（纯字符串），不新增 PayloadToast 分支以免破坏现有穷尽性。

/** zip central directory 中的一条记录，本身不含任何文件内容。 */
data class ZipEntryInfo(
    val name: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val crc32: Long,
    val method: Int,
    val localHeaderOffset: Long,
) {
    val isDirectory: Boolean get() = name.endsWith("/")

    val isStored: Boolean get() = method == METHOD_STORED
    val isDeflate: Boolean get() = method == METHOD_DEFLATE

    /** 仅支持 stored / deflate 两种方式。 */
    val isSupported: Boolean
        get() = !isDirectory && (isStored || isDeflate)

    /**
     * ADR-001 D3：压缩后（网络流量）与解压后（落盘占用）尺寸任一超过 10MB 即禁止下载。
     * 这只是前置判断，解压时另有实时产出校验防线（zip bomb 的 uncompressedSize 可伪造）。
     */
    val canDownload: Boolean
        get() = isSupported &&
            uncompressedSize > 0 &&
            compressedSize <= DOWNLOAD_LIMIT_BYTES &&
            uncompressedSize <= DOWNLOAD_LIMIT_BYTES

    /** ADR-001 D6 白名单初判，决定列表里是否提供「预览」入口；真正的判定在嗅探之后。 */
    val isTextByExtension: Boolean
        get() = TEXT_EXTENSIONS.any { name.substringAfterLast('/').endsWith(it, ignoreCase = true) }

    companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATE = 8

        const val DOWNLOAD_LIMIT_BYTES = 10L * 1024 * 1024
        const val PREVIEW_BYTES = 8 * 1024
        /** Binder 事务上限约 1MB，留足余量。超过则不提供「复制全部」（ADR-001 D8）。 */
        const val CLIPBOARD_LIMIT_BYTES = 128 * 1024

        private val TEXT_EXTENSIONS = listOf(
            ".txt", ".text", ".prop", ".properties", ".sh", ".rc", ".xml", ".json",
            ".cfg", ".conf", ".csv", ".log", ".md", ".ini", ".mf", ".sf", ".script"
        )
    }
}

/** 文本预览结果。 */
data class TextPreview(
    val text: String,
    val encoding: String,
    val bytesLoaded: Long,
    val totalSize: Long,
    /** 因 8KB 上限截断，可由用户按需「加载完整内容」。 */
    val truncated: Boolean,
    /** 是否可整段复制。受 Binder 1MB 上限约束，与 10MB 下载红线无关。 */
    val canCopyAll: Boolean,
)

/** 单条目下载结果。ADR-001 D12：带 CRC32 校验以佐证流式 Range 拼接未错位。 */
sealed class EntryDownloadOutcome {
    data class Success(val fileName: String, val size: Long, val crcOk: Boolean) : EntryDownloadOutcome()
    data class Failure(val reason: String) : EntryDownloadOutcome()
}

/** zip 条目浏览的界面状态。 */
data class ZipBrowserState(
    val entries: List<ZipEntryInfo> = emptyList(),
    val visibleEntries: List<ZipEntryInfo> = emptyList(),
    val searchQuery: String = "",
    val expanded: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val previewEntry: ZipEntryInfo? = null,
    val preview: TextPreview? = null,
    val isPreviewLoading: Boolean = false,
    val previewError: String? = null,
    val downloadingEntryName: String? = null,
)
