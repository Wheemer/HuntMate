# HuntMate map data support

## Current implemented land layers

The Android map currently requests these GeoAtlas Land_Use MapServer layer IDs as GeoJSON:

| Layer ID | App colour | Meaning in the app | Legal interpretation |
|---:|---|---|---|
| 0 | Red | Protected Areas Plan | Not a complete hunting-closure map |
| 4 | Orange | Canadian protected/conserved areas | Check the site's legal status and rules |
| 5 | Blue | Public water supplies | Check applicable access and activity restrictions |
| 2 | Purple | Labrador Inuit Lands | Access and rights depend on land status and applicable agreements |
| 3 | Teal | Labrador Inuit Settlement Areas | Boundary only; not permission to hunt |
| 6 | Yellow outline | Municipal boundaries | Municipal boundary only |
| 7 | White outline | Planning areas | Planning boundary only |
| 8 | Pink | Wind energy land reserve | Reserve boundary; check applicable rules |

Source service: https://dnrmaps.gov.nl.ca/arcgis/rest/services/GeoAtlas/Land_Use/MapServer

## Critical missing data before the map can be advertised as a legal hunting map

1. Official current hunting management zones / wildlife management areas for Newfoundland and Labrador.
2. Official current season-specific closed areas and legal descriptions, including amendments.
3. Official source attribution, dataset publication/update dates, effective dates, and a visible stale/unavailable warning.
4. Feature completeness validation. A single unpaged ArcGIS query can be limited by the service's record cap; do not assume the current direct GeoJSON URL returns every feature.
5. Field verification against the provincial official boundary map and regulations, including Newfoundland and Labrador coverage.

Official provincial hunting boundary reference: https://www.gov.nl.ca/fal/public-education/wildlife/hunting/boundry/

## Required implementation behaviour

- Keep hunting zones separate from land-use overlays. Never infer that a polygon means hunting is allowed or prohibited unless the official source says so.
- Make each layer independently toggleable and provide a legend with plain-language meaning, source, and last-checked date.
- A failed or incomplete layer must show an error state, not silently appear as a valid empty map.
- Preserve the map basemap, GPS position/track, waypoints, and map style selection when layers are refreshed.
- Prefer Wi-Fi for large optional downloads; allow small essential status checks over mobile data.
- Cache only with source metadata and expiry. Do not present expired legal boundaries as current without a stale-data warning.
- Do not label this map as providing legal advice or a guarantee that an area is open to hunting.
