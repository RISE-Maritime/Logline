# TODO

Roughly in the order things should be picked up. A finished item is **ticked and left standing**, with
the commit that did it; **deleting it is done by hand, by the person who asked for the work**, and
nothing else here removes one. What was done and why it was done that way is in that commit and in the
gotchas in [CLAUDE.md](CLAUDE.md) and [README.md](README.md), which is where somebody would actually
go looking — so a ticked item can be deleted without reading it. New findings are added to the end of
the section they belong to.

Last reviewed: 2026-08-19 — the app against keelson `0.6.0-pre.3` (see the end of P1); before that,
2026-08-18, a read of the whole app against upstream keelson `dev`, plus lint. Most of
what is below was found by reading the code rather than running it, so treat anything not marked as
measured as a claim to confirm on a device.

- [ ] **Rename to Logline**: The repo folder on disk is still `KeelsonLogger`.

## P1

Left over from the rig library, and each is a finding rather than a fix. All five are filed together
upstream as [RISE-Maritime/keelson#191](https://github.com/RISE-Maritime/keelson/issues/191) — the first
two are the ones anybody outside this repo can act on.

- [ ] **Crowsnest probes an obsolete `get_config` key shape.**
      `{realm}/@v0/{entity}/@rpc/get_config/connector_platform` predates the `{interface}/{version}`
      chunks, so nothing a current keelson connector serves answers it — `src/apps/os_config/index.jsx`
      builds it and every entry of `src/DB/platform_registry.json` declares it. The phone serves both
      shapes; `legacyPlatformConfigKey()` exists to be deleted once crowsnest moves. While in there:
      its declared `get_data_streams` and `get_queryables` queryables do not exist in keelson at all.
- [ ] **Crowsnest does not publish its platform overlay**, so the shared library is one-way today —
      the phone shares and nothing answers. The change is small and belongs in that repo; the pattern
      to copy is its own `dataflowConfigSync.js`.
- [ ] **Per-rig failure attribution.** `PublisherStatus` is keyed on the registry entry, so three rigs
      publishing `frame_transform` share one row: rig B's failure can be cleared by rig A's next tick.
      Acceptable for a 0.1 Hz loop, and worth revisiting only if a rig ever fails alone in the field.
- [ ] **An import drops what this app does not model** — MMSI, call sign, `data_streams`, `queryables`,
      camera calibrations — and a re-export therefore loses them. The screen says so, which is the
      minimum; keeping the untouched document alongside the rig and merging it back on export is the
      real fix, and it would put a `JsonElement` inside a data model whose whole point is not having one.
- [ ] **The single-rig migration is one-way.** After the first save on this build the old `calib_*`
      keys are gone, so an older APK sees no calibration. Deliberate — mirroring index 0 into them
      forever is a second source of truth that will drift — but worth knowing before a downgrade.

- [ ] **`entity_health`** (`keelson.EntityHealth`) — the app *already* computes per-subject health for
  the status card (`subjectHealth()`: waiting, stalled, failed) and then keeps it to itself. This is
  the subject that puts it on the bus, so a fleet view can see a phone whose barometer stopped
  without anybody looking at the phone. Needs `messages/payloads/EntityHealth.proto` vendored, and a
  look at what upstream's other connectors put in it.
  *(2026-08-19: upstream still forbids a connector computing and publishing this itself — unchanged in
  `0.6.0-pre.3`. What the app can actually do for fleet health is the subject-level liveliness filed at
  the end of this section, which is what lets `entity_health` tell "source up but doesn't advertise
  this" from "advertised but silent".)*



### Found reviewing keelson `0.6.0-pre.3` (2026-08-19)

The wire format did not move: `messages/` is byte-identical between `dev` and `0.6.0-pre.3`, 11 of the
15 vendored protos match the tag exactly, and every QoS profile the app implements matches the released
policy — checked programmatically, no drift. The *specification* moved by 539 lines, and §5 was
rewritten from the ground up. These are the consequences.

- [ ] **`illuminance_lux` is in no released keelson.** Not in `0.6.0-pre.3`, not in `dev`, not in
      `0.5.4` — it exists only on the unmerged one-commit branch `feat/illuminance-subject`, as a
      single line in `subjects.yaml`. The app has been publishing an unratified subject name, against
      its own rule that subject names are protocol and inventing one here produces messages nobody can
      consume. Merging that line upstream is the fix; tearing out a working sensor to satisfy
      bookkeeping is the alternative, and it is worse. Needs a decision from whoever owns `keelson`,
      and it should not go quiet for a second release running.
      *(2026-08-19: [keelson#201](https://github.com/RISE-Maritime/keelson/pull/201) ratifies it,
      open against `dev`. Nothing to change here — the app's subject name and payload type already
      match what that PR adds.)*

- [ ] **The four `Checklist*.proto` are still not upstream** at `0.6.0-pre.3`. Same shape as the
      previous item: a release has now shipped without messages this app builds against, and
      `ChecklistWireTest`'s golden bytes are the only thing pinning them. Reconstructed definitions
      living in one downstream repo is exactly the situation that produced them.
      *(2026-08-19: not lost after all — the definitions were committed in `keelson` on
      `feature/checklist-subjects` and the branch had simply never been pushed, which is why no PR
      existed and the release went without them. Rebased onto `dev` and opened as
      [keelson#202](https://github.com/RISE-Maritime/keelson/pull/202); GitHub reports it mergeable.
      The four files there are byte-identical to this app's vendored copies, checked before and after
      the rebase, so no drift crept in while they sat unmerged. Two review points carried in the PR
      body rather than hidden: the design itself is unreviewed, and `checklist_state` /
      `checklist_procedure` want the router storage backing that `../keelson-router/` now has and
      that repo does not. **Leave this open until #202 merges.**)*


- [ ] **RPC interface-level liveliness (§3.5, §5.3) is deliberately not planned.** The app answers
      crowsnest's `get_config` probe but is not an RPC server in the interface/version sense, and §3.6's
      **full-interface implementation rule** means declaring the token commits to serving all of
      `configurable/v1`. That is a commitment to make deliberately, not in passing while fixing the
      pubsub tiers. Noted here so the omission is a decision rather than an oversight.

## P3 — product


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

- [x] **The README's "Known limitations" section is stale, and it is the misleading kind.** All four
      bullets are wrong now: QoS profiles exist (`keelson/Qos.kt`, verified against `dev`'s `qos.yaml`
      with no drift), liveliness tokens are declared per (entity, source) pair, `ExampleUnitTest` is
      long gone and there are 39 test classes, and `applicationId` is not a template default. A reader
      deciding whether this app is fit for a run reads that section first.
      Done in ceb872b.

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

