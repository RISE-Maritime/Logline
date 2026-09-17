# TODO

Roughly in the order things should be picked up. A finished item is **ticked and left standing**, with
the commit that did it; **deleting it is done by hand, by the person who asked for the work**. 
What was done and why it was done that way is in that commit and in the
gotchas in [CLAUDE.md](CLAUDE.md) and [README.md](README.md), which is where somebody would actually
go looking — so a ticked item can be deleted without reading it. New findings are added to the end of
the section they belong to.


## Live camera over WHEP — blocked in the Zenoh binding (2026-08-22)

Built on branch `whep-live-camera`, not merged: the app **crashes** the moment the signalling query
completes, and that is not something app code can fix.

- [ ] **A Zenoh query that receives a reply from a remote queryable SIGABRTs the app.**
      `JNI DETECTED ERROR IN APPLICATION: ... ClassNotFoundException: Didn't find class
      "io.zenoh.jni.pubsub.EntityGlobalId"`, in `finalize_pending_query`. It is the **same bug as
      `Zenoh.scout`** — the binding builds a class in native code with `FindClass`, on one of Zenoh's
      own threads, where JNI resolves against the system class loader and cannot see app classes — and
      `keelson/Scout.kt` exists because of it. Reproduced twice, zenoh-kotlin 1.10.0.
      Opening the Platforms screen (`livelinessGet`) does *not* crash, so it is the reply path specifically.
      **`KeelsonSession.query()` is affected too — tested on 2026-08-24, and it takes the whole checklist
      feature with it.** Turning "Share checklists" on and opening the screen against a live
      `tls/` bus crashes the app within seconds, every time, three for three: the session
      opens, the presence heartbeat goes out, the bootstrap `get` on `checklist_procedure/*` /
      `checklist_state/*` is answered by the router storage, and the reply callback aborts the process
      on the same `EntityGlobalId` `ClassNotFoundException`.
      **The wire shape is not the problem.** The one message that does escape is well-formed: captured
      off the bus and decoded with keelson's *own* Python bindings, the presence lands on
      `crowsnest/@v0/checklist/pubsub/checklist_presence/pixel_6/{operator_uuid}` carrying `username`,
      `roc_site` and a timestamp. So a crowsnest station sees this phone announce itself and vanish
      seconds later, over and over, and never sees an event or a snapshot.
      No workaround from this side. Either the binding is fixed upstream, or the handshake goes over
      something other than a Zenoh query — which now blocks checklists as well as WHEP.
      **It is worse than a query bug.** With the bootstrap `get` removed and run snapshots taken by
      subscription instead, the app still died — different class, different path:
      `ClassNotFoundException: io.zenoh.jni.time.Timestamp`, raised in a **subscriber callback** by
      `JNI NewStringUTF`. The trigger is any sample carrying a Zenoh timestamp, and on this bus every
      checklist key carries one: measured with a Python subscriber, `checklist_state` and
      `checklist_presence` both arrive `timestamped=True`.
      **The "storages" explanation was wrong** — corrected by the crowsnest session, and my own data had
      already disproved it: `checklist_presence` has *no* storage in the router's compose and was
      timestamped anyway. A Zenoh router timestamps **every sample it forwards**
      (`timestamping.enabled.router` is true by default); storages merely require it, they do not scope
      it. Measured across the fleet bus: 15 053 samples, 42 subjects, four realms, 100% timestamped. `checklist_presence` was already subscribed before any of that work, so the feature
      could never have survived a second station being present — the query crashed it first, so the
      subscription half was never reached. One bug hiding behind another.
      So the blocker is not "queries" and not "checklist keys": **no subscription is safe on this
      binding at all**, and `io.zenoh.jni.sample.SourceInfo` is the next class to bite —
      `SampleCallback.run` takes it alongside the `Timestamp`. `keelson/ZenohBinding.kt` owns the
      diagnosis now; upstream is eclipse-zenoh/zenoh-flat-jni#49, filed against the shared JNI layer
      because it affects zenoh-java identically. 1.10.0 is the newest release, so no bump escapes it.
      **The question about platform discovery is answered: it was affected, and is now gated.** Both of
      `PlatformSync`'s subscriptions are skipped while the gate is on — `platform_registry` especially,
      which is storage-backed and would have aborted the first time any station shared a library, latent
      until somebody did. `livelinessGet` was *verified* rather than assumed: with a run going so the
      phone's own tokens answered it, a scan returned three entities and the app survived, so discovery
      degrades to ids without geometry rather than dying and `shouldSyncPlatforms` needs no gate.
      **A comment in `KeelsonSession` is what hid this for months**: it named an `io.zenoh.jni.callbacks`
      package that does not exist in 1.10.0 and claimed subscriptions marshal primitives only. Replaced.
      **Checklists are gated off meanwhile**, in `checklist/ChecklistAvailability.kt`. The constant is
      checked in `Routes.shouldSyncChecklists` rather than only on the Settings switch, because that is
      the single place a session opens and there are four ways the flag gets set. Re-enabling once the
      binding is fixed is that one boolean; `NavigationRoutesTest` has the positive routing assertions
      guarded rather than inverted, so they start pinning again the moment it flips. Done in d251a07.
      The sync is now query-free and there is a simplified `ChecklistRunsScreen` behind the gate, with
      the five protos re-vendored from `0.6.0-pre.12` — correct and tested, and none of it enough to
      turn the feature on. Done in 5d30568.

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



