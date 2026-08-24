# TODO

Roughly in the order things should be picked up. A finished item is **ticked and left standing**, with
the commit that did it; **deleting it is done by hand, by the person who asked for the work**. 
What was done and why it was done that way is in that commit and in the
gotchas in [CLAUDE.md](CLAUDE.md) and [README.md](README.md), which is where somebody would actually
go looking — so a ticked item can be deleted without reading it. New findings are added to the end of
the section they belong to.


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

## Per-subject publish rates (2026-08-20)

Derived subjects gained a publish rate of their own, capped at the one they ride. Findings:

- [x] **Saving a subject's page always writes a QoS override, even when nothing about the QoS was
      touched.** `onSave` does `qosOverrides + (subject to qos)` unconditionally, so changing only a
      rate leaves the subject reading "Overridden for this phone" against values identical to
      `qos.yaml`. Noticed while testing the rate control — it took a deliberate "Reset to qos.yaml
      policy" to undo something the user never asked for. Pre-existing; the fix is to write the entry
      only when `qos != policyQosForSubject(subject)`.
      Done as prescribed, plus the other side of it: an override dialled back to the policy values by
      hand is *removed*, since keeping it would have the page go on claiming an override over values
      identical to upstream's. `QosTest` pins the invariant the fix rests on — what the screen is handed
      for an untouched subject must compare equal to policy, checked over every subject in the registry,
      since the four profiles convert separately — and the converse, so a change making every save a
      no-op cannot pass.
      **This phone was carrying two of the leftovers**, `linear_acceleration_mpss` and
      `heading_accuracy_deg`. The fix clears them a page at a time: opening the first, which read
      "Overridden for this phone" above "Policy is default", and pressing Save without touching anything
      took its four stored keys to zero and the banner back to "Following keelson qos.yaml".
      `heading_accuracy_deg` still holds one until somebody opens it. Done in c8be8ac.

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


- [ ] **`SettingsProfileTest` does not catch a new `Settings` field on its own.** CLAUDE.md says a
      field added later "fails the test until somebody decides which side it belongs on", and that is
      only true if the fixture is updated as well — the assertions are a hand-written list, not a
      reflective one. `mapTilerKey` was added to both by hand. A reflective check over
      `Settings::class.memberProperties` would make the claim true.

- [ ] **The layer menu says a layer needs a key, but not that a key has stopped working.** An expired
      or over-quota MapTiler key fails per tile, so the chart simply goes blank with the layer still
      ticked — the gear only appears when the field is *empty*. A tile-fetch failure is not currently
      surfaced anywhere.

- [ ] **The Files list only shows recordings written by the current install.** MediaStore ties a file
      to the package that created it, so 101 of the 216 recordings in `Downloads/Logline` on the dev
      phone are invisible to the app — every one written before a reinstall. The empty state already
      says this in words, but the *list* gives no hint that half the folder is missing, and it is why
      the largest file the detail view could be tested against was 479 MB rather than 537 MB.

- [ ] **The `OVERSAMPLE` cap silently truncates a very long track.** Past 20 000 fixes — about five
      hours at 1 Hz — the reader stops and downsamples what it has, so a twelve-hour passage shows its
      first five hours and says nothing about the rest. Bounded work is right; saying so is missing.
      **Asked: never downsample, raw recordings must keep their original values, processing belongs in
      other software. Checked, and nothing on the recording path does.** `OVERSAMPLE` and `downsample()`
      are in `McapTrack.read`, which *reads* a closed `.mcap` out of `Downloads/Logline` to draw the
      Files chart and the row thumbnail — the file on disk is never rewritten and the thinning never
      reaches it. On the write side, `SubjectSink.wrap()` calls `recorder.offer()` unconditionally and
      **before** the publish is considered, so the `PublishDecimator` thins the wire only; `recordRate`
      is the rate the sensor is *registered* at rather than a thinning of what it delivered, and it
      defaults to `SensorRate.Max`. The one way a sample fails to reach the file is the recorder's
      bounded queue overflowing, which is *counted* as `RecordingStatus.dropped` and shown rather than
      silently dropped — a healthy run measures published, written and messages-in-file all equal.
      So this item is display-only, and its fix is to say what the chart is showing rather than to keep
      more of it.

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
