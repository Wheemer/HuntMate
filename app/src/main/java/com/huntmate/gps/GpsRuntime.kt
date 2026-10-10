package com.huntmate.gps

import android.content.Context

object GpsRuntime {
    @Volatile private var engine: TrackingEngine? = null
    fun engine(context: Context): TrackingEngine = synchronized(this) {
        engine ?: TrackingEngine(SharedPrefsTrackStore(context.applicationContext)).also { engine = it }
    }
}
