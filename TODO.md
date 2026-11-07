# TODO

Ideas queued for future iterations (not scoped/committed to yet).

## Trips tab

A third tab (Home / Settings / **Trips**) for reviewing what is stored, without
leaving the app:

- List archived trips from the temp-storage journal store (`archive/`) with
  start time, duration, entry count, distance, and which outputs exist
  (KMZ / ODS) for each.
- Quick actions per trip: open its KMZ (maps), open the output folder, delete
  (moves journal to `failed/` or removes outputs + journal), and a "re-run
  post-processing" for a single trip.
- Section for anything in `pending/` (interrupted sessions awaiting recovery)
  so the crash-recovery state is visible instead of implicit.

## Activity log tab

A chronological history of what the logger actually did, to make field
diagnostics possible without `adb logcat`:

- Events to capture: trip started (trigger: device name / manual / recovery),
  trip ended (entries, duration, segments), post-processing result per trip
  (ODS written, KMZ placemark id, converted | skipped | failed + reason),
  OBD connect/lost, journal split, rebuild runs (N trips re-converted).
- Persist as a small rolling log (e.g. JSONL in app-private storage, cap ~500
  events) with a simple list UI; newest first, with a "share diagnostics"
  action that attaches the recent entries for bug reports.

## Discard trips with fewer than X entries — DONE

Implemented as "Discard Trips With Fewer Than (entries)" (settings
`discardBelowEntries`): applied at trip finalization (including crash
recovery), journals below the threshold skip post-processing, are deleted
instead of archived, and surface a toast. Set it high (e.g. 100000) to keep
test runs out of the logs. An `undo path via failed/` and per-trip visibility
remain ideas for the Trips tab above.
