package com.mytiantian.updater.vivo.payload

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.min

/**
 * zip 容器内条目的读取与文本预览（ADR-001，L0 层）。
 * 术语与层级定义见 docs/GLOSSARY.md。
 *
 * 前提：OTA 包实测 4.3~11.6GB，任何路径都不得整包下载。
 * 依赖 zip「每条目独立压缩」的特性——DEFLATE 条目可单独顺序 inflate，
 * 无需下载其之前的任何数据，这是小流量预览成立的基础。
 */
object VivoZipBrowser {

    private const val TAG = "VivoZipBrowser"
    private const val CENSIG = 0x02014b50L
    private const val LOCSIG = 0x04034b50L
    private const val ZIP64_MAGICVAL = 0xFFFFFFFFL
    private const val ZIP64_EXTRA_ID = 0x0001

    /** central directory 异常膨胀时拒绝分配，避免 OOM。 */
    private const val MAX_CENTRAL_DIRECTORY_BYTES = 8L * 1024 * 1024
    /** local header 读取上限。实测 vivo 的 extra field 可达数万字节。 */
    private const val LOCAL_HEADER_READ_BYTES = 64 * 1024
    /** 预览最多读取的压缩数据量，换出 8KB 明文通常绰绰有余。 */
    const val PREVIEW_INPUT_BYTES = 64 * 1024

    data class EntryData(
        val bytes: ByteArray,
        /** 解压产出的真实字节数。 */
        val producedBytes: Long,
        /** 是否因输出上限而截断（预览场景属预期行为）。 */
        val truncatedByLimit: Boolean,
        /** 数据流是否完整结束。下载场景必须为 true，否则视为数据损坏。 */
        val complete: Boolean,
    )

    /**
     * 枚举 zip central directory 的全部条目。
     * 仅消耗「尾部 4KB + central directory」的流量，与包体大小无关。
     */
    suspend fun listZipEntries(httpUtil: VivoPayloadHttpUtil): List<ZipEntryInfo> =
        withContext(Dispatchers.IO) {
            val fileLength = httpUtil.length()
            if (fileLength <= 0) throw IOException("NOT_A_VALID_ZIP")

            val tailSize = minOf(4096L, fileLength).toInt()
            val tail = ByteArray(tailSize)
            httpUtil.seek(fileLength - tailSize)
            readFully(httpUtil, tail)

            val cen = PayloadUtil.locateCentralDirectory(tail, fileLength)
            if (cen.offset < 0 || cen.size <= 0 || cen.size > MAX_CENTRAL_DIRECTORY_BYTES) {
                throw IOException("NOT_A_VALID_ZIP")
            }
            Log.i(TAG, "listZipEntries: cenOffset=${cen.offset}, cenSize=${cen.size}")

            val central = ByteArray(cen.size.toInt())
            httpUtil.seek(cen.offset)
            readFully(httpUtil, central)
            val entries = parseCentralDirectory(central)
            Log.i(TAG, "listZipEntries: parsed ${entries.size} entries")
            entries
        }

