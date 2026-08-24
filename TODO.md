# TODO

Roughly in the order things should be picked up. A finished item is **ticked and left standing**, with
the commit that did it; **deleting it is done by hand, by the person who asked for the work**. 
What was done and why it was done that way is in that commit and in the
gotchas in [CLAUDE.md](CLAUDE.md) and [README.md](README.md), which is where somebody would actually
go looking — so a ticked item can be deleted without reading it. New findings are added to the end of
the section they belong to.

-
- [ ] **The checklist protocol has grown a run model and photo evidence, and this app knows neither.**
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
      (`active_run_id`, `open_run_ids`). Adopting it means re-vendoring five protos — `ChecklistEvidence.proto`
      is not vendored at all — and reworking `ChecklistSync`/`ChecklistStore` around runs. A product
      decision, not a bug fix, and the checklist feature is off by default meanwhile.
      **Moot until the JNI crash is fixed**: tested on the live bus, turning checklists on crashes the
      app on the bootstrap query's reply before any of this matters — see the `EntityGlobalId` item.


## Future long therm 


## Platform Config

Left over from the platform library, and each is a finding rather than a fix. All five are Fre this repo can act on.

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
-
- [ ] **Crowsnest does not publish its platform overlay**, so the shared library is one-way today —
  the phone shares and nothing answers. The change is small and belongs in that repo; the pattern
  to copy is its own `dataflowConfigSync.js`.
-
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

-


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
      something other than a Zenoh query — which now blocks checklists as well as WHEP, and is the
      reason the checklist feature cannot be recommended on even though it is written and shipped.

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
