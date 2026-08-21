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

- [ ] **No ChunkIndex is written, so a reader scans instead of seeking.** The recording is chunked and
      compressed now, but the summary carries no `ChunkIndex` records — legal MCAP, and no worse than the
      unchunked file that came before, but it means Foxglove reads the whole data section to open a
      file. Keelson's own `keelson2mcap.py` writes full indexes. Adding them is bookkeeping the writer
      already has most of: each chunk's byte offset, its message-time range and a per-channel offset map.
      Worth it once files are routinely hundreds of megabytes.

- [ ] **`audio` and `video_compressed` cannot be thinned, so they have no publish rate.** Dropped H.264
  frames do not decode, and `audio`'s rate is a *chunk length* — dropping a chunk puts a hole in the
  sound rather than thinning the stream. Both are exempt from the decimator, so on those two the wire
  always carries the full recorded rate however the publish rate is set. Nothing on their subject
  pages says so yet. The honest fix for video would be a second, lower-fps encode for the wire,
  which is a real piece of work rather than a setting.


## UI iteration (2026-08-19)

The design review's ten priorities, implemented and walked on a Pixel 6. What follows is what the work
turned up rather than what it did — the *what* is in the commit and in
[docs/architecture.md](docs/architecture.md).

- [x] The user need to be able to enter its own maptiler key in settings
      Done in b4fd8ba — Setup › Settings › Recording › Satellite imagery. Blank falls back to
      Esri, so a keyless phone still draws; the layer menu grew a gear pointing at this field
      in e8fa7e2. Stored per phone, never in the repo or the APK, and carried by a settings
      profile so a fleet provisions from one QR.

- [x] In the in the chart layer section allow used to also to past track, hading line and Vector line
      Done in the chart-marks pass. Four ticks under the base layers: Sea marks, Track,
      Heading line and Course vector — named that rather than "vector line" because it sits
      directly under "Heading line" and the question there is which of the two is which.

- [x] **Can you change the satelite layer to be MapTiler.** Done in the chart-sources pass.
      Esri is kept as the keyless fallback — satellite is the default layer, so an install with
      no key must still draw something rather than opening on a blank grid. The key is a
      per-phone setting, never in the repo or the APK.

- [ ] **The drain loop calls `_status.update` on every written sample** — roughly 217 `MutableStateFlow`
      allocations a second on the one coroutine that must not fall behind. Left alone when the queue
      instrumentation went in (which is why the new depth counters are atomics read by a UI ticker
      rather than another field on that flow), but it is pure overhead on the hot path.


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

## Live view, second pass (2026-08-20)

The design review's eight points on the Live screen, implemented and walked on a Pixel 6 on map and
satellite, inline and full-screen. Findings:

- [ ] **The Session screen's Position row wraps its rate line onto two lines**, and has done since
      before the position format changed — verified by building both formats and comparing the crops,
      so this is not a regression from the middot. The row is the only one in the list whose value is
      wide enough to squeeze `0.1 Hz · set 1.0 · sensor ~1.0` into a wrap. Readable, and it makes that
      one row a line taller than its neighbours. A shorter live value for the fix (the accuracy rather
      than the coordinates?) or letting the subtitle ellipsize would settle it.

- [ ] **`No fix` now shows in red beside a perfectly good position, on a desk indoors.** This is the
      documented and intended behaviour — the fused provider derives a position from wifi and cell with
      the GNSS engine solving nothing, and `location_fix_quality` says so honestly — but the vitals row
      makes it far more prominent than the old run-on string did. Worth watching on an actual trial: if
      a phone under a coachroof spends the day showing red, the colour is crying wolf and `FIX_NO`
      should drop to amber with red kept for a fix that has genuinely stopped arriving.

- [ ] **The four map icons are hand-declared `ImageVector`s and nothing checks them.** `ui/MapIcons.kt`
      is the first drawn icon set in the app; a path typo produces a wrong-looking glyph rather than a
      build failure, and there is no screenshot test to catch it. Checked by eye at 24dp on a Pixel 6
      in both themes. If more get added, that is the point to consider a comparison test.

- [ ] **`MAP_HEIGHT` is still a hand-tuned 400dp.** Now that the chart sits in a surface with the fix
      line attached under it, the pair take a fixed 400dp plus about 40 — a little over half a Pixel 6's
      content height, and proportionally more on a small phone. Worth deriving from the available height
      rather than pinning, the same argument that removed the second hand-tuned constant from the
      full-screen branch.