    private fun parseCentralDirectory(bytes: ByteArray): List<ZipEntryInfo> {
        val entries = ArrayList<ZipEntryInfo>()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        while (buf.remaining() >= 4) {
            val sigPos = buf.position()
            if (buf.int.toLong() != CENSIG) {
                // 扫描式前进 1 字节：单字节错位不该让整段遍历失效（技术债 2）
                buf.position(sigPos + 1)
                continue
            }
            // 固定部分 46 字节，已消费 4
            if (buf.remaining() < 42) break

            val method = buf.getShort(sigPos + 10).toUInt().toInt()
            val crc32 = buf.getInt(sigPos + 16).toUInt().toLong()
            var compressedSize = buf.getInt(sigPos + 20).toUInt().toLong()
            var uncompressedSize = buf.getInt(sigPos + 24).toUInt().toLong()
            val nameLen = buf.getShort(sigPos + 28).toUInt().toInt()
            val extraLen = buf.getShort(sigPos + 30).toUInt().toInt()
            val commentLen = buf.getShort(sigPos + 32).toUInt().toInt()
            var localHeaderOffset = buf.getInt(sigPos + 42).toUInt().toLong()

            val recordEnd = sigPos + 46 + nameLen + extraLen + commentLen
            if (recordEnd > bytes.size) break

            val name = String(bytes, sigPos + 46, nameLen, Charsets.UTF_8)
            val extraStart = sigPos + 46 + nameLen
            val extra = bytes.copyOfRange(extraStart, extraStart + extraLen)

            // 技术债 5：≥4GB 的条目（recovery 包的 super.img 常 5~6GB）走 ZIP64
            if (compressedSize == ZIP64_MAGICVAL ||
                uncompressedSize == ZIP64_MAGICVAL ||
                localHeaderOffset == ZIP64_MAGICVAL
            ) {
                val fields = readZip64Fields(extra)
                var idx = 0
                if (uncompressedSize == ZIP64_MAGICVAL && idx < fields.size) {
                    uncompressedSize = fields[idx++]
                }
                if (compressedSize == ZIP64_MAGICVAL && idx < fields.size) {
                    compressedSize = fields[idx++]
                }
                if (localHeaderOffset == ZIP64_MAGICVAL && idx < fields.size) {
                    localHeaderOffset = fields[idx++]
                }
            }

            entries.add(
                ZipEntryInfo(
                    name = name,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    crc32 = crc32,
                    method = method,
                    localHeaderOffset = localHeaderOffset
                )
            )
            buf.position(recordEnd)
        }
        return entries
    }

