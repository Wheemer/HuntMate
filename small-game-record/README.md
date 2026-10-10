# Small Game Record — Android app

This is a separate Android application with its own application ID (`com.smallgamerecord.app`), launcher label, and APK. It does not launch HuntMate or require GPS, maps, or location permissions.

## Features
- Record small-game species, date hunted, harvest count, hunting zone/area, and notes
- Summary totals, record count, and species count
- Search, edit, and delete records
- Export a CSV backup using Android's system file picker
- Offline record entry; data stays in this app's local WebView storage

## Build
From the repository root, run:

```sh
gradle :small-game-record:assembleDebug
```

APK output:

`small-game-record/build/outputs/apk/debug/small-game-record-debug.apk`

## Test on Android
Install the debug APK on a test device. Add a sample record, close and reopen the app to confirm it persists, edit and delete a record, search the record list, and export a CSV to a location you choose. The app does not request location permission.

This is a personal record book, not an official licence or harvest report. Verify current hunting regulations separately.
