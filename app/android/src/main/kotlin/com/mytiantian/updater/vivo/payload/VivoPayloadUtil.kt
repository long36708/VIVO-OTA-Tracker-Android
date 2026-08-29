package com.mytiantian.updater.vivo.payload

import android.util.Log
import chromeos_update_engine.UpdateMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

object PayloadUtil {

    private const val MAGIC_VALUE = "CrAU"
    private const val FORMAT_VERSION = 2L
    private const val CENSIG = 0x02014b50L
    private const val LOCSIG = 0x04034b50L
    private const val ENDSIG = 0x06054b50L
    private const val ENDHDR = 22
    private const val ZIP64_ENDSIG = 0x06064b50L
    private const val ZIP64_LOCSIG = 0x07064b50L
    private const val ZIP64_LOCHDR = 20
    private const val ZIP64_MAGICVAL = 0xFFFFFFFFL

    private val mutex by lazy { Mutex() }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private suspend fun initPayloadFromHttp(
        fileName: String,
        httpUtil: VivoPayloadHttpUtil,
        payloadOffset: Long
    ): Payload {
        httpUtil.seek(payloadOffset)
        val magicBytes = ByteArray(4)
        httpUtil.readSync(magicBytes)
        if (String(magicBytes, StandardCharsets.UTF_8) != MAGIC_VALUE) {
            throw IOException("Invalid magic value")
        }

        val fileFormatVersionBytes = ByteArray(8)
        httpUtil.readSync(fileFormatVersionBytes)
        val fileFormatVersion = ByteBuffer.wrap(fileFormatVersionBytes).order(ByteOrder.BIG_ENDIAN).long
        if (fileFormatVersion != FORMAT_VERSION) {
            throw IOException("Unsupported file format version")
        }

        val manifestSizeBytes = ByteArray(8)
        httpUtil.readSync(manifestSizeBytes)
        val manifestSize = ByteBuffer.wrap(manifestSizeBytes).order(ByteOrder.BIG_ENDIAN).long

        val metadataSignatureSizeBytes = ByteArray(4)
        httpUtil.readSync(metadataSignatureSizeBytes)
        val metadataSignatureSize = ByteBuffer.wrap(metadataSignatureSizeBytes).order(ByteOrder.BIG_ENDIAN).int

        val manifest = ByteArray(manifestSize.toInt())
        httpUtil.readSync(manifest)
        val metadataSignatureMessage = ByteArray(metadataSignatureSize)
        httpUtil.readSync(metadataSignatureMessage)
        val deltaArchiveManifest = UpdateMetadata.DeltaArchiveManifest.parseFrom(manifest)
        val dataOffset: Long = httpUtil.position()
        val payloadHeader = PayloadHeader(fileFormatVersion, manifestSize, metadataSignatureSize)
        return Payload(
            fileName,
            payloadHeader,
            deltaArchiveManifest,
            dataOffset,
            deltaArchiveManifest.blockSize,
            httpUtil.length(),
            false
        )
    }

    suspend fun initPayload(fileName: String, input: Any, payloadOffset: Long): Payload {
        if (payloadOffset == -1L) {
            throw IOException("Invalid payload offset value")
        }
        return when (input) {
            is VivoPayloadHttpUtil -> initPayloadFromHttp(fileName, input, payloadOffset)
            else -> throw IllegalArgumentException("Only online URL is supported")
        }
    }

    fun getPartitionInfoList(payload: Payload): List<PartitionInfo> {
        val blockSize = payload.deltaArchiveManifest.blockSize
        val srcCount = payload.deltaArchiveManifest.partitionsList.size
        Log.i("VivoPayload", "getPartitionInfoList: input partitions=$srcCount, blockSize=$blockSize")
        return payload.deltaArchiveManifest.partitionsList.map { partition ->
            // 真实镜像大小：所有 dst extents 占用的块数 * blockSize。
            // 某些分区（纯 ZERO / 无数据）operationsList 或 dstExtentsList 可能为空，需做保护。
            val rawSize = partition.operationsList
                .flatMap { it.dstExtentsList }
                .mapNotNull { extent -> extent?.let { it.startBlock + it.numBlocks } }
                .maxOrNull()
                ?.let { (it * blockSize).toLong() }
                ?: partition.newPartitionInfo.size

            val typeStats = partition.operationsList
                .groupingBy { it.type.name }
                .eachCount()

            PartitionInfo(
                partitionName = partition.partitionName,
                size = partition.newPartitionInfo.size,
                rawSize = rawSize,
                sha256 = partition.newPartitionInfo.hash.toByteArray().toHexString(),
                operationsCount = partition.operationsList.size,
                mergeOperationsCount = partition.mergeOperationsList.size,
                typeStats = typeStats
            )
        }
    }

