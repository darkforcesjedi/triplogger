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
