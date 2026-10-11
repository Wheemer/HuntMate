package com.huntmate.gps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class HuntLocationService : Service() {
    companion object {
        const val ACTION_START = "com.smallgamerecord.app.START_HUNT_TRACKING"
        const val PREFS = "small_game_nl_hunt_tracking"
        const val KEY_SAMPLES = "samples"
        private const val CHANNEL_ID = "hunt_location_tracking"
        private const val NOTIFICATION_ID = 7402
    }

    private lateinit var locationManager: LocationManager
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { saveLocation(location) }
        @Deprecated("Deprecated by Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        prefs.edit().putString(KEY_SAMPLES, "[]").apply()
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 15000L, 0f, listener, Looper.getMainLooper())
            } else if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30000L, 0f, listener, Looper.getMainLooper())
            } else {
                stopSelf()
            }
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: IllegalArgumentException) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun saveLocation(location: Location) {
        val point = JSONObject()
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracy", location.accuracy.toInt())
            .put("capturedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(location.time)))
        synchronized(this) {
            val samples = try { JSONArray(prefs.getString(KEY_SAMPLES, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
            samples.put(point)
            prefs.edit().putString(KEY_SAMPLES, samples.toString()).apply()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Active hunt GPS", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows when Small Game Records NL is recording GPS for an active hunt."
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("Small Game Records NL")
            .setContentText("GPS tracking is active for your hunt")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
