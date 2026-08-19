# TODO

Outstanding work only, roughly in the order it should be picked up. Finished items are removed rather
than ticked — what was done, and why it was done that way, is in the git history and in the gotchas in
[CLAUDE.md](CLAUDE.md) and [README.md](README.md), which is where somebody would actually go looking.

Last reviewed: 2026-08-18 — a read of the whole app against upstream keelson `dev`, plus lint. Most of
what is below was found by reading the code rather than running it, so treat anything not marked as
measured as a claim to confirm on a device.

- [ ] **Rename to Logline**: The repo folder on disk is still `KeelsonLogger`.

## P1 

- [ ] The phone should be able to hold multiple calibration rigs at ones as we using multipel rigs when data logging, keelson have something called platforms If you look at crowsenst there is one "own ship slector" or platform selector I think we should be synced with that one. 

## P2 — subjects this phone could publish and does not

This is the answer to the old "other sensors" question below. Every name here exists in
`../keelson`'s `messages/subjects.yaml` on **`dev`** and is not in `PublishedSubject` today; none of
them is listed in `qos.yaml`, so all inherit `default` except `video_compressed` (`transient`). The
app publishes 34 distinct subjects across 36 registry entries today (`radio_rssi_dbm` comes from two),
and its QoS assignment was checked against `dev` — no drift.

Highest value first:

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
      fleet reproducible. TLS credentials stay out of it — they are files, and the whole point of the
      backup exclusions is that they should not travel casually.

- [ ] **Video: `video_compressed`, not WebRTC** — the answer to the old "can we use keelson webrtc for
      video?" question. Upstream's answer for WebRTC is `connectors/mediamtx`, which proxies MediaMTX's
      WHEP endpoint through a Zenoh queryable — signalling over the bus, media over WebRTC, live only,
      nothing recorded and nothing replayable, and it needs a MediaMTX instance the phone can reach.
      That is a viewing pipeline, not a bus payload, and it does not fit an app whose whole design is
      store-and-forward. The bus-native option is `video_compressed` (`foxglove.CompressedVideo`,
      `transient` on `dev`): H.264 out of `MediaCodec`, which every Android device encodes in hardware,
      lands in the MCAP alongside everything else, replays with the rest of the run, and is roughly an
      order of magnitude cheaper than the current time-lapse's ~158 MB/h. Worth prototyping before
      deciding — the honest unknowns are keyframe interval against the replay story, and whether
      `CompressedVideo`'s framing wants Annex B or AVCC.

- [ ] **Verify the boot start on a phone.** Implemented and unit-tested, but never exercised: `adb`
      cannot send `BOOT_COMPLETED` on Android 17 (`SecurityException`, uid 2000 not allowed), so the
      only test is a real restart. Switch **Start on boot** on, reboot, and watch
      `adb logcat -s BootReceiver:V SensorPublisher:V`. The specific risk is that the run is refused
      with a `ForegroundServiceStartNotAllowedException` even as a `location` service, which would put
      "boot starts are not possible at all on Android 15+" in place of the current design.

- [ ] **Samples queued at Stop are dropped rather than drained.** `Recorder.stop()` closes the queue
      and then `cancelAndJoin`s the drain scope, and cancellation beats the `for (sample in queue)`
      loop's remaining buffered elements — so up to `QUEUE_CAPACITY` samples can be lost from the tail
      of every recording. In practice the drain keeps up and the queue is nearly empty, which is why
      nothing has been noticed; it is still a hole the file does not admit to, which is the one thing
      the recorder is careful about everywhere else. Noticed while adding the post-run summary.

- [ ] **Confirm the Pixel 6 actually emits NMEA.** `raw_nmea0183` is implemented and unit-tested, but
      no sentence has been seen: it needs a publishing run, and `addNmeaListener` delivers only while
      something is requesting position. Worth checking on the first run — the subject's row rate, and
      `adb logcat -s SensorPublisher:V` — because a receiver that reports nothing looks identical to a
      collector that failed to register, and the two want different fixes. Check the timestamp branch
      while there: sentences dated to 1970 mean the callback is on the boot clock after all.

## P4 — housekeeping

- [ ] **Push to a remote.** `git init` is done — `main`, four commits — but there is nowhere to push,
      which leaves two things stalled: `.github/workflows/build.yml` has never run, and the checklist
      protos below cannot be PR'd from this side. Creating it is a decision about where this lives
      rather than a command, which is why it is not done. While doing it, note `.claude/settings.json`
      is tracked and carries this machine's absolute `JAVA_HOME` and `ANDROID_HOME`.

- [ ] **Commit the checklist protos upstream.** `../keelson` still has four untracked protos and four
      `subjects.yaml` entries sitting on `feature/operational-authority`
      (`Checklist{Event,State,Presence,Procedure}.proto`). Until they are on a branch and merged, nobody
      else can build against the checklist feature, and this app's vendored copies are the only
      definition of a wire format two projects already speak — crowsnest reconstructed from its
      generated JS, pinned here by `ChecklistWireTest` against golden bytes. Blocked on the remote above.

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
      `ChecklistReminders.kt:120` (`String.toUri`), and a `RedundantLabel` in the manifest.
      `UsableSpace` in `Recorder.kt` is
      *not* one of these: `getAllocatableBytes` counts clearable cache the recorder cannot actually
      have, and the floor being predicted is real free space.
