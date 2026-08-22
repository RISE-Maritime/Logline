# TODO

Roughly in the order things should be picked up. A finished item is **ticked and left standing**, with
the commit that did it; **deleting it is done by hand, by the person who asked for the work**. 
What was done and why it was done that way is in that commit and in the
gotchas in [CLAUDE.md](CLAUDE.md) and [README.md](README.md), which is where somebody would actually
go looking — so a ticked item can be deleted without reading it. New findings are added to the end of
the section they belong to.


## P4 — housekeeping

- [x] **Push to a remote.** `git init` is done — `main`, four commits — but there is nowhere to push,
  which leaves two things stalled: `.github/workflows/build.yml` has never run, and the checklist
  protos below cannot be PR'd from this side. Creating it is a decision about where this lives
  rather than a command, which is why it is not done. While doing it, note `.claude/settings.json`
  is tracked and carries this machine's absolute `JAVA_HOME` and `ANDROID_HOME`.
  Done: `origin` is `RISE-Maritime/Logline`, **private**, first pushed 2026-08-19 and current as of
  `e97233f`. The settings file is therefore not public, though it is still tracked and still carries
  this machine's paths, which stays worth revisiting the moment a second machine builds this.
- [x] **CI has never been green.** It does run — the item above assumed otherwise — and it failed all
  three times it ran, always at the same step: `sdkmanager "platforms;android-37"` answers
  `Warning: Failed to find package` and exits 1. The package is **`android-37.0`**; from API 36
  onwards Android publishes minor releases, so platform paths carry a minor version and the bare
  `android-37` does not exist. Fixed here, but **not yet observed green** — the run after this commit
  is the first that could be, and it may well surface a second failure behind the first, since no
  step past the SDK install has ever executed on a runner.
  Done in `542c988`, and observed: run 32592816237 passed in **10m20s**, no second failure behind the
  first. `testDebugUnitTest lintDebug assembleDebug` have now all executed on a runner, so the claim
  in CLAUDE.md that CI "runs as of the initial commit; nothing has exercised it yet" is retired.
- [ ] **CI's actions are deprecated and will stop working.** The green run warns twice: `checkout@v4`,
  `setup-java@v4`, `android-actions/setup-android@v3` and `gradle/actions/setup-gradle@v4` all target
  Node 20 and are being forced onto Node 24, and `setup-java` v4 is end-of-life in favour of v5.
  Deliberately not bundled with the fix above — a known-green baseline was worth more at that moment
  than pre-empting a breakage that has not happened, and bumping four actions at once is exactly how
  a green build goes red for reasons unrelated to the code.


## PLatfrom Config 

Left over from the platform library, and each is a finding rather than a fix. All five are filed together
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
- [ ] **Per-platform failure attribution.** `PublisherStatus` is keyed on the registry entry, so three platforms
      publishing `frame_transform` share one row: platform B's failure can be cleared by platform A's next tick.
      Acceptable for a 0.1 Hz loop, and worth revisiting only if a platform ever fails alone in the field.
- [ ] **An import drops what this app does not model** — MMSI, call sign, `data_streams`, `queryables`,
      camera calibrations — and a re-export therefore loses them. The screen says so, which is the
      minimum; keeping the untouched document alongside the platform and merging it back on export is the
      real fix, and it would put a `JsonElement` inside a data model whose whole point is not having one.
- [ ] **The single-platform migration is one-way.** After the first save on this build the old `calib_*`
      keys are gone, so an older APK sees no calibration. Deliberate — mirroring index 0 into them
      forever is a second source of truth that will drift — but worth knowing before a downgrade.
- [ ] **The `rig_*` → `platform_*` key migration is one-way too**, and now there are two legacy schemes
      stacked behind the current one. After the first save on this build the `rig_*` keys are gone, so an
      older APK sees no platform library at all — verified on a Pixel 6 by reading the preferences file
      before and after: the six keys were renamed, every value carried across (`platform_count` stayed
      `0`, `platform_registry_version` stayed `2`), and nothing else in the file changed. Worth knowing
      before a downgrade, and worth deleting the fallback once no phone in the fleet predates it.
