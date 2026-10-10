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

## Android packaging
This is currently a standalone web app, not a compiled APK. To publish as an Android app, package this folder with a WebView-based Android project or a trusted web-app wrapper and assign its own unique Android application ID.
