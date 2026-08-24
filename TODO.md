# TODO

Roughly in the order things should be picked up. A finished item is **ticked and left standing**, with
the commit that did it; **deleting it is done by hand, by the person who asked for the work**. 
What was done and why it was done that way is in that commit and in the
gotchas in [CLAUDE.md](CLAUDE.md) and [README.md](README.md), which is where somebody would actually
go looking — so a ticked item can be deleted without reading it. New findings are added to the end of
the section they belong to.

- 
  [ ] **`entity_health`** (`keelson.EntityHealth`) — the app *already* computes per-subject health for
  the status card (`subjectHealth()`: waiting, stalled, failed) and then keeps it to itself. This is
  the subject that puts it on the bus, so a fleet view can see a phone whose barometer stopped
  without anybody looking at the phone. Needs `messages/payloads/EntityHealth.proto` vendored, and a
  look at what upstream's other connectors put in it.
  *(2026-08-19: upstream still forbids a connector computing and publishing this itself — unchanged in
  `0.6.0-pre.3`. What the app can actually do for fleet health is the subject-level liveliness filed at
  the end of this section, which is what lets `entity_health` tell "source up but doesn't advertise
  this" from "advertised but silent".)*
  *(2026-08-24, re-checked at `0.6.0-pre.12` — nine tags on: **still forbidden, and now argued rather
  than asserted.** `connectors/CLAUDE.md` gives two reasons a connector must not compute and publish
  this itself: it bakes health *policy* — what counts as nominal against critical — into the connector,
  and two emitters writing one `entity_health` key race and flip-flop. The prescribed alternative is
  named there too — publish the raw subjects and let a dedicated aggregator watch `(source, subject)`
  freshness, with liveliness tokens carrying connector-alive — and that is exactly what this app
  already does, three tiers included. So this is not blocked work waiting on upstream; it is work
  upstream has decided belongs elsewhere, and the app's side of it is finished. Worth leaving open only
  as the record of that decision.)*

- [ ] **`checklist_evidence` is a fifth checklist subject and this app does not know it exists.** New
      upstream since `0.6.0-pre.3`; present at `0.6.0-pre.12` alongside the four in `Subjects`. Nothing
      is broken — the app neither publishes nor subscribes to it — but crowsnest may, and a checklist
      that carries evidence this phone silently drops is worse than one that never offers it. Needs a
      look at the payload and at whether crowsnest uses it before deciding anything.


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
