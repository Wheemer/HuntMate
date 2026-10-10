package com.huntmate.gps

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.huntmate.gps.activity.ActivityMode
import kotlinx.coroutines.flow.StateFlow

class HuntMateGpsController(context: Context) {
    private val appContext = context.applicationContext
    private val engine = GpsRuntime.engine(appContext)
    val snapshot: StateFlow<TrackingSnapshot> = engine.snapshot

    fun selectActivity(mode: ActivityMode) = engine.selectMode(mode)
    fun currentActivity(): ActivityMode = engine.currentMode()
    fun start() = send(GpsTrackingService.ACTION_START)
    fun pause() = send(GpsTrackingService.ACTION_PAUSE)
    fun stop() = send(GpsTrackingService.ACTION_STOP)

    private fun send(action: String) {
        val command = Intent(appContext, GpsTrackingService::class.java).setAction(action)
        if (action == GpsTrackingService.ACTION_START) {
            // Starting location tracking must promote the service to foreground immediately.
            ContextCompat.startForegroundService(appContext, command)
        } else {
            // Pause/stop are control commands, not new foreground work. Using
            // startForegroundService here can crash when tracking is currently idle.
            appContext.startService(command)
        }
    }

    companion object {
        val requiredPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
