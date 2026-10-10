package com.huntmate.gps

import android.content.Context
import com.huntmate.gps.activity.ActivityMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Activity-scoped persistence. Every mode has its own track namespace and
 * its own session state; no activity can read another activity's data through
 * this API.
 */
interface TrackStore {
    fun save(mode: ActivityMode, points: List<TrackPoint>)
    fun load(mode: ActivityMode): List<TrackPoint>
    fun clear(mode: ActivityMode)
    fun saveSession(mode: ActivityMode, state: TrackingState)
    fun loadSession(): Pair<ActivityMode, TrackingState>?
    fun clearSession()
}

class SharedPrefsTrackStore(context: Context) : TrackStore {
    private val prefs = context.getSharedPreferences("huntmate_gps", Context.MODE_PRIVATE)

    override fun save(mode: ActivityMode, points: List<TrackPoint>) {
        val array = JSONArray()
        points.forEach { p ->
            array.put(JSONObject().apply {
                put("lat", p.latitude)
                put("lon", p.longitude)
                put("accuracy", p.accuracyMeters)
                put("speed", p.speedMetersPerSecond)
                put("bearing", p.bearingDegrees)
                put("time", p.timestampMillis)
            })
        }
        prefs.edit().putString(key(mode), array.toString()).apply()
    }

    override fun load(mode: ActivityMode): List<TrackPoint> {
        val raw = prefs.getString(key(mode), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        TrackPoint(
                            o.getDouble("lat"),
                            o.getDouble("lon"),
                            o.getDouble("accuracy").toFloat(),
                            o.getDouble("speed").toFloat(),
                            o.getDouble("bearing").toFloat(),
                            o.getLong("time")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun clear(mode: ActivityMode) {
        prefs.edit().remove(key(mode)).apply()
    }

    override fun saveSession(mode: ActivityMode, state: TrackingState) {
        prefs.edit()
            .putString(SESSION_MODE, mode.name)
            .putString(SESSION_STATE, state.name)
            .apply()
    }

    override fun loadSession(): Pair<ActivityMode, TrackingState>? {
        val modeName = prefs.getString(SESSION_MODE, null) ?: return null
        val stateName = prefs.getString(SESSION_STATE, null) ?: return null
        return runCatching {
            ActivityMode.valueOf(modeName) to TrackingState.valueOf(stateName)
        }.getOrNull()
    }

    override fun clearSession() {
        prefs.edit().remove(SESSION_MODE).remove(SESSION_STATE).apply()
    }

    private fun key(mode: ActivityMode): String = "track_${mode.name}"

    companion object {
        private const val SESSION_MODE = "session_mode"
        private const val SESSION_STATE = "session_state"
    }
}
