successfully downloaded text file (SHA: d56dda71397acc791f49de4dc253e08cca25e6fd)package com.huntmate.gps

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
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import java.net.URI
import android.content.res.ColorStateList
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
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
    private val mapLayerErrors = mutableMapOf<String, String>()
    private val mapLayerFetchedAt = mutableMapOf<String, Long>()
    private val mapLayerLoading = mutableSetOf<String>()
    private val mapLayerCompleted = mutableSetOf<String>()
    private val mapLayerInitializationErrors = mutableSetOf<String>()
    private val expectedMapLayerCount = 12
    private var manualRefreshRequested = false
    private var allowMobileDataForRefresh = false
    private lateinit var activitySpinner: Spinner
    private lateinit var toolSpinner: Spinner
    private var mapReady = false
    /** First style load positions the camera; basemap switches must never recenter the map. */
    private var initialCameraSet = false
    private val landLayerVisibility = mutableMapOf(
        "hm-protected-plan" to true, "hm-specified-material" to true,
        "hm-inuit-lands" to true, "hm-inuit-settlement" to true,
        "hm-protected-conserved" to true, "hm-water-supply" to true,
        "hm-municipal-boundaries" to true, "hm-planning-areas" to true,
        "hm-wind-reserve" to true
    )
    private val huntingLayerVisibility = mutableMapOf(
        "hm-moose-zones" to true, "hm-caribou-zones" to true, "hm-bear-zones" to true
    )

    /** Layer on/off state persists across basemap switches, recreation, and app restarts. */
    private fun layerPrefs() = getSharedPreferences("huntmate_layers", MODE_PRIVATE)

    private fun loadLayerVisibility() {
        val prefs = layerPrefs()
        landLayerVisibility.keys.toList().forEach { key ->
            landLayerVisibility[key] = prefs.getBoolean("land_$key", true)
        }
        huntingLayerVisibility.keys.toList().forEach { key ->
            huntingLayerVisibility[key] = prefs.getBoolean("hunt_$key", true)
        }
    }

    private fun saveLayerVisibility() {
        val editor = layerPrefs().edit()
        landLayerVisibility.forEach { (key, value) -> editor.putBoolean("land_$key", value) }
        huntingLayerVisibility.forEach { (key, value) -> editor.putBoolean("hunt_$key", value) }
        editor.apply()
    }

    private fun layerRowSuffix(key: String): String {
        if (mapLayerErrors[key] != null) return " — UNAVAILABLE/STALE"
        val fetched = mapLayerFetchedAt[key] ?: return ""
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CANADA).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(fetched))
        return " · updated $date"
    }
    // Keep the initial map stable until the user explicitly recenters on GPS.
    private var followLocation = false
    private var mapMode = 0 // 0 = OpenStreetMap, 1 = satellite, 2 = topographic, 3 = hybrid satellite
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var compassBearing: Float? = null
    private var lastTravelBearing: Float? = null
    private var lastDirectionLocation: Location? = null
    private val savedPlaces = mutableListOf<SavedPlace>()
    private var navigationTarget: SavedPlace? = null

    private data class SavedPlace(val name: String, val latitude: Double, val longitude: Double)

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) controller.start()
        else Toast.makeText(this, "Location permission denied — HuntMate can't show your position or record tracks.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize MapLibre before configuring its HTTP client.
        MapLibre.getInstance(this)
        configureMapNetworking()
        setContentView(R.layout.activity_main)
        mapView = findViewById(R.id.mapView)
        controller = HuntMateGpsController(this)
        mapDataRepository = MapDataRepository(this)
        loadSavedPlaces()
        loadLayerVisibility()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        activitySpinner = findViewById(R.id.activitySpinner)
        toolSpinner = findViewById(R.id.toolSpinner)
        setupActivitySelectors()
        findViewById<Button>(R.id.appsMenuButton).setOnClickListener { startActivity(Intent(this, AppHubActivity::class.java)) }
        findViewById<Button>(R.id.layersButton).setOnClickListener { showLayersMenu() }

        // Status header expands to reveal coordinates and navigation details; collapsed by
        // default so the map gets maximum screen space.
        var detailsExpanded = false
        findViewById<LinearLayout>(R.id.statusHeader).setOnClickListener {
            detailsExpanded = !detailsExpanded
            findViewById<LinearLayout>(R.id.statusDetails).visibility = if (detailsExpanded) View.VISIBLE else View.GONE
            findViewById<ImageView>(R.id.statusChevron).rotation = if (detailsExpanded) 180f else 0f
            findViewById<ImageView>(R.id.statusChevron).contentDescription = if (detailsExpanded) "Hide details" else "Show details"
        }

        findViewById<Button>(R.id.saveWaypointButton).setOnClickListener { saveWaypoint() }
        findViewById<Button>(R.id.returnButton).setOnClickListener { chooseReturnDestination() }

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) followLocation = false
            }
            // Tapping a boundary polygon shows its name, source, type, and verified restrictions.
            map.addOnMapClickListener { point -> onMapTap(point); true }
            applyMapStyle()
        }

        findViewById<Button>(R.id.startButton).setOnClickListener { ensurePermissionAndStart() }
        findViewById<Button>(R.id.pauseButton).setOnClickListener { controller.pause() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { controller.stop() }
        findViewById<Button>(R.id.recenterButton).setOnClickListener {
            val point = controller.snapshot.value.point
            if (point != null) {
                followLocation = true
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 15.5))
            } else {
                ensurePermissionAndStart()
                Toast.makeText(this, "Getting your location…", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.mapTypeButton).setOnClickListener {
            mapMode = (mapMode + 1) % 4
            val name = when (mapMode) {
                0 -> "Standard map"
                1 -> "Satellite"
                2 -> "Topographic"
                else -> "Hybrid satellite"
            }
            Toast.makeText(this, name, Toast.LENGTH_SHORT).show()
            applyMapStyle()
        }
        findViewById<Button>(R.id.compassButton).setOnClickListener {
            val bearing = compassBearing
            if (bearing != null) map.animateCamera(CameraUpdateFactory.bearingTo(bearing.toDouble()))
            else Toast.makeText(this, "Compass is not available on this device.", Toast.LENGTH_SHORT).show()
        }

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

    private fun applyMapStyle() {
        // OpenFreeMap's Bright vector style gives HuntMate a cleaner, Google Maps-like
        // road and place-label appearance. Keep the existing licensed imagery/topo modes.
        val styleUri = when (mapMode) {
            0 -> "https://tiles.openfreemap.org/styles/bright"
            1 -> "asset://huntmate_satellite_style.json"
            2 -> "asset://huntmate_topographic_style.json"
            else -> "asset://huntmate_hybrid_style.json"
        }
        map.setStyle(Style.Builder().fromUri(styleUri)) {
            if (!initialCameraSet) {
                initialCameraSet = true
                // First style load: start over Newfoundland and Labrador so the map is useful
                // before GPS permission or a location fix; prefer the last known location
                // (restored session) when one exists. Never recenter on later style switches.
                val last = controller.snapshot.value.point
                val target = if (last != null) LatLng(last.latitude, last.longitude) else LatLng(49.3, -56.0)
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, if (last != null) 13.0 else 5.2))
            }
            setupLayers()
            findViewById<TextView>(R.id.mapAttribution).text = when (mapMode) {
                0 -> "© OpenStreetMap contributors · OpenFreeMap"
                1 -> "Imagery © Esri and its imagery providers"
                2 -> "Topographic map © Natural Resources Canada"
                else -> "Imagery © Esri · Labels and boundaries © Esri"
            }
        }
    }

    private fun setupLayers() {
        mapReady = true
        val style = map.style ?: return
        mapLayerLoading.clear()
        mapLayerCompleted.clear()
        mapLayerInitializationErrors.clear()
        mapLayerErrors.clear()
        updateMapDataStatus()
        // Use actual ArcGIS GeoJSON queries, not an export image masquerading as an XYZ tile source.
        // The old RasterSource used a bbox export URL that MapLibre could not request as a tile pyramid.
        // Protected-area polygons are NOT a complete hunting-closure dataset; hunting rules have exceptions.
        // Protected Areas Plan 2020 uses brown — deliberately NOT the red reserved for
        // CLOSED caribou zones, so a planning polygon can never read as a hunting closure.
        addGovernmentGeoJsonLayer(style, "hm-protected-plan", 0, "#795548", 0.24f, "#5D4037", 2.5f)
        addHuntingZoneLayer(style, "hm-moose-zones", 0, "#D6A900", "#8D6E00")
        addHuntingZoneLayer(style, "hm-caribou-zones", 1, "#2E7D32", "#1B5E20", true)
        addHuntingZoneLayer(style, "hm-bear-zones", 2, "#6A1B9A", "#4A148C")
        addGovernmentGeoJsonLayer(style, "hm-specified-material", 1, "#607D8B", 0.12f, "#78909C", 1.8f)
        addGovernmentGeoJsonLayer(style, "hm-protected-conserved", 4, "#F57C00", 0.16f, "#EF6C00", 2.0f)
        addGovernmentGeoJsonLayer(style, "hm-water-supply", 5, "#1976D2", 0.20f, "#42A5F5", 2.0f)
        addGovernmentGeoJsonLayer(style, "hm-inuit-lands", 2, "#7E57C2", 0.13f, "#9575CD", 2.0f)
        addGovernmentGeoJsonLayer(style, "hm-inuit-settlement", 3, "#00897B", 0.10f, "#26A69A", 2.0f)
        addGovernmentGeoJsonLayer(style, "hm-municipal-boundaries", 6, null, 0f, "#FDD835", 1.6f)
        addGovernmentGeoJsonLayer(style, "hm-planning-areas", 7, null, 0f, "#FFFFFF", 1.1f)
        addGovernmentGeoJsonLayer(style, "hm-wind-reserve", 8, "#C2185B", 0.10f, "#EC407A", 2.0f)
        manualRefreshRequested = false
        allowMobileDataForRefresh = false
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
            PropertyFactory.iconSize(1.0f)
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
        // The directional arrow is the user's location marker. Do not draw a dot over it,
        // because the dot hides the arrowhead and makes heading hard to read.
        drawSavedPlaces()
        drawTrack(controller.snapshot.value)
    }

    /**
     * Loads real feature geometry from the provincial ArcGIS REST query API.
     */
    private fun addGovernmentGeoJsonLayer(
        style: Style,
        id: String,
        layerIndex: Int,
        fill: String?,
        fillOpacity: Float,
        outline: String,
        lineWidth: Float
    ) {
        val sourceId = "$id-source"
        val simplify = if (layerIndex == 2 || layerIndex == 3) "&maxAllowableOffset=0.0001" else ""
        val url = "https://dnrmaps.gov.nl.ca/arcgis/rest/services/GeoAtlas/Land_Use/MapServer/$layerIndex/query" +
            "?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=geoJSON" + simplify
        beginLayerLoad(id)
        runCatching {
            style.addSource(GeoJsonSource(sourceId, FeatureCollection.fromFeatures(arrayOf())))
            mapDataRepository.load(id, url, forceRefresh = manualRefreshRequested, allowMobileData = allowMobileDataForRefresh) { result ->
                runOnUiThread { handleLayerResult(id, style, sourceId, result) }
            }
            if (fill != null) {
                style.addLayer(
                    org.maplibre.android.style.layers.FillLayer("$id-fill", sourceId).withProperties(
                        PropertyFactory.fillColor(fill),
                        PropertyFactory.fillOpacity(fillOpacity),
                        PropertyFactory.fillOutlineColor(outline),
                        PropertyFactory.visibility(if (landLayerVisibility[id] != false) Property.VISIBLE else Property.NONE)
                    )
                )
            }
            style.addLayer(
                LineLayer("$id-line", sourceId).withProperties(
                    PropertyFactory.lineColor(outline),
                    PropertyFactory.lineWidth(lineWidth),
                    PropertyFactory.lineOpacity(0.95f),
                    PropertyFactory.visibility(if (landLayerVisibility[id] != false) Property.VISIBLE else Property.NONE)
                )
            )
        }.onFailure {
            mapLayerInitializationErrors.add(id)
            mapLayerErrors[id] = "Layer could not be initialized"
            mapLayerLoading.remove(id)
            mapLayerCompleted.add(id)
            updateMapDataStatus()
            android.util.Log.e("HuntMateMap", "Could not add official GeoAtlas layer $layerIndex ($id)", it)
        }
    }

    private fun beginLayerLoad(id: String) {
        mapLayerLoading.add(id)
        mapLayerCompleted.remove(id)
        updateMapDataStatus()
    }

    private fun handleLayerResult(id: String, style: Style, sourceId: String, result: MapDataRepository.Result) {
        if (map.style !== style) return
        val source = style.getSourceAs<GeoJsonSource>(sourceId)
        if (source != null && result.geoJson != null) {
            runCatching { source.setGeoJson(FeatureCollection.fromJson(result.geoJson)) }
                .onSuccess {
                    mapLayerFetchedAt[id] = result.fetchedAt
                    if (result.error != null) mapLayerErrors[id] = result.error
                    else if (id !in mapLayerInitializationErrors) mapLayerErrors.remove(id)
                }
                .onFailure { mapLayerErrors[id] = "Invalid map data: ${it.message ?: "parse/render failure"}" }
        } else {
            mapLayerErrors[id] = result.error ?: "Layer source unavailable"
        }
        mapLayerLoading.remove(id)
        mapLayerCompleted.add(id)
        updateMapDataStatus()
    }

    private fun updateMapDataStatus() {
        val status = findViewById<TextView>(R.id.mapDataStatus) ?: return
        status.text = when {
            mapLayerLoading.isNotEmpty() || mapLayerCompleted.size < expectedMapLayerCount ->
                "Loading official map layers (${mapLayerCompleted.size}/$expectedMapLayerCount)…"
            mapLayerErrors.isNotEmpty() ->
                "MAP DATA WARNING: ${mapLayerErrors.size} layer(s) unavailable or may be stale. Open Layers for details."
            else -> "Official map layers loaded"
        }
        status.visibility = android.view.View.VISIBLE
    }

    private fun addHuntingZoneLayer(style: Style, id: String, layerIndex: Int, fill: String, outline: String, caribouStatus: Boolean = false) {
        val sourceId = "$id-source"
        val url = "https://services8.arcgis.com/aCyQID5qQcyrJMm2/arcgis/rest/services/WLD_BigGameManagementArea/FeatureServer/$layerIndex/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=geojson&maxAllowableOffset=0.0001"
        beginLayerLoad(id)
        runCatching {
            style.addSource(GeoJsonSource(sourceId, FeatureCollection.fromFeatures(arrayOf())))
            mapDataRepository.load(id, url, forceRefresh = manualRefreshRequested, allowMobileData = allowMobileDataForRefresh) { result ->
                runOnUiThread { handleLayerResult(id, style, sourceId, result) }
            }
            val visibility = if (huntingLayerVisibility[id] != false) Property.VISIBLE else Property.NONE
            val fillColor = if (caribouStatus) {
                org.maplibre.android.style.expressions.Expression.switchCase(
                    org.maplibre.android.style.expressions.Expression.eq(
                        org.maplibre.android.style.expressions.Expression.upcase(
                            org.maplibre.android.style.expressions.Expression.toString(
                                org.maplibre.android.style.expressions.Expression.get("status_ope")
                            )
                        ),
                        org.maplibre.android.style.expressions.Expression.literal("CLOSED")
                    ),
                    org.maplibre.android.style.expressions.Expression.literal("#D32F2F"),
                    org.maplibre.android.style.expressions.Expression.eq(
                        org.maplibre.android.style.expressions.Expression.upcase(
                            org.maplibre.android.style.expressions.Expression.toString(
                                org.maplibre.android.style.expressions.Expression.get("status_ope")
                            )
                        ),
                        org.maplibre.android.style.expressions.Expression.literal("OPEN")
                    ),
                    org.maplibre.android.style.expressions.Expression.literal(fill),
                    org.maplibre.android.style.expressions.Expression.literal("#9E9E9E")
                )
            } else org.maplibre.android.style.expressions.Expression.literal(fill)
            style.addLayer(org.maplibre.android.style.layers.FillLayer("$id-fill", sourceId).withProperties(
                PropertyFactory.fillColor(fillColor), PropertyFactory.fillOpacity(0.26f),
                PropertyFactory.fillOutlineColor(outline), PropertyFactory.visibility(visibility)
            ))
            style.addLayer(LineLayer("$id-line", sourceId).withProperties(
                PropertyFactory.lineColor(outline), PropertyFactory.lineWidth(2.0f),
                PropertyFactory.visibility(visibility)
            ))
        }.onFailure {
            mapLayerInitializationErrors.add(id)
            mapLayerErrors[id] = "Layer could not be initialized"
            mapLayerLoading.remove(id)
            mapLayerCompleted.add(id)
            updateMapDataStatus()
            android.util.Log.e("HuntMateMap", "Could not initialize WLD layer " + id, it)
        }
    }

    private fun showLayersMenu() {
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(28, 12, 28, 8)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(content) }
        fun heading(label: String) {
            content.addView(TextView(this).apply {
                text = label; textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 14, 0, 4)
            })
        }
        heading("BASEMAP")
        val radioGroup = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        val baseNames = listOf("Regular road map", "Satellite", "Topographic", "Hybrid satellite")
        baseNames.forEachIndexed { index, label ->
            radioGroup.addView(android.widget.RadioButton(this).apply {
                id = 8000 + index; text = label; isChecked = mapMode == index
            })
        }
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = checkedId - 8000
            if (selected in 0..3 && selected != mapMode) {
                mapMode = selected
                applyMapStyle()
            }
        }
        content.addView(radioGroup)
        heading("HUNTING MANAGEMENT AREAS")
        listOf(
            "Moose management areas" to "hm-moose-zones",
            "Caribou management areas (CLOSED areas highlighted)" to "hm-caribou-zones",
            "Black bear management areas" to "hm-bear-zones"
        ).forEach { (label, key) ->
            content.addView(android.widget.CheckBox(this).apply {
                text = label + layerRowSuffix(key); isChecked = huntingLayerVisibility[key] ?: true
                setOnCheckedChangeListener { _, checked ->
                    huntingLayerVisibility[key] = checked
                    saveLayerVisibility()
                    setLayerGroupVisibility(key, checked)
                }
            })
        }
        heading("LAND AND GOVERNMENT BOUNDARIES")
        val landEntries = listOf(
            "Protected Areas Plan 2020 (planning — island only, not a closure)" to "hm-protected-plan",
            "Specified Material Lands" to "hm-specified-material",
            "Labrador Inuit Lands" to "hm-inuit-lands",
            "Labrador Inuit Settlement Areas" to "hm-inuit-settlement",
            "Protected and Conserved Areas" to "hm-protected-conserved",
            "Public Water Supply Areas" to "hm-water-supply",
            "Municipal Boundaries — boundary only" to "hm-municipal-boundaries",
            "Planning Areas — boundary only" to "hm-planning-areas",
            "Wind Energy Land Reserve" to "hm-wind-reserve"
        )
        val landCheckBoxes = mutableListOf<android.widget.CheckBox>()
        content.addView(android.widget.CheckBox(this).apply {
            text = "All land boundaries on/off"
            isChecked = landEntries.all { landLayerVisibility[it.second] ?: true }
            setOnCheckedChangeListener { _, checked ->
                landEntries.forEach { (_, key) ->
                    landLayerVisibility[key] = checked
                    setLayerGroupVisibility(key, checked)
                }
                saveLayerVisibility()
                landCheckBoxes.forEach { box -> if (box.isChecked != checked) box.isChecked = checked }
            }
        })
        landEntries.forEach { (label, key) ->
            content.addView(android.widget.CheckBox(this).apply {
                text = label + layerRowSuffix(key); isChecked = landLayerVisibility[key] ?: true
                setOnCheckedChangeListener { _, checked ->
                    landLayerVisibility[key] = checked
                    saveLayerVisibility()
                    setLayerGroupVisibility(key, checked)
                }
                landCheckBoxes.add(this)
            })
        }
        heading("DATA UPDATES")
        content.addView(Button(this).apply {
            text = "Refresh official map data"
            setOnClickListener {
                if (mapDataRepository.isWifiConnected()) {
                    refreshMapData(allowMobileData = false)
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Use mobile data?")
                        .setMessage("Refreshing can download large government boundary datasets. Use Wi-Fi when possible. Allow this one refresh over mobile data?")
                        .setPositiveButton("Allow once and refresh") { _, _ -> refreshMapData(allowMobileData = true) }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        })
        heading("INFORMATION")
        content.addView(Button(this).apply {
            text = "Map legend and colours"; setOnClickListener { showMapLegend() }
        })
        content.addView(Button(this).apply {
            text = "Data sources, legal notes and update status"; setOnClickListener { showDataSources() }
        })
        AlertDialog.Builder(this).setTitle("Layers").setView(scroll).setPositiveButton("Done", null).show()
    }

    private fun refreshMapData(allowMobileData: Boolean) {
        manualRefreshRequested = true
        allowMobileDataForRefresh = allowMobileData
        applyMapStyle()
        Toast.makeText(this, "Refreshing official map data…", Toast.LENGTH_SHORT).show()
    }

    /** Fill layers that can be tapped, topmost-rendered first. Line-only layers are skipped. */
    private val tappableFillLayers = listOf(
        "hm-wind-reserve" to "Wind Energy Land Reserve",
        "hm-inuit-settlement" to "Labrador Inuit Settlement Areas",
        "hm-inuit-lands" to "Labrador Inuit Lands",
        "hm-water-supply" to "Public Water Supply Areas",
        "hm-protected-conserved" to "Canadian Protected & Conserved Areas",
        "hm-specified-material" to "Specified Material Lands",
        "hm-bear-zones" to "Black Bear Management Areas",
        "hm-caribou-zones" to "Caribou Management Areas",
        "hm-moose-zones" to "Moose Management Areas",
        "hm-protected-plan" to "Protected Areas Plan 2020"
    )

    private fun onMapTap(point: LatLng) {
        val style = map.style ?: return
        val pixel = map.projection.toScreenLocation(point)
        for ((key, title) in tappableFillLayers) {
            val layerId = "$key-fill"
            val layer = style.getLayer(layerId) ?: continue
            if (layer.visibility.value != Property.VISIBLE) continue
            val features = runCatching { map.queryRenderedFeatures(pixel, layerId) }.getOrDefault(emptyList())
            val feature = features.firstOrNull() ?: continue
            showFeatureInfo(key, title, feature)
            return
        }
    }

    private fun datasetSource(key: String): String = when {
        key.endsWith("-zones") -> "Wildlife Division, Dept. of Forestry, Agriculture and Lands (NL Open Government Licence)"
        else -> "Government of Newfoundland and Labrador (GeoAtlas)"
    }

    /** Honest per-dataset type + restriction wording. Never implies a colour alone decides legality. */
    private fun datasetNotes(key: String): Pair<String, String> = when (key) {
        "hm-moose-zones" -> "Hunting management zone" to
            "Moose management area boundary. Seasons, quotas and licence requirements come from the current NL Hunting and Trapping Guide — the boundary alone does not say whether or when hunting is allowed."
        "hm-caribou-zones" -> "Hunting management zone" to
            "Caribou management area. A CLOSED (red) zone is listed closed by the province. OPEN (green) or unknown-status (grey) does NOT by itself mean hunting is legal — check the current guide."
        "hm-bear-zones" -> "Hunting management zone" to
            "Black bear management area boundary. Check the current NL Hunting and Trapping Guide for seasons and rules."
        "hm-protected-plan" -> "Planning data — NOT a closure" to
            "Protected Areas Plan 2020: planning polygons for the island of Newfoundland only, boundaries not final. This is not a legal closure and does not determine whether hunting is allowed."
        "hm-specified-material" -> "Land boundary" to
            "Specified Material Lands (part of Labrador Inuit Lands). Access rules come from the Labrador Inuit Land Claims Agreement."
        "hm-inuit-lands" -> "Land boundary" to
            "Labrador Inuit Lands. Non-beneficiaries need permission from the Nunatsiavut Government to access."
        "hm-inuit-settlement" -> "Land boundary" to
            "Labrador Inuit Settlement Area boundary. Inuit harvesting rights apply; this line is not a general permission-to-hunt indicator."
        "hm-protected-conserved" -> "Protected / conserved area" to
            "Canadian Protected and Conserved Areas (CPCAD snapshot). Protected status alone does NOT mean hunting is prohibited — rules vary by site."
        "hm-water-supply" -> "Land-use restriction" to
            "Public water supply protection area. Zone 1 has activity prohibitions under the Water Resources Act — a land-use restriction, not a hunting closure per se."
        "hm-wind-reserve" -> "Planning designation" to
            "Wind Energy Land Reserve: a planning designation, not a closure."
        else -> "Boundary data" to "Boundary maps are for reference only."
    }

    private fun showFeatureInfo(datasetKey: String, datasetTitle: String, feature: Feature) {
        val props = feature.properties() ?: return
        fun prop(vararg names: String): String? {
            for (n in names) {
                val e = props.get(n) ?: continue
                if (e.isJsonNull) continue
                val s = runCatching { e.asString }.getOrNull()?.trim() ?: continue
                if (s.isNotEmpty() && !s.equals("null", ignoreCase = true)) return s
            }
            return null
        }
        val name = prop("mma_name", "NAME_E", "SOURCENAME", "AreaName", "name")
        val zoneNo = prop("mma", "mmu", "bma")
        val (type, notes) = datasetNotes(datasetKey)
        val source = datasetSource(datasetKey)
        val statusLine = if (datasetKey == "hm-caribou-zones") {
            when (prop("status_ope")?.uppercase()) {
                "CLOSED" -> "\nStatus: CLOSED — listed closed by the province."
                "OPEN" -> "\nStatus: OPEN — check the current guide for seasons and licences."
                else -> "\nStatus: unknown or not applicable (shown grey) — never assume open."
            }
        } else ""
        val title = listOfNotNull(name, zoneNo?.let { "Zone $it" }).joinToString(" · ").ifBlank { datasetTitle }
        val message = "$datasetTitle\nType: $type$statusLine\nSource: $source\n\n$notes\n\nBoundary maps are for reference only and are not legal documents."
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Close", null).show()
    }

    private fun setLayerGroupVisibility(key: String, visible: Boolean) {
        val visibility = if (visible) Property.VISIBLE else Property.NONE
        val style = map.style ?: return
        val ids = when (key) {
            "hm-moose-zones" -> listOf("hm-moose-zones-fill", "hm-moose-zones-line")
            "hm-caribou-zones" -> listOf("hm-caribou-zones-fill", "hm-caribou-zones-line")
            "hm-bear-zones" -> listOf("hm-bear-zones-fill", "hm-bear-zones-line")
            else -> listOf("$key-fill", "$key-line")
        }
        ids.forEach { id -> style.getLayer(id)?.setProperties(PropertyFactory.visibility(visibility)) }
    }

    private fun showDataSources() {
        val message = """
            ATTRIBUTION
            Protected areas: © Environment and Climate Change Canada (CPCAD), Open Government Licence – Canada. Provincial protected areas: Government of Newfoundland and Labrador, Dept. of Environment, Conservation and Climate Change. Hunting management areas: Wildlife Division, Dept. of Forestry, Agriculture and Lands, NL Open Government Licence. Land-use boundaries: Government of Newfoundland and Labrador (GeoAtlas).

            LEGAL DISCLAIMER
            Boundary maps are for reference only and are not legal documents. Hunting management zones, closed areas and restrictions change annually — consult the current Newfoundland and Labrador Hunting and Trapping Guide and the Wildlife Act and Regulations. Protected-area status alone does not determine whether hunting is permitted. Access to Labrador Inuit Lands requires permission from the Nunatsiavut Government for non-beneficiaries.

            DATASETS
            Newfoundland and Labrador GeoAtlas Land Use MapServer:
            https://dnrmaps.gov.nl.ca/arcgis/rest/services/GeoAtlas/Land_Use/MapServer

            Official WLD Big Game Management Areas (moose, caribou, black bear):
            https://services8.arcgis.com/aCyQID5qQcyrJMm2/arcgis/rest/services/WLD_BigGameManagementArea/FeatureServer

            Official provincial hunting boundaries and rules:
            https://www.gov.nl.ca/fal/public-education/wildlife/hunting/boundry/

            KNOWN GAPS (no published GIS — never approximated in this app)
            Reidville–Deer Lake Closed Area: the 2026–27 guide lists this area as closed to shooting. Use the official boundary description at the link above; HuntMate does not draw an invented polygon. The same applies to the ~33 named Closed Areas, municipal firearm-discharge bylaws, season-specific/emergency closures, and T'Railway Provincial Park.

            Agency publication/update dates are not supplied by these query responses.

            App retrieval times (UTC):
            ${mapLayerFetchedAt.entries.sortedBy { it.key }.joinToString("\n") { it.key + ": " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CANADA).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(it.value)) }}

            Retrieval time is not the agency publication date. Cached data may be stale; check the official source before hunting.
        """.trimIndent()
        AlertDialog.Builder(this).setTitle("Data sources & legal notes").setMessage(message)
            .setPositiveButton("Official hunting boundary page") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gov.nl.ca/fal/public-education/wildlife/hunting/boundry/")))
            }.setNegativeButton("Close", null).show()
    }

    private fun showMapLegend() {
        val legend = """
            HUNTING MANAGEMENT AREAS (Wildlife Division)
            🟡 GOLD — Moose management areas
            🟣 PURPLE — Black bear management areas
            🟢 GREEN — Caribou management area, listed OPEN
            🔴 RED — Caribou management area, listed CLOSED
            ⚫ GREY — Caribou management area, status unknown or not applicable
            A CLOSED (red) caribou zone is listed closed by the province. OPEN (green) or grey does NOT by itself mean hunting is legal — always check the current NL Hunting and Trapping Guide for seasons, licences, and closures.

            LAND AND GOVERNMENT BOUNDARIES
            🟤 BROWN — Protected Areas Plan 2020
            Planning polygons for the island of Newfoundland ONLY. Boundaries are not final. This is NOT a closure and does not determine whether hunting is allowed.

            🟠 ORANGE — Canadian Protected and Conserved Areas
            CPCAD snapshot. Protected status alone does NOT mean hunting is prohibited — rules vary by site.

            🔵 BLUE — Public Water Supply Areas
            Water-supply protection areas. Zone 1 has activity prohibitions under the Water Resources Act — a land-use restriction, not a hunting closure per se.

            🟣 PURPLE — Labrador Inuit Lands
            Non-beneficiaries need Nunatsiavut Government permission to access.

            🟢 TEAL — Labrador Inuit Settlement Areas
            Settlement-area boundaries; Inuit harvesting rights apply. Not a general permission-to-hunt indicator.

            🔘 GREY-BLUE — Specified Material Lands
            Part of Labrador Inuit Lands (specified materials).

            🟡 YELLOW lines — Municipal Boundaries
            Municipal limits only; the line does not itself mean hunting is prohibited. Municipal firearm bylaws have no published map.

            ⚪ WHITE lines — Planning Areas
            Government planning boundaries; not closures.

            🌺 PINK — Wind Energy Land Reserve
            Planning designation, not a closure.

            IMPORTANT
            Hunting management zones and legally closed hunting areas are separate official datasets and are not all shown here. The ~33 named Closed Areas in the hunting guide (including Reidville–Deer Lake, closed to shooting) have no published GIS — see Data sources & legal notes. Do not use colours alone to decide whether hunting is legal.
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("Map colours & boundary legend")
            .setMessage(legend)
            .setPositiveButton("Official hunting maps") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gov.nl.ca/fal/public-education/wildlife/hunting/boundry/")))
            }
            .setNegativeButton("Close", null)
            .show()
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

    private fun drawTrack(snapshot: TrackingSnapshot) {
        val style = map.style ?: return
        val lineSource = style.getSourceAs<GeoJsonSource>("huntmate-track-source")
        val pointSource = style.getSourceAs<GeoJsonSource>("huntmate-location-source")
        val accuracySource = style.getSourceAs<GeoJsonSource>("huntmate-accuracy-source")
        val directionSource = style.getSourceAs<GeoJsonSource>("huntmate-direction-source")
        val coordinates = snapshot.points.map { Point.fromLngLat(it.longitude, it.latitude) }
        if (coordinates.size >= 2) {
            lineSource?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(coordinates)))
        } else {
            // Never pass a null Feature to MapLibre's native GeoJSON source.
            lineSource?.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
        }
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
        updateTrackingControls(s.state)
    }

    /** One clear primary tracking action: only the buttons that make sense right now are shown. */
    private fun updateTrackingControls(state: TrackingState) {
        val start = findViewById<MaterialButton>(R.id.startButton)
        val pause = findViewById<MaterialButton>(R.id.pauseButton)
        val stop = findViewById<MaterialButton>(R.id.stopButton)
        val dot = findViewById<View>(R.id.statusDot)
        when (state) {
            TrackingState.IDLE -> {
                start.visibility = View.VISIBLE
                start.text = "Start tracking"
                start.setIconResource(R.drawable.ic_play)
                pause.visibility = View.GONE
                stop.visibility = View.GONE
                dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#9AA5A0"))
            }
            TrackingState.TRACKING -> {
                start.visibility = View.GONE
                pause.visibility = View.VISIBLE
                stop.visibility = View.VISIBLE
                dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3DDC84"))
            }
            TrackingState.PAUSED -> {
                start.visibility = View.VISIBLE
                start.text = "Resume"
                start.setIconResource(R.drawable.ic_play)
                pause.visibility = View.GONE
                stop.visibility = View.VISIBLE
                dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFB300"))
            }
        }
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
            ensurePermissionAndStart()
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
                    ensurePermissionAndStart()
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
