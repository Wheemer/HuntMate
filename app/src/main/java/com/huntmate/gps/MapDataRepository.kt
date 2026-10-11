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

/** Downloads enabled official layers and retains only validated data for offline use. */
class MapDataRepository(context: Context) {
    data class Result(val geoJson: String?, val error: String?, val fromCache: Boolean, val fetchedAt: Long)
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
    companion object { private const val CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000; private const val PAGE_SIZE = 1000 }
    fun load(key: String, queryUrl: String, callback: (Result) -> Unit) {
        executor.execute {
            val cache = File(app.filesDir, "official_layer_" + key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".geojson")
            val cached = runCatching { cache.takeIf(File::isFile)?.readText()?.takeIf(::isValid) }.getOrNull()
            val cachedAt = if (cached != null) cache.lastModified() else 0L
            if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_MAX_AGE_MS) { callback(Result(cached, null, true, cachedAt)); return@execute }
            if (!isConnected()) { callback(Result(cached, "Connect to the internet to load this official layer.", cached != null, cachedAt)); return@execute }
            try {
                val data = fetchAllPages(queryUrl)
                require(isValid(data)) { "Official service returned invalid map data." }
                val staging = File(cache.parentFile, cache.name + ".new")
                staging.writeText(data)
                require(isValid(staging.readText())) { "Downloaded map data did not validate." }
                if (cache.exists() && !cache.delete()) error("Could not replace cached map data.")
                if (!staging.renameTo(cache)) { staging.copyTo(cache, overwrite = true); staging.delete() }
                callback(Result(data, null, false, cache.lastModified()))
            } catch (error: Exception) {
                Log.w("HuntMateOfficialData", "Could not refresh " + key, error)
                callback(Result(cached, "Could not refresh official data. " + (error.message ?: "Try again later."), cached != null, cachedAt))
            }
        }
    }
    private fun isConnected(): Boolean {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    private fun fetchAllPages(queryUrl: String): String {
        val all = JSONArray(); var offset = 0; var pages = 0
        while (true) {
            require(pages++ < 100) { "Official service pagination limit reached." }
            val separator = if (queryUrl.contains("?")) "&" else "?"
            val url = queryUrl + separator + "resultOffset=" + offset + "&resultRecordCount=" + PAGE_SIZE
            val request = Request.Builder().url(url).header("User-Agent", "HuntMate/1.5 official-data").build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Official service returned HTTP " + response.code + ".")
                response.body?.string() ?: error("Official service returned an empty response.")
            }
            val root = JSONObject(body)
            if (root.has("error")) error(root.getJSONObject("error").optString("message", "Official service error."))
            val features = root.optJSONArray("features") ?: error("Official service response has no features.")
            for (index in 0 until features.length()) all.put(features.getJSONObject(index))
            offset += features.length()
            if (!root.optBoolean("exceededTransferLimit", false) && features.length() < PAGE_SIZE) break
            if (features.length() == 0) break
        }
        require(all.length() > 0) { "Official service returned no features." }
        return JSONObject().put("type", "FeatureCollection").put("features", all).toString()
    }
    private fun isValid(text: String): Boolean = runCatching {
        val root = JSONObject(text)
        root.optString("type") == "FeatureCollection" && (root.optJSONArray("features")?.let { features ->
            features.length() > 0 && (0 until features.length()).all { index -> features.optJSONObject(index)?.optJSONObject("geometry") != null }
        } == true)
    }.getOrDefault(false)
}
