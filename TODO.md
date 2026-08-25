# TODO

Roughly in the order things should be picked up. A finished item is **ticked and left standing**, with
the commit that did it; **deleting it is done by hand, by the person who asked for the work**. 
What was done and why it was done that way is in that commit and in the
gotchas in [CLAUDE.md](CLAUDE.md) and [README.md](README.md), which is where somebody would actually
go looking — so a ticked item can be deleted without reading it. New findings are added to the end of
the section they belong to.

## V1 (2026-08-25)

Released as `1.0`. The guides and the release path landed with it; what is listed below is what V1
knowingly ships without, not what was forgotten.

- [x] **V1 has no operator guide, no deployment guide and no licence.** `README.md` is 1 483 lines of
      Gradle, key expressions and protobuf — nothing written for the person handed the phone, and no
      answer to "how do I get it onto a second one". Added `docs/user-guide.md` (six task-shaped
      sections, quoting what the screens actually say), `docs/deploying.md` (build, sign, distribute,
      provision), `CHANGELOG.md`, Apache-2.0 to match `keelson` and `crowsnest`, and
      `.github/workflows/release.yml` so a `v*` tag builds a signed APK and attaches it to a Release —
      no hosting, no files to chase. Done in 085393a.

- [ ] **The release workflow has never run.** It parses, its tag trigger and permissions are right, and
      one real bug was caught by reading the parsed YAML rather than by running it: the keystore step's
      `if` referenced `env.` for a variable defined in its own `env:` block, which is evaluated too
      late — every release would have come out quietly unsigned. That class of mistake is why the CI
      history in CLAUDE.md records three red runs. **It cannot be proven from here**: it needs a push
      and a tag. Until then the honest status is "written and reviewed", not "working".

- [ ] **The signing keystore does not exist yet.** `docs/deploying.md` has the one `keytool` command,
      and the four GitHub secrets the workflow reads. Deliberately not generated here — a signing key
      outlives the app, and whoever holds it can ship builds that install over yours. Until it exists,
      a release APK is unsigned and cannot install over a signed build.

- [ ] **`main` is 40+ commits ahead of `origin/main`.** Nothing above reaches CI, and no tag can build,
      until that is pushed. Pushing stays the owner's call.

## Future long therm 


## Shared platform library (2026-08-24)

- [x] **Crowsnest's half of the platform library is written — and untracked.** The old item said
      crowsnest does not publish its overlay, so sharing was one-way. **It does now.**
      `services/platformLibrarySync.js`, `hooks/usePlatformLibrarySync.js`, `jotai/platformLibraryAtoms.js`
      and `scripts/checks/platformLibrary.mjs` all exist in `../crowsnest-dev`, the hook is mounted in
      `BasePage.jsx`, and the check is already in `npm run check`. The key builders are
      character-identical to `PlatformRegistry.key()`, `platforms` entity default included.
      **All of it is untracked in a 139-file WIP tree** — written, not shipped, the same trap the
      `os_config` item warned about. Not this repo's to commit.
      Verified rather than read: `PlatformLibraryInteropTest` decodes the literal output of crowsnest's
      `encodeLibrary()` over its own `sf18` registry entry — sensors, offsets and a negative camera yaw
      all intact — and fails if the transforms array is renamed. Neither project had a
      cross-implementation test for this document before, and a renamed field is the failure that makes
      both sides work perfectly and never meet. Done in 4b53b16.

- [ ] **Neither live direction of the library exchange has been tested on a bus.** Phone → crowsnest is
      testable today, since publishing is unaffected by the binding fault, but it needs a platform in the
      phone's library and this phone has none. Crowsnest → phone **cannot** be tested until
      eclipse-zenoh/zenoh-flat-jni#49 lands: receiving a library means subscribing, which aborts. The
      fixture test covers the shape; it cannot cover the wire.

## Blocked on the Zenoh Android binding