- [ ] **The health chips no longer say anything when a run is healthy**, by design — colour is spent
      only on the abnormal now. Flagged because it is the one change in this pass that removes a signal
      rather than quietening it: if a glance at a good run comes to feel like the row is dead, the fix
      is one quiet green dot on the row as a whole, not one per chip.

## Per-subject publish rates (2026-08-20)

Derived subjects gained a publish rate of their own, capped at the one they ride. Findings:

- [ ] **Saving a subject's page always writes a QoS override, even when nothing about the QoS was
      touched.** `onSave` does `qosOverrides + (subject to qos)` unconditionally, so changing only a
      rate leaves the subject reading "Overridden for this phone" against values identical to
      `qos.yaml`. Noticed while testing the rate control — it took a deliberate "Reset to qos.yaml
      policy" to undo something the user never asked for. Pre-existing; the fix is to write the entry
      only when `qos != policyQosForSubject(subject)`.

- [ ] **The row's `max` and the page's cap are two different ceilings and the row shows only one.**
      A derived subject's row still reads its owner's *hardware* maximum, while the page may be
      capping it far lower. The row is honest about what goes out (`set` is the clamped value) and the
      three-number rule says not to add a fourth, so this was left alone deliberately — but somebody
      reading `max 442` on a heading subject capped at 1 Hz has to open the page to find that out.

- [ ] **`ratesCanDiffer` still resolves through the owner**, which is now the only rate function that
      does so for a reason unrelated to recording. It decides whether the *record* control appears, so
      it is correct — but the name reads as a statement about the pair of rates, which for a derived
      subject is no longer what it answers.

- [ ] **Nothing tests the publish path end to end at differing rates within one group.** The
      decimator, the interval map and the resolution are each covered, and the combination was checked
      by hand on a Pixel 6 (`course_over_ground_deg` at 0.2 Hz against the fix at 1.0). An instrumented
      test would need a running publisher and a real sensor, which is why it was not written.

## Chart sources and zoom (2026-08-21)

- [ ] **Esri's imagery over the Swedish coast runs out well before its declared zoom 19.** Verified by
      forcing the chart to 19 at Onsala: every tile came back as Esri's grey "Map data not yet
      available" placeholder. The 19 in its `OnlineTileSourceBase` is a global maximum, not a promise
      about a location, and the app has no way to know where coverage actually ends. This is the
      limitation a MapTiler key lifts, so it may not be worth solving — but a keyless phone zooming in
      hits a grey field with no explanation, and a line saying "no imagery at this zoom" would be
      kinder than the silence it gets.

- [x] **The MapTiler path has never met the real service.** There is no key on this phone, so the URL
      shape, the 512 px tile size and the `.jpg` ending are from their documentation rather than from a
      tile that arrived. `maxZoomFor()` also gives it two levels of upscaling on the assumption that it
      *fails* past zoom 20 — the upscaling mechanism is verified, but with OpenStreetMap, not MapTiler.
      If MapTiler serves a placeholder the way Esri does, it needs the same cap. Settled with a real
      key: 512 px and both URL shapes confirmed, and it turns out MapTiler over-zooms *server-side* to
      22 — z19-z22 all return real content — so it declares 22 itself and the client-side upscaling
      assumption was wrong in the opposite direction. Done in the layer-menu pass.

- [ ] **`SettingsProfileTest` does not catch a new `Settings` field on its own.** CLAUDE.md says a
      field added later "fails the test until somebody decides which side it belongs on", and that is
      only true if the fixture is updated as well — the assertions are a hand-written list, not a
      reflective one. `mapTilerKey` was added to both by hand. A reflective check over
      `Settings::class.memberProperties` would make the claim true.

- [x] **Two MapTiler styles were tried and left out.** `streets-v2` and `outdoor-v2` both work with the
      key, and both render close enough to the existing OpenStreetMap layer that carrying all three
      would be three ways of saying the same thing. One line each in `sourceFor` if that judgement is
      wrong — worth revisiting if anybody wants outdoor's trail rendering ashore. Both added; the
      judgement was wrong, and seeing them on the phone is what showed it — Outdoor draws
      long-distance trail routes OSM's own rendering does not.

- [ ] **The layer menu says a layer needs a key, but not that a key has stopped working.** An expired
      or over-quota MapTiler key fails per tile, so the chart simply goes blank with the layer still
      ticked — the gear only appears when the field is *empty*. A tile-fetch failure is not currently
      surfaced anywhere.