## Checklist

- [ ] **The three checklist QoS assignments are unobserved, and the reason is that nothing publishes
      them — not that nothing can listen.** `checklist_event` is `elevated`, `checklist_presence`
      `transient` and `checklist_state` `background` as of `0.6.0-pre.15`, and `policyQosForSubject`
      now says so. `QosTest` pins the policy table; what no test can reach is the priority a router
      actually sees.
      **An earlier wording of this item blamed the absence of a subscriber, and that was wrong twice.**
      The blocker is the *publisher*: `Routes.shouldSyncChecklists` gates on `CHECKLISTS_AVAILABLE`
      and is the only caller of `checklist.start()`, so with the gate false `ChecklistSync` never
      opens a session and never puts any of the five checklist subjects on the wire at all. And
      `SUBSCRIPTIONS_SAFE` is about *this app* subscribing; a Python subscriber on the fleet bus needs
      nothing from the phone and works today — it is how the 15 053-sample timestamping measurement in
      `keelson/ZenohBinding.kt` was taken, with the app gated exactly as it is now.
      So the check is a minute's work the moment the gate lifts, and needs no new tooling:
      `zenoh-python`'s `Sample` carries `priority`, `congestion_control` and `express`, so subscribing
      to `rise/@v0/roc1/pubsub/checklist_*/**` and printing them settles it.
      Worth knowing meanwhile: the *mechanism* is not in doubt. `qosForSubject` →
      `declarePublisher` is the same path every sensor subject takes, and `location_fix` arriving as
      `DATA_HIGH` is observable on the bus today. What is unconfirmed is only that these three
      subjects travel that path, which is a much smaller claim than the old wording implied.

- [ ] **`ChecklistProcedure.Item.parent_item_id` is vendored and unused, so sub-items render flat.**
      Not a bug — a procedure without sub-items is unaffected — but a nested one reads as a flat list
      with nothing saying it was nested.
      **An earlier wording called this "the one part of the protocol this app does not speak", which
      was wrong and hid two real bugs.** Looking for the others found that `created_by`/
      `created_by_site`/`created_at` were populated only from an *incoming* snapshot, so a run this
      phone created had none — and `snapshotRuns()` filters on exactly those, which made the whole
      snapshot publisher dead code on the runs it existed for. And `items_snapshot` was merged and
      re-emitted but never *produced*, so a run this phone completed published an empty archive.
      Both fixed; both now have tests. Do not read a "the only remaining gap" claim in this file as
      having been checked unless it says how.
      What genuinely remains unspoken, having now been enumerated: `parent_item_id` above;
      `scheduled_for`, which is decoded and merged but which nothing sets, since `planRun` takes no
      due time; `procedure_version`, carried through but never written for this app's own runs; and
      fetching another station's `checklist_evidence`, which needs a query and is blocked by the
      binding rather than unimplemented.

