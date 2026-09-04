package io.github.long36708.updater.vivo

import android.content.Context
import org.json.JSONObject

data class VivoDevice(
    val model: String,
    val codename: String,
    val model_sw_ver: String
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
                    model_sw_ver = obj.getString("model_sw_ver")
                ))
            }
            result[series] = devices
        }
        database = result
    }

    val series: List<String> get() = database.keys.toList()

    fun devicesOf(series: String): List<VivoDevice> = database[series] ?: emptyList()
}
