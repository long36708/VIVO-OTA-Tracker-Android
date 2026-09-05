package io.github.long36708.updater.vivo

import android.content.Context
import org.json.JSONObject

data class VivoDevice(
    val model: String,
    val codename: String,
    val model_sw_ver: String,
    // ADR-003 D1：机型默认软件版本号。空串 = 该机型未配置，不做任何填充。
    // 注意与 model_sw_ver 区分：后者是硬件公开型号（V2419A），
    // 本字段是系统软件版本号（15.0.33.7.W10）。
    val defaultSwVersion: String = ""
)

object VivoDeviceDatabase {

    private var database: Map<String, List<VivoDevice>> = emptyMap()

    fun load(context: Context) {
        if (database.isNotEmpty()) return
        val raw = context.assets.open("vivo_devices.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val result = mutableMapOf<String, List<VivoDevice>>()
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
                    defaultSwVersion = obj.optString("default_sw_version", "")
                ))
            }
            result[series] = devices
        }
        database = result
    }

    val series: List<String> get() = database.keys.toList()

    fun devicesOf(series: String): List<VivoDevice> = database[series] ?: emptyList()
}
