# HuntMate NL — Complete App Vision and Implementation Brief

**Document purpose:** This is the product plan for the whole HuntMate NL hunting and fishing app. It records the owner's requested features and priorities so another AI or developer can implement them. It is a requirements document, not a claim that these features already work.

## 1. Product goal

Build a practical Android app for hunters and anglers, focused especially on Newfoundland and Labrador (NL), that combines hunting and fishing records, a useful map/GPS system, official regulatory and boundary information, and optional friends/stat comparisons. It must be a real, testable app—not just mock screens or placeholder map panels.

## 2. Main feature areas

### A. Hunting and fishing records

- Let users create, view, edit, and remove their own records.
- Record hunting trips: date, location/area or hunting zone, game category, species, quantity harvested, and notes.
- Track small-game and big-game harvests separately, with totals and a history by date and zone.
- Organize hunting-record fields in a familiar, license/logbook-style layout where practical. Do not imply the app replaces a required government licence, tag, or harvest return.
- Include fishing-trip/catch records as part of the overall hunting-and-fishing app: date, location, species, catch quantity, and notes. Confirm any extra mandatory fields from the owner before inventing them.
- Provide a clear summary/dashboard for total harvests/catches and past trips. Preserve records across app restarts and updates; use reliable local persistence and explain any backup/sync behavior.

### B. GPS map and navigation

- Provide a real interactive map, not a blank map area or a fake placeholder.
- Use a supported map SDK/provider (the owner previously requested a Google Maps-like experience and Google Maps integration). Confirm API-key, billing, licensing, and offline-map limitations before choosing implementation details.
- Offer map-layer/view choices such as standard/road and satellite imagery, plus any other supported useful views.
- Request Android location permission at the appropriate time, explain why it is needed, and handle denial gracefully. Display the user's current GPS position and update it as location changes.
- Do not request background location unless a real feature requires it. If background tracking is implemented, follow Android's permission and foreground-service requirements and clearly show when tracking is active.
- Handle disabled GPS, denied permissions, poor/no signal, map loading errors, and missing API keys with clear messages instead of a blank screen.
- Make the map usable outdoors on a phone, with readable controls and status.

### C. Hunting boundaries and official map layers

- Focus first on Newfoundland and Labrador, without omitting Newfoundland; expand coverage to every Canadian province/territory only as official data can be sourced and verified.
- Show relevant hunting management areas/zones, closed or no-shooting areas, and other government boundaries where authoritative data exists.
- Include the user's specifically identified Deer Lake non-shooting zone correctly. Do not display a misleading or obsolete “offline zone” as if it were a real regulatory boundary; if an offline/data-status item is needed, put it in an appropriate settings/status dropdown rather than confusing it with map boundaries.
- Prioritize official government sources and document the source, licence/terms, layer fields, geographic coverage, and last-checked/updated date for every dataset.
- Where relevant, display official big-game management areas and attributes such as open/closed status only when the source actually provides those fields.
- Never invent boundaries or regulatory status. Clearly label missing, stale, unverified, or unavailable data. Do not present the map as legal advice; users should verify current regulations with the official government source.

### D. Data freshness, caching, and offline behavior

- Cache downloaded map/boundary data so the app can remain useful when connectivity is poor, if the selected provider/data licence permits it.
- When internet access becomes available, check whether official datasets or app data have updates.
- Prefer Wi-Fi for large map/data downloads and background refreshes to avoid consuming mobile data. Provide a clear setting for mobile-data downloads if appropriate.
- Make update status visible: last successful refresh, data source/version when available, and whether cached data may be stale.
- If refresh fails but cached data exists, continue showing usable cached data **and keep the refresh error visible**, with a clear “data may be stale” warning. Showing cached data must not silently erase the failure.
- Distinguish a network failure, missing dataset, parsing error, permission problem, and empty-but-valid dataset. Do not report success unless it was actually successful.

### E. Friends and stats comparison

- Provide an optional friends/connection section.
- Allow users to compare small-game and big-game totals with friends and see a leaderboard or “who is on top” summary.
- Define privacy and consent before sharing any personal records or location. Do not expose exact hunting locations by default.
- If accounts, invitations, cloud sync, or a backend are needed, implement them securely and explain what is stored/shared. Do not fake friend data when no backend or friends are connected.
- Keep personal recordkeeping useful even if the friends feature is offline or not configured.

### F. Settings and usability

- Put configuration/status items in Settings or clear dropdowns instead of mixing them into hunting-zone layers.
- Include sensible controls for map layers, data refresh/download preferences, privacy, and app information as those features become available.
- Use clear labels, readable outdoor-friendly UI, and plain-language errors.
- Avoid dead buttons, fake toggles, empty screens, and controls that appear to work but do nothing.

## 3. Regulatory accuracy and source policy

- Official Newfoundland and Labrador government sources are the first priority for NL hunting rules, management-area boundaries, closures, seasons, and harvest limits.
- Expand to other provinces/territories only with province-specific official sources.
- Store source attribution and update metadata alongside each dataset.
- Do not hard-code a season, legal limit, open/closed status, or boundary as current without a verifiable official source and applicable season/date.
- If a source is unavailable or ambiguous, show “unverified” and link the user to the official source rather than guessing.
- Respect government open-data licences, map SDK terms, attribution requirements, and redistribution limits.

