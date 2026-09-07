package io.github.long36708.updater.vivo.payload

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

object VivoPayloadHttpUtil {

    private lateinit var url: String
    private lateinit var fileName: String
    private var fileLength: Long = 0
    private var position: Long = 0
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class)
    suspend fun init(link: String) = withContext(Dispatchers.IO) {
        url = link
        runCatching {
            val request = Request.Builder()
                .url(link)
                .addHeader("Range", "bytes=0-0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val contentRange = response.header("Content-Range")
                    fileLength = contentRange?.split("/")?.get(1)?.trim()?.toLong()
                        ?: response.header("Content-Length")?.toLong()
                        ?: 0L
                    fileName = getFileNameFromHeaders(link, response.header("Content-Disposition"))
                    Log.i("VivoPayload", "http init OK: Content-Range=$contentRange, fileLength=$fileLength, fileName=$fileName")
                } else {
                    throw IOException("Failed to initialize HTTP request: ${response.code}")
                }
            }
        }.onFailure { exception ->
            throw IOException("Failed to initialize HTTP request", exception)
        }
    }

    fun length(): Long = fileLength

    fun position(): Long = position

    fun getFileName(): String = fileName

    suspend fun readSync(byteArray: ByteArray): Int = withContext(Dispatchers.IO) {
        var currentPosition = position
        var totalBytesRead = 0

        // 严格读满 byteArray：服务端可能对 Range 请求分片/截断返回，
        // 单次请求读不满时继续发后续 Range 请求补足，避免上层 Payload.parseFrom
        // 拿到半截数据后无限循环解析导致 UI 一直转圈。
        var reqCount = 0
        while (totalBytesRead < byteArray.size) {
            reqCount++
            val remaining = byteArray.size - totalBytesRead
            val rangeHeader = "bytes=${currentPosition}-${currentPosition + remaining - 1}"
            if (reqCount <= 3 || totalBytesRead == 0) {
                Log.d("VivoPayload", "readSync req#$reqCount range=$rangeHeader want=$remaining")
            }
            val request = Request.Builder().url(url).addHeader("Range", rangeHeader).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
                val body = response.body ?: throw IOException("Empty response body")
                val inputStream = body.byteStream()
                val buffer = ByteArray(4 * 1024)
                var gotAny = false
                // 单次请求内按 4K 缓冲读，读满剩余量或 stream 结束为止
                while (totalBytesRead < byteArray.size) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    gotAny = true
                    val toCopy = minOf(bytesRead, byteArray.size - totalBytesRead)
                    System.arraycopy(buffer, 0, byteArray, totalBytesRead, toCopy)
                    totalBytesRead += toCopy
                    currentPosition += toCopy
                }
                // 本次 Range 请求没读到任何字节：说明已到文件末尾或连接被截断，
                // 不再无限补请求，直接返回已读量（上层会据此判定数据不足）。
                if (!gotAny) break
            }
        }
        if (totalBytesRead < byteArray.size) {
            Log.w("VivoPayload", "readSync EOF: read $totalBytesRead / ${byteArray.size} bytes (data truncated)")
        }
        position = currentPosition
        totalBytesRead
    }

    fun seek(bytePosition: Long) {
        if (bytePosition in 0 until fileLength) {
            position = bytePosition
        } else {
            throw IllegalArgumentException("Invalid seek position")
        }
    }

    private fun getFileNameFromHeaders(url: String, contentDisposition: String?): String {
        if (!contentDisposition.isNullOrEmpty()) {
            val dispositionParts = contentDisposition.split(";")
            for (part in dispositionParts) {
                if (part.trim().startsWith("filename=")) {
                    return part.trim().substringAfter("=").replace("\"", "")
                }
            }
        }
        return Paths.get(java.net.URI(url).path).fileName.toString()
    }
}
