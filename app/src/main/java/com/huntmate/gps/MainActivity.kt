package com.huntmate.gps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.huntmate.gps.activity.ActivityMode
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var controller: HuntMateGpsController
    private lateinit var activitySpinner: Spinner
    private lateinit var toolSpinner: Spinner
    private var mapReady = false
    private var followLocation = true

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) controller.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureMapNetworking()
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        mapView = findViewById(R.id.mapView)
        controller = HuntMateGpsController(this)
        activitySpinner = findViewById(R.id.activitySpinner)
        toolSpinner = findViewById(R.id.toolSpinner)
        setupActivitySelectors()
        findViewById<Button>(R.id.appsButton).setOnClickListener { startActivity(Intent(this, AppHubActivity::class.java)) }

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.REASON_API_GESTURE) followLocation = false
            }
            map.setStyle(Style.Builder().fromUri("asset://huntmate_osm_style.json")) { setupLayers() }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener { ensurePermissionAndStart() }
        findViewById<Button>(R.id.pauseButton).setOnClickListener { controller.pause() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { controller.stop() }
        findViewById<Button>(R.id.recenterButton).setOnClickListener { followLocation = true; controller.snapshot.value.point?.let { map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.5)) } }

        lifecycleScope.launch {
            controller.snapshot.collect { snapshot ->
                updateUi(snapshot)
                if (mapReady) drawTrack(snapshot)
            }
        }
    }

    private fun setupActivitySelectors() {
        val modes = ActivityMode.entries.toTypedArray()
        activitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        activitySpinner.setSelection(controller.currentActivity().ordinal)
        activitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val mode = modes[position]
                if (mode == controller.currentActivity()) return
                runCatching { controller.selectActivity(mode); populateTools(mode) }
                    .onFailure { Toast.makeText(this@MainActivity, "Pause or stop tracking before switching activity.", Toast.LENGTH_SHORT).show(); activitySpinner.setSelection(controller.currentActivity().ordinal) }
            }
        }
        populateTools(controller.currentActivity())
    }

    private fun populateTools(mode: ActivityMode) {
        toolSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mode.tools)
    }

    private fun configureMapNetworking() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "HuntMate-Hunting-Fishing/1.5.1 (https://huntmate.app)")
                .build()
            chain.proceed(request)
        }.build()
        org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(client)
    }

    private fun setupLayers() {
        mapReady = true
        val style = map.style ?: return
        style.addSource(GeoJsonSource("huntmate-track-source", FeatureCollection.fromFeatures(arrayOf())))
        style.addSource(GeoJsonSource("huntmate-location-source", FeatureCollection.fromFeatures(arrayOf())))
        style.addSource(GeoJsonSource("huntmate-accuracy-source", FeatureCollection.fromFeatures(arrayOf())))
        style.addLayer(LineLayer("huntmate-track-line", "huntmate-track-source").withProperties(
            PropertyFactory.lineColor("#C62828"), PropertyFactory.lineWidth(4f), PropertyFactory.lineOpacity(0.9f)
        ))
        style.addLayer(CircleLayer("huntmate-accuracy", "huntmate-accuracy-source").withProperties(
            PropertyFactory.circleColor("#1565C0"), PropertyFactory.circleOpacity(0.14f),
            PropertyFactory.circleStrokeColor("#1565C0"), PropertyFactory.circleStrokeOpacity(0.45f),
            PropertyFactory.circleStrokeWidth(1.5f)
        ))
        style.addLayer(CircleLayer("huntmate-location-dot", "huntmate-location-source").withProperties(
            PropertyFactory.circleColor("#1565C0"), PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)
        ))
    }

    private fun drawTrack(snapshot: TrackingSnapshot) {
        val style = map.style ?: return
        val lineSource = style.getSourceAs<GeoJsonSource>("huntmate-track-source")
        val pointSource = style.getSourceAs<GeoJsonSource>("huntmate-location-source")
        val accuracySource = style.getSourceAs<GeoJsonSource>("huntmate-accuracy-source")
        val coordinates = snapshot.points.map { Point.fromLngLat(it.longitude, it.latitude) }
        lineSource?.setGeoJson(if (coordinates.size >= 2) Feature.fromGeometry(LineString.fromLngLats(coordinates)) else FeatureCollection.fromFeatures(arrayOf()))
        snapshot.point?.let {
            pointSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)))
            accuracySource?.setGeoJson(Feature.fromGeometry(accuracyPolygon(it.latitude, it.longitude, it.accuracyMeters)))
            if (followLocation) map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.5))
        }
    }


    private fun accuracyPolygon(latitude: Double, longitude: Double, radiusMeters: Float): Polygon {
        val radius = radiusMeters.coerceIn(1f, 5000f).toDouble()
        val earthRadius = 6_378_137.0
        val angular = radius / earthRadius
        val lat = Math.toRadians(latitude)
        val lon = Math.toRadians(longitude)
        val ring = (0..48).map { i ->
            val bearing = 2.0 * Math.PI * i / 48.0
            val lat2 = kotlin.math.asin(
                kotlin.math.sin(lat) * kotlin.math.cos(angular) +
                    kotlin.math.cos(lat) * kotlin.math.sin(angular) * kotlin.math.cos(bearing)
            )
            val lon2 = lon + kotlin.math.atan2(
                kotlin.math.sin(bearing) * kotlin.math.sin(angular) * kotlin.math.cos(lat),
                kotlin.math.cos(angular) - kotlin.math.sin(lat) * kotlin.math.sin(lat2)
            )
            Point.fromLngLat(Math.toDegrees(lon2), Math.toDegrees(lat2))
        }
        return Polygon.fromLngLats(listOf(ring))
    }

    private fun updateUi(s: TrackingSnapshot) {
        findViewById<TextView>(R.id.statusText).text = when (s.state) {
            TrackingState.IDLE -> "${controller.currentActivity().emoji} ${controller.currentActivity().title} • GPS ready"
            TrackingState.TRACKING -> "${controller.currentActivity().emoji} ${controller.currentActivity().title} • Tracking active"
            TrackingState.PAUSED -> "${controller.currentActivity().emoji} ${controller.currentActivity().title} • Tracking paused"
        }
        findViewById<TextView>(R.id.locationText).text = s.point?.let { "%.6f, %.6f  •  ±%.1f m".format(it.latitude, it.longitude, it.accuracyMeters) } ?: "Waiting for GPS…"
        findViewById<TextView>(R.id.statsText).text = "Distance: ${formatDistance(s.distanceMeters)}   •   Points: ${s.points.size}"
    }

    private fun formatDistance(meters: Double): String = if (meters < 1000) "${meters.toInt()} m" else "%.2f km".format(meters / 1000.0)

    private fun ensurePermissionAndStart() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) controller.start() else permissionLauncher.launch(HuntMateGpsController.requiredPermissions)
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { mapView.onSaveInstanceState(outState); super.onSaveInstanceState(outState) }
}