Everything under here waits on the same fault: `zenoh-flat-jni` builds callback arguments with
`FindClass` on one of Zenoh's own threads, where JNI cannot see app classes. Four classes have been
seen to trigger it — `ZenohId` (scout), `EntityGlobalId` (a query reply), `Timestamp` and `SourceInfo`
(any subscribed sample) — and a router timestamps every sample it forwards, so **no subscription works
at all**. Filed as
[eclipse-zenoh/zenoh-flat-jni#49](https://github.com/eclipse-zenoh/zenoh-flat-jni/issues/49);
`keelson/ZenohBinding.kt` owns the diagnosis and `SUBSCRIPTIONS_SAFE` is the one boolean that lifts it.
1.10.0 is the newest release, so no version bump escapes it.

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
      feature with it.** Turning "Share checklists" on and opening the screen against the live
      `router.example.com` bus crashes the app within seconds, every time, three for three: the session
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
      guarded rather than inverted, so they start pinning again the moment it flips. Done in 9de8f83.
      The sync is now query-free and there is a simplified `ChecklistRunsScreen` behind the gate, with
      the five protos re-vendored from `0.6.0-pre.12` — correct and tested, and none of it enough to
      turn the feature on. Done in db09694.

- [ ] **The two older checklist screens are out of the nav graph but still in the tree.**
      `ui/ChecklistsScreen.kt` and `ui/ChecklistScreen.kt` — 697 lines of procedure editing, notes and
      reminders — are unreachable since `Routes.CHECKLISTS` began routing to `ChecklistRunsScreen`.
      Left deliberately: deleting a working feature should not be a side effect of adding a simpler one,
      and if the binding is fixed they may be what somebody wants back. Keep or delete is a decision,
      not a cleanup.

- [ ] **Checklist progress is keyed on the procedure, not the run.** Two runs of one procedure live at
      the same time collapse into a single row, and the live bus had five concurrent runs during this
      investigation. The snapshot's `run_id` is read and shown, but re-keying the map touches 22 call
      sites across the reducer, both older screens, the reminder receiver and persistence — and
      `ChecklistEvent` would have to be re-vendored and re-keyed with it, or snapshots and events would
      write to different keys in one map, which is worse than either choice. Only worth doing if the
      feature is ever enabled.

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


- [ ] **BLOKED by Kotlin Zheno - The checklist protocol has grown a run model and photo evidence, and this app knows neither.**
  Looked at properly at `0.6.0-pre.12`, against crowsnest at `origin/main`.
  **`checklist_evidence` is `foxglove.CompressedImage`, one key per `evidence_id`** — deliberately
  its own subject rather than a field on the snapshot, because that snapshot is republished every
  30 s per active run and photos in it would be megabytes on the wire twice a minute into a durable
  store. The *metadata* that names the key rides inside `checklist_event.evidence` (13) and
  `ChecklistState.ItemState.evidence`, and is fetched lazily one key at a time.
  **Crowsnest uses it fully**: `checklistEvidence.js` (fetch by RPC, delete), `checklistEvidenceModel.js`,
  `evidenceKeyExpr` in `checklistWire.js`, and `imageDownscale.js` on the way in.
  **Nothing is broken, and that took checking twice.** A first pass suggested `ChecklistState` had
  been *renumbered* — `status` 2→8, `started_at` 3→9 — which would have been a wire break of the
  exact kind `ChecklistWireTest` exists to catch. It was an artefact of comparing fields across
  nested messages: `ItemState.status = 2` here against `ChecklistState.status = 8` upstream are
  different messages. Compared per message, **every upstream change is additive** and the shared
  fields keep their numbers, so messages still decode correctly in both directions.
  **The key shape did change and crowsnest already tolerates it.** `checklist_state` is now keyed on
  `run_id` rather than `procedure_id` — a procedure is a template and each execution is a run, so
  two runs of one procedure used to overwrite each other. This app still keys on the procedure id,
  and `runIdFor()` in `checklistWire.js` falls back to `procedureId` precisely to tolerate
  "publishers that predate the run model", so its snapshots are accepted rather than dropped.
  So this is a *degraded but tolerated* participant, not a fault. What it cannot do: see or attach
  photo evidence; know a run was planned, abandoned or had a timestamp corrected
  (`EVENT_TYPE_RUN_PLANNED`/`RUN_ABANDONED`/`EVIDENCE_ATTACHED`/`TIME_SET`); render sub-items
  (`ChecklistProcedure.Item.parent_item_id`); or say which runs it has open
  (`active_run_id`, `open_run_ids`). Adopting it means re-vendoring five protos —/pl `ChecklistEvidence.proto`
  is not vendored at all — and reworking `ChecklistSync`/`ChecklistStore` around runs. A product
  decision, not a bug fix, and the checklist feature is off by default meanwhile.
  **Moot until the JNI crash is fixed**: tested on the live bus, turning checklists on crashes the
  app on the bootstrap query's reply before any of this matters — see the `EntityGlobalId` item.