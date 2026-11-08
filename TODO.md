# TODO

Ideas queued for future iterations (not scoped/committed to yet).

## Trips tab — DONE (v1.2)

Four-tab nav (Home / Trips / Activity / Settings). Rows render purely from
trip.properties metadata (end_ms, entries, distance_m, max_speed_mps,
converted, kmz_day, ods_file, status_reason, title, trigger), written by the
finalizer; user titles rename the KMZ placemark in place via the stable
`t<epochms>` track id (legacy label ids stay replaceable, never duplicated).
Placenames/timezone via Nominatim (opt-in, attribution in Settings).
Discard moves journals to failed/ with a reason; "Clear failed" empties it;
truly empty journals are still deleted. Detail sheet: rename, re-run,
discard, look up, outputs list.

Remaining polish ideas: dark-theme pass on the detail sheet, day-section
headers for the Activity tab, per-stop (dwell) journals for delivery/rideshare
mileage with bulk stop resolution.

## Activity log tab — DONE (v1.2)

Rolling JSONL (cap 500, app-private): trip started/ended/converted/
recovered/discarded/failed/renamed, places resolved, OBD connected/lost/
timeout, journal split, rebuild runs, failed-cleared. Share diagnostics via
FileProvider.

## Discard trips with fewer than X entries — DONE

Implemented as "Discard Trips With Fewer Than (entries)" (settings
`discardBelowEntries`): applied at trip finalization (including crash
recovery), journals below the threshold skip post-processing, are deleted
instead of archived, and surface a toast. Set it high (e.g. 100000) to keep
test runs out of the logs. An `undo path via failed/` and per-trip visibility
remain ideas for the Trips tab above.
