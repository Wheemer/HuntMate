package com.huntmate.gps

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var controller: HuntMateGpsController
    private lateinit var mapDataRepository: MapDataRepository
    private lateinit var activityButton: Button
    private var mapReady = false
    private var followLocation = true
    private var mapMode = 0 // 0 = OpenStreetMap, 1 = satellite, 2 = topographic, 3 = hybrid satellite
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var compassBearing: Float? = null
    private var lastTravelBearing: Float? = null
    private var lastDirectionLocation: Location? = null
    private val savedPlaces = mutableListOf<SavedPlace>()
    private var navigationTarget: SavedPlace? = null
    private val officialLayerVisibility = linkedMapOf(
        "nl-protected-areas" to false, "nl-closed-areas" to false, "nl-moose-zones" to false,
        "nl-caribou-zones" to false, "nl-bear-zones" to false
    )
    private data class OfficialLayer(val id: String, val title: String, val url: String, val fill: String, val outline: String)

    private data class SavedPlace(val name: String, val latitude: Double, val longitude: Double)

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var locateAfterPermission = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locateAfterPermission) {
            locateAfterPermission = false
            if (granted) locateMe()
            else Toast.makeText(this, "Location permission is needed to find your position.", Toast.LENGTH_LONG).show()
        } else if (granted) {
            controller.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize MapLibre before configuring its HTTP client.
        MapLibre.getInstance(this)
        configureMapNetworking()
        setContentView(R.layout.activity_main)
        mapView = findViewById(R.id.mapView)
        mapDataRepository = MapDataRepository(this)
        controller = HuntMateGpsController(this)
        loadSavedPlaces()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        activityButton = findViewById(R.id.activityButton)
        setupActivityChooser()
        findViewById<Button>(R.id.toolsButton).setOnClickListener { showToolsMenu() }
        findViewById<Button>(R.id.trackingPanelToggle).setOnClickListener { toggleTrackingDetails() }
        findViewById<Button>(R.id.appsButton).setOnClickListener { startActivity(Intent(this, AppHubActivity::class.java)) }
        findViewById<Button>(R.id.officialLayersButton).setOnClickListener { showOfficialLayers() }

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = false
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) followLocation = false
            }
            applyMapStyle()
        }

        findViewById<Button>(R.id.startButton).setOnClickListener { ensurePermissionAndStart() }
        findViewById<Button>(R.id.pauseButton).setOnClickListener { controller.pause() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { controller.stop() }
        updateMapTypeButton()
        findViewById<Button>(R.id.recenterButton).setOnClickListener { locateMe() }
        findViewById<Button>(R.id.mapTypeButton).setOnClickListener {
            mapMode = (mapMode + 1) % 4
            updateMapTypeButton()
            applyMapStyle()
            Toast.makeText(this, "Map layer: ${currentMapModeName()}", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            controller.snapshot.collect { snapshot ->
                updateUi(snapshot)
                if (mapReady) drawTrack(snapshot)
            }
        }
    }

    private fun setupActivityChooser() {
        val modes = ActivityMode.entries.toTypedArray()
        activityButton.text = controller.currentActivity().title
        activityButton.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Choose activity")
                .setItems(modes.map { it.emoji + "  " + it.title }.toTypedArray()) { _, index ->
                    val mode = modes[index]
                    runCatching { controller.selectActivity(mode) }
                        .onSuccess { activityButton.text = mode.title; updateUi(controller.snapshot.value) }
                        .onFailure { Toast.makeText(this, "Pause or stop tracking before switching activity.", Toast.LENGTH_SHORT).show() }
                }.show()
        }
    }

    private fun showToolsMenu() {
        val items = arrayOf("Save waypoint", "Return to waypoint", "Orient to compass", "Official NL layers", "Official small-game rules")
        AlertDialog.Builder(this).setTitle("Map tools").setItems(items) { _, index ->
            when (index) {
                0 -> saveWaypoint()
                1 -> chooseReturnDestination()
                2 -> { val bearing = compassBearing; if (bearing != null) map.animateCamera(CameraUpdateFactory.bearingTo(bearing.toDouble())) else Toast.makeText(this, "Compass is not available on this device.", Toast.LENGTH_SHORT).show() }
                3 -> showOfficialLayers()
                4 -> chooseOfficialSmallGameGuide()
            }
        }.show()
    }

    private fun toggleTrackingDetails() {
        val details = findViewById<android.widget.LinearLayout>(R.id.trackingDetails)
        val showing = details.visibility == android.view.View.VISIBLE
        details.visibility = if (showing) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<Button>(R.id.trackingPanelToggle).text = if (showing) "Details" else "Hide"
    }

    private fun currentMapModeName(): String = when (mapMode) {
        1 -> "Satellite"
        2 -> "Topographic"
        3 -> "Hybrid"
        else -> "Map"
    }

    private fun updateMapTypeButton() {
        findViewById<Button>(R.id.mapTypeButton).text = currentMapModeName()
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

    private fun applyMapStyle() {
        val styleAsset = when (mapMode) {
            1 -> "asset://huntmate_satellite_style.json"
            2 -> "asset://huntmate_topographic_style.json"
            3 -> "asset://huntmate_hybrid_style.json"
            else -> "asset://huntmate_osm_style.json"
        }
        map.setStyle(Style.Builder().fromUri(styleAsset)) {
            setupLayers()
            findViewById<TextView>(R.id.mapAttribution).text = when (mapMode) {
                1 -> "Imagery © Esri and its imagery providers"
                2 -> "Topographic map © Natural Resources Canada"
                3 -> "Imagery © Esri · Labels and boundaries © Esri"
                else -> "© OpenStreetMap contributors · ODbL"
            }
        }
    }

    private fun setupLayers() {
        mapReady = true
        val style = map.style ?: return
        style.addSource(GeoJsonSource("huntmate-track-source", FeatureCollection.fromFeatures(arrayOf())))
        style.addSource(GeoJsonSource("huntmate-location-source", FeatureCollection.fromFeatures(arrayOf())))
        style.addSource(GeoJsonSource("huntmate-accuracy-source", FeatureCollection.fromFeatures(arrayOf())))
        style.addSource(GeoJsonSource("huntmate-direction-source", FeatureCollection.fromFeatures(arrayOf())))
        style.addSource(GeoJsonSource("huntmate-saved-source", FeatureCollection.fromFeatures(arrayOf())))
        style.addImage("direction-arrow", createDirectionArrowBitmap())
        style.addLayer(LineLayer("huntmate-track-line", "huntmate-track-source").withProperties(
            PropertyFactory.lineColor("#C62828"), PropertyFactory.lineWidth(4f), PropertyFactory.lineOpacity(0.9f)
        ))
        style.addLayer(CircleLayer("huntmate-accuracy", "huntmate-accuracy-source").withProperties(
            PropertyFactory.circleColor("#1565C0"), PropertyFactory.circleOpacity(0.14f),
            PropertyFactory.circleStrokeColor("#1565C0"), PropertyFactory.circleStrokeOpacity(0.45f),
            PropertyFactory.circleStrokeWidth(1.5f)
        ))
        style.addLayer(SymbolLayer("huntmate-direction-arrow", "huntmate-direction-source").withProperties(
            PropertyFactory.iconImage("direction-arrow"),
            PropertyFactory.iconRotate(org.maplibre.android.style.expressions.Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment("map"),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconSize(0.85f)
        ))
        style.addLayer(CircleLayer("huntmate-saved-circles", "huntmate-saved-source").withProperties(
            PropertyFactory.circleColor("#6A3D9A"), PropertyFactory.circleRadius(6f),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)
        ))
        style.addLayer(SymbolLayer("huntmate-saved-labels", "huntmate-saved-source").withProperties(
            PropertyFactory.textField("{name}"), PropertyFactory.textSize(12f),
            PropertyFactory.textColor("#3D245A"), PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1f), PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textAllowOverlap(false), PropertyFactory.textIgnorePlacement(false)
        ))
        style.addLayer(CircleLayer("huntmate-location-dot", "huntmate-location-source").withProperties(
            PropertyFactory.circleColor("#1565C0"), PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)
        ))
        drawSavedPlaces()
        setupOfficialLayers(style)
        drawTrack(controller.snapshot.value)
    }

    private fun createDirectionArrowBitmap(): Bitmap {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(46, 125, 50)
            style = Paint.Style.FILL
        }
        val arrow = Path().apply {
            moveTo(size / 2f, 3f)
            lineTo(size * 0.84f, size * 0.88f)
            lineTo(size / 2f, size * 0.69f)
            lineTo(size * 0.16f, size * 0.88f)
            close()
        }
        canvas.drawPath(arrow, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawPath(arrow, paint)
        return bitmap
    }

    private fun officialLayers(): List<OfficialLayer> = listOf(
        OfficialLayer("nl-protected-areas", "Protected Areas Plan", "https://dnrmaps.gov.nl.ca/arcgis/rest/services/GeoAtlas/Land_Use/MapServer/0/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=geoJSON", "#795548", "#5D4037"),
        OfficialLayer("nl-closed-areas", "Closed areas (official 2022-23 map)", "https://services8.arcgis.com/aCyQID5qQcyrJMm2/arcgis/rest/services/Closed_Areas/FeatureServer/32/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=geoJSON&maxAllowableOffset=0.0001", "#C62828", "#8E0000"),
        OfficialLayer("nl-moose-zones", "Moose management areas", "https://services8.arcgis.com/aCyQID5qQcyrJMm2/arcgis/rest/services/WLD_BigGameManagementArea/FeatureServer/0/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=geoJSON&maxAllowableOffset=0.0001", "#D6A900", "#8D6E00"),
        OfficialLayer("nl-caribou-zones", "Caribou management areas", "https://services8.arcgis.com/aCyQID5qQcyrJMm2/arcgis/rest/services/WLD_BigGameManagementArea/FeatureServer/1/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=geoJSON&maxAllowableOffset=0.0001", "#2E7D32", "#1B5E20"),
        OfficialLayer("nl-bear-zones", "Black bear management areas", "https://services8.arcgis.com/aCyQID5qQcyrJMm2/arcgis/rest/services/WLD_BigGameManagementArea/FeatureServer/2/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=geoJSON&maxAllowableOffset=0.0001", "#6A1B9A", "#4A148C")
    )
    private fun showOfficialLayers() {
        val layers = officialLayers()
        val selected = layers.map { officialLayerVisibility[it.id] == true }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Official NL map layers")
            .setMultiChoiceItems(layers.map { it.title }.toTypedArray(), selected) { _, index, checked -> officialLayerVisibility[layers[index].id] = checked }
            .setPositiveButton("Apply") { _, _ -> applyMapStyle(); Toast.makeText(this, "Official layers updated. Data is cached on this phone for up to seven days.", Toast.LENGTH_LONG).show() }
            .setNeutralButton("Sources & notes") { _, _ -> showOfficialDataNotes() }
            .setNegativeButton("Cancel", null).show()
    }
    private fun showOfficialDataNotes() {
        AlertDialog.Builder(this).setTitle("Official data sources").setMessage(
            "GeoAtlas land-use layer: Government of Newfoundland and Labrador.\n\n" +
            "Management-area layers: NL Wildlife Division, Department of Forestry, Agriculture and Lands.\n\n" +
            "Closed-area geometry: Government of Newfoundland and Labrador. The service metadata says it was last aligned with the 2022-23 guide; always check the current guide.\n\n" +
            "These are reference layers, not legal determinations. Seasons, licences, emergency closures, access permissions and municipal restrictions can change. Check the current NL Hunting and Trapping Guide before hunting.\n\n" +
            "GeoAtlas:\nhttps://dnrmaps.gov.nl.ca/arcgis/rest/services/GeoAtlas/Land_Use/MapServer\n\nHunting guide:\nhttps://www.gov.nl.ca/hunting-trapping-guide/"
        ).setNeutralButton("Small-game guide") { _, _ -> chooseOfficialSmallGameGuide() }.setPositiveButton("Close", null).show()
    }
    private fun chooseOfficialSmallGameGuide() {
        val names = arrayOf("Island small game", "Labrador small game", "Closed Areas")
        val urls = arrayOf(
            "https://www.gov.nl.ca/hunting-trapping-guide/2026-27/hunting-seasons-and-zones/island/small-game/",
            "https://www.gov.nl.ca/hunting-trapping-guide/2026-27/hunting-seasons-and-zones/labrador/small-game/",
            "https://www.gov.nl.ca/hunting-trapping-guide/2026-27/closed-areas/"
        )
        AlertDialog.Builder(this).setTitle("Official NL small-game rules")
            .setItems(names) { _, index -> openOfficialPage(urls[index]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun openOfficialPage(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser is available to open the official guide.", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupOfficialLayers(style: Style) {
        officialLayers().filter { officialLayerVisibility[it.id] == true }.forEach { layer ->
            val sourceId = layer.id + "-source"
            style.addSource(GeoJsonSource(sourceId, FeatureCollection.fromFeatures(arrayOf())))
            style.addLayer(FillLayer(layer.id + "-fill", sourceId).withProperties(PropertyFactory.fillColor(layer.fill), PropertyFactory.fillOpacity(0.18f)))
            style.addLayer(LineLayer(layer.id + "-line", sourceId).withProperties(PropertyFactory.lineColor(layer.outline), PropertyFactory.lineWidth(2f), PropertyFactory.lineOpacity(0.9f)))
            mapDataRepository.load(layer.id, layer.url) { result ->
                runOnUiThread {
                    if (map.style !== style) return@runOnUiThread
                    result.geoJson?.let { style.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(it) }
                    if (result.error != null && result.geoJson == null) Toast.makeText(this, layer.title + ": " + result.error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun drawTrack(snapshot: TrackingSnapshot) {
        val style = map.style ?: return
        val lineSource = style.getSourceAs<GeoJsonSource>("huntmate-track-source")
        val pointSource = style.getSourceAs<GeoJsonSource>("huntmate-location-source")
        val accuracySource = style.getSourceAs<GeoJsonSource>("huntmate-accuracy-source")
        val directionSource = style.getSourceAs<GeoJsonSource>("huntmate-direction-source")
        // Keep short tracks as an empty FeatureCollection. MapLibre's native
        // renderer can crash if given a null GeoJSON Feature.
        lineSource?.setGeoJson(trackGeoJson(snapshot.points))
        snapshot.point?.let { point ->
            val here = Point.fromLngLat(point.longitude, point.latitude)
            pointSource?.setGeoJson(Feature.fromGeometry(here))
            accuracySource?.setGeoJson(Feature.fromGeometry(accuracyPolygon(point.latitude, point.longitude, point.accuracyMeters)))
            val location = Location("huntmate").apply {
                latitude = point.latitude
                longitude = point.longitude
            }
            val previous = lastDirectionLocation
            if (previous != null && previous.distanceTo(location) >= 3f) {
                val bearing = previous.bearingTo(location)
                if (bearing.isFinite()) lastTravelBearing = (bearing + 360f) % 360f
                lastDirectionLocation = location
            } else if (previous == null) {
                lastDirectionLocation = location
            }
            val direction = lastTravelBearing ?: compassBearing
            if (direction != null) {
                directionSource?.setGeoJson(Feature.fromGeometry(here).apply {
                    addNumberProperty("bearing", direction.toDouble())
                })
            } else {
                directionSource?.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
            }
            if (followLocation) map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 15.5))
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
        updateNavigationText(s)
    }


    private fun loadSavedPlaces() {
        savedPlaces.clear()
        val raw = getSharedPreferences("huntmate_waypoints", MODE_PRIVATE).getString("places", "[]") ?: "[]"
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val latitude = item.optDouble("latitude", Double.NaN)
                val longitude = item.optDouble("longitude", Double.NaN)
                if (name.isNotBlank() && latitude.isFinite() && longitude.isFinite() &&
                    latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                    savedPlaces.add(SavedPlace(name, latitude, longitude))
                }
            }
        }
    }

    private fun persistSavedPlaces() {
        val array = JSONArray()
        savedPlaces.forEach { place ->
            array.put(JSONObject().put("name", place.name).put("latitude", place.latitude).put("longitude", place.longitude))
        }
        getSharedPreferences("huntmate_waypoints", MODE_PRIVATE).edit().putString("places", array.toString()).apply()
    }

    private fun saveWaypoint() {
        val point = controller.snapshot.value.point
        if (point == null) {
            locateMe()
            Toast.makeText(this, "Wait for a GPS fix, then save your location.", Toast.LENGTH_LONG).show()
            return
        }
        val input = EditText(this).apply {
            hint = "e.g. Truck, Camp, Hunting Spot"
            setSingleLine(true)
            setText("Waypoint ${savedPlaces.size + 1}")
        }
        AlertDialog.Builder(this)
            .setTitle("Save current location")
            .setMessage("Saved privately on this phone.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Waypoint ${savedPlaces.size + 1}" }
                val place = SavedPlace(name, point.latitude, point.longitude)
                savedPlaces.add(place)
                navigationTarget = place
                persistSavedPlaces()
                drawSavedPlaces()
                updateNavigationText(controller.snapshot.value)
                Toast.makeText(this, "$name saved.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseReturnDestination() {
        if (savedPlaces.isEmpty()) {
            Toast.makeText(this, "Save a truck, camp or waypoint first.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Return to saved location")
            .setItems(savedPlaces.map { it.name }.toTypedArray()) { _, index ->
                navigationTarget = savedPlaces[index]
                updateNavigationText(controller.snapshot.value)
                val point = controller.snapshot.value.point
                val target = navigationTarget!!
                if (point != null) {
                    val from = Location("current").apply { latitude = point.latitude; longitude = point.longitude }
                    val to = Location("saved").apply { latitude = target.latitude; longitude = target.longitude }
                    val bearing = (from.bearingTo(to) + 360f) % 360f
                    Toast.makeText(this, "${target.name}: ${formatDistance(from.distanceTo(to).toDouble())} · bearing ${bearing.toInt()}° straight-line", Toast.LENGTH_LONG).show()
                } else {
                    locateMe()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun drawSavedPlaces() {
        val source = map.style?.getSourceAs<GeoJsonSource>("huntmate-saved-source") ?: return
        val features = savedPlaces.map { place ->
            Feature.fromGeometry(Point.fromLngLat(place.longitude, place.latitude)).apply {
                addStringProperty("name", place.name)
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun updateNavigationText(snapshot: TrackingSnapshot) {
        val view = findViewById<TextView>(R.id.navigationText) ?: return
        val target = navigationTarget
        val point = snapshot.point
        if (target == null) {
            view.text = if (savedPlaces.isEmpty()) "Save a truck, camp or waypoint for return guidance" else "Choose a saved location for return guidance"
            return
        }
        if (point == null) {
            view.text = "To ${target.name} · waiting for GPS"
            return
        }
        val from = Location("current").apply { latitude = point.latitude; longitude = point.longitude }
        val to = Location("saved").apply { latitude = target.latitude; longitude = target.longitude }
        val bearing = (from.bearingTo(to) + 360f) % 360f
        view.text = "To ${target.name}: ${formatDistance(from.distanceTo(to).toDouble())} · ${bearing.toInt()}° bearing (straight-line)"
    }

    private fun formatDistance(meters: Double): String = if (meters < 1000) "${meters.toInt()} m" else "%.2f km".format(meters / 1000.0)

    /**
     * Locates the device without requiring a tracking session to be active.
     * On Android 11+ request a fresh provider fix and fall back to the last
     * known location when the provider cannot return one.
     */
    private fun locateMe() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            // Keep the one-tap locate action separate from starting a tracking session.
            locateAfterPermission = true
            permissionLauncher.launch(HuntMateGpsController.requiredPermissions)
            return
        }
        if (!::map.isInitialized || !mapReady) {
            Toast.makeText(this, "Map is still loading. Try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            Toast.makeText(this, "Turn on Location in your phone settings, then try again.", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "Finding your location…", Toast.LENGTH_SHORT).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                locationManager.getCurrentLocation(provider, CancellationSignal(), mainExecutor) { location ->
                    if (location != null) {
                        showMyLocation(location)
                    } else {
                        val last = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                        if (last != null) showMyLocation(last)
                        else Toast.makeText(this, "Can't get a GPS fix yet. Move outdoors and try again.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: SecurityException) {
                Toast.makeText(this, "Location permission is required to find your position.", Toast.LENGTH_LONG).show()
            }
        } else {
            val last = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            if (last != null) showMyLocation(last)
            else Toast.makeText(this, "Start tracking to get a GPS fix on this Android version.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMyLocation(location: Location) {
        if (!::map.isInitialized || !mapReady) return
        followLocation = true
        val here = Point.fromLngLat(location.longitude, location.latitude)
        map.style?.getSourceAs<GeoJsonSource>("huntmate-location-source")?.setGeoJson(Feature.fromGeometry(here))
        val accuracy = if (location.hasAccuracy()) location.accuracy else 50f
        map.style?.getSourceAs<GeoJsonSource>("huntmate-accuracy-source")?.setGeoJson(
            Feature.fromGeometry(accuracyPolygon(location.latitude, location.longitude, accuracy))
        )
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 15.5))
        findViewById<TextView>(R.id.locationText).text =
            "%.6f, %.6f  •  ±%.1f m".format(location.latitude, location.longitude, accuracy)
    }

    private fun ensurePermissionAndStart() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) controller.start() else permissionLauncher.launch(HuntMateGpsController.requiredPermissions)
    }

    private val compassListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            val rotation = FloatArray(9)
            val orientation = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            compassBearing = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f
            if (mapReady) drawTrack(controller.snapshot.value)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        rotationSensor?.let { sensorManager.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_UI) }
    }
    override fun onPause() {
        sensorManager.unregisterListener(compassListener)
        mapView.onPause()
        super.onPause()
    }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { mapView.onSaveInstanceState(outState); super.onSaveInstanceState(outState) }
}
