# HuntMate Working Map Section — Implementation Handoff v1.0

## Purpose
Use this package to implement the existing HuntMate GPS/map section into another Android project. This is source code, not an APK.

## What is already implemented
- MapLibre Android map renderer.
- OpenStreetMap raster base map using `https://tile.openstreetmap.org/{z}/{x}/{y}.png`.
- Visible OSM attribution.
- Custom identifiable User-Agent (`HuntMateGPS/1.2`).
- Live GPS position marker.
- GPS accuracy footprint/circle.
- Recenter button.
- Recorded track/trail drawn as a line.
- Start / Pause / Stop tracking controls.
- Location/accuracy/distance/point-count status UI.
- Activity/tool selectors are present in the source; the map itself is independent of the activity-specific data.

## Important: what this is NOT
- It is not an offline map system.
- It does not bulk-download OSM tiles.
- It does not contain fabricated hunting boundaries, WMUs, public-land polygons, or regulations.
- It does not contain proprietary onX code or proprietary map data.
- The map/GPS source has not been physically device-tested in this environment; the code has been statically inspected.

## Files to integrate
1. `source/MainActivity.kt` — MapLibre setup, map layers, live position, accuracy footprint, trail rendering, recentering, tracking UI.
2. `source/huntmate_osm_style.json` — OSM raster style.
3. `source/activity_main.xml` — map/tracking UI layout.
4. `source/app_build.gradle.kts` — dependency reference; merge the MapLibre/OkHttp dependencies into the target app rather than blindly replacing its build file.

## Required dependencies
Use the versions already selected by the HuntMate project unless the target app has a compatible newer version:
- `org.maplibre.gl:android-sdk:13.6.1`
- `com.squareup.okhttp3:okhttp:4.12.0`
- AndroidX AppCompat/Core/Lifecycle as required by the target project.
- Google Play Services Location for Fused Location Provider.

## Required Android permissions/service pieces
The target app needs:
- `android.permission.ACCESS_FINE_LOCATION`
- `android.permission.ACCESS_COARSE_LOCATION`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_LOCATION`
- `android.permission.INTERNET`
- notification permission on Android 13+ if using the foreground tracking notification.

For background/ongoing tracking, use a foreground location service and request the appropriate runtime permissions.

## Map initialization pattern
Before `MapLibre.getInstance(...)`, configure MapLibre's HTTP client with an identifiable User-Agent. Then load:
`asset://huntmate_osm_style.json`

The style defines an OSM raster source and visible attribution.

## OSM policy requirements
If continuing to use `tile.openstreetmap.org`, keep:
- exact HTTPS tile URL;
- visible `© OpenStreetMap contributors` attribution;
- identifiable app User-Agent;
- normal HTTP caching;
- no bulk downloading or offline-prefetch features.

If HuntMate later needs true offline maps, switch to a provider/self-hosted tile source whose license explicitly permits offline use. Do NOT implement offline downloading against the standard OSM tile server.

## Hunting data isolation
When this map is integrated into HuntMate Hunting & Fishing, hunting government layers must be separate application data/layers from the generic base map. Only authoritative government datasets should be added later. Do not invent or infer boundaries.

## Integration rules for the other AI
- Preserve the map renderer and GPS engine rather than replacing it with a fake/static map.
- Keep hunting-specific layers behind explicit hunting-only data interfaces.
- Do not merge hunting polygons into the Fishing workspace.
- Do not merge Hunting/Fishing data into the separate HuntMate Outdoor app.
- Keep the map as a reusable UI component, while activity-specific overlays are injected explicitly.
- Do not claim device testing unless the APK is actually installed and tested on hardware.
