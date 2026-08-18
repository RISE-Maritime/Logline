# TODO

Working checklist for Logline, roughly in the order things should be picked up. P0 items block using the app for a real logging run; everything below that is completeness and polish.

Last reviewed: 2026-08-18 — a read of the whole app against upstream keelson `dev`, plus lint. Nothing in P0–P2 was found by running the app, so anything that says it was *measured* was measured earlier and is quoted from the code; the rest is a claim about the code as written and should be confirmed on a device before it is trusted.

- [ ] **Rename to Logline**: The repo folder on disk is still `KeelsonLogger`.

## P0 — before the next real run


- [x] **Android auto-backup carried the TLS client key off the phone** — fixed 2026-08-18. Both
      `res/xml/backup_rules.xml` (API 30, which is minSdk) and `res/xml/data_extraction_rules.xml`
      (API 31+, both `cloud-backup` and `device-transfer`) now exclude `filesDir/tls`,
      `filesDir/recordings` and `filesDir/osmdroid`. Verified on the Pixel 6 against the local backup
      transport rather than reasoned about: with the stub rules the phone backed up **3 648 000 bytes**
      — the whole of `filesDir`, `client_key.pem` included — and with the excludes, **7 168**, which is
      the DataStore settings and nothing else. The local backup set made during that test was wiped
      and the phone put back on the Google transport.

      Left deliberately: an exclude list rather than `allowBackup="false"`, so the settings still
      survive a phone swap. Worth considering separately — moving the credentials to
      `context.noBackupFilesDir` would make it structural rather than a rule a future manifest edit
      can undo, but it orphans credentials already imported on every phone in the fleet unless a
      migration goes with it.

## P1 — a long unattended run should not lie, and should not die quietly

- [ ] **An outage longer than the outbox is not reported anywhere.** `OutboxBuffer` counts what it
      evicted and exposes it as `evicted`, and nothing outside the class ever reads it. The ring holds
      32 768 entries — about 2.5 minutes at the measured 217 samples/s — so a twenty-minute hole in
      coverage silently loses eighteen of those minutes from the replay, while the UI still says
      "Filling the gap" and then "Replayed N samples" as if it had caught up. The recorder already
      surfaces `dropped` for exactly this reason; this is the same honesty applied to the other buffer.

- [ ] **Consider replaying from the MCAP file rather than the RAM ring.** Follows from the item above:
      the complete data *is* on disk, and the outbox exists only because re-reading the file is more
      work. A file-backed replay would turn "fills a 2.5-minute gap" into "fills the whole outage",
      which is the difference between the feature being nice and being the reason to trust the bus.
      Needs the same pacing (~400/s, every profile is `DROP`) and the same `image_compressed`
      exclusion, plus a read path that does not fight the writer for the file being appended to.

- [ ] **"Location is switched off" looks exactly like "no fix yet".** `SensorPublisher.runLocation()`
      checks the *permission* and then subscribes; if location services are off system-wide, or the
      device has no Play Services for `FusedLocationProviderClient`, the callback simply never fires and
      the four GNSS subjects sit on `Waiting` forever with nothing said. `LocationCallback` already
      offers `onLocationAvailability`, which answers this directly — surface it as a setup problem the
      way a missing TLS credential is surfaced.

