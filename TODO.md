# TODO

Roughly in the order things should be picked up. A finished item is **ticked and left standing**, with
the commit that did it; **deleting it is done by hand, by the person who asked for the work**. 
What was done and why it was done that way is in that commit and in the
gotchas in [CLAUDE.md](CLAUDE.md) and [README.md](README.md), which is where somebody would actually
go looking — so a ticked item can be deleted without reading it. New findings are added to the end of
the section they belong to.


## Platform Config 

Left over from the platform library, and each is a finding rather than a fix. All five are Fre this repo can act on.

- [x] **Crowsnest probes an obsolete `get_config` key shape.**
      `{realm}/@v0/{entity}/@rpc/get_config/connector_platform` predates the `{interface}/{version}`
      chunks, so nothing a current keelson connector serves answers it — `src/apps/os_config/index.jsx`
      builds it and every entry of `src/DB/platform_registry.json` declares it. The phone serves both
      shapes; `legacyPlatformConfigKey()` exists to be deleted once crowsnest moves. While in there:
      its declared `get_data_streams` and `get_queryables` queryables do not exist in keelson at all.
      **Half moved, and not the half that matters.** keelson#191's comment says Logline can retire the
      legacy shape; checked against `crowsnest-dev` at `origin/main` (`59c6a3f`), that is not yet true.
      The registry did migrate — twelve `configurable/v1` keys — and `get_data_streams` /
      `get_queryables` are genuinely gone. But `src/apps/os_config/index.jsx:167` is still
      `` `${platform.realm}/@v0/${key}/@rpc/get_config/connector_platform` ``, hardcoded inline, and it
      is the *only* site that builds a `get_config` key. So **keep `legacyPlatformConfigKey()`** —
      deleting it now would stop crowsnest reading any config off a phone.
      Worth passing upstream: the guard added to stop this recurring, `scripts/checks/rpcKeys.mjs`,
      reads `src/DB/platform_registry.json` and nothing else, so it cannot see the one key still in the
      wrong shape. A check that covers the JSON but not the code is why this looked done.
      **Crowsnest's half is written — and is sitting uncommitted in `../crowsnest-dev`.** Do not read
      this as shipped. Two files: `src/apps/os_config/index.jsx` now builds the key through the repo's
      own `rpcKeyFor("configurable/v1")` and filters on the *procedure* via `parse_rpc_key` rather than
      on the substring `get_config/connector_platform`, which had survived the migration only by luck;
      and `scripts/checks/rpcKeys.mjs` gained a scan of `src/` for pre-interface `@rpc` keys, which was
      run against the unfixed code first and failed on exactly that one line.
      **The source chunk is a wildcard**, because no fixed value is knowable: keelson's own connector is
      run with `--source-id platform`, the registry declares `connector_platform`, and this app answers
      on `calibration`. Measured against zenoh's own `KeyExpr.intersects` — the Rust matcher the router
      runs — `…/get_config/*` reaches all three and reaches neither `…/set_config/…` nor another
      entity, so the widening is exactly one chunk.
      **The gate for deleting `legacyPlatformConfigKey()` is now deployment, not code.** Crowsnest must
      be committed, released and actually running at the stations before the phone stops answering the
      old key; a merged PR is not the signal. When that day comes it is `legacyPlatformConfigKey()`,
      its two call sites in `platform/PlatformSync.kt`, and the legacy assertion in `KeysTest`.
      Done — crowsnest's fix is `a3c4853` in `../crowsnest-dev` and the key is gone from this app.
      **The deployment gate above was waived deliberately, not met**, so between now and crowsnest
      actually running at the stations, a station on the old build cannot read a config off a phone.
      `WhepSignalling.legacyKey` is a *different* pre-interface shape, for the WHEP proxy, and stays.
- [ ] **The crowsnest probe fix has never been exercised against a phone.** Everything about it is
      offline: `npm run check` green, eslint and `vite build` clean, the key matching measured against
      zenoh's matcher, and the new filter shown to select the same six registry platforms as the old
      one. What has not happened is a phone publishing a platform and crowsnest's os_config showing it
      online with the config fetched — which needs crowsnest's dev server and the phone on a shared
      router. Worth doing twice: once on this app's default `calibration` source and once with the
      source changed, since reaching both is the entire point of the wildcard. Also confirm a platform
      declaring no `get_config` still shows *no* dot rather than a grey one.
      **This matters more than when it was filed.** The legacy key was a safety net while it was still
      served: if the wildcard probe turned out not to work in practice, crowsnest fell back to a shape
      that did. That net is gone, so this is now the only path from a station to a phone's config, and
      it is unproven on a bus. Do it before anyone relies on it in the field.
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
      subjects nor `illuminance_lux` until that PR merges. Close this when it does.
      2026-08-23: **#202 is still open — OPEN, MERGEABLE, CLEAN** — and `0.6.0-pre.7` has since been
      cut, which changes nothing here except to make the point sharper: that tag *is* `dev`, on a
      different lineage from `pre.5`, so the newest release now has neither the checklist subjects nor
      `illuminance_lux`. Folded in here: a second item, "Commit the checklist protos upstream", said
      the same thing against the older branch name `feature/operational-authority` and predated the
      branch being found and pushed. Two descriptions of one blockage is how they drift apart; this is
      the one to keep.)*




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
      should drop to amber with red kept for a fix that has genuinely stopped arriving. Can we display each solution sepratly GNSS, wifi, cell and then have the fused posiiton, that woudl be relevant information to collect and anlyse to understad the technical pression of reach position source. 

- [ ] **Add ad ligth and drak team switch into the setttings** 

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