- [ ] **Two §7.4 open ends this app now inherits by being a conformant participant.** Neither is
      fixable here; both are worth knowing before reading the behaviour as a bug.
      **A reopen cannot reach a station that missed the event.** §7.2 makes `ItemState.status`
      monotone and says reopening is carried by `EVENT_TYPE_ITEM_REOPENED` — but events have no
      router storage and no delivery guarantee, and `ItemState` has no field a snapshot could carry a
      reopen in. So a station that misses it holds COMPLETED permanently, and §7.2 forbids any later
      snapshot from correcting it. It degrades twice: the re-completion that follows is discarded as
      a "confirmation", so the two stations also disagree about *when* it was done — and because a
      confirmation is a legitimate outcome, neither can tell that from two operators checking one
      thing.
      **`checklist_state`'s key set grows without bound**, one key per run forever, and bootstrap
      enumerates it. Upstream records that as an accepted cost with no retention story. This phone's
      DataStore grows with it, and capping the persisted set to the non-terminal runs plus the most
      recent N is a change the app *could* make on its own.

- [ ] **The two older checklist screens are out of the nav graph but still in the tree.**
      `ui/ChecklistsScreen.kt` and `ui/ChecklistScreen.kt` — 697 lines of procedure editing, notes and
      reminders — are unreachable since `Routes.CHECKLISTS` began routing to `ChecklistRunsScreen`.
      Left deliberately: deleting a working feature should not be a side effect of adding a simpler one,
      and if the binding is fixed they may be what somebody wants back. Keep or delete is a decision,
      not a cleanup.
      **"Unreachable" is wrong, and was wrong when written.** `Routes.CHECKLIST` (`checklist/{id}`)
      routes to both of them and a reminder notification deep-links straight there, so they are one
      tap from any armed reminder. They were ported through the run re-key with a one-line hop
      (`ChecklistState.openRunOf`) rather than dropped, which is what that discovery made the right
      call. The decision is still open and is now a real one: `ChecklistRunsScreen` has grown flags,
      evidence and run controls, so the overlap is larger than it was — but the note and reminder
      dialogs still live only in the old pair.


## Recording

- [ ] **Recordings rescued before recovery rebuilt summaries are still summary-less, and nothing upgrades
      them.** `McapRecovery.finalise` returns early on any file that already ends in the closing magic,
      which every previously rescued file does — so the ones sitting in `Downloads/Logline` keep their
      `summary_start = 0` and keep opening in Foxglove on a timeline back to 1970. There were two on the
      dev phone. `mcap recover in.mcap -o out.mcap` fixes one on a desktop and was verified to; whether
      the app should offer to re-finish them in place is a decision, not a cleanup, and it would mean
      the sweep revisiting files it has already published.

- [ ] **The free-space estimate measures the wrong volume once a folder is chosen.**
      `Recorder.trackFreeSpace()` polls `filesDir`, which is right for what stops a run — every
      recording is written to app-private storage and copied at the end — but the *destination* can
      now be a card, a stick or a synced folder on another volume. That one can fill while the card
      still says "about 16 days of recording", and the failure lands at the end of a run rather than
      the start. Nothing is lost when it does: the file stays in app storage and the launch sweep
      retries. A second estimator, or a copy that checks first, is a decision rather than a cleanup.


## Radio (2026-09-14)

Found while mapping data-link coverage from `logline-2026-09-14.mcap` (Phone 5, 5 h 53 min at sea)
in the Foxglove coverage panel. The panel left most of the track empty, and the cause turned out to
be here, not in the panel.

