# TODO

Forward-looking list. Shipped features live in git history and the release
tags (v1.0 initial, v1.1 Live Updates, v1.2 Trips/Activity).

## Per-stop mileage tracking (delivery / rideshare use case)

Trips only log Start/End today. To reconcile against Uber/DoorDash logs:

- Detect dwell periods while recording (speed < ~1 m/s for > N seconds,
  configurable) and write them to the journal as stops
  (start_ts, end_ts, lat, lon) in a `stops.csv` alongside gps/obd.
- Resolve stop placenames in the existing bulk Nominatim pass (never live:
  the 1 req/s fair-use limit means lookups must stay batched at trip end).
- Show stops in the trip detail sheet (collapsible list, each opening in
  OSM); expose per-stop addresses in ODS export or a dedicated stops sheet.
- Interacts with discard-below-entries: a 20-stop delivery crawl is a
  legitimate trip even at low poll counts.

## Multi-source distance estimation (tax-grade mileage)

The current estimator (haversine sum with a 10 m jitter gate) deletes every
segment measuring <= gate: below ~5 m/s at 2 s polling (10 mph) true travel
is dropped, so stop-and-go driving systematically under-reports deductible
miles. Compute ALL estimators that available sensors support at finalize
time, record each in the trip metadata (trip.properties now; DB column once
the metadata database lands), and display the best estimate:

- distance_odo_m (best): silently use the standard odometer PID, no user
  setup. At trip start, probe Mode 01 PID A6 once (short timeout, tolerate
  no-reply/NRC so connection setup never fails over it and cars without it
  never see it again that trip); if it answers, poll it at a low cadence
  (~every 15 s) alongside the live PIDs and keep the latest reading. Decode
  verified on the 2022 Escape (2026-11-08 odotest scan): 41A60008BAFE =
  4-byte big-endian tenths of KILOMETERS even on a miles-dash car
  (ABCD/10 km, fall back to 2-byte AB/10 for other cars; convert for
  display).
- Trip end may coincide with ignition off, so never rely on reading the
  odometer at finalization: persist every accepted reading to the journal
  as it arrives (crash-safe and reconnect-safe), and at finalization log
  odo_start_km and odo_end_km to the trip metadata, end = last successful
  reading. distance_odo_m = delta (km, SI-stored).
- Validate the source: reject constant/garbage replies (all-0x00/0xFF
  bytes), monotonic guard, and require the delta to stay within ~15% of the
  wheel-speed integration over the same window; on failure drop the odo
  distance and log an Activity entry instead of trusting it.
- Vendor-specific odometer PIDs (mode 22) are a possible later extension
  for cars lacking A6 (the odotest project and its logs stay on hand); no
  Settings UI for now.
- distance_obd_m: trapezoidal integration of PID 0D wheel speed
  (sum (v1+v2)/2 * dt); this is the dash speedometer signal, so it matches
  what the driver sees and the odometer within tire-size tolerance.
- distance_gps_speed_m: trapezoidal integration of GPS Doppler speed with
  ZUPT-style motion gating (stationary = speed < ~0.4 m/s for N consecutive
  fixes freezes the accumulator; fixes without a speed field fall back to
  the gated haversine segment for that step).
- distance_track_m: today's haversine-with-gate sum, kept as the fallback
  for GPS-only logs and as a cross-check.
- distance_src: odo | obd | gps_speed | track, chosen by that priority
  among the values present. Trips rows and the detail sheet show the best
  estimate; the detail sheet lists all recorded values with their source
  labels (audit trail for expense reports).

Implementation notes: A6 never enters the live PID list; its slow poll rides
the poll loop on its own schedule (response length validated per reply,
clone-safe); pure distance_estimators(rows) function in the finalizer with
unit tests on synthetic traces (parked jitter, 3 m/s crawl, highway,
OBD-absent) asserting the crawl no longer under-counts; store
distance_algo = 1 so re-runs can distinguish old distance_m from
multi-source output (leave legacy distance_m displayed until recomputed).

## Trip metadata database (foundation for categories + multi-select)

Replace trip.properties entirely with one SQLite database stored in the
journal root (beside pending/ success/ failed/, e.g. "triplogger.db"), so
all trip state lives in exactly one place; two stores risk drift. The Trips
tab renders from the DB, which is why folder enumeration disappears from the
fast path.

- Migration: one-time scan imports every existing trip.properties into the
  DB (keyed by the epoch track id, stable across renames), then the
  properties files are deleted; after migration nothing reads or writes
  them. Journals (gps/obd CSVs) remain the raw data as today.
- Storage mechanics: open SQLite via the underlying filesystem path of the
  selected SAF folder (primary external storage paths resolve; the app
  already treats that folder as a normal directory); WAL mode.
