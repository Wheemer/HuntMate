package com.huntmate.gps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class GpsTrackingService : Service() {
    companion object {
        const val ACTION_START = "com.huntmate.gps.START"
        const val ACTION_PAUSE = "com.huntmate.gps.PAUSE"
        const val ACTION_STOP = "com.huntmate.gps.STOP"
        private const val CHANNEL_ID = "huntmate_gps_tracking"
        private const val NOTIFICATION_ID = 7101
    }

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var engine: TrackingEngine

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(engine::accept)
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        engine = GpsRuntime.engine(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking()
            ACTION_PAUSE -> pauseTracking()
            ACTION_STOP -> stopTracking()
            null -> if (engine.snapshot.value.state == TrackingState.TRACKING) startTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        engine.resumeAfterProcessRestart()
        engine.start()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(3f)
            .setWaitForAccurateLocation(true)
            .build()
        fused.removeLocationUpdates(callback)
        fused.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun pauseTracking() {
        fused.removeLocationUpdates(callback)
        engine.pause()
        updateNotification()
    }

    private fun stopTracking() {
        fused.removeLocationUpdates(callback)
        engine.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle(getString(R.string.tracking_notification_title))
        .setContentText("GPS trail recording is active")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .build()

    private fun updateNotification() {
        if (Build.VERSION.SDK_INT >= 24) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.tracking_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
