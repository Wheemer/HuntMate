package com.huntmate.gps

import android.location.Location
import android.os.SystemClock
import com.huntmate.gps.activity.ActivityMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

class TrackingEngine(private val store: TrackStore) {
    private val _snapshot = MutableStateFlow(TrackingSnapshot())
    val snapshot: StateFlow<TrackingSnapshot> = _snapshot.asStateFlow()

    private val points = mutableListOf<TrackPoint>()
    private var distanceMeters = 0.0
    private var lastLocation: Location? = null
    private var lastAcceptedElapsed = 0L
    private var mode = ActivityMode.HUNTING

    init {
        val restored = store.loadSession()
        if (restored != null) {
            mode = restored.first
            loadMode(mode, restored.second)
        } else {
            loadMode(mode, TrackingState.IDLE)
        }
    }

    @Synchronized
    fun selectMode(newMode: ActivityMode) {
        if (newMode == mode) return
        require(_snapshot.value.state != TrackingState.TRACKING) {
            "Pause or stop tracking before changing activity."
        }
        mode = newMode
        loadMode(mode, TrackingState.IDLE)
        store.saveSession(mode, TrackingState.IDLE)
    }

    fun currentMode(): ActivityMode = mode

    @Synchronized
    fun start() {
        if (_snapshot.value.state == TrackingState.TRACKING) return
        if (_snapshot.value.state == TrackingState.IDLE) {
            points.clear()
            distanceMeters = 0.0
            lastLocation = null
            lastAcceptedElapsed = 0L
            store.clear(mode)
        }
        store.saveSession(mode, TrackingState.TRACKING)
        _snapshot.value = _snapshot.value.copy(
            state = TrackingState.TRACKING,
            points = points.toList(),
            distanceMeters = distanceMeters
        )
    }

    @Synchronized
    fun pause() {
        if (_snapshot.value.state == TrackingState.TRACKING) {
            store.save(mode, points)
            store.saveSession(mode, TrackingState.PAUSED)
            _snapshot.value = _snapshot.value.copy(state = TrackingState.PAUSED)
        }
    }

    @Synchronized
    fun stop() {
        store.save(mode, points)
        store.saveSession(mode, TrackingState.IDLE)
        _snapshot.value = _snapshot.value.copy(
            state = TrackingState.IDLE,
            points = points.toList(),
            distanceMeters = distanceMeters
        )
        lastLocation = null
        lastAcceptedElapsed = 0L
    }

    /** Prevents a process-restart gap from becoming a false distance segment. */
    @Synchronized
    fun resumeAfterProcessRestart() {
        lastLocation = null
        lastAcceptedElapsed = 0L
    }

    @Synchronized
    fun accept(location: Location) {
        if (_snapshot.value.state != TrackingState.TRACKING) return
        if (!location.hasAccuracy() || location.accuracy < 0f) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val previous = lastLocation
        if (previous != null) {
            val segment = previous.distanceTo(location).toDouble()
            val seconds = max(0.001, (nowElapsed - lastAcceptedElapsed) / 1000.0)
            if (segment / seconds > 150.0) return
            distanceMeters += segment
        }

        val point = TrackPoint(
            location.latitude,
            location.longitude,
            location.accuracy,
            if (location.hasSpeed()) location.speed else 0f,
            if (location.hasBearing()) location.bearing else 0f,
            location.time
        )
        points.add(point)
        lastLocation = location
        lastAcceptedElapsed = nowElapsed
        store.save(mode, points)
        store.saveSession(mode, TrackingState.TRACKING)
        _snapshot.value = TrackingSnapshot(
            TrackingState.TRACKING,
            point,
            points.toList(),
            distanceMeters
        )
    }

    private fun loadMode(newMode: ActivityMode, state: TrackingState) {
        points.clear()
        points.addAll(store.load(newMode))
        distanceMeters = calculateDistance(points)
        val last = points.lastOrNull()?.let {
            Location("stored").apply {
                latitude = it.latitude
                longitude = it.longitude
            }
        }
        // Stored coordinates are useful for display, but must not be used as
        // a live segment anchor after a process restart.
        lastLocation = if (state == TrackingState.TRACKING) null else last
        lastAcceptedElapsed = 0L
        _snapshot.value = TrackingSnapshot(
            state,
            points.lastOrNull(),
            points.toList(),
            distanceMeters
        )
    }

    private fun calculateDistance(list: List<TrackPoint>): Double {
        var total = 0.0
        for (i in 1 until list.size) {
            val a = list[i - 1]
            val b = list[i]
            val results = FloatArray(1)
            Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
            total += results[0]
        }
        return total
    }
}
