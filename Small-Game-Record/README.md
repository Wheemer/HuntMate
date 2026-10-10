# Small Game Record

A standalone, mobile-friendly small-game harvest log. This project is kept separate from the HuntMate app code and has its own name and screen identity.

## Features
- Record species, hunt date, harvest count, hunting zone/area, and notes
- Summary totals and species count
- Search, edit, and delete saved records
- Export records as CSV
- Works offline after the page is available; records are stored locally in the browser on the device

## Run
Open `index.html` in a modern browser. No build step is needed for the web version.

## Data and safety
Records are stored in that browser's local storage on that device; they do not automatically sync between devices. Export CSV backups regularly. This is a personal record book, not an official licence or harvest report. Check current local hunting regulations.

## Android app
A separate Android app module is now included at `small-game-record/`, with its own application ID (`com.smallgamerecord.app`), launcher identity, and offline interface. It does not require GPS or map permissions.

Build it from the repository root with `gradle :small-game-record:assembleDebug`. The APK is written to `small-game-record/build/outputs/apk/debug/small-game-record-debug.apk`. GitHub Actions publishes a `small-game-record-debug-apk` artifact after the build passes.
