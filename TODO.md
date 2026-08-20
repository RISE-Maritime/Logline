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

## P4 — housekeeping

- [ ] **Push to a remote.** `git init` is done — `main`, four commits — but there is nowhere to push,
  which leaves two things stalled: `.github/workflows/build.yml` has never run, and the checklist
  protos below cannot be PR'd from this side. Creating it is a decision about where this lives
  rather than a command, which is why it is not done. While doing it, note `.claude/settings.json`
  is tracked and carries this machine's absolute `JAVA_HOME` and `ANDROID_HOME`.


## PLatfrom Config 

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

## Checklist

 [ ] **The four `Checklist*.proto` are still not upstream** at `0.6.0-pre.3`. Same shape as the
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
      that repo does not.
      2026-08-19, later: **`0.6.0-pre.5` ships all four**, and they are byte-identical to this app's
      vendored copies — checked file by file against the tag, along with every other proto (17 of 17
      identical) and every subject name and QoS profile (no drift). So the app no longer publishes
      anything unratified, which is what this item was really about. **Still open because #202 itself
      is**: the tag was cut from its branch rather than from `dev`, so `dev` has neither the checklist
      subjects nor `illuminance_lux` until that PR merges. Close this when it does.)*

[ ] **Commit the checklist protos upstream.** `../keelson` still has four untracked protos and four
`subjects.yaml` entries sitting on `feature/operational-authority`
(`Checklist{Event,State,Presence,Procedure}.proto`). Until they are on a branch and merged, nobody
else can build against the checklist feature, and this app's vendored copies are the only
definition of a wire format two projects already speak — crowsnest reconstructed from its
generated JS, pinned here by `ChecklistWireTest` against golden bytes. Blocked on the remote above.




## UI iteration (2026-08-19)

The design review's ten priorities, implemented and walked on a Pixel 6. What follows is what the work
turned up rather than what it did — the *what* is in the commit and in
[docs/architecture.md](docs/architecture.md).You 

- [x] **The backlog indicator has been seen to move under load.** Verified on a Pixel 6 by putting the
      gyroscope on `Maximum`: the whole rate went from 743 to ~2295 samples/s, the gyro itself ran at
      **443 Hz against an advertised 416**, and the app reported **"Losing data — 8 samples never left
      the sensor"**, with the Live breakdown naming the two collectors that lost them — `roll rate` and
      `angular velocity`, both riding the gyro. Before this change those eight samples vanished with no
      trace anywhere in the app.
      Two things worth keeping from the run. The loss was **upstream of the recorder**: its queue sat at
      a depth of 1 with a peak of 24 — *lower* than the 53 seen at 743 samples/s, because the writer was
      untroubled — so the recorder's `dropped` counter would never have seen this, which is exactly why
      the per-subject sensor counters exist. And the healthy path holds a long way: a 6 h 36 m run at
      743 samples/s wrote **17 684 195 samples / 479.1 MB with zero drops and zero shed**.
      Done in the working tree, uncommitted.

- [ ] **A sensor can publish faster than the ceiling it advertises.** The subject rows now read
      `55.3 Hz · max 50.0` for `linear_acceleration` on a Pixel 6 — the achieved rate is above the
      maximum. Not a display bug: the ceiling is `Sensor.getMinDelay()`, which is what the sensor
      *advertises*, and Android delivers whatever the fastest client on that sensor asked for, so a
      rate above it is normal rather than impossible. Worth deciding whether the row should say so,
      cap the display, or leave it — it currently looks like an inconsistency to anyone who has not
      read `SensorCapabilities.kt`.

- [ ] **A 512 MB rotation stalls the recorder's drain, and that is the likeliest cause of the drops
      the new indicator reports.** `Recorder.drain()` calls `publish(session.path)` inline on the drain
      coroutine, and that copies up to 512 MB into Downloads — nothing is drained for its duration. The
      10 000-sample queue gives about 46 s of cover at the measured rate, which a large copy can plausibly
      exceed. Deliberately not fixed when the indicator was added, so the first thing the indicator is
      likely to report is this. The fix is the shape `publishOrphans()` already uses: its own coroutine,
      which the comment at `Recorder.kt:169-173` says was moved off the drain for exactly this reason
      after it cost 20 000 samples in half a minute.

- [ ] **The drain loop calls `_status.update` on every written sample** — roughly 217 `MutableStateFlow`
      allocations a second on the one coroutine that must not fall behind. Left alone when the queue
      instrumentation went in (which is why the new depth counters are atomics read by a UI ticker
      rather than another field on that flow), but it is pure overhead on the hot path.

- [ ] **`MAP_HEIGHT_EXPANDED` is now compensating for the navigation bar by hand.** Dropped 640 → 560dp
      so the *visible* chart is the size it always was, with the bar kept on screen deliberately (a
      control that disappears strands somebody on a full-screen map). A real fix reads the bar's height
      rather than hard-coding the difference; this is measured against one device.

- [ ] **The live view's chip row scrolls off the right edge** — "Rig calibratio" is clipped on a Pixel
      6 at six groups. Correct behaviour now that it is horizontally scrollable, and it still looks
      like a truncation bug at a glance. A fade or a narrower `chipName` for the calibration group
      would settle it.

- [ ] **Settings' Save is enabled when nothing is dirty.** `saveEnabled = saveable`, not
      `dirty && saveable` — so Save is blue on a freshly opened screen and pressing it restarts the run
      to write settings that did not change. Pre-existing, unrelated to the regroup, and visible on
      every screenshot of that screen. `CalibrationScreen` gets this right (`dirty && …`).

- [ ] **"Find a router" is filed under Advanced, which is a judgement call.** The plan's six-group
      table had it under Connection and put the scout multicast address in Advanced on its own —
      but that address is what the Scan button uses, and splitting them would leave a control in one
      group and its input in another. The whole discovery section moved instead, on the grounds that
      typing an endpoint is the normal path and scanning for one by multicast is not. Revisit if
      anybody goes looking for it under Connection.

- [ ] **Rig calibration lost its intro paragraph to the ⓘ, and gained a `BackHandler` it should have
      had all along** — it was the one form screen where a system-back discarded unsaved edits
      silently, unlike `SettingsScreen`, `AnnotationButtonsScreen` and `SubjectQosScreen`.