- Storage re-pick moves the archive, it does not abandon it: if the old
  journal root still exists and contains trips, prompt with Move (default)
  / Copy / Leave behind. Move = copy trip folders first, then the DB last
  (VACUUM INTO or post-copy integrity_check + row count vs folder count),
  only then delete the old copies; interrupted runs are idempotent because
  the DB lists what already landed. Copy yields two independent stores.
  Leave behind creates the fresh DB at the new root. The old
  silent-abandon behavior goes away with this item.
- Crash safety moves from atomic property writes into transactions:
  trip lifecycle rows commit around the folder move, and a startup
  reconciliation scan repairs drift both ways (folder without row is
  re-imported from journal names and CSV headers; row without folder gets
  raw availability cleared).
- Track raw availability per trip: when a trip row exists but its journal
  folder is gone from the temp store (user deleted/moved it), the tile shows
  a "raw data not found" indicator and reprocess/re-run are disabled; the
  converted KMZ/ODS outputs remain listed.
- DB corruption fallback: losing the DB never loses trip data (journals and
  KMZ/ODS outputs are untouched); a rescan rebuilds rows from folder names,
  CSV headers, and output listings, though free-form fields (titles,
  categories) go with it, so the journal root belongs in backups.

## KMZ speed-plot PNG names should use the trip id

Placemark images are named "<wall-clock label>.png" (e.g. "2026-11-05
21.43.50 UTC-05:00.png"); that is the same collision class as the old
wall-clock track ids (DST fold, rebuilt days). Name new/updated entries
"t<start_ms>.png" matching the track id and write the <img src> to match.
Legacy entries stay valid: merge/rename matches BOTH {t<id>, label} image
names and replaces in place, never duplicating.

## Trip categories (delivery / personal / etc.)

- Category table in the trip DB: id, unique name, color (ARGB). trips
  .category_id nullable, FK ON DELETE SET NULL.
- Detail sheet: category chip under the trip name; tap opens a picker of
  existing categories + "New category" (name field + color swatch palette).
- Trip tiles: colored dot (category color) beside the title; the name is
  also shown in the detail sheet so color is never the only signal.
- Renaming/recoloring a category reflects on every trip instantly (DB join).
- Deleting a category prompts a confirmation dialog stating how many trips
  will be uncategorized, then nulls their category_id.

## Multi-select trip tiles

Long press enters selection mode (checkboxes appear, tap toggles, back
clears). While a selection is active a toolbar replaces the normal header
with: Reprocess, Categorize, Discard (confirm first, count in message),
and Clear. Actions apply to all selected trips; pending/failed tiles are
not selectable for Reprocess. Shares the per-trip action code paths.

## Batch KMZ reprocess writes once per day

Reprocessing several trips on the same KMZ day currently decompresses,
merges one track, and recompresses the shared KMZ per trip. For batch
reprocess (multi-select, rebuild-all): render every trip's PNG + KML
placemark fragments to temp storage first, then open the day's KMZ once and
merge all entries in a single read-modify-write. Fewer rewrites, and no
window where an interrupted run leaves the day KMZ holding only the
trips merged so far.

## Activity tab: day-section headers

The Trips tab groups rows under Today / Yesterday / date headers; Activity is
one flat newest-first list. Group events the same way (same formatter),
collapse older days by default.

## Trip detail sheet: theme polish

The ModalBottomSheet uses raw theme colors; verify/adjust for light theme and
One UI dynamic palettes: link blue on sheet surface, "—" placeholder color,
stat-label contrast, badge colors, and the status_reason error line. Decide
whether the tappable place line should look like a link (underline) rather
than relying on color alone.

## Gate Elm327Client.createBond() (pairing-dialog pop-in)

The JFIND JF327 drops its bond when the car is off. The connector's
createBond() fallback then pops the system Bluetooth pairing dialog over the
driving UI unprompted. Only attempt bonding when the user explicitly picked
the adapter (Settings), not during automatic reconnects; on failure show a
toast + Activity log entry instead.

## Rotate release signing key: mostly done

The old throwaway key's passwords were briefly exposed in this branch's
public history (commit a84d83d, cached by GitHub until GC). The key has been
rotated, which makes the leaked password inert, and signing is now
secret-free at rest: keystore.properties holds only storeFile/keyAlias, and
passwords are supplied per-sign through environment variables at build time
(see BUILDING.md).

- [x] Fresh keystore generated; v1.2 republished with the new key
      (cert SHA-256 5afaca57...; README notes the uninstall-first caveat).
- [x] Burned keystore archived (*.burned, referenced by nothing).
- [ ] Optional: ask GitHub support to purge a84d83d, now that the old
      password no longer unlocks anything.

## Localization (icebox)

All UI strings are English-only; a `values-de/` (and later more) would be
welcome, but machine translation quality is poor and there is no multilingual
reviewer lined up. Park until a human-verified translation source exists.
