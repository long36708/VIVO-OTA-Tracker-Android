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
    val operationsCount: Int = 0,
    val mergeOperationsCount: Int = 0,
    val typeStats: Map<String, Int> = emptyMap(),
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
    val inputUrl: String = "",
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
