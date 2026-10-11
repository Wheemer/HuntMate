# HuntMate Hunting & Fishing

A map-first Android companion for hunting and fishing in Newfoundland and Labrador.

## What it includes

- Live GPS location, track recording, distance, accuracy and return guidance.
- Private waypoints for a truck, camp, hunting spot or other saved place.
- Road, satellite, hybrid and topographic base maps.
- Optional official Newfoundland and Labrador reference overlays: protected areas, closed areas, and moose, caribou and black-bear management areas.
- Direct links to the current official small-game guide for Island, Labrador and closed-area rules.
- A private, offline Small Game Records log with search, edit/delete and CSV export.
- A compact map-first workflow: activity selection, layers, map style and practical map tools are available without covering the map.

## Data and field use

Official overlays are references, not legal determinations. Seasons, access, closures, licences and restrictions can change. Always check the current NL Hunting and Trapping Guide before hunting.

Base-map tiles are online. Official overlay data is cached on the phone for up to seven days after it has been loaded. HuntMate does not yet offer offline base-map downloads, so it must not be relied on as an offline map application.

## Building and signed APKs

GitHub Actions runs unit tests, builds app-debug.apk, verifies its APK signature and 16 KB alignment, then publishes a direct APK release asset for pushes to main.

To let the workflow consistently update an installed copy, configure all four repository secrets for the permanent release key:

- HUNTMATE_KEYSTORE_BASE64
- HUNTMATE_KEYSTORE_PASSWORD
- HUNTMATE_KEY_ALIAS
- HUNTMATE_KEY_PASSWORD

Without that same key, Android will reject an APK update over an existing installation signed by another certificate.

Current Android configuration: compile/target SDK 37, min SDK 26, Java 17, MapLibre Native Android, Google Play Services Location and OkHttp.