- [ ] **The cellular quality subjects mostly republish a stale cached measurement, and each repeat
      gets a new timestamp.** `radio_rsrp_dbm`, `radio_rsrq_db`, `radio_sinr_db`, `radio_rssi_dbm`
      and `radio_access_technology` come from `TelephonyManager.getSignalStrength()` in
      `RadioProvider.readCellular()`, polled at 1 Hz.
      **Measured on that recording:**
      - **Held values:** 20 577 of 20 836 consecutive readings repeat the previous value.
      - **Rare refreshes:** `SignalStrength.getTimestampMillis()` moved only 166 times in the
        whole run, so the modem refreshed about every 2 minutes on average.
      - **Staleness:** the published timestamp is a median of 246 s behind log time, p90 1 725 s,
        worst 2 827 s (47 minutes).
      - **Pattern:** the lag climbs steadily and drops back at each refresh. It never jumps upward,
        which rules out a wrong clock base.
      So the stream looks like 1 Hz data but carries about 166 measurements.
      The provider KDoc already expects a held value. It says carrying the real timestamp "is what
      lets a consumer tell a fresh reading from one held for two minutes". **That promise is broken
      by the clock conversion, not by the timestamp.**
      `SensorPublisher.runRadio` calls `SensorClock.epochNanosNow(measuredAtElapsedNanos)` on every
      tick, and that re-reads `currentTimeMillis` and `elapsedRealtimeNanos` each time. So one
      measurement comes out as a slightly different epoch time on every tick: 18 766 "distinct"
      timestamps for about 166 real measurements. A consumer comparing timestamps sees a new
      reading every second.
      **What that did downstream:** a consumer joining readings to position by their own timestamp
      piled 20 837 readings into about 45 of the 112 areas the boat covered. One joining by log
      time would do worse and paint a value measured up to 47 minutes earlier along the track.
      **Fix, in order of value:**
      1. **Stop repeating a held measurement.** Publish the quality subjects only when
         `measuredAtElapsedNanos` changes. If a steady rate matters to someone, at least convert
         each measurement's epoch time once and reuse it, so repeats are identical and
         recognisable. Either way the timestamp then keeps the KDoc's promise.
         *Fix 1 (convert once, keep repeating) done in 0d14ce1; 2 and 3 still open, and not yet
         verified on a device.*
      2. **Get fresher measurements.** Check whether the serving `CellInfo`'s
         `getCellSignalStrength()` (`CellSignalStrengthLte`/`Nr`) refreshes faster than
         `SignalStrength`: the identity from the same cell list was only a median of 9 s behind
         (max 31 s) on this run. If it does, read RSRP/RSRQ/SINR/RSSI from there, and consider
         `TelephonyManager.requestCellInfoUpdate()` (API 29, `ACCESS_FINE_LOCATION`, both already
         met) to ask the modem for a new report instead of reading its cache.
         **Check the NSA case before switching.** `readCellular()` deliberately takes the NR leg
         when attached to 5G NSA, and the cell list may only carry the LTE anchor. Losing the NR
         leg's RSRP would be a regression hiding behind a freshness gain.
         *The probe and reports for this measurement are ready; procedure in
         [docs/development.md](docs/development.md#checking-radio-freshness-on-a-device).*
      3. **Say how old a value is.** If a held measurement is still published at all, a
         measurement-age subject, or a note in `configuration_json` saying the quality subjects are
         a cache refreshed at the modem's discretion, would stop a consumer mistaking the poll rate
         for the measurement rate.
      **A test worth adding:** a fake `SignalStrength` held for N ticks must produce either one
      message or N messages with byte-identical timestamps, never N distinct ones.

- [x] **The same conversion jitter applies to the cell identity subjects, less severely.**
      `radio_cell_id`, `radio_physical_cell_id`, `radio_earfcn`, `radio_band` and
      `radio_downlink_bandwidth_mhz` use `CellInfo.getTimestampMillis()` through the same per-tick
      `epochNanosNow`. On this run the cell list refreshed often (lag median 9 s, max 31 s), but
      20 708 of 20 836 consecutive values still repeat under a new timestamp. Fix 1 above applies
      unchanged. The comment in `runRadio` says identity is stamped this way precisely so a
      consumer can tell whether two measurements straddle a handover, which the jitter currently
      defeats. Done in 0d14ce1.

- [x] **A "minimum" logging config button on session page** for phones just used as event marker, we should keep some oter data as well so we know were the devise is but dos not need a a high rate and acceleramtion is not needed as someone might pick up the phone or what do you think Done in 0156141.

- [x] **Measure a Minimum run: MB/h and battery drain.** The Logging card deliberately shows no size
      figure because none has been measured. Record an hour in Minimum on a phone, then read the
      file size and the battery estimate, and put the MB/h beside the others in `Capacity.kt` so the
      card can state it. While at it, confirm the file holds only the eight Minimum channels, with
      `location_fix` at ~0.2 Hz, and that `dumpsys sensorservice` lists no Logline listeners.
      Done in a3377da.

- [x] **Consider a lower location priority in Minimum.** `LocationProvider` always asks for
      `Priority.PRIORITY_HIGH_ACCURACY`, which keeps the GNSS engine busy even at one fix per 5 s.
      `PRIORITY_BALANCED_POWER_ACCURACY` could save a lot of battery on an event-marker phone, but it
      falls back to wifi and cell accuracy. Decide once the drain above is measured, and only if
      marks placed that coarsely are still useful.
      Done in a3377da.

- [ ] **Re-measure the Minimum drain now that it asks for balanced power — and revert if it barely
      moves.** The −3.98 %/h in CLAUDE.md was measured with the *old* high-accuracy request and is no
      longer what the shipped mode draws. The MB/h figure survives the change (neither the subject
      set's rates nor the 0.2 Hz cap moved); the drain does not. Record another hour in Minimum,
      unplugged, and read `battery_state_of_charge_pct` back out of the `.mcap`.
      **This is the decision, not a formality.** What was given up is measured and specific: the
      high-accuracy hour solved `FIX_3D` on 723 of 726 samples at a **3.4 m median, p90 3.9 m**. If
      balanced power comes in under about 3 %/h the trade is worth it; if it barely moves — or if
      `location_fix_accuracy_horizontal_m` in the new file shows it is still solving, which would mean
      the saving was never there — take `balancedPower` back out rather than keep it for its own sake.
      Read `location_fix_quality` in the same file: `FIX_NO` becoming common is expected and is not the
      failure, it is the cost.

- [ ] **Chunk framing is 45% of a Minimum file, and it is deliberately not fixed.** Measured:
      889 chunks averaging 0.4 kB uncompressed, so zstd manages 1.58x against a full run's 3.05x, and
      203 kB of compressed payload sits inside a 372 kB file. Raising the two-second flush bound would
      roughly halve that — and must not. That bound is what caps what a killed process loses, and
      trading bounded loss for bytes amounting to 8.7 MB a day is the wrong way round. Recorded so the
      next person meets the argument before rediscovering it as an optimisation.

- [ ] **The Minimum channel count is nine now, not eight.** `battery_current_a` joined
      `MINIMUM_SUBJECTS` so the mode's own power cost is answerable from the file rather than from a
      gauge that moves in whole percentage points. It rides the charge poll (`rateOwner`), so there is
      no extra collector and no extra listener, and it costs about 0.02 MB/h. Anything written down as
      "the eight Minimum channels" — including the ticked item above — predates it.

- [x] **`MainScreenTest` (androidTest) no longer compiles.** It passes no `activeTags`, `onToggleTag`,
      `onAddTag` or `onRemoveTag`, which `421f334` added to `MainScreen` without defaults.
      `compileDebugAndroidTestKotlin` fails on exactly those four. CI does not build androidTest, so
      nothing flagged it. 
      Done in 4d8a856.

- [ ] **Run `MainScreenTest` on a phone — it compiles again but has never been executed since.** Ten
      arguments were missing, not four, and three of the five tests were asserting text the screen had
      stopped saying: the card no longer says "Not publishing" or "Start to put this phone's sensors on
      the bus.", and the two `ConnectionChip`s it looked for ("Router Idle", "Router Connected") were
      replaced by the app bar's `PUB`/`REC` lamps. The assertions were rewritten against whatcna 
      `StatusCard` and `RunStatus` say today — `Ready to publish`, `Last run`, `Publishing`,
      `Publishing to nothing`, and the lamps' own descriptions — and the test now provides
      `LocalRunState` itself, since the lamps read the run's state from there rather than from
      `MainScreen`'s arguments. None of that has been checked against a device. Note the cost of
      checking: `connectedDebugAndroidTest` reinstalls the app, which wipes `filesDir` and regenerates
      the entity id, so export a settings profile first and follow the recovery steps in CLAUDE.md.