- [ ] **Ask for a battery-optimisation exemption.** A foreground service and a partial wake lock are
      enough on a Pixel; several OEM battery managers still kill a multi-hour background run. Standard
      mitigation is a one-time `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt, shown once, from
      the same conditional shape as the other permissions — not on first launch, but the first time a
      run is started.

- [ ] **Start on boot, opt-in.** `RECEIVE_BOOT_COMPLETED` is already declared (the checklist reminders
      need it) and `PublisherService` is already `START_STICKY`, so a process kill resumes — but a
      reboot does not. For a phone left wired into a rig this is the difference between an unattended
      install and one somebody has to visit. Wants its own setting, defaulting off, and it must not
      start audio or the camera without the permissions already being granted.

- [ ] **The recording vanishes from the screen the moment you press Stop.** The recording card is
      rendered only while `recording.recording` is true, so the last thing a run tells you — file name,
      message count, size, that it was copied to Downloads/Logline — disappears at exactly the moment
      somebody wants to read it. A short post-run summary (duration, samples published, files written
      and where they went) would answer "did it save?" without a file manager.

- [ ] **The notification's sample count is ungrouped.** `strings.xml` has `notification_samples` as
      `%1$d samples`, so the lock screen reads `681204 samples` while every other surface in the app
      reads `681 204`. `formatCount` is right there. (Lint also flags this string as a plurals
      candidate — same fix.)

## P2 — subjects this phone could publish and does not

This is the answer to the old "other sensors" question below. Every name here exists in
`../keelson`'s `messages/subjects.yaml` on **`dev`** and is not in `PublishedSubject` today; none of
them is listed in `qos.yaml`, so all inherit `default` except `video_compressed` (`transient`). The
app publishes 34 distinct subjects across 36 registry entries today (`radio_rssi_dbm` comes from two),
and its QoS assignment was checked against `dev` — no drift.

Highest value first:

- [ ] **`raw_nmea0183`** (`keelson.TimestampedString`) — Android hands the GNSS chip's raw sentences
      straight out through `LocationManager.addNmeaListener` / `OnNmeaMessageListener`, under the
      `ACCESS_FINE_LOCATION` this app already holds. That is a phone acting as a plain NMEA source on
      the bus, which is what most of the rest of the fleet speaks, and it carries DOP, fix quality and
      satellite detail that the fused `Location` object throws away. Rate is the phone's fix rate, so
      the cost is small. Note the sentences are the *chip's*, not the fused position — which is a
      feature: it is the only unfiltered GNSS this app can offer.

- [ ] **`location_fix_satellites_used`, `location_fix_satellites_visible`** (`TimestampedInt`) and
      **`location_fix_quality`** (`keelson.LocationFixQuality`) — from `GnssStatus`, via
      `LocationManager.registerGnssStatusCallback` (API 30, which is minSdk). "Is this fix any good"
      is the question a marine log gets asked most often afterwards, and right now the file cannot
      answer it. `LocationFixQuality` is a message of its own — upstream's
      `messages/payloads/LocationFixQuality.proto` — so it needs vendoring like the rest.

- [ ] **`location_fix_accuracy_horizontal_m`, `location_fix_accuracy_vertical_m`**
      (`TimestampedFloat`) — `loc.accuracy` and `loc.verticalAccuracyMeters` are already read in
      `runLocation()` to build the covariance matrix, and are then unavailable to anything that does
      not decode a 9-element matrix. Publishing them as scalars costs two lines and makes accuracy
      plottable in the live view and in Foxglove. Ride the location collector (`rateOwner`).

- [ ] **`altitude_above_msl_m`** and **`location_fix_undulation_m`** (`TimestampedFloat`) —
      `Location.getMslAltitudeMeters()` / `hasMslAltitude()` landed in API 34, so this is guarded but
      real on any recent phone. `location_fix.altitude` is the WGS84 ellipsoidal height today, which is
      tens of metres from the altitude anybody expects; the undulation is the difference between them
      and is worth publishing precisely because it explains the discrepancy.

- [ ] **`roll_deg`, `pitch_deg`, `yaw_deg`** and **`roll_rate_degps`, `pitch_rate_degps`,
      `yaw_rate_degps`** (`TimestampedFloat`) — the first three come out of the same
      `SensorManager.getOrientation()` call that already produces `heading_magnetic_deg`; the rates are
      the gyro's three axes in degrees per second, which is a unit conversion of a stream already
      being published. Quaternions are correct and unreadable: a plot of roll in degrees is what
      answers "how much was it moving". Watch the message count — riding the rotation vector at the
      50 Hz default, three more subjects is another ~150 messages a second, so they may want their own
      rate rather than `rateOwner`.

- [ ] **`device_uptime_duration`** (`keelson.TimestampedDuration`) — `SystemClock.elapsedRealtime()`,
      once a minute, on the battery collector. Cheap, and it is the field that distinguishes "the
      phone rebooted" from "the app was restarted" when reading a file back months later.

- [ ] **`air_temperature_celsius`, `air_relative_humidity_pct`, `dew_point_celsius`**
      (`TimestampedFloat`) — `TYPE_AMBIENT_TEMPERATURE` and `TYPE_RELATIVE_HUMIDITY`; the dew point is
      derived from the two. Almost no modern phone has either sensor (a Pixel 6 has neither), but the
      registry already handles an absent sensor by publishing fewer subjects, so the cost of supporting
      the phones that do have them is one `ScalarSensorProvider` entry each.

- [ ] **`imu_temperature_celsius`** (`TimestampedFloat`) — `TYPE_TEMPERATURE` where a device exposes
      it. Same shape as above, same near-zero cost, and it is the one thing that explains IMU bias
      drift on a phone sitting in the sun.

- [ ] **`entity_health`** (`keelson.EntityHealth`) — the app *already* computes per-subject health for
      the status card (`subjectHealth()`: waiting, stalled, failed) and then keeps it to itself. This is
      the subject that puts it on the bus, so a fleet view can see a phone whose barometer stopped
      without anybody looking at the phone. Needs `messages/payloads/EntityHealth.proto` vendored, and a
      look at what upstream's other connectors put in it.

## P3 — product

- [ ] **A recordings screen.** Files are copied to `Downloads/Logline` and the app has no way to list,
      open, share or delete them — after a run the only route to the data is a file manager or `adb`.
      A list with size, duration and a share intent is a small screen and removes the most annoying
      step of every field session. (`MediaStore` gives back the URIs it wrote, so nothing new is needed
      to find them.)

- [ ] **Offline map tiles.** `TrackMap` configures osmdroid's cache under `filesDir/osmdroid` and its
      own comment says "it is that cache which lets a pre-loaded area keep rendering with no network" —
      but nothing in the app ever pre-loads one. At sea that means a blank grid. osmdroid's
      `CacheManager.downloadAreaAsync` covers a bounding box at chosen zoom levels; the UI is a
      "download this area" action plus an honest size estimate, and OSM's tile usage policy has to be
      respected in what it will let somebody grab.

- [ ] **Export and import settings.** Provisioning a second phone means retyping realm, entity, source
      ids, endpoints, per-subject switches, rates, QoS overrides and the operator identity through the
      settings screen. A JSON export and import (share sheet, or a QR code for the small case) makes a
      fleet reproducible. TLS credentials stay out of it — they are files, and the point of P0's backup
      item is that they should not travel casually.

- [ ] **Video: `video_compressed`, not WebRTC** (this replaces the open question in P4). Upstream's
      answer for WebRTC is `connectors/mediamtx`, which proxies MediaMTX's WHEP endpoint through a
      Zenoh queryable — signalling over the bus, media over WebRTC, live only, nothing recorded and
      nothing replayable, and it needs a MediaMTX instance the phone can reach. That is a viewing
      pipeline, not a bus payload, and it does not fit an app whose whole design is store-and-forward.
      The bus-native option is `video_compressed` (`foxglove.CompressedVideo`, `transient` on `dev`):
      H.264 out of `MediaCodec`, which every Android device encodes in hardware, lands in the MCAP
      alongside everything else, replays with the rest of the run, and is roughly an order of magnitude
      cheaper than the current time-lapse's ~158 MB/h. Worth prototyping before deciding — the honest
      unknowns are keyframe interval against the replay story and whether `CompressedVideo`'s framing
      wants Annex B or AVCC.

## P4 — feature plans

- [ ] **Other sensors** the phone can provide — answered above, see P2.
- [x] **Video streams** — answered above, see P3.
- [x] **Annotation view** — `log_message` / `foxglove.Log`, configurable buttons (label + severity +
      category) and a free-text note, on the **Mark event** screen. Reads natively in Foxglove's Log
      panel. Foxglove *Events* (marks on the playback bar) are a separate post-processing job and are
      not done — see the README.
- [x] **Check Lists** — done. Full peer on crowsnest's checklist protocol: subscribes to every site's
      `checklist_event`, publishes its own, heartbeats `checklist_presence`, and bootstraps from
      `checklist_state` in the router's storage. Per-item reminders are phone-local (`AlarmManager`,
      inexact by design) and never published — no checklist message carries a due time.

      Three things came out of it worth knowing. The protos existed **nowhere** in `../keelson` — only
      as generated JS in the git-ignored `sdks/js/dist/`, which crowsnest consumes via a `file:` dep;
      they are now reconstructed in `messages/payloads/` and pinned by `ChecklistWireTest` against
      golden bytes from crowsnest's own encoders. Procedure *definitions* never travelled on the bus
      at all (crowsnest seeds a hardcoded constant per browser), so `ChecklistProcedure.proto` and a
      router storage were added — which also fixes crowsnest's own `get_once` bootstrap, which had
      nothing behind the key. And the `rise/@v0/*/pubsub/checklist_*` storages already on the router
      belong to an earlier design no client speaks; they are untouched.

      **Not committed upstream.** `../keelson` has four new untracked protos and four `subjects.yaml`
      entries sitting on `feature/operational-authority`; they want their own branch and a PR before
      anyone else can build this. Blocked behind P0's `git init` on this side too.

- [x] **Calibration of equipment** — a **Rig calibration** screen that records the rig's zero point and
      each sensor's pose relative to it, publishes them as `frame_transform` (one message per sensor)
      and `configuration_json` under the **rig's** entity id, and exports the platform-geometry file
      keelson's own `connectors/platform` reads unchanged. Offsets are typed or captured from an
      averaged fix; rotations are always typed, because a phone can measure where a sensor is and not
      where it is aimed. The written description, the frame conventions and an honest account of what a
      phone fix is worth are in [docs/calibration.md](docs/calibration.md) — which also proposes the
      provenance block upstream's schema has nowhere to put.

## P5 — housekeeping

- [ ] **The README's "Known limitations" section is stale, and it is the misleading kind.** All four
      bullets are wrong now: QoS profiles exist (`keelson/Qos.kt`, verified against `dev`'s `qos.yaml`
      with no drift), liveliness tokens are declared per (entity, source) pair, `ExampleUnitTest` is
      long gone and there are 39 test classes, and `applicationId` is not a template default. A reader
      deciding whether this app is fit for a run reads that section first.

- [ ] **CLAUDE.md's QoS note is stale in the same way** — it says only the three GNSS subjects differ
      from Zenoh's defaults. It is eight subjects across four profiles today: five `elevated`
      (`location_fix`, `speed_over_ground_knots`, `course_over_ground_deg`, `heading_magnetic_deg`,
      `heading_true_north_deg`), two `transient` (`audio`, `image_compressed`), one `background`
      (`log_message`).

- [ ] **No instrumented tests at all** — `app/src/androidTest` is an empty directory tree. The 39 JVM
      tests cover the wire format, the registry, the units and the formatting well; nothing covers a
      screen. A handful of Compose tests over `MainScreen`'s status states (not publishing / publishing
      / disconnected / stalled) would catch the class of regression that currently only shows up on a
      phone.

- [ ] **Dependency bumps.** Lint reports nine outdated dependencies, five with newer versions
      available, plus an AGP update. Worth one deliberate pass rather than drifting — and zenoh-kotlin
      in particular wants checking against the `Zenoh.scout` crash and the `initLogFromEnvOr` guard
      before it moves.

- [ ] **Lint nits**, all one-liners: two `AutoboxingStateCreation` (`ChecklistScreen.kt:365`,
      `SensorMountScreen.kt:87` — `mutableIntStateOf` / `mutableLongStateOf`), `UseKtx` in
      `ChecklistReminders.kt:120` (`String.toUri`), a `RedundantLabel` in the manifest, and the
      `PluralsCandidate` folded into the notification item in P1. `UsableSpace` in `Recorder.kt` is
      *not* one of these: `getAllocatableBytes` counts clearable cache the recorder cannot actually
      have, and the floor being predicted is real free space.

- [x] **Set up a release build** — done. Signing comes from `LOGLINE_KEYSTORE*` env vars or
      `local.properties`, an unconfigured build warns and emits `app-release-unsigned.apk` rather than
      failing, `versionCode`/`versionName` live in `version.properties`, and the decision on
      `optimization { enable = false }` is made and written down in both the build file and the README:
      minification stays off, because R8 would need hand-written keeps for protobuf-lite and the Zenoh
      JNI classes and both fail at runtime rather than at build time.
