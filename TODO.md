# TODO

Forward-looking list. Shipped features live in git history and the release
tags (v1.0 initial, v1.1 Live Updates, v1.2 Trips/Activity).

## Per-stop mileage tracking (delivery / rideshare use case)

Shipped core (v1.3): the stop detector and its two modes. Settings choose
Off / Mark stops on trips / End trips at stops with a minimum dwell
(default 120 s); enabling caps GPS logging at 5 s while recording and adds
1 s wheel-speed (0D) polls between OBD log cycles. Wheel rules: exact 0
km/h begins detection, 3 consecutive >0 scans abort or end (end = first
of the three); GPS rules: dt-corrected EMA (tau 5 s), open below 0.6 m/s
filtered, abort on 3 scans above 1.5 m/s, spatial census at confirm
(>=90% of cached fixes within 25 m of the centroid), start backdated to
the first raw sub-trigger sample. Parameters were tuned against 41 real
archived trips (evaluation harness lived outside the repo; the replay
guard for the key rule is a unit test). A dwell only records once motion
resumes - a trip ending during a dwell records NO stop. Confirmed stops
go to the database (stops table + stop_count/stop_time_s metrics,
annotate mode) or split the trip (split mode); no stops.csv. The detail
sheet lists recorded stops with map links.

Remaining for the delivery/rideshare reconciliation story:

- Resolve stop placenames in the existing bulk Nominatim pass (never live:
  the 1 req/s fair-use limit means lookups must stay batched at trip end);
  show them beside the stop coordinates in the detail sheet.
- Expose per-stop addresses in ODS export or a dedicated stops sheet.
- Split-mode refinements: continued_from annotation on the continuation
  trip row and a grouped view of segments belonging to one drive.
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

Replace trip.properties entirely with one SQLite database "triplogger.db"
stored in the journal root (beside pending/ archive/ failed/), so all trip
state lives in exactly one place; two stores risk drift. The Trips tab
renders from the DB, which is why folder enumeration disappears from the
fast path. Design agreed on 2026-11-14/15:

- Engine: plain SQLiteOpenHelper (no Room, no codegen). WAL mode; fall
  back to journal_mode=TRUNCATE if WAL fails on the mounted storage and
  record that in meta. Versioning via PRAGMA user_version, one
  transaction per upgrade. A meta table keeps schema bookkeeping
  (schema_migration, root_token UUID minted at DB creation so the app can
  tell same-store-new-mount from fresh store, last_backup/restore stamps).
- Identity: trips.id = epoch track id "t<start_ms>" (unique across DST
  fall-back, rename-stable, already embedded in KMZ tracks). The current
  folder name moves to trips.folder (UNIQUE); folders are never renamed
  by the migration. updated_at everywhere; deleted_at reserved (NULL =
  live) for a future trash/undo.
- Tables:
  trips(...) hot predicates as real columns: state
    CHECK(state IN ('pending','archive','failed')), start_ms, end_ms,
    entries, segments, pids (JSON), title, trigger, category_id,
    place_start/place_end, tz_id/tz_offset_min/tz_name, distance_source,
    raw_state, raw_purged_at, raw_bytes, raw_file_count, status_reason,
    converted_at, app_version, samples_backend (default 'csv'),
    created_at/updated_at/deleted_at. Indexes: start_ms DESC,
    (state,start_ms DESC), category_id.
  metrics(trip_id, kind, source, value REAL, note) PK(trip_id,kind,source)
    - every measured/estimated value (distance per method, max speed,
    duration, later stop_count/fuel/idle); new measures cost no schema.
  trip_ext(trip_id, key, value, updated_at) - overflow KV; the migrator
    parks any unrecognized property key here so future app versions can
    adopt it instead of losing it.
  categories(id, name UNIQUE NOCASE, color INT, created_at);
    trips.category_id FK ON DELETE SET NULL (deleting a category nulls
    it, matching the categories TODO).
  outputs(trip_id, kind ('kmz_day'|'ods'|...), name, bytes, created_at) -
    replaces kmz_day/ods_file columns.
  odo_readings(trip_id, ts, km) PK(trip_id,ts) - mirrors odo.csv so the
    DB can answer odometer continuity and per-stop mileage queries.
  activity(id, ts, event, trip_id FK SET NULL, message, fields JSON) +
    indexes (ts DESC), (event,ts DESC), (trip_id,ts DESC) - replaces
    activity-log.jsonl (whole-file rewrites end; events correlate to
    trips; share is a SELECT; events before the DB exists buffer in
    memory until first open).
  settings(key PK, value JSON, updated_at) - AppSettingsRepository
    becomes a typed facade; one-time DataStore import on first launch,
    DataStore is then deleted. Restoring the app = pick the journal
    folder, re-pair Bluetooth: settings travel with the DB. Stale
    device-local references self-heal (persisted SAF URIs validated with
    takePersistableUriPermission; invalid ones clear and re-prompt). The
    ONE preference that must stay device-local is journal_root_uri
    itself (chicken and egg: the DB lives inside the root).
  backups(name PK, created_at, bytes, integrity_ok, trips_at_backup,
    error) - bookkeeping mirror; truth is the files in backups/.
