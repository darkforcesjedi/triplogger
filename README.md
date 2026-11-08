# Release artifacts

Prebuilt release APKs, signed with the project maintainer's own key.
Verify with `sha256sum -c SHA256SUMS.txt`.

Download the latest:

    https://github.com/darkforcesjedi/triplogger/raw/releases/DrivingTripLogger-v1.2.apk

**Signing key change (v1.2):** this build is signed with a new release key
(certificate SHA-256 `5afaca57af22e3807ce312910b5d01c5ed1440394cf2785ff616399f8e19629c`).
If you have a v1.0/v1.1 build installed (older key), uninstall it first —
Android refuses an update signed by a different key. All future releases use
the new key.

v1.2 - built from master at 65cca79, minSdk 24 (Android 7.0+).

- Trips tab: metadata-backed history (distance, max speed, entries,
  trigger, KMZ/ODS badges), day sections, unfinished + failed/discarded
  sections; detail sheet renames trips (propagated into the KMZ
  placemark), re-runs post-processing, discards, and clears failed.
- Activity tab: rolling event log with share-diagnostics export.
- POI-level placenames via OpenStreetMap Nominatim (opt-in), start/end
  lines open in OpenStreetMap, coordinates never rounded.
- Rebuild-from-archive and bulk placename lookups confirm and show a
  progress notification.
- Stable epoch-based KMZ track ids; DST-proof, rename-safe.

v1.1 - built from master at a37facb. Live Update trip notification with
Samsung Now bar support, status chip, discard-below-entries setting,
ELM327 late-reply hardening.

v1.0 (initial release) - built from master at 95b2401. Install over the
debug build after uninstalling it (different signing key).
