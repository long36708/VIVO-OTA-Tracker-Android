package io.github.long36708.updater.vivo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

data class VivoDevice(
    val model: String,
    val codename: String,
    val model_sw_ver: String,
    // ADR-003 D1：机型默认软件版本号。空串 = 该机型未配置，不做任何填充。
    // 注意与 model_sw_ver 区分：后者是硬件公开型号（V2419A），
    // 本字段是系统软件版本号（15.0.33.7.W10）。
    val defaultSwVersion: String = "",
    // 可选版本号数组：个别机型有多个官方版本可选。空列表 = 不显示下拉。
    val optionalSwVersions: List<String> = emptyList()
)

object VivoDeviceDatabase {

    private const val TAG = "VivoDeviceDatabase"

    /** 设备列表数据来源（MobileModels 项目，vivo 国行机型汇总） */
    private const val REMOTE_URL = "https://khwang9883.github.io/MobileModels/brands/vivo_cn.html"
    private const val CACHE_FILE = "vivo_devices_remote.json"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    @Volatile
    private var database: Map<String, List<VivoDevice>> = emptyMap()

    /** 数据版本号，每次成功在线更新 +1 */
    private val version = AtomicInteger(0)

    fun load(context: Context) {
        if (database.isNotEmpty()) return
        // 优先使用上次在线更新成功后的缓存，其次回退到打包内置的列表
        val cached = runCatching { readCache(context) }.getOrNull()
        database = cached ?: loadFromAssets(context)
    }

    val series: List<String> get() = database.keys.toList()

    fun devicesOf(series: String): List<VivoDevice> = database[series] ?: emptyList()

    fun dataVersion(): Int = version.get()

    // ------------------------------------------------------------------
    // 在线更新
    // ------------------------------------------------------------------

    enum class RefreshResult { UPDATED, UNCHANGED, FAILED }

    /**
     * 从远端页面拉取最新设备列表。
     * 成功解析且与缓存不同时更新内存数据并写缓存，返回 [RefreshResult.UPDATED]。
     * 需在子线程调用。
     */
    fun refresh(context: Context): RefreshResult {
        return try {
            val html = fetch(REMOTE_URL) ?: return RefreshResult.FAILED
            val parsed = parseHtml(html)
            if (parsed.isEmpty()) {
                Log.w(TAG, "Remote device list parsed empty, keep current data")
                return RefreshResult.FAILED
            }
            val serialized = serialize(parsed)
            val oldCache = runCatching { readCacheRaw(context) }.getOrNull()
            if (serialized == oldCache) return RefreshResult.UNCHANGED
            runCatching { writeCache(context, serialized) }
                .onFailure { Log.w(TAG, "Failed to persist device cache", it) }
            database = parsed
            version.incrementAndGet()
            RefreshResult.UPDATED
        } catch (e: Exception) {
            Log.w(TAG, "Device list refresh failed", e)
            RefreshResult.FAILED
        }
    }

