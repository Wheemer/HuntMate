# HuntMate Hunting & Fishing v1.5.0

Standalone Android GPS/navigation foundation for later integration into HuntMate.

## Included

- High-accuracy Android location tracking through Fused Location Provider.
- Fine/coarse location permissions and Android location foreground service.
- Start, pause, stop, live position, accuracy, distance, speed and bearing.
- Persistent per-activity track storage with activity-scoped session state and restart-safe tracking recovery.
- Main product workspaces: Hunting and Fishing only.
- HuntMate Apps hub for the optional HuntMate Outdoor companion app.
- Separate activity tool menus so hunting data is not mixed with other outdoor activities.
- MapLibre Native Android map renderer.
- OpenStreetMap raster base map for interactive viewing, with visible attribution and an identifiable app User-Agent.
- Recorded trail overlay, current-position marker, and a live GPS accuracy footprint.
- Government hunting-data adapter contract; no invented boundaries, WMUs, seasons or regulations are included.
- GitHub Actions build workflow that installs Gradle directly, runs unit tests, and produces a debug APK artifact.
- Unit tests for activity isolation and tracking-state protection.

## Map licensing / usage

MapLibre Native Android is used as the map renderer. OpenStreetMap data is licensed under ODbL and requires attribution. This project does not bundle proprietary commercial hunting-map data.

The current development map uses `tile.openstreetmap.org` only for normal interactive map viewing. It deliberately does **not** implement tile prefetching, bulk downloads, or offline tile packs. If HuntMate needs offline maps for production, replace this source with a provider or self-hosted tile service whose license explicitly permits offline/prefetch use.

## Government hunting data

The `GovernmentHuntingLayer` interface requires agency, dataset, jurisdiction, source URL and retrieval timestamp metadata. A production adapter should ingest only an identified authoritative government dataset and preserve its source metadata. The app intentionally ships without fabricated hunting boundaries or regulations.

## Building

Android's official command-line workflow uses the Gradle wrapper (`./gradlew assembleDebug`) and SDK command-line tools. The included GitHub Actions workflow installs the Android SDK and builds `app-debug.apk` automatically on push, pull request, or manual dispatch.

A release build must be signed with the developer's own release key before distribution.

## Persistent install signing

Android will refuse to update an installed app when the next APK has a different signing certificate, often showing only “App not installed.” The Android workflow supports a persistent private signing key through these repository secrets:

- `HUNTMATE_KEYSTORE_BASE64`: the base64-encoded `.jks` file
- `HUNTMATE_KEYSTORE_PASSWORD`
- `HUNTMATE_KEY_ALIAS`
- `HUNTMATE_KEY_PASSWORD`

When all four are set, the workflow signs both debug and release APKs with that key. Keep the keystore private and back it up outside the repository. Without these secrets, local development and pull-request builds continue to use the normal ephemeral debug signer and must not be used to update an installed HuntMate app.

## Integration API

`HuntMateGpsController` is the public integration surface. It exposes activity selection, start/pause/stop controls, and a `StateFlow<TrackingSnapshot>` without requiring the host HuntMate application to use this UI.

## Optional HuntMate Outdoor companion

The Hunting & Fishing app is the primary HuntMate product. Its **HuntMate Apps** section detects whether `com.huntmate.outdoor` is installed and, when installed, opens it directly. If it is not installed, the same button can open the official download center URL supplied at build time with the Gradle property `huntmateOutdoorDownloadUrl`. No placeholder or fabricated URL is embedded.

The companion remains a separate Android package and has separate application storage, so Outdoor activity records do not become Hunting/Fishing records. Android package visibility for `com.huntmate.outdoor` is declared explicitly so the hub can detect the companion app.

## Map/GPS handoff integration status (v1.5.1 source package)

The Map Section Handoff source has been compared with this project. The main MapLibre activity, style asset, activity-mode definitions, GPS controller, foreground location service, tracking engine, and activity-scoped track store are present in this project. The map's HTTP User-Agent is now set to `HuntMate-Hunting-Fishing/1.5.1 (https://huntmate.app)`, and manual camera gestures disable GPS-follow mode; the Recenter button restores it.

Build configuration remains Android Gradle Plugin 8.7.3, Kotlin 2.0.21, compile/target SDK 35, min SDK 26, Java 17, MapLibre Native Android 13.6.1, Play Services Location 21.3.0, and OkHttp 4.12.0.

### Verification status

This is a source project ZIP, not a compiled APK. The current execution environment does not have an Android SDK or Gradle installed, so a compile/test result cannot be truthfully reported here. The repository includes a GitHub Actions Android workflow that installs Java/Android SDK, runs unit tests, builds the debug APK, and uploads it as an artifact. The base OSM style is online; this project does not claim offline OSM tiles. Government data remains incomplete until authoritative datasets are downloaded and validated.
