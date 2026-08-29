package com.mytiantian.updater.vivo.payload

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
        return payload.deltaArchiveManifest.partitionsList.map {
            PartitionInfo(
                it.partitionName,
                it.newPartitionInfo.size,
                (it.operationsList[it.operationsList.size - 1].dataOffset + it.operationsList[it.operationsList.size - 1].dataLength) - it.operationsList[0].dataOffset,
                it.newPartitionInfo.hash.toByteArray().toHexString()
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
            throw IOException("Central directory not found, not a valid OTA zip")
        }

        httpUtil.seek(centralDirectoryInfo.offset)
        val centralDirectory = ByteArray(centralDirectoryInfo.size.toInt())
        httpUtil.readSync(centralDirectory)

        val localHeaderOffset = locateLocalFileHeader(centralDirectory, fileName)
        if (localHeaderOffset < 0) {
            throw IOException("payload.bin not found in zip")
        }

        val localHeaderBytes = ByteArray(256)
        httpUtil.seek(localHeaderOffset)
        httpUtil.readSync(localHeaderBytes)
        return locateLocalFileOffset(localHeaderBytes) + localHeaderOffset
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
                if (fileName == String(fileNameBytes, Charsets.UTF_8)) {
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
            byteBuffer.position(byteBuffer.position() + 22)
            val fileNameLength = byteBuffer.getShort().toUInt().toInt()
            val extraFieldLength = byteBuffer.getShort().toUInt().toInt()
            byteBuffer.position(byteBuffer.position() + fileNameLength + extraFieldLength)
            localFileOffset = byteBuffer.position().toLong()
        }
        return localFileOffset
    }
}
