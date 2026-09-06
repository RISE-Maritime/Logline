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

  