- [x] **The platform photograph is unverified on a device.** Written, unit-tested and built, but the
      phone dropped off wireless adb before any of it could be exercised: nothing has yet picked a real
      image, so the EXIF rotation, the scaling figures (150-350 kB at 1280px/80 is reasoned, not
      measured here), the thumbnail at 48dp and the rename-moves-the-file path have all been checked
      only in tests. Do this before trusting the backup arithmetic in `data_extraction_rules.xml`.
      Done on a Pixel 6 — and it found one: `scaleJpeg` passes an already-small image straight
      through, so a 1080x2400 PNG screenshot was stored verbatim in a file called `.jpg`, which would
      have put a lossless multi-megabyte file where the backup arithmetic assumes a compressed one.
      `importPlatformPhoto` now decodes, caps the long edge and re-encodes as JPEG itself.
- [x] **A platform photo can only be picked, not taken.** `PickVisualMedia` opens the gallery, which is
      the literal ask and needs no permission — but the moment somebody wants a picture of a platform is
      usually while standing next to it. Taking one needs `TakePicture`, a `FileProvider` and the
      `CAMERA` permission, and it has to be thought about beside the time-lapse: a Pixel 6 kills the
      camera HAL when two use cases bind at once, so this may have to refuse while a run is recording.
      Done — and it does refuse while a run holds the camera. Verified on a Pixel 6: the permission is
      asked at the tap, the camera app opens, and the shot comes back through `importPlatformPhoto` as
      a 964x1280 JPEG with the temp file cleaned up.
- [ ] **A measured sensor rotation is only as good as the compass, and indoors that is ±90°.** The
      screen says so in red past 15°, which is the honest thing to do and not a solution. Worth knowing
      before reading a yaw off a phone next to a radar: pitch and roll are unaffected, being gravity's.
      A sight against a known bearing is the check nobody has automated.
- [ ] **A measured rotation assumes the platform is level and cannot tell when it is not.** Heel and
      trim at the moment of measurement go straight into pitch and roll, and afterwards a heeled boat
      and a tilted sensor are the same reading. The screen instructs; nothing verifies. A phone that is
      already publishing `roll_deg`/`pitch_deg` could in principle warn when the platform is visibly
      moving, which is a real improvement and a separate change.
- [ ] **Mirrored EXIF orientations (2, 4, 5, 7) are not corrected**, only the three rotations. They come
      from a flipped front camera and a wrong flip is worse than none — it puts the port side to
      starboard in a picture somebody is placing sensors from — but a photo that arrives mirrored will
      stay mirrored with nothing on screen saying so.
- [ ] **The platform screens' route values changed with the rename** — `calibration/rig/{entityId}` is now
      `calibration/platform/{entityId}`. Harmless today, because routes are not persisted and the two
      Zenoh sessions are scoped on the `calibration` prefix, which did not move. Filed because a prefix
      match that goes wrong fails *silently*: the screen still opens and simply never finds anything on
      the bus, so any future route rename has to be re-checked on a device rather than reasoned about.

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

- [x] **Settings' Save is enabled when nothing is dirty.** `saveEnabled = saveable`, not
      `dirty && saveable` — so Save is blue on a freshly opened screen and pressing it restarts the run
      to write settings that did not change. Pre-existing, unrelated to the regroup, and visible on
      every screenshot of that screen. `CalibrationScreen` gets this right (`dirty && …`).
      Done in the commit that follows this note.

- [ ] **"Find a router" is filed under Advanced, which is a judgement call.** The plan's six-group
      table had it under Connection and put the scout multicast address in Advanced on its own —
      but that address is what the Scan button uses, and splitting them would leave a control in one
      group and its input in another. The whole discovery section moved instead, on the grounds that
      typing an endpoint is the normal path and scanning for one by multicast is not. Revisit if
      anybody goes looking for it under Connection.

- [ ] **Platform calibration lost its intro paragraph to the ⓘ, and gained a `BackHandler` it should have
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

