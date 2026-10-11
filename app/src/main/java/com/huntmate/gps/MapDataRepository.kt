package com.huntmate.gps

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    companion object {
        private const val CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    }

    fun isWifiConnected(): Boolean {
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * A basemap/style rebuild uses fresh validated cache only. Missing/stale data is
     * fetched on Wi-Fi; mobile-data downloads require explicit caller consent.
     */
    fun load(
        key: String,
        baseUrl: String,
        forceRefresh: Boolean = false,
        allowMobileData: Boolean = false,
        callback: (Result) -> Unit
    ) {
        executor.execute {
            val file = File(app.filesDir, "mapdata_" + key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".geojson")
            val requireCaribouStatus = key == "hm-caribou-zones"
            val cached = runCatching {
                file.takeIf { it.isFile }?.readText()?.takeIf { valid(it, requireCaribouStatus) }
            }.getOrNull()
            val cachedAt = if (cached != null) file.lastModified() else 0L
            val cacheFresh = cached != null && System.currentTimeMillis() - cachedAt < CACHE_MAX_AGE_MS

            if (cached != null && cacheFresh && !forceRefresh) {
                callback(Result(cached, null, true, cachedAt))
                return@execute
            }

            if (!isWifiConnected() && !allowMobileData) {
                val reason = if (cached != null) {
                    "Refresh skipped: cached map data may be stale. Connect to Wi-Fi or explicitly allow mobile-data downloads."
                } else {
                    "Map data not cached. Connect to Wi-Fi or explicitly allow mobile-data downloads."
                }
                callback(Result(cached, reason, cached != null, cachedAt))
                return@execute
            }

            try {
                val data = fetchComplete(baseUrl, allowMobileData)
                require(valid(data, requireCaribouStatus)) {
                    if (requireCaribouStatus) "Caribou layer missing required status_ope attribute" else "Downloaded data failed validation"
                }
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(data)
                require(valid(temp.readText(), requireCaribouStatus)) { "Temporary cache failed validation" }
                if (file.exists() && !file.delete()) error("Could not replace cache")
                if (!temp.renameTo(file)) { temp.copyTo(file, overwrite = true); temp.delete() }
                callback(Result(data, null, false, file.lastModified()))
            } catch (e: Exception) {
                Log.w("HuntMateMapData", "Refresh failed for " + key, e)
                if (cached == null) {
                    callback(Result(null, "Could not load official layer: " + (e.message ?: "network/data error"), false, 0L))
                } else {
                    callback(Result(cached, "Refresh failed; cached data may be stale. Verify official rules.", true, cachedAt))
                }
            }
        }
    }

    private fun fetchComplete(baseUrl: String, allowMobileData: Boolean): String {
        val all = JSONArray()
        var offset = 0
        var pages = 0
        while (true) {
            require(isWifiConnected() || allowMobileData) {
                "Wi-Fi disconnected during map download; remaining pages were not downloaded over mobile data."
            }
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

    private fun valid(text: String, requireCaribouStatus: Boolean = false): Boolean = runCatching {
        val root = JSONObject(text)
        if (root.optString("type") != "FeatureCollection") return@runCatching false
        val features = root.optJSONArray("features") ?: return@runCatching false
        if (features.length() == 0) return@runCatching false
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: return@runCatching false
            if (feature.optJSONObject("geometry") == null) return@runCatching false
            if (requireCaribouStatus) {
                val properties = feature.optJSONObject("properties") ?: return@runCatching false
                if (!properties.has("status_ope") || properties.isNull("status_ope") ||
                    properties.optString("status_ope").isBlank()) return@runCatching false
            }
        }
        true
    }.getOrDefault(false)
}