    private fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) VivoOtaTracker")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "Remote device list HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Device list download failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // HTML 解析
    //
    // 页面结构（Jekyll 渲染的稳定标记）：
    //   <h2>系列名</h2>
    //   <p><strong>机型名 (<code>PDxxxx</code>):</strong></p>   <- 设备，codename 可缺省
    //   <p><code>VxxxxA</code>: 版本描述</p>                    <- 固件变体
    // ------------------------------------------------------------------

    private val SECTION_RE = Regex("<h2[^>]*>(.*?)</h2>(.*?)(?=<h2\\b|$)", RegexOption.DOT_MATCHES_ALL)
    private val STRONG_RE = Regex("<strong>(.*?)</strong>", RegexOption.DOT_MATCHES_ALL)
    private val CODE_TEXT_RE = Regex("<code[^>]*>([^<]+)</code>")
    private val VARIANT_RE = Regex("<code[^>]*>([^<]+)</code>\\s*:\\s*([^<]*)", RegexOption.DOT_MATCHES_ALL)
    private val TAG_RE = Regex("<[^>]+>")

    private fun parseHtml(html: String): Map<String, List<VivoDevice>> {
        val result = linkedMapOf<String, MutableList<VivoDevice>>()
        for (section in SECTION_RE.findAll(html)) {
            val seriesName = stripTags(section.groupValues[1])
            if (seriesName.isEmpty()) continue
            val devices = result.getOrPut(seriesName) { mutableListOf() }

            var currentModel = ""
            var currentCodename = ""
            for (paragraph in splitParagraphs(section.groupValues[2])) {
                val strong = STRONG_RE.find(paragraph)
                if (strong != null) {
                    // 设备标题行：<strong>机型名 (<code>codename</code>):</strong>
                    val codes = CODE_TEXT_RE.findAll(strong.groupValues[1]).map { it.groupValues[1].trim() }.toList()
                    val plain = stripTags(strong.groupValues[1])
                    currentModel = plain.substringBefore('(').trim().trimEnd(':', '：')
                    currentCodename = if (codes.isEmpty()) "" else codes.joinToString("/")
                } else {
                    // 固件变体行：<code>VxxxxA</code>: 描述
                    val variant = VARIANT_RE.find(paragraph) ?: continue
                    val swVer = variant.groupValues[1].trim()
                    if (swVer.isEmpty()) continue
                    val model = currentModel.ifEmpty { stripTags(variant.groupValues[2]) }.trim()
                    // 无 codename 的机型（手表/平板组）以 sw_ver 兜底，保证可发起查询
                    val codename = currentCodename.ifEmpty { swVer }
                    devices.add(VivoDevice(model = model, codename = codename, model_sw_ver = swVer))
                }
            }
        }
        return result
    }

    private fun splitParagraphs(html: String): List<String> = html.split("<p>")

    private fun stripTags(html: String): String {
        val noTags = TAG_RE.replace(html, "")
        return decodeEntities(noTags).trim()
    }

    private fun decodeEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

    // ------------------------------------------------------------------
    // JSON 序列化（与内置 vivo_devices.json 同构）
    // ------------------------------------------------------------------

    private fun serialize(data: Map<String, List<VivoDevice>>): String {
        val root = JSONObject()
        for ((series, devices) in data) {
            val arr = JSONArray()
            for (d in devices) {
                arr.put(JSONObject().apply {
                    put("model", d.model)
                    put("codename", d.codename)
                    put("model_sw_ver", d.model_sw_ver)
                })
            }
            root.put(series, arr)
        }
        return root.toString()
    }

    private fun parseJson(raw: String): Map<String, List<VivoDevice>> {
        val root = JSONObject(raw)
        val result = linkedMapOf<String, List<VivoDevice>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val series = keys.next()
            val arr = root.getJSONArray(series)
            val devices = mutableListOf<VivoDevice>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                devices.add(VivoDevice(
                    model = obj.getString("model"),
                    codename = obj.getString("codename"),
                    model_sw_ver = obj.getString("model_sw_ver"),
                    // ADR-003 D1：可选字段，缺失即空串。
                    // 必须用 optString——getString 对缺字段会抛 JSONException，
                    // 而 load() 无 try-catch，会导致整个机型库加载失败。
                    defaultSwVersion = obj.optString("default_sw_version", ""),
                    // 可选版本号数组，缺失即空列表。optJSONArray 缺字段返回 null，不会抛异常。
                    optionalSwVersions = obj.optJSONArray("optional_sw_versions")?.let { a ->
                        List(a.length()) { i -> a.getString(i) }
                    } ?: emptyList()
                ))
            }
            result[series] = devices
        }
        return result
    }

    // ------------------------------------------------------------------
    // 内置列表与本地缓存
    // ------------------------------------------------------------------

    private fun loadFromAssets(context: Context): Map<String, List<VivoDevice>> {
        val raw = context.assets.open("vivo_devices.json").bufferedReader().use { it.readText() }
        return parseJson(raw)
    }

    private fun cacheFile(context: Context) = java.io.File(context.filesDir, CACHE_FILE)

    private fun readCacheRaw(context: Context): String? {
        val f = cacheFile(context)
        if (!f.exists()) return null
        return f.readText(Charsets.UTF_8)
    }

    private fun readCache(context: Context): Map<String, List<VivoDevice>>? {
        val raw = readCacheRaw(context) ?: return null
        return parseJson(raw)
    }

    private fun writeCache(context: Context, serialized: String) {
        cacheFile(context).writeText(serialized, Charsets.UTF_8)
    }
}