## 4. Engineering and delivery requirements

- Inspect the existing repository and preserve working features. Do not rewrite unrelated code or regress to an older version without a clear reason.
- Keep GPS/map development separable enough to test independently, then integrate it with the main app.
- Use real implementation and data, not only a design mockup.
- Build and test the actual Android APK. A successful CI compile alone does not prove that the APK installs or works on the owner's Pixel 7.
- Verify package/application ID, min/target SDK compatibility, signing/installability, manifest permissions, resources/icons across density folders, and runtime startup.
- Test fresh install and upgrade scenarios; map rendering; permission grant/deny; GPS off/on; offline/online transitions; refresh failures with cache; and record persistence.
- Keep logs/errors useful without leaking secrets or precise location unnecessarily.
- Never commit API keys, passwords, signing keys, or other secrets.
- Provide the owner a directly accessible APK artifact when possible, with build/version details and honest test results. The owner has had trouble downloading releases directly from GitHub, so explain the easiest available download path.
- Report what changed, files touched, tests run, tests not run, known limitations, and the exact APK/artifact link. Never claim an APK is ready until the build artifact exists and has been verified.

## 5. Suggested implementation order

1. **Audit first:** inspect the current code, branches, existing screens, map implementation, data sources, and build workflow. Report findings before risky changes.
2. **Stabilize app/build:** fix compile, packaging, installation, startup, and regression problems; verify an installable APK.
3. **Reliable records:** implement persistent hunting and fishing logs, small/big game totals, trip dates/zones, and summary screens.
4. **Map foundation:** get the real map rendering and permission/current-location flow working; show helpful error states.
5. **Authoritative NL layers:** integrate verified official boundaries and closures, including the Deer Lake non-shooting zone, with source and freshness metadata.
6. **Caching and updates:** implement offline cache, Wi-Fi-preferred updates, refresh status, and stale-data warnings.
7. **Friends and comparisons:** implement opt-in connections, small/big-game comparisons, and privacy safeguards.
8. **Broader geographic coverage:** add other provinces/territories from verified official datasets.
9. **Field testing:** test on a physical Android phone and fix issues found outdoors before calling the feature complete.

## 6. Acceptance checklist

A feature is not complete just because the screen exists. Verify that:
- [ ] Hunting and fishing entries can be saved, edited, reopened after app restart, and counted correctly.
- [ ] Small-game and big-game statistics are separate and consistent with the underlying records.
- [ ] The map renders actual map tiles/content and is not blank.
- [ ] Location permission is requested appropriately and current GPS position updates when allowed.
- [ ] Denied permissions, GPS disabled, offline mode, and map/API failures show clear guidance.
- [ ] Map layers switch to the intended real layer.
- [ ] Official boundary data has source attribution, coverage, and freshness metadata.
- [ ] The Deer Lake non-shooting area is represented accurately from an official source.
- [ ] Refresh failure retains the error and warns when cached data may be stale.
- [ ] Large downloads prefer Wi-Fi as configured.
- [ ] Friend stats are based on real, consented data, not placeholders.
- [ ] The APK installs and launches on the owner's Pixel 7, and the result is documented.

## 7. Instructions to the implementing AI

Treat this file as the owner's product requirements for **the entire HuntMate NL app**, not just the GPS feature. First inspect the repository and compare the current implementation with these requirements. Do not claim unimplemented items are done. Work in small, reviewable changes, protect existing functionality, run appropriate tests, and report blockers instead of inventing data or hiding failures. Ask the owner only when a decision cannot be resolved safely from the existing app or authoritative sources.


## 8. Additional owner-specific requirements to preserve

- **Coloured map overlays:** Use clear, distinguishable coloured boundary shading for hunting areas and relevant map layers. Keep the legend understandable and ensure shading does not obscure roads, GPS position, or map details.
- **GPS accuracy indicator:** Preserve the blue GPS-accuracy/accuracy-radius shading around the current location where that feature is part of the existing map design. Do not remove it during unrelated map changes; make its meaning clear and update it with the actual location accuracy.
- **Waypoints:** The project has a `feature/premium-waypoints` branch and waypoints are part of the development context. Inspect the existing implementation and determine exactly what is already built before changing it. Preserve working waypoint functionality, and document any premium/paid gating clearly if it exists. Do not invent a subscription or payment system if one has not been specified.
- **Map-layer regression protection:** Test every layer toggle and combinations of layers after changes. The selected layer must match what is displayed, and a failed/empty dataset must not be represented as a valid boundary.
- **Project scope separation:** These requirements apply to the HuntMate GPS mapping and hunting/fishing companion app in this repository. Do not change a separate “Small Game Records NL” app/repository as part of HuntMate work.
- **No destructive shortcuts:** Do not roll back or remove working map features just to fix another issue. Inspect the relevant history/branch and make a focused fix; preserve the GPS-accuracy shading and existing functionality unless a change is specifically required and justified.
