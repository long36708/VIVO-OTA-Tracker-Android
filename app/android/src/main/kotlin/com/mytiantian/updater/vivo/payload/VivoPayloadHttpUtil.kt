package com.mytiantian.updater.vivo.payload

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
        val buffer = ByteArray(4 * 1024)
        var bytesRead: Int
        val requestBuilder = Request.Builder().url(url)
        var currentPosition = position
        var totalBytesRead = 0

        val rangeHeader = "bytes=$position-${position + byteArray.size - 1}"
        val request = requestBuilder.addHeader("Range", rangeHeader).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
            val body = response.body
            if (body != null) {
                val inputStream = body.byteStream()
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (totalBytesRead + bytesRead > byteArray.size) {
                        val remainingSpace = byteArray.size - totalBytesRead
                        System.arraycopy(buffer, 0, byteArray, totalBytesRead, remainingSpace)
                        totalBytesRead += remainingSpace
                        break
                    } else {
                        System.arraycopy(buffer, 0, byteArray, totalBytesRead, bytesRead)
                        totalBytesRead += bytesRead
                    }
                }
                currentPosition += totalBytesRead
            }
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