    /**
     * ZIP64 extra field (0x0001)。字段按 uncompressed / compressed / localOffset / diskStart
     * 顺序排列，且**仅当对应 32 位字段被置为 0xFFFFFFFF 时才出现**，
     * 因此这里只收集全部 8 字节槽位，由调用方按需取值。
     */
    private fun readZip64Fields(extra: ByteArray): LongArray {
        val buf = ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 4) {
            val id = buf.short.toUInt().toInt()
            val size = buf.short.toUInt().toInt()
            if (size < 0 || size > buf.remaining()) break
            if (id == ZIP64_EXTRA_ID) {
                return LongArray(size / 8) { buf.long }
            }
            buf.position(buf.position() + size)
        }
        return LongArray(0)
    }

    /**
     * 读满整个 out 数组。
     *
     * HttpUtil.readSync 走的是单次 Range 请求，服务端若分片或截断返回就会读不满，
     * 上层拿半截 zip 结构去解析会得出错误结论。这里循环补足。
     * （不改动 HttpUtil 本身，避免影响既有 payload 解析路径。）
     */
    private suspend fun readFully(httpUtil: VivoPayloadHttpUtil, out: ByteArray): Int {
        var total = 0
        while (total < out.size) {
            val chunk = out.copyOfRange(total, out.size)
            val read = httpUtil.readSync(chunk)
            if (read <= 0) break
            System.arraycopy(chunk, 0, out, total, read)
            total += read
        }
        return total
    }

    /**
     * 读取条目内容。
     *
     * @param maxOutputBytes 产出上限，是防 zip bomb 的最后一道闸：
     *                       header 里的 uncompressedSize 可被伪造，不可只信它（ADR-001 D3）。
     */
    suspend fun readEntryBytes(
        httpUtil: VivoPayloadHttpUtil,
        entry: ZipEntryInfo,
        maxOutputBytes: Long,
        maxInputBytes: Long = Long.MAX_VALUE
    ): EntryData = withContext(Dispatchers.IO) {
        if (!entry.isSupported) throw IOException("UNSUPPORTED_ENTRY_METHOD")

        val dataOffset = readEntryDataOffset(httpUtil, entry)
        Log.i(TAG, "readEntryBytes: '${entry.name}' dataOffset=$dataOffset method=${entry.method}")

        if (entry.isStored) {
            val want = minOf(entry.uncompressedSize, maxOutputBytes).toInt()
            val out = ByteArray(want)
            httpUtil.seek(dataOffset)
            val read = readFully(httpUtil, out)
            return@withContext EntryData(
                bytes = if (read == want) out else out.copyOf(read),
                producedBytes = read.toLong(),
                truncatedByLimit = entry.uncompressedSize > maxOutputBytes,
                complete = read.toLong() >= entry.uncompressedSize
            )
        }

        val inputWant = minOf(entry.compressedSize, maxInputBytes).toInt()
        val compressed = ByteArray(inputWant)
        httpUtil.seek(dataOffset)
        val compressedRead = readFully(httpUtil, compressed)
        val actualInput =
            if (compressedRead == inputWant) compressed else compressed.copyOf(compressedRead)
        inflateRaw(actualInput, maxOutputBytes)
    }

    private suspend fun readEntryDataOffset(
        httpUtil: VivoPayloadHttpUtil,
        entry: ZipEntryInfo
    ): Long {
        val available = httpUtil.length() - entry.localHeaderOffset
        if (available <= 0) throw IOException("BAD_LOCAL_HEADER")
        val readSize = minOf(LOCAL_HEADER_READ_BYTES.toLong(), available).toInt()
        val header = ByteArray(readSize)
        httpUtil.seek(entry.localHeaderOffset)
        httpUtil.readSync(header)
        if (header.size < 30) throw IOException("BAD_LOCAL_HEADER")

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int.toLong() != LOCSIG) throw IOException("BAD_LOCAL_HEADER")
        // fileNameLength 在绝对偏移 26、extraFieldLength 在 28（LOCSIG 已消费 4 字节）
        val nameLen = buf.getShort(26).toUInt().toInt()
        val extraLen = buf.getShort(28).toUInt().toInt()
        val dataOffset = 30 + nameLen + extraLen
        if (dataOffset > header.size) throw IOException("BAD_LOCAL_HEADER")
        return entry.localHeaderOffset + dataOffset
    }

    private fun inflateRaw(compressed: ByteArray, maxOutputBytes: Long): EntryData {
        // 技术债 6：zip 的 DEFLATE 是 raw deflate，没有 zlib 头，必须 nowrap
        val inflater = Inflater(true)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var produced = 0L
        var hitOutputLimit = false
        // 必须在 finally 的 end() 之前取状态：end() 之后再调 finished() 结果不可靠
        var complete = false

        try {
            inflater.setInput(compressed)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                val room = maxOutputBytes - produced
                if (room <= 0) {
                    hitOutputLimit = true
                    break
                }
                val toWrite = min(n.toLong(), room).toInt()
                out.write(buf, 0, toWrite)
                produced += toWrite
            }
            complete = inflater.finished()
        } catch (e: DataFormatException) {
            throw IOException("BAD_DEFLATE_DATA")
        } finally {
            inflater.end()
        }

        return EntryData(
            bytes = out.toByteArray(),
            producedBytes = produced,
            truncatedByLimit = hitOutputLimit,
            complete = complete
        )
    }

    /**
     * ADR-001 D6：NUL 字节硬拦截 + 可打印字符比例。
     *
     * 只看比例是不够的——`apex_info.pb`、`care_map.pb` 这类二进制 protobuf
     * 含大量可打印字节却没有 NUL，按比例会被误判成文本并渲染出一屏乱码。
     */
    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val sample = bytes.copyOf(min(bytes.size, 4096))
        var printable = 0
        for (b in sample) {
            val v = b.toInt() and 0xFF
            if (v == 0) return false
            if (v == 9 || v == 10 || v == 13 || v in 32..126 || v >= 0x80) printable++
        }
        return printable.toDouble() / sample.size >= 0.9
    }

    /** ADR-001 D7：BOM → 严格 UTF-8 → GBK 回退。返回 (文本, 编码名)。 */
    fun decodeText(bytes: ByteArray): Pair<String, String> {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8 BOM"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE) to "UTF-16LE BOM"
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE) to "UTF-16BE BOM"
        }
        // 截断预览可能正好切在多字节字符中间，回退 1~3 字节再验一次
        for (trim in 0..3) {
            if (bytes.size - trim <= 0) break
            val slice = bytes.copyOf(bytes.size - trim)
            if (isStrictUtf8(slice)) return String(slice, Charsets.UTF_8) to "UTF-8"
        }
        return runCatching { String(bytes, Charset.forName("GBK")) to "GBK" }
            .getOrDefault(String(bytes, Charsets.ISO_8859_1) to "ISO-8859-1")
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        }.getOrDefault(false)
    }

    fun crc32(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }
}