- [ ] **`SettingsProfileTest` does not catch a new `Settings` field on its own.** CLAUDE.md says a
      field added later "fails the test until somebody decides which side it belongs on", and that is
      only true if the fixture is updated as well — the assertions are a hand-written list, not a
      reflective one. `mapTilerKey` was added to both by hand. A reflective check over
      `Settings::class.memberProperties` would make the claim true.


- [ ] **The layer menu says a layer needs a key, but not that a key has stopped working.** An expired
      or over-quota MapTiler key fails per tile, so the chart simply goes blank with the layer still
      ticked — the gear only appears when the field is *empty*. A tile-fetch failure is not currently
      surfaced anywhere.

## Recording details (2026-08-21)

- [x] **The track scan is unmeasured on a large recording.** The plan said to time it on the biggest
      file on the phone and put the figure in the commit; the phone locked behind its fingerprint
      before that could be done. What *is* measured: a 2.3 MB / 96 054-message recording had its track
      drawn within a second of the tap, navigation and layout included. The worst case on this phone is
      a **537 MB** file — three of them are sitting in Downloads — and that is a full zstd decompress of
      the data section. If it turns out to take long enough that a spinner is the wrong affordance, the
      fix is a progress fraction from the bytes consumed, which the reader already knows.
      Done in 689b637.

- [x] **A partially-read track is indistinguishable from a complete one.** `McapTrack.read` catches a
      mid-file failure and returns what it has, which is the right call — the points it got are real —
      but the screen then says "N positions" as though that were the whole run. It should say when the
      read stopped early. Done in 689b637.

- [ ] **The Files list only shows recordings written by the current install.** MediaStore ties a file
      to the package that created it, so 101 of the 216 recordings in `Downloads/Logline` on the dev
      phone are invisible to the app — every one written before a reinstall. The empty state already
      says this in words, but the *list* gives no hint that half the folder is missing, and it is why
      the largest file the detail view could be tested against was 479 MB rather than 537 MB.

- [ ] **The `OVERSAMPLE` cap silently truncates a very long track.** Past 20 000 fixes — about five
      hours at 1 Hz — the reader stops and downsamples what it has, so a twelve-hour passage shows its
      first five hours and says nothing about the rest. Bounded work is right; saying so is missing.

## Bulk delete removed more than it showed (2026-08-21)

- [ ] **`e95ed93`'s delete-all removed 53 files where its dialog said 50, and the three extras are not
      accounted for.** The dialog read "Delete 50 incomplete recordings?" and the code deletes exactly
      the `shown` list, which the Incomplete filter had narrowed to 50. What actually went was those 50
      *plus* the three newest files on the phone: `logline-settings-2026-08-21T142616.json` (the profile
      exported minutes earlier, and verified on screen as excluded — the list read "50 of 120", not 51),
      `logline-2026-08-21T104536.mcap` and `logline-2026-08-21T102926.mcap`, both **complete**
      recordings with summaries and message counts. Every older complete recording survived, including
      the 502 MB one, so it is not "deleted everything newer than X" either.
      Nothing was recoverable: MediaStore did not trash them, and the logcat buffer had rotated by the
      time it was checked.
      Worth knowing when reproducing: the deletion runs on a coroutine and takes longer than it looks —
      a file count taken ~2 s after confirming still showed all 221 files, and the sweep completed
      minutes later. Any check that a delete did nothing has to wait for the coroutine, not the dialog.
      Until this is explained the button should be treated as untrustworthy.

      **Investigated 2026-08-21. The button is not the cause.** Four things were established on the
      device, none of them by reading the code:

      * *The delete-all submits exactly the shown set.* A build that logged instead of deleting was
        given a corpus of two throwaway incomplete recordings, one freshly exported settings profile
        and sixty-seven complete recordings. It logged `asked for 2 files` and named exactly those two,
        with the right kind and completeness. The export and the complete recordings were never
        submitted.
      * *The tap in the incident hit Keep, not Delete.* Tapping the identical coordinate (x=580) on the
        same dialog dismissed it and logged nothing at all. The confirm sits at x≈797.
      * *A real delete is visible in 0.25 s.* Measured by polling `ls` on the device across a confirm:
        171 files → 169 within a quarter of a second. So the file count taken ~2 s after the incident
        tap, which showed all 221 files present, is real evidence that no bulk delete had run — not a
        race, as was first assumed.
      * *Nothing deletes spontaneously.* Four minutes idle on the same screen, with logcat capturing:
        no change.

      So the app's only MediaStore delete path — `deleteSavedRecording`, reached from the row button or
      the sweep — did not run, and there is no other. What removed those 53 files in the window between
      the count of 221 and the next query is still unknown, and the logcat from that window had rotated
      before any of this was looked at. Worth noting the phone was left unlocked with the
      `Delete all 50 · 542 MB` button on screen for several minutes.

