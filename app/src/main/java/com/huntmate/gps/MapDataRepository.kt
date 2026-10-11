package com.huntmate.gps

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MapDataRepository(context: Context) {
    data class Result(val geoJson: String?, val error: String?, val fromCache: Boolean, val fetchedAt: Long)
    private val app = context.applicationContext
    private val executor = Executors.newFixedThreadPool(3)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()

    fun load(key: String, baseUrl: String, callback: (Result) -> Unit) {
        executor.execute {
            val file = File(app.filesDir, "mapdata_" + key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".geojson")
            val cached = runCatching { file.takeIf { it.isFile }?.readText()?.takeIf(::valid) }.getOrNull()
            if (cached != null) callback(Result(cached, null, true, file.lastModified()))
            try {
                val data = fetchComplete(baseUrl)
                require(valid(data)) { "Downloaded data failed validation" }
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(data)
                require(valid(temp.readText())) { "Temporary cache failed validation" }
                if (file.exists() && !file.delete()) error("Could not replace cache")
                if (!temp.renameTo(file)) { temp.copyTo(file, overwrite = true); temp.delete() }
                callback(Result(data, null, false, file.lastModified()))
            } catch (e: Exception) {
                Log.w("HuntMateMapData", "Refresh failed for " + key, e)
                if (cached == null) callback(Result(null, "Could not load official layer: " + (e.message ?: "network/data error"), false, 0L))
                else callback(Result(cached, "Refresh failed; cached data may be stale. Verify official rules.", true, file.lastModified()))
            }
        }
    }

    private fun fetchComplete(baseUrl: String): String {
        val all = JSONArray()
        var offset = 0
        var pages = 0
        while (true) {
            require(pages++ < 100) { "Pagination safety limit reached" }
            val url = baseUrl + (if (baseUrl.contains("?")) "&" else "?") + "resultOffset=" + offset + "&resultRecordCount=1000"
            val request = Request.Builder().url(url).header("User-Agent", "HuntMate-Hunting-Fishing/1.5.1").build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP " + response.code)
                response.body?.string() ?: error("Empty response")
            }
            val root = JSONObject(body)
            if (root.has("error")) error(root.getJSONObject("error").optString("message", "ArcGIS error"))
            val features = root.optJSONArray("features") ?: error("Missing features array")
            for (i in 0 until features.length()) all.put(features.getJSONObject(i))
            val exceeded = root.optBoolean("exceededTransferLimit", false)
            offset += features.length()
            if (features.length() == 0) {
                if (exceeded) error("Transfer limit reported with empty page")
                break
            }
            if (!exceeded && features.length() < 1000) break
        }
        require(all.length() > 0) { "Empty dataset; existing cache preserved" }
        return JSONObject().put("type", "FeatureCollection").put("features", all).toString()
    }

    private fun valid(text: String): Boolean = runCatching {
        val root = JSONObject(text)
        if (root.optString("type") != "FeatureCollection") return@runCatching false
        val features = root.optJSONArray("features") ?: return@runCatching false
        if (features.length() == 0) return@runCatching false
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: return@runCatching false
            if (feature.optJSONObject("geometry") == null) return@runCatching false
        }
        true
    }.getOrDefault(false)
}