    private suspend fun extractFromHttp(
        op: UpdateMetadata.InstallOperation,
        partOutput: RandomAccessFile,
        httpUtil: VivoPayloadHttpUtil,
        blockSize: Int,
        offset: Long,
    ) {
        mutex.withLock {
            httpUtil.seek(offset + op.dataOffset)
            withContext(Dispatchers.IO) {
                partOutput.seek(op.dstExtentsList[0].startBlock * blockSize)
            }

            val copyCompressedData: (CompressorInputStream) -> Unit = { compressorInputStream ->
                compressorInputStream.use { input ->
                    FileOutputStream(partOutput.fd).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            when (op.type) {
                UpdateMetadata.InstallOperation.Type.REPLACE_XZ -> {
                    val data = ByteArray(op.dataLength.toInt())
                    httpUtil.readSync(data)
                    copyCompressedData(XZCompressorInputStream(data.inputStream().buffered()))
                }
                UpdateMetadata.InstallOperation.Type.REPLACE_BZ -> {
                    val data = ByteArray(op.dataLength.toInt())
                    httpUtil.readSync(data)
                    copyCompressedData(BZip2CompressorInputStream(BufferedInputStream(data.inputStream().buffered())))
                }
                UpdateMetadata.InstallOperation.Type.REPLACE -> {
                    val data = ByteArray(op.dataLength.toInt())
                    httpUtil.readSync(data)
                    withContext(Dispatchers.IO) {
                        partOutput.write(data)
                    }
                }
                UpdateMetadata.InstallOperation.Type.ZERO -> {
                    val data = ByteArray(op.dataLength.toInt()) { 0x00 }
                    withContext(Dispatchers.IO) {
                        partOutput.write(data)
                    }
                }
                else -> throw RuntimeException("Unsupported operation type ${op.type}")
            }
        }
    }

    suspend fun extractPartition(
        metadataPartition: UpdateMetadata.PartitionUpdate,
        input: Any,
        outputDir: String,
        payload: Payload,
        onProgressUpdate: (Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val downloadDir = Paths.get(outputDir)
            if (!Files.exists(downloadDir)) {
                Files.createDirectories(downloadDir)
            }

            RandomAccessFile(
                "$outputDir/${metadataPartition.partitionName}.img",
                "rw"
            ).use { partOutput ->
                val httpUtil = input as? VivoPayloadHttpUtil
                    ?: throw IllegalArgumentException("Only online URL is supported")
                metadataPartition.operationsList.forEach { operation ->
                    extractFromHttp(
                        operation,
                        partOutput,
                        httpUtil,
                        payload.blockSize,
                        payload.dataOffset
                    )
                    onProgressUpdate(partOutput.channel.position())
                }
            }
        }
    }

    suspend fun getPayloadOffset(url: String): Long {
        if (url.substringBefore('?').endsWith(".bin")) return 0L

        val endBytes = ByteArray(4096)
        val fileName = "payload.bin"
        val httpUtil = VivoPayloadHttpUtil

        httpUtil.seek(httpUtil.length() - 4096)
        httpUtil.readSync(endBytes)
        val centralDirectoryInfo = locateCentralDirectory(endBytes, httpUtil.length())
        if (centralDirectoryInfo.offset < 0 || centralDirectoryInfo.size <= 0) {
            throw IOException("NOT_A_VALID_ZIP")
        }

        httpUtil.seek(centralDirectoryInfo.offset)
        val centralDirectory = ByteArray(centralDirectoryInfo.size.toInt())
        httpUtil.readSync(centralDirectory)

        val localHeaderOffset = locateLocalFileHeader(centralDirectory, fileName)
        Log.i("VivoPayload", "getPayloadOffset: cenOffset=${centralDirectoryInfo.offset}, cenSize=${centralDirectoryInfo.size}, localHeaderOffset=$localHeaderOffset")
        if (localHeaderOffset < 0) {
            throw IOException("NOT_A_PAYLOAD_ZIP")
        }

        // local header 读取缓冲需足够大：vivo 的 payload.bin 的 local header
        // 可能包含极长的 fileName / extra field（实测可达数万字节），
        // 读太小会导致 locateLocalFileOffset 越界。用 256KB 保险。
        val localHeaderBytes = ByteArray(256 * 1024)
        httpUtil.seek(localHeaderOffset)
        httpUtil.readSync(localHeaderBytes)
        val headHex = localHeaderBytes.take(16).joinToString(" ") { "%02x".format(it) }
        Log.i("VivoPayload", "localHeader @$localHeaderOffset first16=$headHex")
        val off = locateLocalFileOffset(localHeaderBytes)
        if (off < 0) {
            Log.e("VivoPayload", "localHeader parse failed, first16=$headHex")
            throw IOException("Failed to parse payload.bin local header")
        }
        val probeOffset = localHeaderOffset + off
        val probe = ByteArray(32)
        httpUtil.seek(probeOffset)
        val probeRead = httpUtil.readSync(probe)
        val probeHex = probe.take(probeRead).joinToString(" ") { "%02x".format(it) }
        Log.d("VivoPayload", "probe @$probeOffset read=$probeRead hex=$probeHex")
        // 也探针附近 ±64 字节范围，确认 CrAU(43 72 41 55) 真实位置
        val before = ByteArray(64)
        httpUtil.seek((probeOffset - 64).coerceAtLeast(0))
        val beforeRead = httpUtil.readSync(before)
        val beforeHex = before.take(beforeRead).joinToString(" ") { "%02x".format(it) }
        Log.d("VivoPayload", "probeBefore @${probeOffset - 64} read=$beforeRead hex=$beforeHex")
        return off + localHeaderOffset
    }

    internal fun locateCentralDirectory(byteArray: ByteArray, fileLength: Long): FileInfo {
        val byteBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        val offset = byteBuffer.capacity() - ENDHDR
        var cenSize: Long = -1
        var cenOffset: Long = -1

        for (i in 0..byteBuffer.capacity() - ENDHDR) {
            byteBuffer.position(offset - i)
            if (byteBuffer.getInt().toLong() == ENDSIG) {
                val endSigOffset = byteBuffer.position()
                byteBuffer.position(byteBuffer.position() + 12)

                if (byteBuffer.getInt().toUInt().toLong() == ZIP64_MAGICVAL) {
                    byteBuffer.position(endSigOffset - ZIP64_LOCHDR - 4)
                    if (byteBuffer.getInt().toLong() == ZIP64_LOCSIG) {
                        byteBuffer.position(byteBuffer.position() + 4)
                        val zip64EndSigOffset = byteBuffer.getLong()
                        byteBuffer.position(byteArray.size - (fileLength - zip64EndSigOffset).toInt())
                        if (byteBuffer.getInt().toLong() == ZIP64_ENDSIG) {
                            byteBuffer.position(byteBuffer.position() + 36)
                            cenSize = byteBuffer.getLong().toULong().toLong()
                            cenOffset = byteBuffer.getLong().toULong().toLong()
                        }
                    }
                } else {
                    byteBuffer.position(endSigOffset + 8)
                    cenSize = byteBuffer.getInt().toUInt().toLong()
                    cenOffset = byteBuffer.getInt().toUInt().toLong()
                    break
                }
            }
        }
        return FileInfo(cenOffset, cenSize)
    }

    private fun locateLocalFileHeader(byteArray: ByteArray, fileName: String): Long {
        val byteBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        var localHeaderOffset: Long = -1

        while (true) {
            if (byteBuffer.getInt().toLong() == CENSIG) {
                byteBuffer.position(byteBuffer.position() + 24)
                val fileNameLength = byteBuffer.getShort().toUInt().toInt()
                val extraFieldLength = byteBuffer.getShort().toUInt().toInt()
                val fileCommentLength = byteBuffer.getShort().toUInt().toInt()
                byteBuffer.position(byteBuffer.position() + 8)
                val localHeaderOffsetTemp = byteBuffer.getInt().toUInt().toLong()
                val fileNameBytes = ByteArray(fileNameLength)
                byteBuffer.get(fileNameBytes)
                val entryName = String(fileNameBytes, Charsets.UTF_8)
                Log.d("VivoPayload", "central entry: '$entryName' -> localOffset=$localHeaderOffsetTemp")
                if (entryName.endsWith("payload.bin") || fileName == entryName) {
                    localHeaderOffset = localHeaderOffsetTemp
                    break
                }
                byteBuffer.position(byteBuffer.position() + extraFieldLength + fileCommentLength)
            } else {
                break
            }
        }
        return localHeaderOffset
    }

    private fun locateLocalFileOffset(byteArray: ByteArray): Long {
        val byteBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        var localFileOffset: Long = -1

        if (byteBuffer.getInt().toLong() == LOCSIG) {
            // Local file header 字段布局(从 0 起)：
            // LOCSIG(4) + version(2) + flag(2) + method(2) + modTime(2) + modDate(2)
            // + crc32(4) + compSize(4) + uncompSize(4) + fileNameLength(2) [偏移 26]
            // + extraFieldLength(2) [偏移 28]。getInt() 已消耗前 4 字节(position=4)，
            // 需再跳 22 字节才能到达 fileNameLength 字段(绝对偏移 26)。
            byteBuffer.position(byteBuffer.position() + 22)
            val fileNameLength = byteBuffer.getShort().toUInt().toInt()
            val extraFieldLength = byteBuffer.getShort().toUInt().toInt()
            // 边界保护：文件名 + extra 超出缓冲区说明不是合法 local header，返回 -1。
            val dataOffset = byteBuffer.position() + fileNameLength + extraFieldLength
            if (dataOffset <= byteBuffer.capacity()) {
                localFileOffset = dataOffset.toLong()
            }
        }
        return localFileOffset
    }
}