- Raw journals stay CSV files: the DB stores metadata, not samples
  (multi-MB-per-trip data would collapse the move/backup design and a DB
  corruption would lose every trip at once). trips.samples_backend and
  a repository API returning measurement sequences keep the door open
  for a future per-trip switch to DB-backed samples (parked schema:
  gps_samples / obd_samples narrow tables, PK(trip_id,ts,seq,pid)); old
  trips keep working either way.
- Raw-data lifecycle: raw_state
    present | purged_retention | purged_manual | missing, plus
  raw_purged_at and a raw_bytes/raw_file_count snapshot taken at
  conversion. Retention purge deletes folder files first, then updates
  the row in one transaction (failed delete leaves 'present', retried);
  reconciliation flags external deletions 'missing' (activity row) and
  flips them back to 'present' if a folder reappears. Trip metadata,
  metrics, categories and outputs outlive retention by default: the row
  IS the permanent record; tiles show "raw data removed" and sample
  reading actions (reprocess/rebuild) are enabled only when present.
- Migration (dev-scale dataset, no legacy ceremony): one scan imports
  every trip.properties (known keys to columns, distance_* to metrics
  rows, unknown keys to trip_ext) and deletes the file; folders without
  rows are always re-imported, so interrupted runs self-heal. Journals
  (gps/obd CSVs) remain the raw data.
- Storage re-pick moves the archive, it does not abandon it: if the old
  journal root still exists and contains trips, prompt with Move
  (default) / Copy / Leave behind. Move = copy trip folders first, then
  the DB last (post-copy integrity_check + row count vs folder count),
  only then delete the old copies; interrupted runs are idempotent
  because the DB lists what already landed. Copy yields two independent
  stores (root_tokens differ); Leave behind creates the fresh DB at the
  new root and rows for left trips become 'missing'. The old
  silent-abandon behavior goes away with this item.
- Crash safety moves from atomic property writes into transactions:
  trip lifecycle rows commit around the folder moves, and a startup
  reconciliation scan repairs drift both ways (folder without row is
  re-imported from journal names and CSV headers; row without folder
  gets raw_state adjusted).
- Automatic backups (journal root backups/ folder, so they ride re-pick
  and any file backup): VACUUM INTO a timestamped single-file snapshot
  (API 24-26 fallback: wal_checkpoint(TRUNCATE) + file copy), verified
  by opening the COPY read-only with PRAGMA integrity_check and a trips
  row count - a corrupt live DB can never bless a backup. Triggers:
  service start/daily when meta.db_dirty since the last good backup,
  always before the migration run and before purge sweeps. Retention:
  newest 8. Corruption at open never auto-restores: a dialog offers the
  latest verified backup ("rolling back to yesterday's state"); after
  restore the app writes a backup_restored activity row (backup name,
  taken-at, restored-at) into the restored DB so the audit trail is
  honest about its blind spot. Losing the DB never loses trip data
  (journals and outputs are untouched; rescan rebuilds rows, though
  free-form fields go back only as far as the last backup, so the
  journal root belongs in backups).
- Shipped through Stage C: engine/identity/tables (A), reader/writer
  flip with migration + reconciliation (B), settings table with the
  DataStore mirror (C1: temp_location_uri alone stays device-local, it
  points at the DB; rows win over the mirror when readable, seed on
  empty covers import and re-picks. DataStore is kept as a live
  write-through mirror rather than deleted: it is the degraded-mode
  source whenever no journal DB is open, and boot reads it), activity
  table with JSONL spool import (C2), backups + restore dialog
  (C3, VACUUM INTO >= API 30, checkpoint+copy below), storage re-pick
  Move/Copy/Leave behind (C4).
  Spec deltas: journal_mode is recorded in meta as designed; Leave
  behind starts a fresh DB at the new root (the old journal keeps its
  own DB and rows; the "rows flip to missing" variant applies only if
  the SAME DB is kept while folders stay behind); per-trip DB-backed
  samples remain the parked door.

## Merge a copied journal folder into the current database