- [x] **Verify the bulk delete end-to-end.** Never done for `e95ed93`, which stopped at the confirm
      dialog rather than destroy 542 MB of the user's recordings. Done on throwaway data: two
      deliberately-interrupted runs were created, the sweep removed exactly those two, and the settings
      profile and the newest complete recording both survived. Done in the commit that follows this
      note.

- [x] **The recordings list is read once and never refreshed while you are looking at it.**
      `produceState(null, recordingsRevision)` re-runs when the screen is composed or after a delete,
      and nothing else. Leaving the tab and coming back does re-read, because the route's composable is
      disposed and recreated — but a run that finishes while the Files tab is on screen does not appear
      until something else happens. Worth bumping the revision when a run stops, or on resume.
      Found while answering "why do I not see the latest recording?", where the actual cause turned out
      to be different (an interrupted run only reaches Downloads at the *next* app start, through
      `publishOrphans`), but the gap is real and would produce the same complaint.
      Done in the commit that follows this note: the revision is bumped when `recording.recording` goes
      false. Note the gap was narrower than feared — leaving the tab and returning *does* re-read,
      verified by deleting a file behind the app's back and watching the count go 7 to 6 — so only a run
      ended from the notification while the tab is on screen was ever affected.

## Live camera over WHEP — blocked in the Zenoh binding (2026-08-22)

Built on branch `whep-live-camera`, not merged: the app **crashes** the moment the signalling query
completes, and that is not something app code can fix.

- [ ] **A Zenoh query that receives a reply from a remote queryable SIGABRTs the app.**
      `JNI DETECTED ERROR IN APPLICATION: ... ClassNotFoundException: Didn't find class
      "io.zenoh.jni.pubsub.EntityGlobalId"`, in `finalize_pending_query`. It is the **same bug as
      `Zenoh.scout`** — the binding builds a class in native code with `FindClass`, on one of Zenoh's
      own threads, where JNI resolves against the system class loader and cannot see app classes — and
      `keelson/Scout.kt` exists because of it. Reproduced twice, zenoh-kotlin 1.10.0.
      Opening the Platforms screen (`livelinessGet`) does *not* crash, so it is the reply path specifically;
      whether `KeelsonSession.query()` is also affected when a storage actually answers is **untested**
      and matters, because the checklist bootstrap uses it.
      No workaround from this side. Either the binding is fixed upstream, or the handshake goes over
      something other than a Zenoh query.

- [ ] **`ghcr.io/rise-maritime/keelson:latest` (0.5.3) cannot serve a WHEP handshake at all.**
      Two independent faults, both found by running it:
      `WHEPResponse(res.text)` raises `TypeError: No positional arguments allowed` — protobuf requires
      keyword arguments, and the repo source has `WHEPResponse(sdp=res.text)`, so it is fixed upstream
      and unreleased. And it declares the **legacy** key
      `rise/@v0/{entity}/@rpc/whep_signal/{responder}` with no interface or version chunk, where the
      repo source and crowsnest both use `.../@rpc/whep_proxy/v1/whep_signal/{responder}`.
      So crowsnest's camera feature cannot be working against `latest` either. Worth telling RISE.

- [ ] **MediaMTX silently drops AAC for WebRTC.** `skipping track 2 (MPEG-4 Audio)` — a WHEP viewer
      gets video and no sound. The source has to publish Opus. Not an app problem, but it is the first
      thing to check when a feed has no audio.
