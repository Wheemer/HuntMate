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
        ContextCompat.startForegroundService(appContext, Intent(appContext, GpsTrackingService::class.java).setAction(action))
    }

    companion object {
        val requiredPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