Users import data by copying trip journal folders into the journal root
by hand. Reconciliation only creates a minimal row for such a folder
(name-derived): titles, metrics and outputs have no path back into the
database, and the user has no way to finish the import. Ship a merge
action: read the unknown folder (CSVs plus any trip.properties) and
upsert the row, metrics and outputs keyed by folder name; idempotent,
never deletes the source, richer values win on conflict. Surface it as
"this folder is not in your database - Merge" rather than leaving
restores as the only lever. Until merge exists, the restore card must
NOT offer a rollback for hand-imported folders: the offer is gated on
the backup actually containing one of the folders on disk
(DbBackup.backupCoversAny), otherwise a healthy database with copied-in
folders would be blamed forever.

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

## Batch KMZ output writes (rename + reprocess)

The shared per-day KMZ is a zip: every edit decompresses and recompresses
the WHOLE day file, so per-trip write paths cost hundreds of KB of churn
each. An active driver accrues dozens of trips per day. Two use cases,
one mechanism: read/modify/write each day file exactly once per batch.

1. Deferred renames. Renaming a converted trip today immediately reads,
   edits, and rewrites the day KMZ (TripsRepository.setTitle ->
   DailyKmz.renamePlacemark), so renaming ten trips means ten full
   day-file round trips. Hold KMZ name sync back as "dirty": setTitle
   writes the database (the truth) and flags the row (name_stale, so
   pending edits survive process death); tiles show the new title from
   the DB immediately. When converted trips have pending names, the
   trip list exposes a bottom bar prompt: "Apply modified names to
   output files?" Tapping it applies all pending renames at once;
   navigating away from the Trips tab prompts to apply first (apply now
   / keep pending). Applying groups rows by kmz_day, and per file: read
   once, rename every pending placemark (track-id keyed, so the old
   title is never needed), write once, then clear the flags; failures
   keep the flag set and say so. Renames on not-yet-converted trips
   need no flag: finalize uses the current DB title.
2. Batch reprocess. Reprocessing several trips on the same KMZ day
   currently decompresses, merges one track, and recompresses per trip
   (finalizeTrip takes one trip; rebuild-all loops it). For batch
   reprocess (multi-select, rebuild-all): render every trip's PNG + KML
   placemark fragments to temp storage first, then open the day's KMZ
   once and merge all entries in a single read-modify-write. Fewer
   rewrites, and no window where an interrupted run leaves the day KMZ
   holding only the trips merged so far.

Implementation: DailyKmz.merge already takes a trip list; renamePlacemark
takes one (trackIds, title) group per call, so decompose it into an
in-memory edit over unzipped content plus a generalized
editPlacemarks(bytes, List<(trackIds, title)>) that applies any number
of groups in one unzip/rezip pass, used by both flows.

## Reject trips by distance at post-processing

Finalization can reject journals by entry count
(discard_below_entries, before any conversion work) and retention purges
archived trips by duration; with the distance estimators computed at
finalize time, add a distance-based rejection: "Discard trips shorter
than X" (unit-aware input, 0 = keep everything). Order the checks by
cost: entries first (nothing to estimate from an empty journal), then
the winning estimate (odometer preferred) below threshold -> discard
with status_reason discarded_distance and skip conversion, mirroring the
entries path (activity row, toast). Unknown is not short: when no
estimate exists (no odometer support and too few samples to integrate
speed), keep the trip. Later, retention can grow the same knob for
purging raw journals from metrics.distance without needing the folder.

## Resume an interrupted trip after a crash or reboot

Recovery finalizes whatever a crash or reboot left pending, and the
next trigger event opens a new journal. When the recording conditions
still hold at recovery time (force logging on, or the trigger device is
connected), the interruption was incidental and the real trip is
ongoing: silently resume the pending journal instead of closing it.

- Decide resume vs finalize at the start of the recovery pass: exactly
  one recoverable pending journal AND conditions hold -> reopen the
  folder as the current journal (original start time and trip id,
  append to the last split file), re-register GPS/OBD, skip
  trip_started. Conditions gone -> today's behavior (finalize, then the
  next connect starts fresh), so a drive that ended during the dead
  window still closes cleanly.
- The trigger check at recovery is the same pattern match the ACL
  receiver uses against currently connected devices; a boot with the
  phone still paired to the head unit therefore resumes in the car.
- UI continuity: the ongoing notification and trip timer already derive
  from the journal start; seed the live distance accumulator from the
  existing entries, and odometer continuity is automatic since
  readings key on the trip id.
- Journal meta keeps the original start; KMZ day naming, track ids and
  metrics all follow it. The boot-time race with the temporary
  allowlist does not apply: resume runs wherever recovery runs today.

## Activity tab: day-section headers

The Trips tab groups rows under Today / Yesterday / date headers; Activity is
one flat newest-first list. Group events the same way (same formatter),
collapse older days by default.

## Trip detail sheet: theme polish

The ModalBottomSheet uses raw theme colors; verify/adjust for light theme and
One UI dynamic palettes: link blue on sheet surface, "-" placeholder color,
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
