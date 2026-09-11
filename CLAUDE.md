# Logline

Android (Kotlin + Compose) publisher that puts a phone's GNSS, IMU, barometer and battery on a
Keelson/Zenoh bus.
Single Gradle module `:app`, package `se.rise.logline`, ~3000 lines of Kotlin.
Read [README.md](README.md) for what it does; this file is how to work on it.
**The README is a front page, not the reference** — it is the pitch, the screenshots and an
index. The detail lives in `docs/`: `subjects.md`, `keys-and-liveliness.md`, `connecting.md`,
`settings.md`, `live-view.md`, `recording.md`, `development.md`, plus the older `user-guide.md`,
`deploying.md`, `calibration.md` and `architecture.md`. A new explanation belongs on one of those
pages and in the index, never appended to the README.

## Build environment — read this first

There is **no `java` on the PATH**. Every Gradle invocation needs `JAVA_HOME` pointed at a JDK 25
(the daemon toolchain is pinned in `gradle/gradle-daemon-jvm.properties`):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

`.claude/settings.local.json` sets `JAVA_HOME` and `ANDROID_HOME` for this project, so Bash calls here
already have them. **That file is git-ignored and machine-specific** — it holds absolute paths, which is
why it is the local half rather than the tracked `settings.json`, so a fresh clone has to write its own
before Gradle will run. If a Gradle command fails with `Unable to locate a Java Runtime`, that env was
lost or was never there — re-export it inline rather than debugging Gradle.

`adb` is not on the PATH either; use `$ANDROID_HOME/platform-tools/adb`.

## Key commands

```bash
./gradlew :app:assembleDebug        # build APK  -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:compileDebugKotlin   # fastest check that the code compiles
./gradlew :app:testDebugUnitTest    # JVM unit tests
./gradlew :app:lintDebug            # Android lint
./gradlew :app:assembleDebug --rerun-tasks   # when the configuration cache lies to you

$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/platform-tools/adb logcat -s SensorPublisher:V
```

Prefer `compileDebugKotlin` for a correctness check — `assembleDebug` spends most of its time
packaging ~66 MB of Zenoh native libraries (four ABIs). A clean build takes minutes; the configuration cache
(`org.gradle.configuration-cache=true`) makes warm builds a few seconds.

## Architecture in one pass

```
LoglineApp (Application) ─ owns SettingsRepository + SensorPublisher for the process
  ├── MainActivity (Compose, NavHost: "main" | "settings") ── renders status, sends start/stop intents
  ├── PublisherService (foreground, location|dataSync) ── notification + wake lock + run lifetime
  │     └── reads Settings from DataStore, then drives ↓
  └── SensorPublisher ─── owns CoroutineScope + KeelsonSession + one Publisher per PublishedSubject
        ├── LocationProvider       callbackFlow over FusedLocationProviderClient → foxglove.LocationFix
        ├── ImuProvider            callbackFlow over SensorManager       → keelson.Decomposed3DVector
        │                                                                → keelson.TimestampedQuaternion
        ├── ScalarSensorProvider   callbackFlow over SensorManager       → keelson.TimestampedFloat
        └── BatteryProvider        polls BatteryManager                  → keelson.TimestampedFloat/Bool
                                                    each payload → enclose() → core.Envelope → publisher.put()
```

Alongside that, and deliberately not part of it:

```
LoglineApp
  └── ChecklistSync ─── its own KeelsonSession, opened only while a checklist screen is up
        ├── subscribes  checklist_event/*, checklist_presence/**   → ChecklistStore (a StateFlow)
        ├── queries     checklist_procedure/*, checklist_state/*   → router storage, on join
        └── publishes   this operator's events, heartbeat, snapshots
```

```
LoglineApp
  └── PlatformSync ─── its own KeelsonSession, opened only while a platform screen is up
        ├── discovers  liveliness + a listening window on configuration_json
        ├── serves     configurable/v1 — get_config per platform (plus crowsnest's older key shape),
        │               set_config refused in a typed way, and the interface liveliness token
        └── shares     the platform library as raw JSON on platform_registry/library/latest
```

`ChecklistSync` and `PlatformSync` share nothing with `SensorPublisher` — different session, different
lifetime — because a checklist is worked through, and a platform surveyed, with logging stopped. See
"Checklists" and "Platform library" below.

`SensorPublisher` is the only stateful thing in the app. It exposes `StateFlow<PublisherStatus>`
holding a per-subject sample count and last-publish timestamp; the UI is otherwise stateless.

**Nothing that outlives a screen may be held by Compose.** The publisher lives on `LoglineApp`
precisely so the service and the Activity see the same instance and the same status flow. Start and
stop go through `PublisherService.start(context)` / `.stop(context)` — never call
`SensorPublisher.start()` from the UI.

Full walkthrough: [docs/architecture.md](docs/architecture.md).

## Conventions

- **A platform's keys come from the platform, not from `entityFor()`.** `Settings.entityFor(entry)` answers for
  the phone and only for the phone. It used to return the platform's entity for the three calibration
  entries, which was right while there could be only one platform; with a library, one registry entry is
  published by *several* platforms under different entity ids and there is no single answer to return.
  `Settings.platformKeys(platform)` is the counterpart and the only place a platform's entity reaches a key, and
  `SensorPublisher` therefore leaves `SourceKind.CALIBRATION` entries out of the per-entry
  `keys`/`publishers` pass entirely. A function that kept the old name and silently picked one of
  several platforms is exactly the bug this shape exists to prevent.
- **Key expressions only via `pubsubKey()`** in `keelson/Keys.kt`. Never concatenate a key inline —
  the `{realm}/@v0/{entity_id}/pubsub/{subject}/{source_id}` layout is protocol, not formatting.
  **`@v0` is verbatim: no wildcard crosses it**, so any subscription or tooling must spell it out —
  `rise/**` matches nothing and fails silently, `rise/@v0/**` works. `KeysTest` pins the chunk position.
- **QoS comes from `qos.yaml`, via `qosForSubject()`** in `keelson/Qos.kt`. Those profiles are a
  transcription of `../keelson/messages/qos.yaml` — a copy, like the vendored protos. Never hand-tune a
  publisher's priority or reliability here; if a profile is wrong it is wrong upstream first.
  **Thirteen subjects differ from Zenoh's defaults**, across three profiles — six `elevated`
  (`location_fix`, `speed_over_ground_knots`, `course_over_ground_deg`, both headings, and
  `checklist_event`), four `transient` (`audio`, `image_compressed`, `video_compressed`,
  `checklist_presence`), three `background` (`raw_nmea0183`, `log_message`, `checklist_state`) — and
  everything else is unlisted upstream and inherits `default` on purpose, so the same subject travels
  identically from every connector. **Re-verified against `0.6.0-pre.15`, and this time there was
  drift**: the ten *sensor* subjects still match exactly, and the three checklist ones above were
  assigned upstream in `pre.15` having been unlisted in every release before it. That check is a dozen
  lines of script and worth re-running against each release rather than reading this list, which has
  been wrong before. Two subjects are `default` **by decision rather than by omission**, which
  `qos.yaml` now says in words: `checklist_procedure`, a template edited at human pace, and
  `checklist_evidence` — which carries the *same* `foxglove.CompressedImage` as `image_compressed` and
  deliberately takes the opposite stance, because a camera frame is corrected by the next one and an
  evidence photo is a one-shot write nothing will ever republish. Grouping the two by payload type is
  the obvious tidy-up and would make a safety photo the cheapest thing on the link. Upstream now names
  **61** non-default subjects across *six* profiles, having gained `no_drop` (`DATA_HIGH`, **BLOCK**,
  RELIABLE, express) for `scenario_tick_ack`; `QosProfile` transcribes all six and this app publishes
  in neither `realtime` nor `no_drop`. `QosTest` no longer asserts that every profile drops — it
  asserts that nothing *this app publishes* blocks, which is the real hazard since every publish here
  happens on a sensor collector, plus that `no_drop` is the only BLOCK entry.
  The Settings screen exposes a per-subject override on top of that — `policyQosForSubject()`
  is upstream policy, `qosForSubject(subject, overrides)` is what actually gets used. Overrides are an
  escape hatch, not the norm: default them to Auto and keep the policy path the one that works.
- **Liveliness is three tiers, and the app declares two of them** (protocol specification §5, as
  rewritten in keelson `0.6.0-pre.3`). **This inverted the previous rule here** — one token per source
  used to be the whole of it, and a note in this file said declaring one per subject was a misreading
  of the spec. It is now what the spec asks for.
  - **Source tier**, `sourceLivelinessKey()` — `{realm}/@v0/{entity_id}/*/{source_id}`, one per
    producing `(entity, source)` identity. The `*` is literal and sits in the **category** slot, where
    `pubsub` would be, because presence is category-agnostic. That position is load-bearing: §5.5
    classifies a received token by the chunk after the entity, so a key built one chunk along is
    indistinguishable from the legacy shape and silently mis-tiered.
  - **Subject tier** — one token per subject the phone claims, and it has **no key function** because
    the key *is* the publisher's key. `SensorPublisher` declares these from the very map it declared
    publishers from; a separately-constructed key could drift, and a token advertising a key nothing
    publishes on is worse than no token. `subjectLivelinessKeys()` in `keelson/Liveliness.kt` decides
    the set, `runSubjectLiveliness()` holds the tokens.
  - **Legacy coarse token**, `legacyLivelinessKey()` — the old `.../pubsub/*/{source_id}`, still
    declared beside the source tier because §5.7 asks aggregators to read both during the transition.
    Delete it once `entity_health` and crowsnest read the new tiers.
  - **RPC interface tier** — not declared. §3.6's full-interface rule means the token commits the app
    to serving all of `configurable/v1`, which is a decision, not a detail.

  What the subject tier is *for*: upstream's `entity_health` counts a `*` subject chunk as presence but
  **not advertisement**, and `authority.py` then drops those subjects from the coverage denominator as
  a fault in the monitor's own config. Declaring only the coarse token therefore has a perfectly
  healthy phone contribute nothing to a vessel's score.
  Note `authority.py` **moved out of `entity_health` in `0.6.0-pre.7`** (`8a3d057`, "split the
  composite policy out of entity_health") and now lives in a new `connectors/composite_aggregator/`.
  The behaviour above went with it unchanged; only the path to go and read is different.
- **A liveliness token is capability, not activity** (§5.2), and it must **not** be retracted because
  data has stopped. `heading_true_north_deg` keeps its token while it waits for the first fix and
  `log_message` keeps one through a run nobody annotates — silence is not a withdrawal. Only two things
  remove a token: hardware that is not there (`unavailableSubjects()`), and a subject somebody switched
  off, which is a configuration change rather than silence. That second one is why the per-subject
  switches now do something on the wire, having previously done nothing beyond the phone.
- **Subject names only via `Subjects`** in `keelson/Keys.kt`, and the subject *set* only via
  `PublishedSubject` in `keelson/SubjectRegistry.kt`. A new subject means a constant plus a registry
  entry, and the name must already exist in `keelson/messages/subjects.yaml` upstream. **The newest tag is now the
  right place to look** — re-checked at `0.6.0-pre.15`, of which `pre.12` *is* an ancestor
  (`git merge-base --is-ancestor` says so), and every subject this app publishes is present:
  `illuminance_lux`, the four `checklist_*` ones and `checklist_evidence` included. `pre.15` added
  `depth_below_{transducer,keel,surface}_m`, `scenario_event`, `scenario_tick_ack` and
  `envelope_exceedance`, none of which a phone has the hardware or the role to publish.
  **Take upstream files from a tag, not from `../keelson`'s worktree.** That checkout is a working
  repository and is routinely parked on a feature branch: while this was being done it sat on one
  that branched before the checklist merge and has no `Checklist*.proto` at all, so `/sync-protos`
  against it would have reported five vendored files as having no upstream — which reads as "invented
  here" and is false.
  **That was not true for most of this project's life, and the reason is worth keeping.** The lineages
  had forked: `pre.7` *was* `dev` while `pre.5` was cut from a feature branch, they diverged at
  `8621035` with neither an ancestor of the other, and the newest tag carried none of those five
  subjects — so "check the newest tag" would have reported that `illuminance_lux` does not exist
  upstream, which is a subject this app publishes. The lesson that survives the merge: **a tag being
  newest does not make it a superset**, so when a subject seems to be missing, check whether the
  lineages have forked again — `git merge-base --is-ancestor <older-tag> <newest>` — before concluding
  it is not there.
- **Rate ownership resolves by (subject, `SourceKind`), never by subject alone.**
  `PublishedSubject.rateOwnerEntry()` is the one way to get from a derived subject to the one whose
  rate governs it, and it matches the source kind as well as the subject because a subject string does
  not identify an entry: **`location_fix` is published by two of them** — the phone's live fix and the
  platform's surveyed zero point — so `forSubject()` answers with whichever sits earlier in the enum. That
  is the right one today, which is exactly why `SubjectRegistryTest` pins it; reordering the entries
  would silently point the platform's zero at the phone's GNSS. The UI needs an *entry* rather than a
  subject anyway, since `Routes.subjectQos()` is keyed on the entry name. One hop always reaches the
  head — no owner has an owner — and that too is pinned, because `Settings.rate()` does the same
  single hop and a chain would quietly read the wrong subject's rate.
- **Every row states a requested rate, in every state.** `Off · set 1.0`, `Not on this device · set
  5.0`, `32 343 last run · set 1.0` — because the states where a subject is *not* producing are
  exactly the ones somebody is reading while deciding what to ask for. The only exception is
  `log_message`, which is event-driven and has no rate to state; its page says so in words
  (`Setting — not applicable`) rather than dropping the section, which used to leave the one source
  that said nothing at all.
- **A rate is three numbers, and a row that shows one of them lies by omission.** Every subject row
  reads `55.3 Hz · set 50 · max 200` — achieved, requested, ceiling — and the per-subject page spells
  the same three out as **Hardware / Setting / Actual** with a sentence each on what kind of number it
  is. They can all disagree honestly and usually do: a `SensorRate` is a *hint* the platform may beat
  or miss, and Android delivers to every client at the fastest rate any of them asked for, so 55.3 Hz
  out of a sensor advertising 50 is normal rather than a bug. `set max` is a word rather than a figure
  because `SensorRate.Max` is a zero delay — "give me everything" — not the advertised maximum written
  out. A subject riding another's samples takes the owner's requested rate too, via `Settings.rate()`,
  which resolves `rateOwner` for exactly this reason.
- **A subject row's ceiling is four different claims, and `rateCeilings()` keeps them apart.** Every
  source now states the fastest it can go — `55.3 Hz · max 200`, `Not published yet · max ~1.0`,
  `on change` — and the number comes from one of four places, which `CeilingBasis` records because on
  screen they look identical. **`Reported`** is `Sensor.getMinDelay()`, the device's own answer, and is
  *not* a hard cap: Android delivers to every client at the fastest rate any of them asked for, so a
  row reading above its own maximum is normal. **`Imposed`** is a floor this app holds a loop to and is
  exact — the battery and radio poll floors, the audio chunk, the time-lapse interval, the calibration
  republish — and each is **quoted from the provider that enforces it**, never copied, so a ceiling on
  screen and the loop behind it cannot drift. **`Estimated`** is a judgement about hardware that
  answers no query and prints with a `~`; GNSS is the only one, because `FusedLocationProviderClient`
  has no supported-rate API and a 5 Hz chipset will simply beat it. **`OnChange`** carries no number at
  all: `TYPE_LIGHT` reports `minDelay == 0`, meaning it speaks when the reading moves, and dividing by
  that would either crash or promise an infinite rate for a sensor that goes twenty minutes silent.
  A subject that rides another's samples takes its owner's ceiling verbatim — a derived subject cannot
  outrun the callback it is published from. `RateCeilingTest` exercises every branch with the two
  platform lookups stubbed, which is the only way any of it gets checked without owning the phone that
  would contradict it.
- **The rate mode is a two-option selector, not a switch, and that is a correctness point.** A toggle's
  *off* reads as "recording is disabled" when it means "use the configured rate instead of the
  maximum" — the state being chosen is *which rate*, which a `Configured | Maximum` pair says and a
  switch cannot. The section is **Sampling rates**, not "Max rate", because that named one option
  rather than the purpose.
  **`recordAllMax` defaults to true, and an absent `recordRates` entry means the subject's own default
  — not Max.** Those two facts have to move together. When absent-means-Max was the fallback instead,
  the chip read *Configured* while the phone recorded at ten times the megabytes-per-hour printed
  beside it. Note the persistence default has to agree as well: `readSettings` reading
  `RECORD_ALL_MAX ?: false` silently contradicted the data class and shipped a fresh install showing
  the wrong chip.
  Each row states the **consequence** rather than an adjective — `~241 MB/h` at maximum against
  `~23 MB/h` configured, both measured — which is why `Capacity.kt` carries two constants and
  `baseMegabytesPerHour()` picks between them. "Much larger files" is not a number anyone can plan with.
- **Every subject has its own publish rate, and a derived one is capped by the subject it rides.**
  About half the registry carries a `rateOwner`, and those subjects used to have no rate of their own at
  all — all three of `recordRate`, `publishRate` and `ratesCanDiffer` opened with
  `rateOwner ?: subject` and read the owner's entry throughout, so a declination that moves over a day's
  sailing was pinned to whatever the fix published at. `recordRate` still does that and must: one
  listener serves the whole group, so there is nothing per-subject to ask the sensor for. Publishing is
  pure decimation, and `SubjectSink` already held one `PublishDecimator` per registry entry, so nothing
  on the publish path changed — only the resolution in `Settings`.
  It is now three functions rather than one expression, because three different questions were tangled
  in it. **`publishCeiling()`** is the cap: a subject's own record rate where it has a listener (physics
  — no sample exists to send faster), and the **owner's publish rate** where it rides one. That second
  is *policy* and was chosen over the physical limit, which would be the owner's record rate: the
  samples are genuinely there, but a group whose members can each outrun the one they derive from is a
  group nobody can read off the Session screen. Raising past the cap means raising the owner first,
  which is what the page's link to it is for. **`requestedPublishRate()`** is the raw stored value, and
  **`publishRate()`** is `slowerOf` the two.
  Three things are load-bearing. **Absent means "follow the owner"**, not "use my own registry default":
  every derived entry does carry a default equal to its owner's, so a fresh install cannot tell the
  difference — but an install that had tuned `location_fix` down would find speed and course silently
  jumping back on upgrade. **The clamp happens on read, never on save**, so an owner lowered for one
  trial and raised again brings the whole group's tuning back — the same argument `recordAllMax` makes
  about being a mode over the maps, and clamping on save would additionally mean walking every derived
  subject each time an owner moved. And **the editor binds to `requestedPublishRate`, not
  `publishRate`**: a field showing the clamped value cannot be typed into, since 5 Hz against a 1 Hz
  ceiling redraws as 1.0 and saving then stores the clamp — opening the screen would destroy the
  request. That round trip was already wrong for subjects clamped to their own record rate.
  On the page the off-state is **"Follow *owner*", not "Maximum"** — the ceiling is another subject's
  configured rate, not the hardware's — and it stores **null**, which `MainActivity` turns into a key
  *removal*. Writing the owner's current rate instead would freeze the subject at today's number rather
  than leaving it following. A record rate is likewise not written for a derived subject: `recordRate`
  reads the owner's entry, so a key there is one nothing ever reads back.
  Measured on a Pixel 6: with `location_fix` at 1 Hz and `course_over_ground_deg` set to 0.2, the row
  reads `0.2 Hz · rec 1.0 · pub 0.2` while position, speed and declination stay at 1.0 — one `Location`
  callback, four different wire rates.
- **Two rates per subject: `recordRate()` fills the file, `publishRate()` feeds the bus.** The file is
  what analysis is run against and defaults to `SensorRate.Max`; the wire is for watching a trial and
  keeps the per-subject rate that used to be the only one — `sensorRates` still uses its `rate_*`
  DataStore keys precisely so existing settings carry over as the *publish* rate. The sensor is
  registered at the **record** rate, because it is the higher and the publish side can only thin what
  arrives. `rate()` is retained as an alias for `publishRate()`.
  **`Max` is only offered where something samples on its own clock** — `recordsContinuously()` in
  `Settings`. A poll at "max" would spin against the telephony and power APIs; `audio`'s rate is a
  chunk length and the camera's a capture interval; `illuminance_lux` and `imu_temperature_celsius` are
  on-change and held on a ticker, so Max there would repeat one unchanged reading ten times a second
  and call it data. For those, record and publish are the same number and the row shows one rate.
  **Publish is clamped to record, never validated against it**: no sample exists to send faster than it
  is sampled, and it is not a combination anybody can see is impossible.
  `recordAllMax` / `publishAllMax` are a **mode layered over the maps, never a bulk edit** — flipping to
  full rate for a trial and back has to return the tuned profile intact.
  Measured on a Pixel 6, read back out of the `.mcap`: `air_pressure_pa` records at 25 Hz and publishes
  at 1 Hz; `angular_velocity_radps` records at 442 Hz against an advertised 416.
- **The decimator lives in `SubjectSink.emit()`, with everything else that gates a sample.** `wrap()`
  is called first and always — that is the file's copy — and only then is the publish considered. It
  used to be evaluated as the *argument* to `publish`, which made recording depend on the publish
  cadence as well as its outcome. Two consequences worth keeping: a decimated sample returns
  `Result.success` rather than null, because null means *the subject is off* and callers use it to skip
  work a real sample still needs (the fix that feeds the map, the frame that feeds the thumbnail); and
  the **outbox moved to the publish side**, since buffering at the record rate would have a replay push
  samples onto the bus faster than the live stream ever ran. Decimating per sink is also what keeps the
  shared listeners right — eight subjects ride one `Location` callback.
- **`PublishedSubject.featured` is what the live view shows under "Basic".** Eight subjects — the fix,
  speed, course, true heading, horizontal accuracy, fix quality, air pressure and charge. It defaults
  to false, so a new subject appears under **All** and nowhere else, and `SubjectRegistryTest`
  transcribes the set so adding an operational one is a decision rather than an omission. It is not a
  switch and not a rate: a subject left out of Basic still publishes, is still recorded, and still
  counts towards its group's health badge.
- **`Subjects` is wire names; `PublishedSubject` is the *sensor* set.** They are usually the same
  list, and the four `checklist_*` subjects are the exception: they are in `Subjects` and deliberately
  **not** in the registry. Everything derived from the registry — rates, `SensorManager` types, MCAP
  channels, live rings, the main screen's rows, the per-subject switches, `McapSchemasTest` — is about
  a sensor producing samples. A subject a person taps does not belong there, and putting it there grows
  a phantom sensor card with a rate control.
- **Everything on the wire is wrapped.** Payload → `enclose()` → `core.Envelope` bytes → `put()`.
  A raw payload on the bus is a bug; consumers unwrap the envelope first.
- **Sensors are `callbackFlow`.** Register the Android listener inside, unregister in `awaitClose`.
  That pairing is what keeps sensors from leaking when publishing stops.
- **Explanation lives behind ⓘ; only four kinds of text stay on the page.** `SectionHeader(title,
  onInfo = …)` opens an `InfoDialog`, and where a screen has no section header the icon goes in
  `ScreenScaffold(actions = …)`. The rule, applied to every string so the next person can extend it:
  **documentation** — how or why something works, the same on every run — moves; **consequence**
  (restarts the run, cannot be undone, publishes your name to other stations, costs disk or battery),
  **state** (a count, an error, why a list is empty, *why a control is disabled*) and **instruction**
  (the only thing telling you what to do at this step) all stay. One-line descriptions that label a
  control stay too, including every `SettingSwitch(description = …)`: a switch reduced to a bare title
  is not compact, it is unreadable.
  The trap is the mixed sentence — the audio codec rationale sits with its MB/hour figure, the entity-id
  helper doubles as an error slot — so those split rather than move wholesale. And hiding a control's
  *only* label is the failure this can cause: the calibration screen's forward-axis paragraph stays
  precisely because it is the only thing distinguishing `Baseline`, `Compass` and `Type`. Two controls
  had to *gain* a line during the pass for the same reason — the disabled Baseline button now says a
  zero point is needed, and the Severity dropdown gained supporting text.
  Do not add an ⓘ where nothing was moved: an icon on every heading trains people to ignore all of them.
- **Compose screens take data and lambdas**, never a repository or a `Context`. There are no
  exceptions left — `MainScreen` takes a `PublisherStatus`, and anything needing a `Context`
  (`sensorCapabilities()`, the file picker, the osmdroid `MapView`) is resolved in `App()` in
  `MainActivity` and handed down. `LiveScreen` takes its map as a `@Composable (Modifier) -> Unit`
  for exactly this reason.
- **The top bar is pinned, centred, and carries the run status on every screen.** Logo or back arrow
  left, view name centred, status right — and the status is the reason the bar no longer scrolls away.
  It used to use `enterAlwaysScrollBehavior` on the argument that a long list should not spend a row on
  a name you already know, which was right while the bar carried only a name. Now it answers "is this
  phone publishing, and is it recording", which is worth seeing at any moment, and a status that leaves
  on the first downward flick is not one.
  **The status is a `CompositionLocal`, not a parameter** — `LocalRunState`, provided once in `App()`.
  The bar is shared chrome, and threading the publisher's connection state through fourteen screen
  signatures to reach it would put a run's state into the argument list of the platform editor. It replaced
  two separate chips that had already drifted (`ConnectionChip` said `Idle`, `LiveChip` said `IDLE`),
  which is the other thing one shared implementation buys.
  **Two lamps, `PUB` and `REC`, not one word.** A single label had to pick between them by priority, so
  a recording run said `REC` and stopped saying anything about the link — hiding the more important of
  the two, since whether samples reach a router is in doubt and whether the file is being written is
  not. Only `REC` blinks, and only while a file is actually being written.
  It is also the *only* place either is stated. A second blinking `REC` lamp used to sit beside the Stop
  button, and had to while the bar still used `enterAlwaysScrollBehavior` and left the screen on the
  first downward flick — on a page of thirty-nine subject rows that could scroll the answer to "is it
  still recording" away. Pinning the bar made the copy redundant, and a fact stated twice on one screen
  teaches the eye to trust neither.
- **Colour is a traffic light and means one thing everywhere: green fine, amber warning, red error,
  blue general information, grey off.** That is why `StatusTone.Positive` is `signalGreen()` rather than
  the app's `primary` blue — blue is information, not approval — and why **recording is green rather
  than the conventional red**: red here means something is wrong, and a healthy recording is the
  opposite of that. `connectionColor()` is the one function that decides the link's colour, so the top
  bar and the status card cannot drift apart. Colour never carries a state alone: every lamp has its
  word beside it, which is what survives sunlight and a colourblind reader.
  **The Stop button is the one large block of red in the app, and it is an exception on purpose.** The
  traffic light governs *readouts* — a lamp has to be trusted at a glance. Stop is an action, and the
  destructive half of a pair: Start is a filled primary button, so an outlined Stop read as the lesser
  of the two when it is the one that ends a run and closes the file. It uses `error` over `onError`
  rather than a hand-picked pair, because those two tokens are *defined* as a legible combination in
  both themes — 7.7:1 on this phone's dark theme (a light red field with near-black-red text) and 6.5:1
  in light, where it flips to a strong red field with white text instead of becoming two dark reds
  nobody can read. The stop glyph takes `LocalContentColor` rather than naming a colour, so it and the
  word cannot end up different reds.
- **The live view is an instrument, not a control panel, and the hierarchy is deliberate**: chart →
  the three navigation values → data quality → the sensor groups. Four decisions hold it together and
  each has a failure it exists to prevent.
  **The heading line takes the layer's own ink and has no halo** — white over imagery, black over map
  tiles, from `chartInk()`, which the attribution also reads at 70% alpha so the two cannot disagree
  about which layer is which. It is the one thing drawn here without a white under-stroke, and that
  follows: a halo gives a *coloured* line contrast its hue cannot provide, and this line has no hue to
  keep — it is simply the opposite of whatever is underneath, so a halo would be outlining white in
  white. The course vector keeps its halo, being blue on both layers. Note the halos used to be drawn
  before *both* lines for a reason that has gone with it: the two share an origin, and a heading halo
  painted afterwards notched the course line exactly where the eye starts reading.
  **The heading line is twelve nautical miles, and therefore scales with the chart.** A fixed pixel
  length is a different distance at every zoom, which is the one thing a heading line must not be, so it
  goes through the same `metersToPixels` the accuracy circle uses. At working zoom the twelve miles run
  off the screen and it reads as a ray — which is what a chartplotter's heading line looks like — and
  zoomed out far enough to see twelve miles, it ends where it should. Verified by measuring: at zoom 10
  and 57.4°N the ground resolution is 82.3 m/px and the drawn line came to 262 px, i.e. 21.6 km against
  the 22.2 expected, the shortfall being the colour threshold clipping its antialiased ends. Twelve *km*
  would have been 146 px, so the two are not confusable.
  It is clamped to the canvas diagonal, which changes nothing visible: at zoom 16 the line is about
  17 000 px and at zoom 20 nearer 280 000, all of it clipped but handed to `drawLine` first. The
  **course** vector is deliberately still a fixed pixel length — it says which way the boat is moving,
  not how far it will get, and a distance there would imply a prediction this app does not make.
  **Everything drawn on the chart carries a white halo**, and that is what makes it work on more than
  one base layer. The course vector, the heading vector and the track are all drawn twice — a wider
  white stroke, then the coloured line on top — because a dark blue course line is perfectly legible on
  the standard map's pale tiles and nearly gone on Esri's imagery, which is dark green forest and darker
  water for most of a Swedish coastline. There is no single colour that works on both: imagery covers
  snow and asphalt too, and an imported offline archive could be anything. With a halo the contrast comes
  from the drawing rather than from the background, so it holds on any tile — and on pale tiles it simply
  disappears, where the colour already had contrast. `positionEdge` had always done this for the dot; the
  vectors and the track now do it too. Two details are load-bearing: **both halos are drawn before either
  line**, since the two vectors share an origin and always overlap near the dot — halo-then-line twice
  paints the heading's white stroke across the course line and leaves a notch at exactly the point the
  eye starts reading from; and the track needs a **second `Polyline`** underneath rather than a paint
  list, because osmdroid gives an overlay one outline paint.
  **The chart's controls are icons in one hugging container**, not chips. Three `FilterChip`s in three
  translucent surfaces took a strip about as wide as the position readout. Note the container must size
  to the *icons*: a "Following" pill inside it made the whole toolbar as wide as the pill, with two
  small icons rattling around a dark panel — worse than what it replaced. The word went entirely and the
  follow icon carries its own selected fill, which is right for a *control*: the rule that colour never
  carries a state alone is about readouts, where a lamp has to survive sunlight and a colourblind
  reader. A toggle is pressed and responds, and the word survives in its content description. The four
  glyphs are
  hand-declared `ImageVector`s in `ui/MapIcons.kt` on the standard 24x24 grid: only `material-icons-core`
  is on the classpath and it has none of `MyLocation`/`Layers`/`Fullscreen`.
  **A position and its accuracy are attached to the chart, inside the same rounded surface**, because
  the eye otherwise went from tiles straight into body text with nothing marking the boundary. And
  `FixLine` has *three* states: a stale fix says `Last known position · 12 s ago` in words above the
  same numbers, since `±11 m` beside a coordinate reads as current whatever its age. Staleness comes
  from `subjectHealth(...) == Stalled` — the app's one rate-aware definition — never a threshold
  invented at the call site, which would eventually disagree with the GNSS heading three rows below it
  reading the same status.
  **`No fix` in the vitals row is not the same fact**, and the two must never be merged: that is
  `location_fix_quality` saying the receiver is not solving, which it does while a perfectly current
  *fused* position derived from wifi and cell keeps arriving. A position can be fresh and unsolved, or
  solved and old.
  **`SOG` / `COG` / `HDG T`, and bearings are zero-padded through `formatBearing()`.** Three readings
  centred in three columns will shove each other about as a course steps 9 → 10 → 100, and a readout
  that twitches while the phone turns reads as unreliable whatever the numbers say. The degree sign goes
  *in the figure*, at the figure's own size — set at `labelMedium` on the baseline of a 32sp number it is
  a few pixels across, sits exactly where a full stop sits, and `000°` reads as `000.`. Every other unit
  is a word and is correctly quieter than its number. The captions are abbreviations and the
  `readAsOneItem` descriptions are not, so nothing is lost to a screen reader.
  **Colour is spent only on the abnormal.** `VitalsLine` is a labelled value each rather than one
  run-on `No fix · 0 sats · SINR 13 dB · 100 %` string, and the healthy tone is `onSurface`, not green —
  six green statements of the obvious compete with the one reading that matters. Same reason the health
  chips lost their dot when healthy: six coloured dots along the bottom read as a legend for a chart
  that is not there. `gnssQuality`/`cellularQuality`/`batteryQuality`/`fixKindQuality` in
  `ui/LiveSignals.kt` decide the tones and are pinned; the satellite count is deliberately never
  coloured, because a fused fix indoors solves with none and the GNSS verdict beside it already says so.
  Figures stay figures — `13 dB`, not "Good" — for the same reason `~241 MB/h` beat "much larger files".
  **The chip row is no longer the plot list.** `Platform calibration` is filtered out of it: geometry surveyed
  once and republished on a ten-second loop is configuration the phone is announcing, not telemetry it is
  measuring. The group keeps its plot section, so a stalled republish loop is still visible somewhere.
  **What the chart draws over its base layer is one value, `ChartMarks`.** Sea marks, the track, the
  heading line and the course vector, each a tick rather than a choice — they are overlays, so any
  combination is legal. As four booleans they would be eight parameters through the live screen, its
  toolbar and the menu, and ten the next time somebody adds a mark; as one value the menu hands back a
  `copy()` and knows nothing about what each flag reaches. **The position and its accuracy circle are
  deliberately not switchable** — a chart with no "you are here" is not a chart.
  Two details. The ticks **do not close the menu**, unlike a layer choice: turning two marks off is one
  errand, and re-opening between them would make it two. And `MainActivity` holds four separate
  `rememberSaveable` booleans rather than one `ChartMarks`, purely because there is no saver for an
  arbitrary data class — losing which marks are on to a process death is the sort of small wrongness
  that reads as the app forgetting things. The value is assembled at the call site.
  **A layer that needs a key it has not got is shown, not hidden — with a gear instead of a tick.**
  `MapLayer.needsKey` drives it: the row keeps its place in the menu, says "Needs a MapTiler key", and
  tapping anywhere on it opens Settings rather than selecting it. Selecting a layer that cannot fetch a
  tile is not a choice, it is a dead end — the chart would go blank with nothing on screen saying why,
  the same failure as opening on an uncovered satellite grid. **Satellite is deliberately not marked**,
  because it falls back to Esri and therefore always draws.
  `chartInk()` is exhaustive over `MapLayer` on purpose rather than defaulted: a layer added later is
  one whose background nobody has looked at, and the compiler asking beats a white heading line
  vanishing into a white sea.
  **The satellite layer is MapTiler where a key is set and Esri where it is not**, and the fallback is
  the point rather than a leftover: satellite is the *default* layer, so a keyless install would
  otherwise open the Live tab on a blank grid, which reads as a broken app. `Settings.mapTilerKey` is a
  per-phone field, never in the repo and never in the APK — the same stance the mTLS credentials take,
  and for the same reason, a debug build gets passed around. A settings profile **can** carry it, unlike
  the five install-identity fields — a tile key is a shared credential rather than an identity, so a
  fleet provisioning from one file is legitimate — but it is **off by default**, behind `withSecrets` on
  `Settings.toProfile()` and a tick in `ExportProfileDialog`. The default is what changed: an export
  lands in `Downloads/Logline/config`, which other apps can read and a cloud client syncs, and it is the
  file people forward, so the common errand of handing over endpoints and rates must not quietly include
  a key somebody is billed for. **The operator identity is gated by the same flag**, because import had
  a `withOperator` question and export — the end that matters, since it cannot know where the file goes
  — had none. Note the gate does not apply to *backup*: `files/datastore/` still carries the key to
  Google Drive, which `data_extraction_rules.xml` now states in words, because excluding the datastore
  would defeat the point of restoring settings at all.
  Note `SettingsProfileTest` does *not* catch a new field on its own; it is a hand-written list of
  assertions, not a reflective one, so CLAUDE.md's old claim that a new field fails it until somebody
  decides which side it belongs on holds only if the fixture is updated too. Its round-trip helper now
  passes `withSecrets = true` deliberately: with the default it would still have passed, because
  `applyProfile` falls back to the phone's own value for an absent key, so the assertion proved nothing.
  `a default export carries no credential and nobody's name` is the one that pins the default.
  **Six base layers, and Ocean is the one that earns its place.** MapTiler's bathymetry — depth
  contours and soundings under a plain land mask — is the only one that says what is under the hull
  rather than where the shore is. Topographic covers terrain ashore, Outdoor adds trail and cycle routes
  over it, and Streets is the plain road map. Outdoor and Streets were left out at first as too close to
  the OpenStreetMap layer; seen side by side on the phone they are not — Outdoor draws long-distance
  trails OSM's own rendering does not. **New layers are appended, never slotted in**, so the positions of
  the ones already in the menu do not shift under somebody who knows where they are. Note the two URL
  shapes — raw tilesets are `tiles/{id}`, rendered styles
  are `maps/{id}` — and that every endpoint returns **512px** tiles, both verified against the service
  rather than taken from the documentation.
  **How far the chart zooms is per source, and each answer came from what the service does.**
  OpenStreetMap 404s past 19, so osmdroid's approximater has something to work with — verified, zoom 21
  draws building footprints upscaled from 19, soft-edged, with the position and heading line sharp on
  top; it is the only source given the extra levels. **Esri does not fail past 19; it serves a grey
  "Map data not available" tile**, which is a tile as far as the provider is concerned, so there is
  nothing to approximate from and over-zooming buys a grey field rather than a blurry one — it is
  capped at what it serves. **MapTiler over-zooms server-side to 22**: measured with a real key over
  Onsala, z19–z22 all return 200 with real content, shrinking in size as their own upscaling kicks in,
  so it declares 22 itself and needs no help. Worth knowing before reading a blank chart as a bug:
  Esri's declared 19 is a global maximum and its *coverage* over the Swedish coast runs out earlier,
  which is the limitation a MapTiler key exists to lift.
  **The chart opens on satellite, over Gothenburg.** Imagery is the right default for a tool used on
  the water: the standard map's value is street names and building outlines, and there are none at sea —
  what a track is read against is the shoreline, the shoals and the jetty being approached. It costs no
  more, both being online tiles fetched only for what is on screen. The *centre* had to move with it.
  osmdroid with no centre set opens at 0°N 0°E, which OSM covers with a plain blue ocean tile and Esri
  does not cover at all — so the satellite default's first screenful was a grid reading "Map data not
  yet available", which reads as a broken map rather than as a missing fix. `HOME_CENTRE` /`HOME_ZOOM`
  in `TrackMap.kt` is a placeholder at regional zoom, and the first fix sets **both** the tracking zoom
  and the centre, so a phone that has not solved yet shows the home water rather than a random street in
  a city it is not in.
  **Attribution is a condition of use, not a design element.** `CopyrightOverlay` defaults to 12dp black
  (`paint.setTextSize(dm.density * 12)`; `setTextSize` takes dp), which competed with the readouts and
  was near-invisible on dark imagery besides. It is set to 9dp and coloured per layer — near-black on map
  tiles, white on satellite — both at ~70% alpha. The *text* needs no wiring: `draw()` re-reads the notice
  from the current tile source every frame. The colour does, from the `update` lambda.
- **Five screens are tabs; everything else is pushed.** `TopLevel` in `ui/components/Screen.kt` names
  them — Session, Live, Events, Files, Setup — and they are the only destinations that carry
  `LoglineNavBar`. Files is the recordings list, promoted out of Setup because the saved files are what
  this app produces rather than something it is configured with.
  The bar goes into `ScreenScaffold`'s **existing `bottomBar` slot**, which is also where `FormActions`
  lives on the five form screens; the two cannot collide because no screen is both. **The start screen
  stacks a third thing into that same slot** — `Actions`, i.e. Start, or Live/Mark/Stop while a run is
  going — pinned *above* the bar in a `Column`, because a page thirty-nine subjects long put the one
  control the screen exists for a scroll away. Stack into the slot; do not add a second `Scaffold`. There is still
  exactly one `Scaffold` in the app, which is what keeps `enableEdgeToEdge()` insets applied once —
  nesting a second one put a band of dead space above every title, and that is why `MainActivity`
  deliberately has none. Tab switches go through `goToTab()`, whose
  `popUpTo(start) { saveState = true }` + `restoreState` + `launchSingleTop` is what stops tab-hopping
  accumulating back-stack entries and what lets Live keep its scroll. **`main` must stay the start
  destination**: system back on any tab pops to it and then exits.
  **The two Zenoh sessions are still scoped by route *prefix*** — `startsWith("checklist")` and
  `startsWith("calibration")` — so moving Platforms and Checklists under Setup changed nothing there, and
  `setup` collides with neither prefix. Anything that renames a route has to be re-checked on a device
  against those two, because a broken prefix match fails *silently*: the screen still opens and simply
  never finds anything on the bus.
- **`formatCount` groups digits with U+202F, a narrow no-break space**, so a figure never wraps
  mid-number. It bites when writing tests: an expected `"95 317"` typed with an ordinary space fails
  against a real `"95\u202F317"`, and the two are indistinguishable in the failure message — JUnit
  prints `expected:<95[ ]317> but was:<95[ ]317>`. Spell it `\u202F` in a test literal rather than
  pasting the character, or pick a figure under five digits, which `formatCount` leaves ungrouped.
- **Numbers are formatted with `.fmt()`**, the `Locale.ROOT` helper in `ui/Format.kt` — never Kotlin's
  bare `"%.1f".format(x)`, which uses `Locale.getDefault()` and prints `55,3` on a Swedish phone. The
  same rule is why `Double.json()` in `calibrate/PlatformGeometryJson.kt` goes through `BigDecimal`.
  `FormatTest` pins it by setting the default locale to `sv-SE`.
- **Kotlin official code style** (`kotlin.code.style=official`), 4-space indent, trailing commas in
  multi-line argument lists. Match the file you're editing.
- **Dependencies go in `gradle/libs.versions.toml`**, referenced as `libs.*`. No inline coordinates
  in `app/build.gradle.kts`.

## Vendored protobuf — do not edit

`app/src/main/proto/` contains byte-identical copies of upstream Keelson definitions. Java/Kotlin
lite bindings are generated at build time into `app/build/generated/source/proto/`.

| Local | Upstream |
| --- | --- |
| `Envelope.proto` | `../keelson/messages/Envelope.proto` |
| `Primitives.proto`, `Decomposed3DVector.proto`, `Audio.proto`, `LocationFixQuality.proto` | `../keelson/messages/payloads/` |
| `Checklist{Event,State,Presence,Procedure}.proto` | `../keelson/messages/payloads/` |
| `ErrorResponse.proto` | `../keelson/interfaces/` — **not** `payloads/`, the only one from that directory |
| `foxglove/{LocationFix,Quaternion,Vector3,CompressedImage,Log,FrameTransform}.proto` | `../keelson/messages/payloads/foxglove/` |

Editing these locally forks the protocol silently. To take an upstream change, copy the file over
(`/sync-protos` does this) and rebuild. To *change* a message, change it in the `keelson` repo first.

Generated Kotlin lives in packages `core`, `keelson`, and `foxglove` — note that `EnvelopeOuterClass`,
`Primitives`, and `Decomposed3DVectorOuterClass` are the outer class names the imports use.

## Adding a new published subject

`PublishedSubject` in `keelson/SubjectRegistry.kt` is the **single source of truth** for the subject
set — status keys, rate defaults, sensor types, source ids, persistence and the UI row list all derive
from it. There used to be ten hand-maintained lists and six of them failed *silently* when missed; that
is what the registry exists to prevent, so resist adding a parallel list.

1. Confirm the subject exists in `../keelson/messages/subjects.yaml` and note its payload type. If it
   doesn't exist, it belongs in the `keelson` repo first — don't invent subject names here. Look it up
   in `../keelson/messages/qos.yaml` too: if it is listed under a profile other than `default`, add it
   to `policyQosForSubject()` in `keelson/Qos.kt` (e.g. anything `image_*` is `transient`, i.e.
   BEST_EFFORT).
2. Copy the payload `.proto` into `app/src/main/proto/` if it isn't vendored yet.
3. Add the constant to `Subjects` in `keelson/Keys.kt` and the entry to `PublishedSubject`. Set
   `sensorType` if it comes off `SensorManager`, `source` to whichever id names the hardware, and
   `rateOwner` if it rides another subject's samples rather than having a rate of its own. Add its name
   to the expected set in `SubjectRegistryTest`.
4. Add a sensor flow in `sensors/` if there is no source for it yet — `callbackFlow`, listener
   registered inside, unregistered in `awaitClose`. **If keelson's unit differs from Android's, add the
   conversion to `sensors/Units.kt` with a test against a known constant.** Most of them do differ.
5. In `SensorPublisher`, add a `runX()` collector and start it with `supervised(name, subjects) { ... }`;
   the publisher is already declared for you from the registry. Stamp the payload with the
   *observation* time from the sample itself — `SensorClock.epochNanosNow(sample.elapsedNanos)` for
   anything off `SensorManager` — not `Instant.now()`. Publish through **`SubjectSink.emit()`**, never
   `session.publish` directly: `emit` is where the per-subject switch, the recorder, the replay outbox,
   the live store and the sample counter all hang off one call, and a collector that goes around it
   publishes a subject nobody can switch off.
   **Then add the entry to `COLLECTOR_GROUPS` in `publish/CollectorGroups.kt`** — one group per sensor
   stream, listing every subject that rides it. `CollectorGroupsTest` fails if an entry is missing,
   which is the point: without it the subject's switch would still stop its samples, so nothing would
   look broken, while its listener stayed registered for the whole run.
6. Add any new Android permission to `AndroidManifest.xml` *and* the runtime request path — the
   location permission gate in `SensorPublisher.runLocation()` is the pattern to copy. A `uses-feature`
   should be `required="false"`: a device without the sensor should publish fewer subjects, not fail to
   install.

Nothing else needs touching — `MainScreen`, `SettingsRepository`, `Settings.defaultRate()`,
`sensorCapabilities()`, the per-subject switch and the notification total all read the registry.

## Checklists

A shared checklist that several sites work at once, interoperating with crowsnest's checklist app
(`../crowsnest-dev/src/apps/checklist/`). Lives in `checklist/`, and touches almost nothing else.

- **The protocol was not upstream, and now it is — including the hard half.** `keelson.ChecklistEvent`
  / `ChecklistState` / `ChecklistPresence` once existed only as generated JS in the git-ignored
  `../keelson/sdks/js/dist/`, built from `.proto` files nobody committed; they were reconstructed from
  those encoders and contributed. All five are released files as of `0.6.0-pre.15`, and the vendored
  copies are re-synced to it. `ChecklistWireTest` still pins them against golden bytes produced by
  crowsnest's own bindings, and that test is the only thing standing between a field-number slip and a
  message that decodes cleanly into the wrong fields.
  **Its six golden assertions split by direction, and the difference is load-bearing.** The three
  *decode* ones must pass untouched forever: bytes crowsnest produced still decode field for field,
  which is the proof that everything `pre.15` added is additive or an identifier rename. The three
  *encode* ones pass only because proto3 omits empty strings, zero enums and empty repeated fields —
  `encodeSnapshot` writes ten more fields than it used to, and a fixture left at its defaults still
  encodes to the same bytes. **Do not "improve" those fixtures** by filling in `runId` or `status` to
  make them look realistic: that changes the encoding, and the tempting fix — regenerating the golden
  — re-pins it to this app's own output and destroys the only cross-implementation oracle in the file.
- **§7 of the protocol specification is now normative, and it cites `ChecklistSync.kt` by name** as one
  of its two as-built reference implementations (crowsnest's `useChecklistSync.js` is the other). The
  merge rules that used to live in proto comments are binding, so a rule broken here is a rule broken
  against a published specification rather than a local bug. `ChecklistMergeTest` is a test per rule,
  each quoting the one it pins, and the one that matters most is the last: the same three snapshots
  applied in all six orders must converge, which is §7.2's whole claim.
  §7.3's storage table is the other half, and it is not decoration — `checklist_state` exists *only*
  so a late joiner can bootstrap, so with no storage behind it a station joining between two 30 s
  ticks sees nothing, which looks exactly like an empty checklist. Two deployment mistakes upstream
  records as already having cost real debugging time, both silent: **a `memory` volume answers a
  single-key `get` and returns nothing for a wildcard**, which is what bootstrap uses; and a storage
  whose key expression names the wrong realm or entity persists nothing and says so nowhere.
- **`event_count` is a staleness hint, never arbitration**, and the guard that treated it as one has
  been deleted. `applySnapshot` used to open with `if (snapshot.eventCount < current.eventCount)
  return state`; §7.2 and §7.4 forbid exactly that, because the count is a scalar — two sites that each
  applied a *different* twelve events both hold 12, each discards the other as stale, and neither ever
  converges. What made the guard look necessary was that the merge beneath it was a whole-`ItemProgress`
  assignment, so an older snapshot genuinely could flip a completed item back to pending. The
  protection moved from rejecting the message to merging it correctly, which is the only version that
  converges. Do not put it back.
- **Procedure *definitions* travel on the bus; nothing else knew that.** An event names an item by id
  and carries no text, so a client with no definition can say something was completed but not what it
  said. Crowsnest seeds its library from a hardcoded constant per browser. `checklist_procedure` fixes
  that — one key per procedure, held by the router's `storage_manager`, read with a single `get` on
  join. `STARTER_PROCEDURES` is crowsnest's library transcribed **with its ids**, offered for publishing
  only when the query comes back empty; the ids are what make the two sides agree, so changing one does
  not rename anything, it makes them stop agreeing silently.
- **The router needs storages, and the ones it had were for something else.**
  `../keelson-router/docker-compose.keelson-router-rise.yml` now covers
  `rise/@v0/*/pubsub/checklist_procedure/*` and `.../checklist_state/*`. The pre-existing
  `rise/@v0/*/pubsub/checklist_{state,controller}` storages match nothing any client publishes.
- **The tree moved on 2026-08-26: `crowsnest/@v0/checklist` → `rise/@v0/roc1`.** `crowsnest` is an
  application, not a deployment; `roc1` is the operations centre's entity and names the TREE, not a
  station — which site an operator sits at is already the source id. `DEFAULT_CHECKLIST_REALM` /
  `DEFAULT_CHECKLIST_ENTITY` in `config/Settings.kt` follow it, but **a phone with a saved profile
  carries the old values forward** and will talk to a tree nobody else is on, which looks exactly
  like working. Check Settings on any phone that was configured before that date.
  Note that `command:` is a folded YAML scalar — a `#` line inside it is an argument, not a comment.
- **`ChecklistSync` owns its own session**, opened when a checklist screen is up and closed when it is
  not, keyed on `route.startsWith("checklist")` so stepping between the list and a procedure does not
  cycle it. Two Zenoh sessions in one process are fine; `initZenohLogOnce()` already guards the one
  thing that may only happen once.
- **Progress is keyed on the *run*, not on the procedure.** A procedure is a template and each
  execution of it is a run with its own id, progress and history; §7.3 makes `checklist_state/{run_id}`
  one key per run, forever. Keyed on the procedure, two concurrent runs collapsed into one row with
  one run's ticks landing on the other's — and the live bus had five concurrent runs.
  **The migration is by construction, not by a migration.** `runIdOf()` resolves an empty run id to
  the procedure id, which is what upstream instructs for a publisher predating the run model, what
  crowsnest's `runIdFor` already did for this app's events, and what `decodeSnapshot` was doing
  anyway — so records this phone persisted before the re-key decode into a legacy run keyed exactly
  where they were. `ChecklistState.openRunOf(procedureId)` is the hop for callers that still name a
  procedure because they predate runs: a reminder firing, a notification deep link. Reminders stay
  procedure-keyed on purpose — they are phone-local and about the template — and the receiver resolves
  the run *at fire time*, so one set before a run started still finds it.
  A run id and an evidence id are **single tokens by construction**, and `ChecklistKeys` refuses a
  composite one outright: §7.3 names that as a silent failure, since a key with a slash publishes
  without error and never persists, a storage's expression matching one token. `checklistId()` is what
  generates them.
- **The phone publishes `checklist_state` now, having only ever consumed it — and only for runs it
  created.** It has to, once it can create a run: that subject exists solely so a late joiner can
  bootstrap, so a run this phone started and never snapshotted is invisible to every station that was
  not listening and unrecoverable afterwards, because nothing else will ever write that key. The
  restriction is what makes it safe. §7.4 is blunt that **storage does not merge, it keeps the last
  value**, so a joiner reads one arbitrary writer's snapshot and a subset writer would silently hand
  it the subset; partitioning the writer set by creator is the nearest thing to upstream issue #204's
  one-key-per-writer that needs no payload change, and it is the partition crowsnest uses. Note
  `created_by`/`created_by_site` name *who created the run*, never who last republished it — crowsnest
  made that substitution and, because it also chose which runs to republish from those fields, the
  publish duty silently migrated with the label. A wrong label is a display bug; a wrong publisher is
  a convergence bug. A `put` rather than a declared publisher, because the key set is unbounded and
  one publisher per run would leak declarations for the life of the session.
- **Evidence photos go on `checklist_evidence/{evidence_id}`, and this app can attach but not fetch.**
  Bytes first, then the event, because the event advertises a key and publishing it first would have
  every station render a tile for a photo that was never sent. Fetching somebody else's needs a Zenoh
  `get`, whose reply aborts the process on this binding, so a remote photo renders from its metadata —
  which is what `ChecklistItemEvidence`'s width and height are for. The import path is
  `calibrate/importPhoto`, shared with the platform editor so there is one scale-rotate-re-encode path
  in the app; `format` carries the **full** media type here (`image/jpeg`) rather than the camera
  path's `"jpeg"`, because upstream requires it echoed so a recovered blob is self-describing.
  `filesDir/checklist-evidence/` is excluded from **both** backup files — the opposite of `platforms/`,
  and for the opposite reason: a platform photograph is configuration nothing can rebuild, this is a
  copy of bytes the router holds durably, and a run's worth would fail the whole 25 MB backup.
  **A lost evidence publish is unsolved and undetectable from either end** (§7.4): every profile is
  DROP, so a shed publish is gone with no ack, nothing republishes it, and the snapshot goes on
  rendering a tile for it. The UI must not imply otherwise.
- **Its store is pushed, not pulled — the opposite of `LiveSampleStore` and `AnnotationLog`.** That rule
  is about the *publish path*: ~217 sensor samples a second must not drive recomposition. Nothing on the
  publish path feeds `ChecklistStore`. It is fed by taps, at both ends of the link, and a ticker there
  would only add latency to a tap. Updates still go through `MutableStateFlow.update`, because a Zenoh
  thread, a heartbeat coroutine and the UI all write it.
- **A flag is a record, not a current value, and the five scalars are its cache.**
  `ItemState.flags` carries the whole raise-to-resolve cycle; `flagged`/`flag_reason`/`flagged_by`/
  `flagged_by_site`/`flagged_at` are derived from it by `ItemProgress.withFlagCache()`, which every
  write to the list goes through so the two cannot disagree. The direction is one-way and that is what
  keeps the cache honest: a non-empty list overrides whatever a snapshot claims the scalars are, and
  the scalars are authoritative **only** from a publisher that sends no list. Both go on the wire until
  the release that stops writing the scalars, because crowsnest still reads `flagged`. The deliberate
  consequence: once one station is on `flags`, a legacy peer can no longer clear a flag at all, which
  is the correct direction to fail.
  Two things here were found by the tests rather than by reading the spec. **A `FLAG_RESOLVED` with an
  empty `reference_id` was this app's own output** until `flagItem` started generating a flag id — and
  without a receiver-side fallback to the one open flag, a resolve can only *append*, so an item
  reported itself flagged the instant somebody cleared it. And **the unions converged as sets but not
  as lists**: notes, flags and evidence came out in arrival order, so two stations showed one item's
  notes differently. They are ordered by their own instants now, which makes the join properly
  commutative and reads chronologically besides.
- **Conflicts resolve the way crowsnest resolves them, on purpose.** Earliest completion wins and a
  later one is logged as a confirmation; an already-completed item cannot be un-started. Two additions:
  de-duplication by event id (a publisher's sample cache and the bootstrap `get` both re-deliver), and
  notes de-duplicated by note id as well, since a note arrives both as an event and inside a snapshot.
  `ChecklistReducerTest` covers each case.
- **Reminders are phone-local and never published.** No checklist message carries a due time.
  `AlarmManager.setAndAllowWhileIdle`, not `setExact` — that would pull in `SCHEDULE_EXACT_ALARM`, which
  is an alarm-clock permission. The `PendingIntent` puts the ids in the intent's `data`, not only its
  extras: two intents differing only by extras are the *same* intent to `AlarmManager`, so without it a
  second reminder replaces the first.
- **The site id must differ from any crowsnest station's.** Crowsnest drops an incoming event whose
  `roc_site` *and* `operator_id` both match its own, so a phone that borrowed a station's name would
  have its ticks silently ignored there. It defaults to the entity id for that reason.
- **Crowsnest's own snapshot bootstrap has never worked**, and the storage added here fixes it as a side
  effect: `useChecklistSync.js` does a `get_once` on `checklist_state/{procedureId}` with nothing behind
  the key. Worth knowing before reading its behaviour as intended.
- Checklist events are **not** recorded to MCAP. `Recorder` is per-run and hangs off the publish path;
  wiring an event-driven subject into it is a separate change.

## Platform library

The phone holds several platforms. `entity_id` is, per the protocol specification, "normally the
platform name", so the library *is* a platform list and the counterpart to crowsnest's own-ship
selector. Lives in `calibrate/` and `platform/`.

**This was called a *rig* until 2026-08-22**, which is worth knowing because the word survives in two
places on purpose: the `rig_*` DataStore keys, read once by the migration in `SettingsRepository` and
cleared on the next save, and `Routes.PLATFORM_PREFIX`, whose *value* is still `calibration` because the
two Zenoh sessions are scoped by route prefix and renaming one fails silently — the screen opens and
simply never finds anything on the bus.

- **One active platform, plus any opted in — and the active one always publishes.** `Settings.publishingPlatforms()`
  includes the active platform whether or not it is in `publishingPlatformEntityIds`, so no switch can express
  "the phone is on this platform and its geometry is not on the bus". The UI renders the active platform's switch
  on and disabled rather than letting it be pressed and do nothing.
- **A platform's photograph is a file named after its entity id, and nothing else records it.** Kept in
  `filesDir/platforms/` by `PlatformPhotos`, long edge capped at 1280 and **always re-encoded as JPEG**
  at quality 80 — measured on a Pixel 6, a 3072x4080 photograph of 1.98 MB stored as 1280x964 and
  120 kB. The re-encode is the part that was got wrong first: `scaleJpeg` returns its input untouched
  when it is already small enough, which is right on the camera's publish path and wrong here, and a
  1080x2400 PNG screenshot went in and came straight back out into a file called `.jpg`. Worse than
  the misnaming, a lossless multi-megabyte file would sit where the backup arithmetic below assumes a
  compressed one. It is
  **deliberately not a field on `PlatformCalibration`** for the same reason the publish flag is not: that
  struct is the platform *document*, upstream's schema is `additionalProperties: false` at every level,
  and `configuration_json` is republished every ten seconds — a 250 kB base64 photo on that loop is
  ~90 MB/h to restate something that has not changed since the boat was built. So the file name *is* the
  key: no preference, no stored path, nothing to leave dangling. The cost is two obligations at the call
  site in `MainActivity`, at the only two moments an entity id stops naming what it named — **a rename
  has to `move()` the file** and **a delete has to `remove()` it**, or the next platform given that id
  inherits a stranger's boat. `PlatformPhotosTest` pins both, plus that the move happens *before* a
  pending pick is written, since the other order has the move overwrite what was just saved.
  Three smaller things. The pending photo is held as **bytes rather than a temp file** — the editor is
  transactional, so a pick must show before Save and must not exist on disk until Save, and bytes make
  cancel, a crash and a second pick all free. The stored photo is looked up under **`draftEntityId`, not
  the draft's own `entityId`**, or the picture vanishes halfway through renaming a platform. And EXIF
  rotation is applied **at import**, because `BitmapFactory` ignores the tag and `Bitmap.compress` does
  not write it back — stored as-is, a phone photograph would be sideways permanently, the tag saying
  which way up it goes having been dropped by then. `androidx.exifinterface` rather than the framework
  class, which lint warns about for parser bugs and which is reading a file somebody picked.
  `platforms` is the one directory in `filesDir` deliberately **left in** the backup rules: it is
  configuration nothing can rebuild, and the size is what keeps it clear of the 25 MB quota, so raising
  `PHOTO_MAX_EDGE` is a decision about `data_extraction_rules.xml` as much as about picture quality.
  Verified end to end on a Pixel 6: pick, EXIF-rotate (a photograph tagged orientation 6 comes back
  1280x964 rather than 964x1280), store, thumbnail, **rename moves the file**, and delete removes it
  leaving the directory empty.
- **A sensor's rotation can be measured from the phone, and the frame conversion is the whole risk.**
  `calibrate/SensorAttitude.kt` is the angular counterpart to `bodyOffsetMetres()` — that rotates an
  ENU *offset* into the platform's body frame, this does the same for an *orientation*. It is pure,
  taking the quaternions `ImuProvider` already produces rather than reaching for `SensorManager`,
  because three frames compose here and each is a place two axes can be swapped and still read
  plausibly. **The posture is fixed and shared**: phone flat against the mounting face, screen up, top
  edge the way the sensor faces — the same one `HeadingSource.COMPASS` documents, so there is one
  phone-holding instruction rather than two that drift. Sensor forward = device +Y, starboard =
  device +X, down = device −Z: two axes swapped and one negated, so a **rotation** rather than a
  mirror image, and `SensorAttitudeTest` asserts that because a mirror reads as a perfectly plausible
  rotation and puts every sensor's port side to starboard.
  **The trap that actually bit was the reference frame, not the axes.** Android's rotation vector is
  referenced to **magnetic** north; a platform's heading is recorded as **true**. Subtracting one from
  the other leaves every yaw wrong by the local declination — caught on a Pixel 6 at Onsala, where the
  app's own true heading read 327° and an uncorrected capture read 321.2°, and confirmed by the fix
  moving a measurement from −130.1° to −125.0°. The correction is applied to the *heading* rather than
  by turning the attitude, since both are rotations about the same vertical and one subtraction is
  harder to get wrong than a fourth frame. **No position means no declination**, which is an ordinary
  tape-measured platform, and there the screen says the yaw is magnetic rather than absorbing the error.
  Three more things are load-bearing. **`eulerFromMatrix` must be the exact inverse of
  `quaternionFromYawPitchRollDegrees`**, or a measured rotation and a typed one meaning the same thing
  put different transforms on the wire. **Gimbal lock is an echo sounder, not a corner case** — at
  pitch ±90° yaw and roll turn about one axis, so roll is fixed at zero and the screen says which of
  the two is carrying the turn. And rotations **average as quaternions, never as Euler angles**, for
  the reason `circularMeanDegrees` exists, with three angles to get wrong at once; the spread about
  that mean is the capture's own scatter figure, and `angleBetweenDegrees` uses `atan2` rather than
  `acos` because `acos` near 1 reports a couple of thousandths of a degree for a phone that did not
  move at all.
  **Yaw's doubt and the spread's are different doubts and must not merge.** A phone held perfectly
  still beside a mast gives a tight spread around a wrong yaw. Only the compass figure is coloured,
  past `YAW_SUSPECT_DEGREES`; pitch and roll are gravity's and a steel mast does not touch them.
  Provenance is `rotationCapture` / `rotationAccuracyDeg`, separate from `capture` / `accuracyM`
  because the commonest survey is a walked position with a typed angle — and **written only when the
  rotation was measured**, so every document produced before this still parses unchanged.
- **A platform photograph can be taken as well as chosen, and both end at `importPlatformPhoto`.** One
  scale-rotate-re-encode path however a picture arrives. Two things the camera needs that the picker
  did not: a `FileProvider` (`res/xml/file_paths.xml`, a `cacheDir` subdirectory, deleted after the
  import — `PlatformPhotos` owns the copy that lasts), and the **CAMERA permission actually granted**.
  The app *declares* it for the time-lapse, and Android requires an app that declares it to hold it
  before `ACTION_IMAGE_CAPTURE` will run at all; an app that never declared it would need no
  permission here. Asked at the tap, the shape `RECORD_AUDIO` uses. Taking is disabled while a run is
  recording stills or video, because CameraX holds the camera and the camera app is a different
  process wanting the same hardware.
- **`"%.1f".format(x)` is a data-loss bug, not a cosmetic one, and it shipped.** `SensorMountScreen`
  wrote a captured offset into its text fields with `format`, which follows `Locale.getDefault()`: on
  a Swedish phone that is `0,220`, which `toDoubleOrNull()` rejects, so the field went red and saving
  stored **0.0** — putting the sensor exactly on the platform's origin, the one wrong answer nothing
  downstream can distinguish from a real measurement. Twelve sites across `SensorMountScreen` and
  `CalibrationScreen` are now `.fmt()`; `SensorMountFieldsTest` pins the sv-SE round trip through the
  field and back out. Note `.fmt()` is an extension on **String**, so it is `"%.3f".fmt(x)`.
- **The publish flag is not a field on `PlatformCalibration`.** That struct is the platform *document* — it
  is what the exporter writes, what `configuration_json` carries, what `get_config` replies with, and
  what crosses to and from crowsnest. A local policy flag inside it would either leak into a file that
  is `additionalProperties: false` at every level, or need excluding in three serialisers and the
  parser.
- **A rename is a re-key, and `Settings.upsertPlatform(previousEntityId, platform)` owns it.** The entity id is
  the identity *and* the `{entity_id}` chunk of every key the platform publishes on, and both the active
  selection and the publish set name the old id. Renaming anywhere else silently deselects the platform
  somebody just renamed. `entityIdTaken()` refuses a collision outright — two platforms on one id would
  publish onto the same three keys and overwrite each other in Zenoh's latest-value store.
- **Changing the active platform or a publish switch restarts the run**, unlike the per-subject switches.
  It changes which publishers are declared and which liveliness tokens exist, so it goes through
  `saveSettings()`. The platform list is read-only while a run is going for that reason.
- **A platform is stored as one JSON string per indexed key**, not as a spray of flat keys like everything
  else in `SettingsRepository`. Two nested indices (platform, then sensor) would mean a clear-out that walks
  both, and that clear-out is the part already got wrong once. The stored form is
  `toStoredJson()` — the *wire* document plus `entity_id` and `parent_frame_id`, which upstream's
  schema has no room for. Note it therefore **normalises rotations into `[-180, 180]` on save**: `-180`
  comes back as `180`, the same rotation, and `SettingsRepositoryTest` pins it.
- **The migration from the single-platform `calib_*` keys is read-only and one-way.** `platform_count` absent is
  the trigger; present and zero is an emptied library and must *not* fall back, or a deleted platform
  resurrects. The migrated platform is nominated active by `migrateActivePlatform()` — an update that silently
  stopped geometry that was going out before is the one outcome a migration must not produce. After the
  first save the old keys are gone, so an older APK sees no calibration.
- **`platform_active_entity` absent and empty mean different things**, the same rule the annotation buttons
  have. Absent is a file from before the library and its one platform gets nominated; **empty is a selection
  somebody cleared by deleting the active platform**, and re-nominating there would silently start a
  surviving platform's geometry going out under its own entity id — because the active platform always
  publishes. `readSettings` therefore uses `?:` on the raw key, never `ifBlank`.
- **A Zenoh subscriber is not torn down by cancelling the scope that declared it.** The discovery scan
  closes its own subscriber in a `finally`, and must: left to `stop()`, every scan leaves a live
  callback behind, and the next scan's fresh list is then overwritten by the previous scan's stale
  one the moment another document arrives. The same `finally` is what stops the scan state being left
  on `Scanning` forever when a settings change restarts the session mid-scan — and since `PlatformSync`
  outlives every screen, "forever" means until the process dies.
- **Nothing heavy runs in the discovery callback.** It hands `(key, bytes)` to an unbounded channel and
  a coroutine does the protobuf and JSON parsing, because the callback is on Zenoh's receive path and
  the wildcard subscription can see every platform on the realm at once. Same rule the queryable's
  pre-rendered reply follows.
- **Applying a shared library bumps past the version it merged**, rather than adopting it.
  `mergeRemotePlatforms` keeps platforms this phone is publishing, so what comes out is not what arrived;
  republishing that at the sender's own version would leave the shared key holding two different
  libraries both claiming to be the same one, with neither station able to accept the other's.
- **`PlatformGeometryParse.kt` is the first thing in this app that parses JSON**, and it uses
  `kotlinx-serialization-json` — runtime API only, no compiler plugin, no `@Serializable`. It is
  already on the runtime classpath (`zenoh-kotlin-android` pulls it in at runtime scope), so declaring
  it costs nothing that ships and the unit tests exercise the same implementation the phone runs. That
  last part is why it is not `org.json`, whose `android.jar` stubs throw "not mocked" in a JVM test —
  the tested parser and the shipped parser would be different implementations of the same API, for the
  one class that consumes foreign input. The writer stays hand-rolled: it is a small fixed shape only
  ever written, and pinned character-for-character.
- **The parser is tolerant the way `readCalibration` is tolerant** — a transform missing a frame id or
  any translation component is skipped, never read as zeros, because zeros put the sensor exactly at
  the platform's origin and nothing downstream can tell that from a measurement. It also accepts the older
  `keelson-platforms` shape where `translation` is a `[x, y, z]` array; **that array is
  `[roll, pitch, yaw]`**, squaternion's own argument order, and reading it yaw-first would roll a platform
  onto its side.
- **`configuration_json` has two wire shapes and both are real.** On pubsub it is an enveloped
  `keelson.TimestampedString`; as a `get_config` reply it is **raw JSON bytes**. Crowsnest carries the
  same fork. `decodeConfigurationJson()` handles both, and anything reading these documents that
  handles only one silently finds nothing on half the sources.
- **Advertising an RPC interface commits the app to answering every procedure in it**, protocol
  specification §3.6 — with a typed refusal where it cannot comply, but **never with silence**. That is
  the whole reason `configurable/v1`'s token was safe to declare: the interface is two procedures, and
  the app serves `get_config` and refuses `set_config` with a serialised `keelson.interfaces.ErrorResponse`
  (`setConfigRefusal()` in `platform/PlatformSync.kt`, `declareRefusingQueryable()` in `KeelsonSession`).
  Refusing is a decision on the merits, not laziness: geometry arrives either from the phone's own
  editor or through `mergeRemotePlatforms`, which never deletes a platform this phone is publishing and never
  takes remote *policy* — an unauthenticated `set_config` from anyone on the fleet bus goes around all
  of it. The code is `PERMISSION_DENIED` because `ErrorResponse.Code` **has no `UNSUPPORTED`**, which is
  what §3.6 actually asks for here; the description says "permanent" in words so a consumer's operator
  is not invited to retry, and `ConfigurableRpcTest` pins that wording. Do not soften it to
  `UNAVAILABLE`, which reads as "not ready yet".
- **The RPC interface token lives and dies with the platform screens**, because `PlatformSync` does. §3.5
  forbids holding a token for an interface a source does not currently serve, so that is correct rather
  than a bug — but it does mean a fleet tool probing a phone that is *logging* finds no configurable
  interface at all. The intermittency is now visible instead of silent, which is the improvement; making
  the service always available is a separate decision with a battery cost, filed in TODO.md.
- **The `get_config` reply is the one unwrapped thing this app puts on the wire.** `configurable/v1`
  replies raw JSON (`op.reply_ok(json.dumps(...).encode())` upstream); wrapping it in an envelope would
  break every consumer that already speaks it. Do not "fix" it to match the everything-is-wrapped rule.
- **`get_config` is served on one key now, and the source chunk is why that took two repos.** The
  specification's shape is `.../@rpc/configurable/v1/get_config/{source}`, and this app answers on
  `Settings.calibrationSource`. It used to *also* serve a pre-interface
  `{realm}/@v0/{entity}/@rpc/get_config/connector_platform` because crowsnest probed it; that is gone,
  along with `legacyPlatformConfigKey()`, once crowsnest moved (`a3c4853` there).
  **The reason a fixed source id could never work is worth keeping.** That chunk names *which*
  responder, and three values are in use for this one procedure: keelson's own platform connector is
  run with `--source-id platform`, crowsnest's registry declares `connector_platform`, and this app
  uses `calibration`. A consumer probing a platform it did not configure cannot know it — so crowsnest
  now probes `.../get_config/*`. Measured against zenoh's own `KeyExpr.intersects`, that reaches all
  three and reaches neither `set_config` nor another entity, so the widening is one chunk exactly.
  Do not "fix" this app to a fixed source to match some consumer; the wildcard is the consumer's job.
  Note `WhepSignalling.legacyKey` is a **different** pre-interface shape, for the WHEP proxy in
  `keelson:0.5.3`, and is still served. Crowsnest's `get_data_streams` / `get_queryables` — which exist
  nowhere in keelson — were deleted there rather than renamed.
- **Discovery listens; it does not query.** No router storage covers `configuration_json`, so a `get`
  returns an empty list indistinguishable from an empty bus. The scan subscribes for ~12 s instead —
  slightly over the 10 s republish interval every platform connector uses precisely so a late joiner
  need not ask — and takes entity ids from a liveliness get alongside. The liveliness half is wrapped
  in a `Result` because it is the newest native call site in the app and the least load-bearing.
- **`platform_registry` is deliberately not a keelson subject** and must never be added to `Subjects`
  or `PublishedSubject`. That is what makes a consumer's decode fall through to raw JSON instead of
  failing to unwrap an envelope — the same trick crowsnest plays for `dataflow_config`, `route` and
  `voyage`. The library lives under a *config* entity (`platforms`), not a platform's: filing a list of
  platforms under one platform's entity is a category error, and filing it under the phone's would make
  each phone's library private.
- **A remote library replaces documents and never local policy**, and **never deletes a platform this phone
  is publishing**. The first stops one operator's save silently starting every phone in the fleet
  publishing geometry under entity ids nobody told them about; the second is a knowing deviation from
  crowsnest's whole-map replace, because taking a platform out from under a live publisher is the one case
  where last-writer-wins is unacceptable. `platformRegistryOrigin` drops this phone's own echoes — a
  publisher's sample cache re-delivers, so without it the version ratchets on every reconnect.
- **The router needs a storage for the library key** or a station joining late sees nothing;
  `../keelson-router/docker-compose.keelson-router-rise.yml` has one now. Crowsnest does not publish its
  own overlay yet, so sharing is one-way until it does.
- **The three calibration status rows aggregate across platforms.** `statusStore.tick(subject)` is keyed on
  the registry entry, and re-keying it on (entry, platform) would ripple into the notification total, the
  group badges, the live rings and `SubjectQosScreen` for a 0.1 Hz loop. `configuration_json`'s plotted
  value is therefore the **whole library's** sensor count, not each platform's — a per-platform count would have
  three platforms writing three numbers into one series and the plot would oscillate.
- **Still one `calibration` collector.** N platforms is a fan-out *inside* it, not N collectors, so
  `COLLECTOR_GROUPS` and `CollectorGroupsTest` are untouched. The per-platform `SubjectSink` carries a key
  override, which is what gives each platform its own MCAP channels rather than merging two platforms' transforms
  onto one topic.

## Gotchas

- **The launcher icon is generated, not hand-written.** `art/logline_icon.svg` is the source;
  `python3 art/svg_to_adaptive_icon.py` regenerates `drawable/ic_launcher_{background,foreground}.xml`.
  Never put an `.svg` under `res/` — AAPT will fail the build. The foreground is deliberately scaled to
  0.80 so a circular launcher mask cannot clip the mark.
- **Release builds are unsigned by design when no key is configured — *locally*.** `assembleRelease`
  warns and emits `app-release-unsigned.apk` rather than failing, so a developer without the keystore
  is not blocked. **CI is the opposite and deliberately so**: both workflows fail on a missing
  `LOGLINE_KEYSTORE_BASE64`, because an unsigned APK published to a release is worse than no release —
  Android treats a different signature as a different app, so every phone that installed it needs an
  uninstall to get back, and an uninstall wipes the mTLS certificates and the entity id. Credentials
  come from env vars or `local.properties`; see the README.
- **`versionCode` is derived from the commit count, and a shallow clone is the failure it exists to
  catch.** `versionCodeBase` in `version.properties` plus `git rev-list --count HEAD`, read through a
  `ValueSource` in `app/build.gradle.kts` — 10 187 today. `versionName` stays hand-bumped, because
  that one is an editorial claim and nothing should derive it. Hand-bumping the *code* is what left it
  at `1` across 187 commits, so every APK ever built reported `1.0 (1)` and none could upgrade another.
  The derivation is configuration-cache safe and not by luck: a `ValueSource` read at configuration
  time is a **build configuration input**, so Gradle re-executes it before every build to decide
  whether the cached configuration still holds. Verified rather than assumed — an empty commit makes
  Gradle say `a build logic input of type 'GitRepositoryState' has changed` and the APK's code moves
  from 10187 to 10188. A cached number would be silently wrong on every phone that took the APK, which
  is why this is a `ValueSource` and not a file read. It is also **not** `providers.exec()`: that
  throws out of `.get()` when git is missing with no configuration-cache-compatible way to catch it
  (gradle/gradle#23914), where `obtain()` returning null is the same information with a fallback
  attached. The `.git` probe lives *inside* `obtain()` for two reasons — a bare `exists()` at
  configuration time is an undeclared input, and `git` walks **upwards** looking for a repository, so
  a checkout nested inside another clone would otherwise take that repository's count without a word.
- **`actions/checkout` defaults to `fetch-depth: 1`, and that default is what would break upgrades
  permanently.** In a shallow clone `git rev-list --count HEAD` does not fail: it **succeeds and
  returns the clone depth**, which is 1. Reproduced here with `git clone --depth 1`. A build that way
  would be green, the APK entirely normal-looking, and it would carry `versionCodeBase + 1` — and
  Android refuses to install a lower `versionCode` over a higher one, so every phone that took it is
  stuck until somebody uninstalls, which wipes the certificates. `app/build.gradle.kts` therefore
  **fails** on a shallow clone rather than falling back, naming `fetch-depth: 0`; only an absent
  repository entirely (a source archive) falls back, and it warns. Every job in `.github/workflows/`
  that builds an APK sets `fetch-depth: 0` for this reason and no other. If history is ever squashed
  or rewritten the count can go *down* — it was rewritten once already, on 2026-09-11 — and the only
  repair is to raise `versionCodeBase` above the highest code ever released. Never lower it.
- **`local.properties` is git-ignored** and holds `sdk.dir`. A fresh clone won't build without it.
- **Configuration cache is on.** Build-logic edits are picked up; if a change seems ignored, add
  `--rerun-tasks` before assuming the code is wrong.
- **AGP 9.3.1 / compileSdk 37** is a leading-edge combination. Treat unfamiliar AGP DSL errors as a
  version-specific API question, not as broken code — `compileSdk { version = release(37) }` and the
  `optimization { }` release block are AGP 9 syntax and are correct as written.
- **Endpoints are a list, and the default is loopback, `tcp/127.0.0.1:7447`.** That default is
  deliberately somewhere the phone cannot reach anybody: this is a public repository and a shipped
  default must not name a deployment's infrastructure. A fleet's own endpoint arrives by
  connection-profile QR, which carries no credentials. Stored
  newline-delimited under the *same* `router_endpoint` DataStore key, so an old single-value preference
  migrates for free. The list is **failover, not fan-out**: Zenoh tries them in order and attaches to
  whichever answers first, so there is still one session and one router at a time. A refused entry costs
  nothing; a blackholed one costs up to the 10 s transport-open timeout, because they are tried
  sequentially. The endpoint *scheme* is what turns TLS on — `tls/` and `quic/` get a
  `transport/link/tls` block, `tcp/` gets none. There is deliberately no separate "use TLS" toggle to
  drift out of sync with the locator. Note Zenoh's session info exposes routers' *zids*, not the locator
  a transport was opened on, so the app cannot say which endpoint won — don't add a field promising it.
- **`Zenoh.scout` kills the app on Android, so `keelson/Scout.kt` speaks the exchange itself.** The
  binding builds the callback's `ZenohId` in native code with `FindClass`, on one of Zenoh's own
  threads, where JNI resolves against the system class loader and cannot see app classes: the process
  SIGABRTs with `ClassNotFoundException: io.zenoh.jni.config.ZenohId` the instant a router answers.
  Verified on zenoh-kotlin 1.10.0 — no app-side callback avoids it, because the id is constructed before
  any Kotlin runs. The replacement is three bytes out and one datagram back, pinned by `ScoutWireTest`
  against captures from a real Zenoh node; if a future zenoh changes the framing, that test is what
  fails. Don't "simplify" it back to `Zenoh.scout`.
  **It was never scout-only, and that mistake cost a lot.** The same `FindClass` fault fires for a
  *query reply* (`io.zenoh.jni.pubsub.EntityGlobalId`) and for **any subscribed sample carrying a Zenoh
  timestamp** (`io.zenoh.jni.time.Timestamp`; `io.zenoh.jni.sample.SourceInfo` is the same path and has
  not been seen yet only because the other fires first). A router timestamps *every* sample it forwards
  by default, so in practice **no subscription is safe on this binding at all** — measured across this
  fleet's bus, 15 053 samples over 42 subjects and four realms, 100% timestamped. `ZenohBinding` in
  `keelson/` owns the diagnosis and the evidence; upstream is eclipse-zenoh/zenoh-flat-jni#49.
  Two wrong diagnoses got written down before that one, and both came from stopping too early: that it
  was queries (removing them exposed the subscriber fault), and that the router timestamps "what its
  storages keep" (the measurement disproving it — `checklist_presence` timestamped with no storage —
  was already in hand). A comment in `KeelsonSession` naming a non-existent `io.zenoh.jni.callbacks`
  package is what made the subscription path look safe for months; the real interface is
  `io.zenoh.jni.sample.SampleCallback`, whose `run` takes a `Timestamp` **and** a `SourceInfo`.
- **Android 17 gates the local network behind `ACCESS_LOCAL_NETWORK`.** Local Network Protections make
  any LAN address *and* multicast a runtime permission. Without it the failure is `EPERM` from `sendto`
  deep inside Zenoh — the scan looks like an empty network and a `tcp/192.168.x.x` endpoint silently
  never connects; the only honest signal is `appops get <pkg>` showing `ACCESS_LOCAL_NETWORK` rejections.
  It is requested on the first scan and, via `startupPermissions(settings)`, when a run starts with a
  local endpoint in the list — `isLocalEndpoint()` decides, and loopback deliberately does not count.
  Keep it conditional: prompting the default cloud-only config for "nearby devices" teaches users to
  dismiss it.
- **A `tls/` endpoint fails to start unless credentials are imported, by design.** `clientConfigJson`
  throws naming the missing file, which surfaces through `statusStore.setupFailed()`. Zenoh 1.10's
  `transport/link/tls` fields take **file paths**, not inline PEM or base64 — there are no `_base64`
  variants — so `TlsCredentialStore` copies imports to `filesDir/tls/` and the config points at those
  absolute paths. `filesDir` is the security boundary: never `getExternalFilesDir`, which any app with
  storage access can read.
- **TLS credentials are never bundled in the APK, and `certificates/` is git-ignored.** The client key
  authenticates this phone to the *shared* fleet bus; a 120 MB debug build gets passed around. If you
  ever find yourself adding a PEM to `res/` or `assets/`, stop.
- **The backup rules are the third leg of that, and they are not decoration.** `filesDir` is where the
  key lives, and Android's auto-backup takes *all* of `filesDir` unless told otherwise — so with the
  Android Studio stub rules the key went to Google Drive and rode a phone-to-phone transfer. Measured
  with the local backup transport on a Pixel 6: **3 648 000 bytes** backed up with the stubs, **7 168**
  with the excludes in `res/xml/{backup_rules,data_extraction_rules}.xml`. Both files, always: API 30
  reads the first and 31+ the second, and minSdk is 30, so changing one alone fixes half the fleet.
  `recordings` and `osmdroid` are excluded for a different reason — cloud backup's 25 MB quota is
  per app and a file over it fails the *whole* backup, so a run in progress would cost the settings
  their backup too. Adding any `<include>` flips the file to an allow-list and silently stops backing
  up everything unlisted; the settings are meant to survive a phone swap, which is why this is an
  exclude list rather than `allowBackup="false"`.
- **`tcp/127.0.0.1:7447` is the phone's own loopback**, not the dev machine's. Testing against a
  laptop router needs the LAN IP, or `adb reverse tcp:7447 tcp:7447`.
- **The battery-optimisation prompt is asked once, and recorded with `update()`, never `saveSettings()`.**
  It fires at the first Start — not first launch, where a question about background execution has
  nothing running to be about — and `Settings.batteryExemptionAsked` remembers only that it was *asked*,
  never the answer: whether it was granted comes from `PowerManager` on every resume, which is the only
  thing a trip through Android settings cannot leave stale. Writing the flag through `saveSettings()`
  would stop and restart the service to redeclare publishers, i.e. tear down the run that was just
  started, to record that a question had been put. Note also that `requestBatteryExemption()` launches
  and catches `ActivityNotFoundException` rather than asking `resolveActivity` first — package
  visibility on Android 11+ can hide a perfectly launchable activity, which would send some devices
  down the fallback path for no reason. The permission is Play-policy restricted; that is deliberate
  and `@SuppressLint("BatteryLife")` says why.
- **A boot start cannot use every foreground service type, and the failure is an exception.** Android
  15+ refuses `dataSync`, `microphone` and `camera` foreground services started from a
  `BOOT_COMPLETED` broadcast — `startForeground` throws rather than quietly dropping the type, so a
  boot start carrying audio would lose the *whole run* rather than one subject. Hence two rules that
  look like product decisions and are not: `BootReceiver` declines unless `ACCESS_FINE_LOCATION` is
  granted (that is what makes the run a `location` service, which is permitted), and `forBootStart()`
  takes audio and the camera off the settings the run is given — in *settings*, so the type mask and
  the collectors cannot disagree. `BootStartTest` pins it. Note also the service is started from
  inside `onReceive` rather than a coroutine: the exemption from Android 12's ban on background
  foreground-service starts belongs to the broadcast, so the settings read there is deliberately
  blocking.
- **The foreground service type is chosen per start.** `location` when `ACCESS_FINE_LOCATION` is
  granted, `dataSync` (IMU only) when it isn't. `dataSync` carries a ~6 h/24 h cap on Android 15+,
  which is why `PublisherService.onTimeout()` exists — removing it turns a cap into a crash.
- **`Zenoh.initLogFromEnvOr` may only be called once per process.** A second call aborts the whole app
  with `Builder::init should not be called after logger initialized` — a native SIGABRT, not a catchable
  exception. `KeelsonSession.openClient` guards it with an `AtomicBoolean`; do not remove that guard.
  Any second run in one process reaches it (Stop then Start, or saving settings while publishing), so
  the crash looks like "the app vanished when I restarted logging".
- **`SensorPublisher.stop()` is fire-and-forget.** It nulls the fields and flips the status
  synchronously, then closes the session on `closeScope` after joining the cancelled collectors. Code
  that assumes the Zenoh session is gone the moment `stop()` returns is wrong — a new session may
  briefly overlap with the old one closing, which is harmless.
- **A successful `put` does not mean the sample was delivered.** `KeelsonSession.publish()` returns a
  `Result`, but on a session that has lost its router the put still succeeds and the data is dropped —
  measured on a Pixel 6, ~9000 successful puts landed on an empty bus during a 26 s outage. The only
  reliable signal is `KeelsonSession.isConnectedToRouter()`, polled by the watchdog in
  `SensorPublisher`. Never treat a rising sample counter as proof anything is receiving.
- **The per-subject switches are the one setting that does *not* restart the run.** Everything else
  goes through `saveSettings()`, which stops and starts the service so publishers are redeclared with
  their new key and QoS. A switch only decides whether samples are let through, so `toggleSubject()`
  writes DataStore directly and `PublisherService.watchOffSubjects()` pushes the new set into the live
  run — routing it through `saveSettings` instead would drop the Zenoh session, and every *other*
  subject's stream with it, to switch one of them off. **Audio and the camera are the exception** and do
  take the restart: their foreground-service type and permission are fixed at `startForeground`, which
  is what `START_TIME_SUBJECTS` names.
- **A switched-off subject is gated in exactly one place, `SubjectSink.emit()`.** Wire, MCAP channel,
  replay outbox, live store and sample counter all hang off that call, so they cannot end up disagreeing
  about whether a sample happened — a subject switched off for a whole run has *no channel at all* in
  the file, which is what makes the recording honest rather than full of gaps. The gate alone would
  still leave the `SensorManager` listener attached, though, which is most of what a subject costs: the
  matching half is `supervise()`, which cancels a collector once **every** subject riding it is off.
  Four subjects come off one `Location` callback and twelve off one radio poll, so it is never one
  collector per subject. Verified on a Pixel 6: switching the barometer off mid-run removed the app
  from `dumpsys sensorservice` within a second and left a 15 s hole in `air_pressure_pa` and in nothing
  else.
- **A section's master switch deliberately does not govern audio or the camera.** It moves everything
  in the group except `START_TIME_SUBJECTS`, because "Device" also contains the microphone and the
  camera and a group switch must never turn those on — the privacy decision behind `audioEnabled`
  defaulting to off is not one a sweep of the barometer's heading gets to make. They keep their own row
  switch. The master also writes **one** `Settings` value for the whole group: `update()` is a
  read-modify-write against a captured `current`, so twelve individual calls would have eleven
  overwrite each other and only the last would stick — hence `Settings.withSubjects()`.
- **`groupBadge` has to handle an empty denominator.** A switched-off subject leaves `GroupSummary.total`,
  so a fully switched-off group has `total == 0` — and `unavailable == total` is then trivially true,
  which had a heading announcing perfectly good hardware as "not on this device". One tap of a master
  switch reaches that state, so `total == 0` is checked first and reads "off". The `· N off` suffix
  exists for the same reason: without it a group reading `3/3 ✓` silently hides two subjects somebody
  switched off.
- **Two kinds of failure, and they are not interchangeable.** `PublisherStatus.error` means a fatal
  *setup* failure and stops the foreground service. A lost link is `ConnectionState.Disconnected` and
  must not stop it — Zenoh reconnects on its own once the router returns.
- **`PublisherStatus.error` must be cleared when a run starts, and the watcher attached after that.**
  `PublisherService.watchForFailure()` collects that field to know when to tear a run down, and a
  `StateFlow` hands a new collector the *current* value the moment it subscribes — so a failed run left
  its error sitting there and the **next** run's watcher read it as its own, stopping the service before
  the session had opened. One failed start then bricked every start after it until the app was
  force-stopped, which reads as "the app refuses to run". `SensorPublisher.start()` calls
  `statusStore.clearError()` synchronously before launching anything, and the service calls
  `watchForFailure()` *after* `start()` returns; both halves are needed, since the watcher used to
  attach while `start()` was still queued on a coroutine. Do not "simplify" this into comparing error
  strings to tell runs apart — retrying an unreachable router produces byte-identical text every time,
  so a comparison silently swallows the second failure. `PublisherStatusStoreTest` pins both.
- **Never write `_status.value = _status.value.copy(...)`.** Every collector races on that value; a
  read-modify-write loses increments (a contended test loses 60–70% of them). Counter updates go
  through `PublisherStatusStore`, which uses `MutableStateFlow.update`.
- **`SensorEvent.timestamp` is a boot clock, not epoch time.** Publishing it raw puts the stream
  decades in the past. Payload timestamps go through `SensorClock.epochNanosNow()`; `location_fix` uses
  `Location.time`, which is already UTC. A new subject reaching for `Instant.now()` is reintroducing
  the bug this replaced — that stamps publish time, not observation time.
- **`SensorPublisher` builds a separate `ImuProvider` per IMU flow.** Harmless — `SensorManager` is a
  system service — but don't take it as a pattern.
- **A sparkline that looks noisy is usually an axis problem, not a data problem.** Measured: the
  barometer varies 2.2 Pa in 100 035 Pa — 0.0022% — and an axis scaled to the data's own range turns
  that into a full-height wobble. `plotBounds()` in `ui/Sparkline.kt` floors the drawn span at 1% of the
  value for exactly this. Note that smoothing does *not* fix it: averaging reduces the noise and the
  autoscale then re-expands whatever is left to fill the card, so the plot looks just as jagged, only
  slower. The band-and-mean rendering (`envelope()`) is the smoothing, and it keeps the extremes.

- **The live store records the *arrival* time; the payload and the recording keep the observation
  time.** They are the same instant for nearly every subject, and deliberately not for the ones that
  hold a reading between reports (`illuminance_lux`, the radio subjects). Storing the observation time
  there put every held sample on one instant: the live plot had no width, no rate, and dropped out of
  the time window entirely while the subject was still publishing once a second. `SubjectSink.record`
  stamps `System.currentTimeMillis()` for that reason — do not "fix" it back to the observation time.
- **`radio_*_bitrate_bps` carries negotiated link speed from this connector, not throughput.** It comes
  from `WifiInfo.getRxLinkSpeedMbps()`. The app labels it "Link speed" for that reason; the subject
  keeps keelson's name because other connectors may legitimately publish measured traffic into it, and
  splitting the two meanings is an upstream conversation.

- **`illuminance_lux` is the one subject that republishes a reading it did not just receive.**
  `TYPE_LIGHT` is an *on-change* sensor (`minDelay=0`): measured here, twenty minutes with no event in
  a steady room. `sensors/SampleHold.kt`'s `heldAt()` repeats the last sample on a ticker so the series
  is unbroken and the stalled detection stays honest — and the payload keeps the **original**
  observation timestamp, which is what makes it a repeat rather than a fabrication. That conversion is
  memoised per reading on purpose: `SensorClock.epochNanosNow()` re-reads the boot-to-epoch offset on
  every call, so converting a held sample repeatedly moved its timestamp by a millisecond each time and
  defeated the point. Also note the unit needs **no conversion** — Android reports lux and the subject
  is lux, which is the exception to the rule below.

- **`raw_nmea0183` shares the location collector, and `rateOwner` is what forces that.** It has its own
  Android listener rather than riding the `Location` callback, so it looks like a candidate for a
  collector of its own — `CollectorGroupsTest` rejects that, and rightly: a subject that follows
  another's rate has to share its lifecycle. Here the reason is physical. `NmeaProvider` does not start
  the GNSS engine, it only hears one that is running, and the fused request in `runLocation` is what
  keeps the chip talking. Sharing the group makes that work in both directions — NMEA alone keeps the
  collector and the request alive, and switching every GNSS subject off stops the sentences too.
  The callback's timestamp is **checked, not trusted**: documented as epoch millis, read as a boot
  clock below `EPOCH_FLOOR_MILLIS`, because the older `GpsStatus.NmeaListener` supplied one and
  publishing it raw dates the stream to 1970. `NmeaTest` pins both branches.
- **`location_fix_quality` says `FIX_NO` while a position is on the bus, and that is the point.** The
  fix this app publishes is the *fused* one, which Android will derive from wifi and cell with the GNSS
  engine solving nothing — and from every other subject that is indistinguishable from a good fix,
  because a position arrives either way. `fixQualityOf()` reads `usedInFix == 0` as the receiver's own
  statement that it is not solving, and the satellite count is trusted rather than second-guessed with
  a threshold of ours: if a receiver marks satellites used, it is solving, which is what the flag
  means. 2D versus 3D comes from whether the *fix* carried an altitude, shared from the location
  collector through `lastFixHadAltitude` the same way the declination already is. `rtk_status` and
  `integrity` are deliberately left unset — Android reports neither, their zero values already mean
  "not reported", and a plausible guess would be worse than the truth. `GnssStatusTest` pins the
  mapping, including that the live row's word and the published enum cannot drift apart.
- **Two altitudes, and the undulation is what tells them apart.** `location_fix.altitude` is height
  above the **WGS84 ellipsoid** — Android's definition, and foxglove's proto says only "Altitude in
  meters", so the wire does not disambiguate it. `altitude_above_msl_m` is the one a person means, and
  `location_fix_undulation_m` is **N = h − H** (ellipsoidal minus MSL, the standard geodetic sign):
  positive across northern Europe, negative over much of the Indian Ocean, so an `abs()` would pass
  every test written here and be wrong only on the other side of the world. `GeoidTest` pins the sign
  and that both altitudes are recoverable from it. Three API facts worth not re-deriving:
  `getMslAltitudeMeters()` and `AltitudeConverter` are **API 34**, but
  `AltitudeConverter.tryAddMslAltitudeToLocation` — the non-blocking one — is **API 35**, which is why
  `MslAltitudeResolver` takes the blocking call on `Dispatchers.IO` instead. MSL is *optional* on a
  fix and the fused provider commonly omits it, so the resolver reports-then-derives; that means a
  series can change provenance mid-run, which the README states rather than hides. And below API 34
  both subjects go in `unavailableSubjects()` — an OS version the row cannot otherwise distinguish
  from missing hardware.
- **The attitude angles get their own collector, and that is the deviation, not an accident.** Six
  subjects — `roll_deg`/`pitch_deg`/`yaw_deg` and the three `*_rate_degps` — come off sensors two other
  collectors are already listening to, so a second `SensorManager` registration each is exactly what
  this file warns against elsewhere. The reason is the rate: riding the rotation vector at its 50 Hz
  default would have added ~300 messages a second (~65 MB/h) for subjects describing motion with a
  period of seconds, and a `rateOwner` gives no dial to turn it down. They default to 10 Hz, and
  `roll_deg` / `roll_rate_degps` own the rate for their trio because one listener cannot serve three.
  Two traps in the content. The rates are **body rates**, which is what the gyro measures and what a
  marine system means by "roll rate" — *not* `d(roll_deg)/dt`, which they equal only near level. And
  the axis naming is three assignments that all look right when two are swapped, so it lives in
  `attitudeRatesOf()` with `AttitudeTest` on it rather than inline at the publish site: pitch about
  +X, roll about +Y, yaw about +Z. `SensorFrame.DeviceAngle` exists so the per-subject screen can say
  these are the *phone's* axes — roll and pitch are the readings most likely to be taken for a
  vessel's.
- **The compass is derived, not a sensor, and three of its four subjects are conditional.**
  `heading_magnetic_deg` is `getOrientation`'s azimuth off `TYPE_ROTATION_VECTOR` — the direction of the
  phone's **+Y axis**, which is meaningless when +Y points at the sky. `heading_true_north_deg` and
  `magnetic_variation_deg` need a position for `GeomagneticField`, so they publish **nothing** until the
  first fix rather than referencing north wrongly; `heading_accuracy_deg` is absent on a device that
  reports no estimate. All four ride another subject's samples (`rateOwner`), so they have no rate of
  their own — and the three heading ones therefore run at the rotation vector's rate, which at the 50 Hz
  default is ~150 extra messages a second. Do not confuse heading with `course_over_ground_deg`: one is
  where the phone points, the other where it is going.

- **`protoDuration` truncates where `protoTimestamp` floors, and that is protobuf's rule, not a
  preference.** A `Timestamp` wants a non-negative `nanos` even when `seconds` is negative; a
  `Duration` requires both parts to carry the **same** sign. Copying the timestamp helper's
  `floorDiv`/`floorMod` into the duration one would emit an invalid message for anything negative.
  Nothing produces a negative duration today — `device_uptime_duration` cannot run backwards — so this
  is a trap set for whoever adds the subject that can; `EnvelopesTest` holds both shapes.
- **`imu_temperature_celsius` comes from a vendor sensor found by *string* type, and there is no
  fallback on purpose.** Android has no constant for a die temperature: `TYPE_TEMPERATURE` is
  deprecated, `TYPE_AMBIENT_TEMPERATURE` is the air, and a Pixel 6 exposes **neither** — verified with
  `dumpsys sensorservice`, which lists only `com.google.sensor.gyro_temperature` (the LSM6DSR) and
  `com.google.sensor.pressure_temp`. So `imuTemperatureSensor()` walks `getSensorList(TYPE_ALL)`
  matching `stringType`, because the numeric type (65538 here) is vendor-assigned and portable
  nowhere. Do **not** add an ambient fallback: the chip runs hotter than the air, which is the entire
  reason to log it, so ambient published under this name would be a plausible wrong number. The
  registry entry therefore has no `sensorType` and `unavailableSubjects()` asks for the sensor
  directly. Its scaling is guaranteed by no contract either, hence the once-per-run range check.
- **Android's units are not keelson's units.** The subject name declares the unit and it usually is not
  the one Android hands you: gauss vs microtesla, pascals vs hectopascals, knots vs m/s, volts vs
  millivolts, amps vs microamps, Celsius vs tenths of Celsius. `sensors/Units.kt` holds every
  conversion with a test against a physical constant, because the failure mode is a plausible number
  that is silently off by a constant factor. A magnetic field reading near 50 rather than 0.5 is the
  tell.
- **Never publish a scalar the platform did not report — with one deliberate exception.** proto3 cannot
  distinguish an absent float from `0.0`, so an unguarded publish turns "no current reading" into
  "drawing nothing". The nullable fields on `BatterySample` / `CellularSample` / `WifiSample` exist for
  this; skip the value instead. Sentinels are worse than absence and differ per API family: telephony
  uses `Integer.MAX_VALUE` (`CellInfo.UNAVAILABLE`), `NetworkCapabilities` uses `Integer.MIN_VALUE`,
  WiFi uses `-1` for link speed and `-127` dBm for a disconnected radio. `-140` dBm is a *real* RSRP.
  **They also differ in width**: `CellIdentityNr.getNci()` returns `CellInfo.UNAVAILABLE_LONG`
  (`Long.MAX_VALUE`) while every other identity getter returns the 32-bit one, so checking a 36-bit NCI
  against `Integer.MAX_VALUE` publishes `9223372036854775807` as a cell id. Hence separate
  `intOrAbsent` / `longOrAbsent` helpers rather than one clever generic — `UnitsTest` pins it.
  **The exception is `speed_over_ground_knots` and `course_over_ground_deg`**, which publish `0.0` when
  the fix carries no value — a product decision, documented in the README, taken because an unbroken
  series was judged worth more than the distinction. Do not "fix" it back without asking.
  **The two accuracy subjects sit on the other side of that line, off the same `Location` object**:
  `location_fix_accuracy_{horizontal,vertical}_m` are skipped when absent, because `0.0` metres of
  error reads as an exact fix, which is never true and is the most dangerous thing this app could
  say. Note they are checked *separately*, unlike the covariance matrix they also feed, which takes
  both or neither — a zero in one slot of that matrix would read as a perfectly known axis.
- **A location row that says nothing is a bug, and `Waiting` is the only honest silence.** The four GNSS
  subjects ride one callback, so all four go quiet together and the *reason* has to reach all four —
  `LOCATION_SUBJECTS.forEach { statusStore.failed(...) }`. `LocationProvider` emits a `LocationUpdate`
  rather than a bare `Location` for this: `Unavailable` carries text a person can act on, `Available`
  clears it without waiting for a fix (the time to first fix after the switch goes back on is tens of
  seconds, and the stale reason reads as the setting not having taken). The master switch is read from
  `LocationManager.isLocationEnabled` and watched through `MODE_CHANGED_ACTION` — **not** inferred from
  `onLocationAvailability`, which is also false for a phone indoors. That distinction is the whole
  design: only causes that are certain and actionable become a failure, because a row that cries wolf
  when the phone is under a roof is a row nobody reads. A missing permission is reported the same way
  instead of returning quietly, which used to leave four rows on `Waiting` for an entire IMU-only run.
- **Cell identity is the only radio data that needs a permission.** `getAllCellInfo()` requires
  `ACCESS_FINE_LOCATION`; the signal-quality subjects require nothing. When it is denied or the app-op
  is suppressed, the platform returns an **empty list rather than an error** — so "suppressed" and "no
  cells in range" are indistinguishable, and both publish nothing. Do not treat empty as a measurement.
  It keeps working backgrounded only because the foreground service uses the `location` type, which
  grants `PROCESS_CAPABILITY_FOREGROUND_LOCATION`; a `dataSync`-only run gets nothing.
- **The radio subjects break the one-entry-per-subject assumption.** `radio_rssi_dbm` is published from
  both `cellular` and `wifi`, which is how upstream models links. The registry's unique key is therefore
  the **(subject, source) pair**, navigation and status are keyed on the *entry* name, and
  `PublishedSubject.forSubject()` returns whichever entry publishes that subject — fine for
  subject-scoped settings (QoS, rate), wrong for identity. Use `forName()` for identity.
- **Tapping a live card opens the subject, and `detailKind()` decides what it opens *as*.** Three
  presentations — a full plot, a text log, a state timeline — and one classifier in `ui/DetailKind.kt`
  that both the card and the screen branch on, so a subject cannot be plotted in one place and
  tabulated in the other. `raw_nmea0183` is **Text**; `location_fix_quality`, `battery_is_charging` and
  the three radio identifiers are **Timeline**; everything else plots. The identifiers are there because
  a cell id is a *name* that happens to be a number: the difference between two of them is not seven of
  anything, and what one is read for on a moving vessel is handovers.
  **A circular subject is labelled in bearings and summarised as a turn, never as a range.**
  `unwrapAngles` has to run before binning — mixing 359° and 1° into one bin gives a band spanning the
  whole circle — and it deliberately leaves 0-360, so *its* extremes are not directions: a phone turned
  round and round read `135 – 638 °`, which is neither a bearing nor a range of them. The fix is at the
  labels, not the geometry. Axis ticks go through `formatBearing` (mod 360, zero-padded, which is itself
  the tell that it is a bearing), and the footer states `circularTurn()` — where it started, where it
  ended, how far it turned, with the direction as a **word**: `-225°` is a turn to port to anyone who
  reads the minus and a mystery to anyone who does not. The turn is *net*, so swinging out and back is
  `steady` rather than a distance travelled. A window spanning more than a full turn can label two
  gridlines the same, which is true rather than confusing — the vessel did pass that bearing twice, and
  the footer says how far it went.
  Note the constraint the unwrap rests on: **consecutive compass readings never jump 180°**. A test
  written with a synthetic 198° step had it read as -162° and reported a 495° turn as 135° — the code
  was right and the data was not.
  **The detail plot draws against the data's true range, deliberately not `plotBounds`.** That floor
  exists because a 34dp sparkline has no axis, so a full-height wobble is indistinguishable from a real
  swing and a barometer varying 0.002% has to render flat. A labelled axis removes the ambiguity — and
  keeping the floor here drew air pressure as a straight line on a 220dp canvas whose own footer said
  the range was 5 Pa, which defeats the screen. `boundsOf` already pads a constant series, so a
  degenerate axis is not a case to handle.
  **A timeline segment ends at its own last sample, not at the next one's start** — the two differ by a
  sample interval, and at 0.2 Hz that is five seconds added to every duration. Rows are ordered
  first-seen, because sorting would have them jump about as a new cell id appears mid-run.
  **The text is kept in its own ring and deliberately not on `LiveSnapshot`.** Copying two thousand
  strings on the Live tab's 5 Hz ticker, for a screen usually closed, is the exact tax this store exists
  to avoid; `liveText(subject)` is pulled by the detail screen alone. The ring is 2000 lines because
  NMEA was measured at **77 sentences a second** — the obvious few hundred would hold under four
  seconds. It is the one place in `LiveSampleStore` that stores objects rather than flat primitives, and
  if more history is wanted the answer is the MCAP recording, not a bigger ring.
  Note the numeric ring still gets the sentence *length*, so the subject keeps a rate, a sample count
  and its place in the health checks — the card just knows not to plot it. Do not have the card state a
  line count from that ring: it is 8192 where the text ring holds 2000.
  **NMEA shed is wildly variable run to run and is not caused by this.** Measured across three runs of
  the same length: 123 shed with the text ring, 12 without it, then 6 with it again. It tracks GNSS
  burst size and sky view, not collector cost — worth knowing before reading a single run as a
  regression.
- **The live view pulls, it never gets pushed.** `LiveSampleStore` is deliberately not a `StateFlow`:
  the publish path runs at ~217 samples/s across 8 collectors, and emitting per sample would put the
  UI's recomposition rate at the mercy of the sensors. Collectors only append to a per-subject ring;
  `LiveScreen` pulls a snapshot on a 5 Hz `produceState` ticker. Measured with the view open: IMU still
  55.3 Hz, 0.29% janky frames. If you ever find yourself adding a flow emission on the publish path,
  that is the thing this design exists to avoid.
- **The ring buffers are `synchronized`, and the reason is visibility, not just races.** Collectors run
  on `Dispatchers.Default` and can migrate threads; Compose reads on Main. A plain array write would
  not be reliably visible. Note `LiveSampleStoreTest` had to be written carefully to catch this — a
  first version passed against an unsynchronised append because every writer wrote the same value to
  one ring; the test now uses distinct values on one ring and fails without the lock.
- **The live view keeps the raw `Location`, not the published one.** The wire carries `0.0` for an
  absent bearing on purpose; a map trusting that would draw a heading arrow due north whenever the
  phone is stationary, which is most of the time. `TrackPoint.bearingDegrees` is nullable for this.
- **OSM tiles cannot be bulk-downloaded, and osmdroid enforces that — offline maps are imported.**
  `TileSourceFactory.MAPNIK` is built with `TileSourcePolicy(2, 15)`, and `15` sets all four flags
  including `FLAG_NO_BULK`, so **every `CacheManager` constructor throws `TileSourcePolicyException`**
  for OSM. `downloadAreaAsync` is unreachable; do not plan a "download this area" button against the
  default source. What works instead costs almost nothing: `MapTileProviderBasic` already builds a
  `MapTileFileArchiveProvider`, and that provider's `findArchiveFiles()` lists
  `Configuration.getOsmdroidBasePath()` — so an archive **copied into `filesDir/osmdroid` is picked up
  with no provider wiring**, ahead of the downloader in the chain. Hence `osmdroidBasePath()` is one
  function used by both the import and `configureOsmdroid`: two spellings of that path would leave a
  400 MB file sitting next to a blank map. The extension is the whole dispatch key
  (`ArchiveFileFactory` keys on it: `mbtiles`, `gemf`, `sqlite`, `zip`), so `archiveFileNameOrNull`
  rejects anything else *before* the copy — `OfflineMapsTest` pins that. Note `findArchiveFiles()` runs
  when the provider is constructed, so an import appears the next time a `MapView` is built.
- **Esri's satellite URL puts the row before the column, and attribution is not automatic.** Two traps
  in one file. `/tile/{z}/{y}/{x}` is not what `XYTileSource` builds (`{z}/{x}/{y}`), so the satellite
  layer is a custom `OnlineTileSourceBase` overriding `getTileURLString` — swap the two and every tile
  still loads, from the wrong place, which reads as a working map of somewhere else. And
  `CopyrightOverlay` **must be added explicitly**: osmdroid does not draw the notice by itself, the map
  never added one, and a comment claimed otherwise for as long as that was true of nothing. Both OSM's
  and Esri's terms require it. There is no bundled global satellite source — `TileSourceFactory.USGS_SAT`
  is the United States only and is blank over Sweden.
- **osmdroid needs a user agent or it silently shows nothing.** OSM's tile servers answer the library
  default with `403`, and the failure looks like a blank grid rather than an error. `TrackMap`
  sets `Configuration.userAgentValue` to the package name and calls `MapView.onResume()` — `AndroidView`
  does not forward lifecycle, and osmdroid starts its tile threads there. Both were needed before a
  single tile appeared.
- **Tags travel in the recording, as an MCAP Metadata record.** `McapWriter` writes one named `tags`
  holding a single entry, `tags`, newline-separated — verified with the real `mcap` Python library,
  which reads it back as `tags {'tags': 'quay trial\nengine run'}`, so any MCAP tool can see them.
  A **MetadataIndex** goes in the summary beside Statistics, so a reader finds them in one seek rather
  than the full scan the track needs; `readMcapDetails` follows it.
  **Written at close, never at open.** `RecordingSession.close(tags)` takes them as an argument for
  exactly that reason — "the configuration at the end of the run" is what gets stored — and it has a
  consequence worth stating: a run that **rotates** at 512 MB gives each file the tags that were on as
  *it* closed, not the run's final set.
  Two traps. `putBytes` already writes the uint32 length prefix, so the map needs `putBytes(encoded)`
  and **not** a `putUInt32` before it — writing the length twice produced a file that parsed perfectly
  and carried no tags. And the metadata count in Statistics has to move with the record, or the figures
  describe a file that is not there.
- **Audio, the time-lapse and video are switched on the Session page, and nowhere else.** They used to
  be a collapsed section two screens away in Settings *and* rows inside the Session page's Device group,
  because `toggleSubject()` already mapped `AUDIO` / `IMAGE_COMPRESSED` / `VIDEO_COMPRESSED` onto
  `audioEnabled` / `cameraEnabled` / `videoEnabled` — the same control in two places, which is how they
  drift. `MediaSection` is now the one place, and it sits **last on the page**, below the per-subject
  groups: audio and the camera are the heaviest things a run can carry and the least often changed, so
  above the groups they pushed the sensor list a screen further down for a control most runs never touch.
  **`MainScreen` filters `START_TIME_SUBJECTS` out of the per-subject groups** so the rows do not also
  appear there. That set is *exactly* those three — `MediaSubjectsTest` pins it, because a fourth
  start-time subject added later would otherwise vanish from its group with no switch anywhere — and the
  group master switch already filtered on it for the same reason, so this is the existing rule one level
  up. Device drops from twelve entries to nine.
  Two things the section has to carry because the Device rows carried them: a **tap-through per subject**
  (the time-lapse *interval* and the video frame rate are subject rates, set on their own page, not
  settings), and **"not on this device"** for hardware that is absent, since a switch that can be pressed
  and does nothing is worse than one that is plainly disabled.
  **Every change here restarts the run**, said once at the top of the card rather than on each control —
  unavoidable, because the foreground-service type and its runtime permission are fixed at
  `startForeground`. `SettingsScreen` builds `edited` as `initial.copy(...)`, which is what made removing
  its media block safe: the fields simply pass through untouched. Verified rather than assumed — with the
  time-lapse on at 1280x720, an unrelated Settings save left `camera_enabled=true` and
  `camera_width=1280` exactly as they were.
- **A tag is a state, not an event, which is why it lives on the Session tab as switches.** A quick mark
  says something happened at a moment; a tag says what the whole run *is*. It began on Events and moved:
  Events is for marking moments as they pass, and what the run *is* belongs with the other things chosen
  before Start — directly above **Sampling rates**, which is the same kind of decision one step less
  fundamental (the rates say how much of the run there will be; the tags say what it was). The gear in
  the section header turns the chips into an editor rather than opening a screen: the chips *are* the
  control, and this is the control for them.
  The vocabulary and which of them are on both persist in `Settings`, like the annotation buttons — a boat that is always "harbour
  trial" should not have to be told twice — and toggling one goes through **`update()`, never
  `saveSettings()`**, the same rule the per-subject switches follow: tearing down the Zenoh session and
  the open MCAP file to record a word would end the very run being labelled. `PublisherService.watchTags`
  pushes the set into the running recorder, exactly as `watchOffSubjects` does.
  **A settings profile carries the vocabulary but not the selection.** The words are fleet
  configuration; which are switched on is the state of one run on one phone, and importing somebody
  else's would quietly label this phone's next recording with their afternoon — a label that then goes
  into the file. Anything active but missing from an imported vocabulary is dropped, or a phone stays
  switched on for a tag it cannot see.
  The Files detail screen shows a recording's tags **read-only**: a closed MCAP file is not rewritten to
  change its mind about what the run was.
- **Tags are the only thing on a recording that a person chose.** A file name is a timestamp: it answers
  "when" and nothing else, which is why a list of them is hard to read. `RecordingTags` stores free text
  per recording and the search matches it, so "quay trial" finds the run somebody labelled that.
  The separator is a newline and `normaliseTag` guarantees no tag contains one — a delimiter that cannot
  appear in a value needs no escaping, which is where round trips go wrong. `RecordingTagsTest` pins
  that, because the failure is quiet: a tag that splits in two on the way back out is a tag nobody typed.
  This began as a per-recording DataStore keyed on the file name, edited from the Files detail screen.
  That is gone: a tag that does not travel with the file is a note about somebody's phone, not about the
  recording, and the first thing anybody does with a recording is copy it off.
- **"Why do I not see the latest recording?" has three answers and none of them is a stale list.**
  Measured rather than assumed — deleting a file behind the app's back and returning to the tab takes
  the count from 7 to 6, so navigation genuinely re-reads. What does explain it: a run **in progress** is
  deliberately absent, living in app-private storage until it closes; a run that was **interrupted**
  rather than stopped reaches Downloads **when the app is opened**, and the Files tab awaits that
  sweep before it lists, so the answer it gives is never one taken mid-rescue. It arrives labelled
  "incomplete, never closed". That used to happen only when the **next run started** —
  `publishOrphans()` was called from `Recorder.start()` alone — which meant a phone back from a flat
  battery showed an empty Files tab, with the recording sitting in `filesDir/recordings` where no
  file manager can see it either, until somebody happened to press Start. Reproduced on the dev
  phone: a 1.5 MB recording, every record intact, invisible. Fixed by sweeping from
  `MainActivity.onCreate` and from the Files route as well; and a run that ends **while the Files tab is on screen**
  — which only the notification's stop action can do — used to need a trip away and back, since the
  listing is otherwise read once per composition and after a delete. That last one is now handled by
  bumping the revision when `recording.recording` goes false.
- **The Files list is searched, ordered and filtered, and all three read a recording's *name*.** With 119
  recordings on the dev phone whose names differ only by a timestamp, the list was unusable without them.
  **Search ignores separators on both sides**, so `2026-08-21`, `20260821` and `0821` all find
  `logline-2026-08-21T104536.mcap` — a recording's name *is* its date, so a search insisting on the exact
  punctuation would be a date search that rejects dates. A query of pure punctuation normalises to empty
  and matches everything, which is what an empty box does and the only sensible reading of "no constraint".
  **An unknown duration sorts last under "Longest first", never as zero.** An incomplete recording has no
  summary and so no duration, and it may well be the longest run there is — it is the one that was still
  going when the process died. Ranking it as a zero-second run buries exactly the file somebody is
  hunting for. Every order breaks ties by newest so the list cannot reshuffle under the thumb.
  **`items(key = …)` anchors a `LazyColumn` to the item it was showing**, which is right for a delete and
  wrong for a re-sort: choosing "Largest first" scrolled to wherever the previously-visible file had
  moved, so the 479 MB recording sat at the top of a list still showing 2 MB ones and the control read as
  broken while working perfectly. A `LaunchedEffect` scrolls to the top when the ordering changes,
  guarded against firing on first composition — `rememberLazyListState` restores through the tab's
  `saveState`, and jumping to the top on every return from a recording would be its own small wrongness.
  **Three empty states, not two.** "Nothing saved yet" is a lie with a hundred files on the phone, so a
  search that excludes everything gets its own, and it keeps the toolbar on screen so there is a way out.
  The count says `23 of 119 recordings` whenever anything is hidden, because a filter that quietly drops
  ninety-six files looks exactly like a phone that has lost them.
  **`RecordingFacts` exists so the logic can be tested.** `SavedRecording` carries a MediaStore `Uri`,
  which has no implementation off a device, and there is no Robolectric and no mocking framework on the
  test classpath — the same stance that had `PlatformGeometryParse` choose `kotlinx-serialization` over
  `org.json`. Naming the four facts the query actually reads is what keeps it a JVM test.
- **Every row carries its track, and the cost of that is the whole design.** Extracting a track means
  decompressing a recording's entire data section — `McapWriter` writes no chunk index — so this looked
  like the one thing not to do per row. Measured rather than assumed, it is cheap for almost every row
  and expensive for one: of the 67 listed recordings the median is **2.1 MB** (~18 ms at the measured
  111 MB/s) and exactly one is 502 MB (**4.55 s**, measured through `/proc/<pid>/io`). So it is read
  lazily from inside the row — a `LazyColumn` composes a handful, and `produceState` keyed on the file
  cancels with the row — through a **`Semaphore(2)`**, so a fast scroll cannot start sixty reads.
  **`TrackCache` makes it a once-ever cost**: `filesDir/tracks/<id>.trk`, keyed on
  `(id, sizeBytes, savedAtMillis)` so a file replaced under the same MediaStore id cannot show the old
  shape. Verified: the second launch reads **0.4 MB** where the first read 504 MB. Coordinates are
  **doubles, not floats** — a float's ~1 m error at 57°N is wider than most of the stationary runs this
  is meant to tell apart. An **empty track is stored as an answer**, or every visit rescans every
  IMU-only run to learn the same nothing. A `stoppedEarly` scan is deliberately *not* cached: one wrong
  shape cached is wrong forever, and re-reading a truncated file is the cheaper error.
  The listing now reads `readMcapDetails` rather than `readMcapSummary` — same seeks, same summary
  section — purely so `fixChannelId` comes for free and **a recording with no GNSS never starts a scan**.
  **New scans do not run while a run is recording.** Cached rows still draw. Reading half a gigabyte off
  the volume the recorder is draining onto is exactly the contention this codebase goes out of its way
  to avoid, and Files is a between-runs screen.
  `tracks` is excluded from **both** backup files, like `recordings` and `osmdroid`: it is derived data
  keyed on MediaStore ids that mean nothing on another phone.
- **The thumbnail is a canvas, and below the span floor it stops being one.** It cannot be
  `RecordingChart`: a `MapView` owns tile threads and a tile cache, and sixty-seven of them in a list is
  not a thing to do — so a row shows **shape and scale, not place**, and place stays one tap away.
  It uses the same `MIN_CHART_SPAN_METRES` floor as the detail chart, and that has a consequence at
  64dp the 220dp chart does not have: a 10 m scatter inside a 200 m frame is about **three pixels**,
  which is honest and invisible. So a track under the floor is drawn as a **ring and dot** meaning
  "did not move" — in the track's own colour, because it *is* a track. The muted **dash** is the
  different answer, "this recording has no positions at all", and an empty square is the third,
  "nobody has read it yet". Three states that would otherwise all be a blank square.
  `thumbnailOffsets()` is pure and tested because none of its arithmetic is visible until it is wrong:
  at this size a squashed track still looks like a plausible track. Note the trap that caught the first
  version of its test — each track is scaled to **its own** extent, so the `cos(latitude)` correction
  has to be checked *within* one track; comparing two separate tracks gives a ratio of 1 whatever the
  projection does.
- **Where recordings are written is one setting with two jobs, and that is deliberate.**
  `Settings.recordingsFolderUri` began as a read-back grant — MediaStore ties a file to the install
  that wrote it, so a reinstall leaves the app's own recordings invisible — and is now also the
  destination, with the exports following into a `config` subfolder inside it. Blank means
  `Downloads/Logline` and nothing about that path changed. Splitting the two would let a phone fill
  one folder while listing another, which is a state nobody could diagnose from the screen.
  **The picker already took the write flag**, so this needed no new permission path — only a new
  consumer. `persistedFolderGrants` now requires `isWritePermission` as well as read: a grant that
  cannot be written to is not a destination, and finding that out at the end of a run is too late.
  `saveOutput` in `record/Downloads.kt` is the one entry point and branches on the Uri being blank.
  The `MediaStore` arm is unchanged, `IS_PENDING` and all. The tree arm uses `DocumentsContract`
  directly rather than `androidx.documentfile` — the listing beside it already speaks that — and gets
  the pending guarantee from a **`.part` name renamed on success**, since SAF has no such flag; a
  reader must never take a half-copied 512 MB file for a whole one, and `isPartialName` keeps those
  out of the listing. The `config` folder is **found before it is created**, because `createDocument`
  on an existing name makes `config (1)` and a phone exporting weekly would fill up with them.
  **Read when a file is published, never when a run starts**, so choosing a folder mid-run lands the
  next file — including the one a rotation is about to open — and it goes through `update()` rather
  than `saveSettings()`, the rule the grant already followed. The **orphan sweep is passed the folder
  explicitly** (`publishOrphanRecordings(folderUri)`): it runs at app launch with no run behind it, so
  there is nothing to have pushed one in, and a rescued recording landing somewhere else is a split
  nobody would look for.
  **A document id is only a path on a storage volume.** `folderLabelOf` turns `primary:Download/Logline`
  into `Download/Logline` and refuses ids whose first chunk is not `primary` or a `1234-5678` serial —
  a Drive folder granted on the dev phone came back as `acc=1;doc=encoded=E0ykFiymkwJqjorOU52y7snEhi7…`,
  which is not a name anybody would recognise. `folderLabel(context, uri)` falls back to asking the
  provider for the display name, which gave `GPSLoggerPHONE_4`. Verified end to end against that Drive
  folder: a run landed there, two consecutive exports both went to one `config` subfolder, and the
  Files tab listed the recording and not the exports.
  Note the free-space estimate still measures `filesDir`, which is what actually stops a run — the
  file is written there and copied afterwards — so a destination on another volume can fill while the
  estimate is happy. Recorded in TODO.md rather than solved.
- **Exports go to `Downloads/Logline/config`, recordings to `Downloads/Logline`.** A subfolder, and that
  is what keeps a settings profile out of the Recordings tab: `savedRecordings()` matches
  `RELATIVE_PATH` for *exactly* `Download/Logline/`, so anything a level down is excluded by
  construction rather than by a filter somebody has to remember to keep working. `saveToDownloads` takes
  the folder; `CONFIG_FOLDER` is used by the settings profile, the platform geometry and the platform library, and
  `RecordingsQueryTest` pins that the two folders stay nested — as siblings every export would be back
  in the list. Files written before this still sit among the recordings and are still labelled by
  `recordingKindOf`, which is why that classification stays worth having.
- **`Downloads/Logline` is not a folder of recordings, and `savedRecordings()` lists it by *path*.**
  Four things write there — the recorder, `exportSettingsProfile`, `exportPlatformGeometry` and
  `exportPlatformRegistry` — and the query filters on `RELATIVE_PATH` with **no extension test**, so all
  four appear in the Files tab. `recordingKindOf(name)` names them, which is what stops an export being
  read as a recording that failed: a platform-geometry export used to read `982 B · no summary`, describing a
  broken recording rather than a file that is exactly as it should be.
  **`RecordingFacts.isComplete` is therefore `Boolean?`, and the null is load-bearing.** While it was a
  plain `Boolean` every JSON in the folder was *incomplete*, so a bulk delete built on the Incomplete set
  would have destroyed a hand-surveyed platform geometry document — a thing no amount of re-recording brings
  back, only a tape measure. Null means "not a recording, the question does not apply", and because both
  filter arms compare against a value (`== true`, `== false`) those files fall out of each without the
  query logic knowing kinds exist. `RecordingsQueryTest` pins it; that test is the one that fails if
  anybody simplifies the type back.
  Four such exports were already in the folder on the dev phone and happened to be invisible, because
  MediaStore ties a file to the install that wrote it and an earlier install wrote them. Luck, not
  design — a profile exported today is listed, which is exactly how this was verified.
  **The bulk delete is offered only under the Incomplete filter**, so the set it removes is exactly the
  one named on the button and it can never become a one-tap way to destroy good recordings. Its dialog
  has to carry the thing the word "incomplete" invites people to get wrong: these runs hold **every
  message they captured** — only the closing figures were never written, and this app reads them
  perfectly well. Measured on the dev phone: 50 files, 542 MB. Figures rather than adjectives, the same
  rule that produced `~241 MB/h` over "much larger files". `deleteSavedRecordings` returns a **count**
  rather than a boolean because MediaStore refuses a delete from a package that did not write the file,
  so a sweep can partly fail and the snackbar says which — silence there would read as a clean sweep.
  Note that limitation is not small: of the 89 incomplete recordings on this phone's disk, only 50 are
  reachable at all.
  The button is an `OutlinedButton` with `error` **content**, never a filled red block — that one is
  Stop, deliberately, and this is a step up from the per-row `TextButton` without taking it over.
- **A recording's figures are free; its track is not, and the Files tab is built around that split.**
  `McapWriter.finish` puts the Schema and Channel records in the summary section beside Statistics, and
  `writeStatistics()` already emits `channelMessageCounts` — so `readMcapDetails()` gets every topic and
  its own message count for the same few seeks `readMcapSummary` pays, whatever the file's size. The
  **track** is the opposite: the writer emits no ChunkIndex, so `McapTrack` has to decompress every chunk
  to find the fixes. That is why the track is on the detail screen and not a thumbnail in every row, and
  why the two are loaded separately — the figures must never wait for the picture.
  Three things in `McapTrack` are load-bearing. **It picks the fix channel by source and excludes
  `calibration`**: two registry entries publish `location_fix`, and a platform's surveyed zero point is a
  jetty somebody stood on with a tape measure — including it draws a line from the boat to the shore and
  calls it a track. **No fix channel means the scan never starts**, which is what makes an IMU-only run
  free rather than a full decompress that finds nothing. And **`0, 0` is dropped**, because proto3 cannot
  tell an absent double from a zero one and a single fix in the Gulf of Guinea rescales the whole chart
  to the Atlantic.
  The chart scales longitude by **`cos(latitude)`** — 0.54 at 57°N — or a track drawn on raw degrees comes
  out nearly twice as wide as it was sailed. Same correction, same reason, as the accuracy circle's
  `metersToPixels`.
- **A track fitted to its own extent draws GNSS scatter as a voyage, and `MIN_CHART_SPAN_METRES` is the
  same fix `plotBounds()` made for the barometer.** Every recording on the dev phone is a phone sitting
  indoors, and on the longer axis — which is what `trackExtentMetres()` answers — a **three-hour** run of
  1 843 fixes spans **10.6 m**, a 47-fix run 14.8 m, and a 17-fix run **1.8 m**. Autoscaled, all three
  filled the card with a vigorous journey. The chart therefore never opens on less than **200 m**: about
  thirteen times the widest scatter seen here and well above a single fix's own horizontal accuracy, so
  anything genuinely under way still fills the frame. **It is a floor on the chart, never on the data** —
  the fixes are drawn where they were, and a small track simply occupies a small part of a frame.
  The footer states the extent alongside the count, and **the preposition carries the finding**: `1843
  positions within 11 m` against `980 positions over 1.2 km`. The count alone cannot tell them apart —
  1 843 positions reads as a passage whichever it was — which is exactly what the screen used to say.
  No verdict word, for the reason `13 dB` beats "Good": the reader is the one who knows whether eleven
  metres matters.
- **`RecordingChart` is not `TrackMap`, and that is deliberate.** The live chart exists to follow a fix
  that is still arriving — follow mode, heading vector, course vector, live position — and none of those
  mean anything about a file that closed hours ago. What the two genuinely share is shared as functions
  (`sourceFor`, `configureOsmdroid`, `chartInk`, `attributionColour`, `maxZoomFor`) rather than by making
  one component serve both. Three things carried over because they are not optional: the track is drawn
  **twice**, a white halo under the coloured line, since no single colour is legible on both pale tiles
  and dark imagery; `CopyrightOverlay` must be added by hand, osmdroid draws no notice by itself and both
  licences require one; and `onResume()` starts the tile threads, without which the map is a blank grid
  that reads as broken. One trap of its own: **`zoomToBoundingBox` is a no-op before the view is laid
  out**, which is exactly when `update` first runs — hence `addOnFirstLayoutListener`, the same shape as
  the live chart's note about `animateTo`. The box is squared before use, because osmdroid fits the whole
  box into the view and a box one metre tall by two hundred wide would still zoom to the metre. And it is
  fitted **once per track, not per recomposition**: `update` runs again whenever the layer or the key
  changes, and re-fitting there yanks the view back to the whole track just as somebody has zoomed into
  part of it.
- **The recording's chart opens full screen, and that is what makes it usable.** Collapsed it sits in a
  `verticalScroll`, so a drag across it is a gesture the page and the map both want and the page wins —
  panning barely works, which is not a thing to fix with a bigger card. Expanded there is no scroll to
  compete with and the pinch, drag and double-tap the `MapView` has always had come into their own.
  It mirrors the live view's `mapExpanded` deliberately, down to the glyphs: `rememberSaveable` rather
  than hoisted, because expanding is something you do for a minute while looking at something rather
  than a preference carried between screens; the top bar **stays**, for the reason recorded there — a
  control that disappears is how somebody ends up stranded on a full-screen map; and it fills `padding`
  rather than a hand-tuned height, which is correct on every device by construction.
  Two things this one adds because it is a *pushed* screen rather than a tab. **Back collapses before it
  leaves** — a `BackHandler` plus the same lambda behind the bar's arrow, so the gesture and the arrow
  agree and a full-screen chart is not somewhere you accidentally back out of the recording from. And
  the title becomes **Track**, which is the only thing on screen saying what you are now looking at.
  `MapIconButton` moved to `ui/MapIcons.kt` to be shared. The recording's chart deliberately does *not*
  reuse `MapToolbar`: there is no fix to follow and no marks to draw over a run that finished hours ago,
  so three quarters of that toolbar would be controls for things this chart has not got.
- **The track reader must read every shape this app has ever written, and saying otherwise is a lie about
  somebody's day.** `TrackScan` carries three facts rather than a list — `channelFound`, `fixes`,
  `stoppedEarly` — because an empty list has three causes the screen must never merge: a run with no
  GNSS, a fix channel that yielded nothing, and a read that failed before it could say. Both bugs this
  shape exists to prevent were found on the phone rather than reasoned about, and both put a confident
  false statement on screen.
  **Messages are read at the top level as well as inside Chunks.** Everything written today is
  zstd-chunked; everything written before that change is a flat run of `OP_MESSAGE` records, and 101 of
  the 216 files on the dev phone are that shape. Descending only into chunks had a 479 MB, three-hour
  recording holding **1 843 fixes** report "Not enough positions — this recording holds 0". Note it read
  the whole 502 MB while finding nothing, because an 8 kB buffer faults the file in to satisfy the skips;
  a fast scan is not evidence it looked at anything.
  **A scan with no channel list discovers the fix channel itself.** `McapRecovery.finalise` used to
  write `summary_start = 0` for every run a killed process interrupted, so `readMcapDetails` returned
  null and there was no channel list to consult — and a missing *footer* says nothing whatever about
  GNSS. Passing null means "find it", which works because `McapWriter` keeps Schema and Channel records
  outside the chunks and ahead of the messages. Before this, a rescued 3 MB recording holding **47
  fixes** said "GNSS was not publishing while this ran". Recovery rebuilds the summary now, so a
  freshly rescued file *does* hand over a channel list — but every file rescued before that still does
  not, so the null branch is live and stays.
  **The walk stops at `OP_DATA_END`**, and that is what makes a short read unambiguous rather than
  merely tidy: everything past it is summary, so a well-formed file always reaches it, and running out
  of bytes first means the file was cut off mid-record. Hence `readExactly`/`skipExactly` throw where
  the old helpers answered null — the one catch turns that into `stoppedEarly`, and the footer reads
  "N positions **so far** — reading stopped early". Without the `OP_DATA_END` break the trailing eight
  magic bytes are a short 9-byte header, and *every* healthy file would report itself truncated.
  Measured on a Pixel 6, `/proc/<pid>/io` sampled at ~5 Hz through `run-as`: the 479 MB file is read in
  **4.33 s at 116 MB/s**, track on screen within five seconds of the tap. A spinner is the right
  affordance; no progress bar is needed.
- **`readMcapSummary` is the deliberate mirror of `McapWriter.writeStatistics()`, and only a test holds
  them together.** It steps over four fields it does not want to reach the four it does, in the right
  widths, so a wrong width produces a plausible number rather than an error — `McapSummaryTest` writes
  a file with the writer and reads it back rather than anyone eyeballing offsets. It returns **null**
  where the file has no summary section, which is not a corner case: every recording rescued before
  recovery learned to rebuild one has no statistics, and a file whose chunks cannot be decompressed
  still gets none deliberately. Zeroes there would report a 62 MB recording as empty.
- **A rescued recording is finished the way a closed one is, and the statistics are rebuilt rather
  than abandoned.** `McapRecovery.finalise` used to trim the file and stamp a footer declaring
  `summary_start = 0`, the spec's "no summary", on the argument that the statistics had never been
  written. They had not, and they were still *derivable*: everything a summary states comes out of the
  data section, which is where it came from in the first place.
  What that cost was measured on the phone rather than argued about. A reader with no time range has
  none to show, so **Foxglove opened a rescued recording on a timeline running from 1970 to the
  afternoon it was made** — 46 years of nothing, with every message minutes old — and `mcap info`
  answered `channels: unknown`. The writer was never at fault: `log_time` is `Instant.now()` at the
  drain, every Chunk header carries a real range, and three closed files parsed record by record had
  no zero anywhere.
  So the walk that finds the trim point now also collects what the summary needs — the Schema and
  Channel records **verbatim**, so nothing has to parse them; the per-channel counts and the log-time
  range, which means decompressing every chunk; and any Metadata records, for the indexes. Then
  `McapWriter.finishRescued` states it. The *layout* has one implementation, `writeSummary`, shared
  with `finish()`: a second transcription of the summary offsets and the footer's two pointers is
  exactly the thing that drifts and fails silently.
  Three things are load-bearing. **A chunk that will not decompress falls back to `summary_start = 0`**
  rather than to figures that undercount — the same argument `readMcapSummary` makes for returning
  null, and a count somebody plans against must not be a guess. **No ChunkIndex and no MessageIndex**,
  for the reason recorded below: a rescued file gets exactly what a closed one gets. And the file
  carries a **`recovery` Metadata record** saying the run was interrupted, because a rescued file is
  now a proper MCAP and nothing else about its bytes says so — `SavedRecording.isComplete` is
  `summary != null && !rescued`, and without the second clause every interrupted run would quietly
  become complete and drop out of the bulk delete.
  Verified end to end on a Pixel 6: a 30 s run killed with `am force-stop`, swept at the next launch,
  came back reporting **108 354 messages over 29.98 s** starting at the run's own first message — and
  `mcap recover`, counting independently, agreed to the message. Files already rescued and sitting in
  Downloads are *not* upgraded; `mcap recover in.mcap -o out.mcap` fixes those on a desktop.
- **MCAP records the unwrapped payload, never the envelope.** keelson's replayer re-wraps with
  `enclose(payload=message.data, enclosed_at=message.publish_time)`, so writing envelopes produces
  doubly-wrapped messages that decode to garbage everywhere downstream. The channel topic is the full
  Zenoh key, because the replayer republishes it verbatim. `connectors/mcap/bin/keelson2mcap.py` is the
  reference — read it rather than the connector README, which says "records envelopes" and means the
  opposite.
- **The recording is zstd-chunked, and the flush interval is a recovery decision rather than a
  compression one.** It used to be unchunked and uncompressed — the least dense form the spec allows —
  and the cost was measured, not guessed: a 38.4 MB capture held 17.4 MB of payload across 668 039
  messages, so **55% of the file was framing**. Thirty-one fixed bytes per message against a ~26 byte
  average payload, most of it structurally redundant (an eight-byte length whose top five bytes are
  always zero, two absolute epoch timestamps differing only in their low bytes, a dense sequence
  counter, `frame_id` re-serialised at the sample rate, and float32 readings widened into
  `foxglove.Vector3`'s doubles so ~11 bytes per IMU message are guaranteed-zero mantissa). Measured
  after: 241 MB/h against 720, and a file that stores *less* than the payload it contains.
  zstd because **MCAP standardises only zstd and lz4** — `java.util.zip.Deflater` would produce files
  Foxglove and `mcap-python` refuse. Level 3, because this runs on the drain coroutine, the one thing
  that must not fall behind.
  **Chunks flush at 256 kB or after two seconds, whichever comes first, and the time bound is the point.**
  A killed process loses the open chunk, where before it lost a single partial message; without a time
  bound that loss would scale with the sample rate rather than the clock. Verified on a Pixel 6 by
  killing the app 25 s in: 87 193 messages covering 24.7 s came back. `McapRecovery` needed no change at
  the time — a Chunk is a length-prefixed record, so a truncated one is already its incomplete-record
  branch — and `readMcapSummary` needed none either, because MCAP keeps summary records *outside*
  chunks. It reads inside them now, but only to rebuild a summary, and the trim is unchanged.
- **What an abrupt end costs, and the chunk bound is only half of it.** The bullet above bounds the
  *open chunk*; there are four buffers between a sample and the disk, and a flat battery or a kill
  takes a different set of them. At the measured 241 MB/h default (~67 kB/s of file):
  the `Recorder` channel (~45 s of capacity, but drained continuously, so normally near-empty); the
  **open zstd chunk**, 256 kB uncompressed or 2 s; the **64 kB `BufferedOutputStream`** in
  `RecordingSession`; and the **OS page cache**.
  **`RecordingSession.commit()` collapses the bottom two into the chunk bound.** After each chunk it
  flushes the buffer and calls `fd.sync()`, so the only thing in flight is the open chunk — which
  means a process kill and a hard power cut now cost the *same* thing, at most two seconds, where
  before the cut was bounded by the kernel's 5–30 s writeback schedule rather than by anything this
  app controls. It used to be that `sink.flush()` appeared exactly once, in `McapWriter.finish()`.
  **The cost was measured, because it lands on the drain**: a Pixel 6 at 336 samples/s across 50
  streams took **1.65 ms mean** over 71 commits (1.29–1.99 ms), one every **~1.4 s**, i.e. **0.12% of
  the drain's wall time**, with nothing dropped. The interval is set by the 256 kB size bound rather
  than the 2 s time bound at those rates, so a *slower* run syncs less often, not more. A sync per
  message would be a different proposition entirely — the same 1.65 ms would be over half the drain's
  time at 336/s and would not keep up at the 800/s this app has been measured at.
  The one real end-to-end measurement of a kill still stands: 25 s in, 87 193 messages covering
  24.7 s came back, ~0.3 s lost. **The hard-cut case has still never been reproduced on this phone** —
  what changed is the bound, not the evidence — so say so before quoting it at anybody.
  Either way the file is left with **no DataEnd, no summary and no footer**, which is unreadable
  rather than merely lossy: a reader seeks to the footer first, so every message is present and none
  is reachable until `McapRecovery.finalise()` has walked it — which now happens when the app is
  opened. Measured on the real thing: an orphan of 1 540 716 bytes came back as 1 540 766, every
  original byte preserved verbatim and **50 appended** — a DataEnd, a footer and the closing magic —
  with all 15 chunks, 12 schemas and 50 channels intact across the 20.5 s it had recorded. Nothing
  was trimmed, because the last chunk had flushed before the phone went down. What gets appended is a
  whole summary section now rather than those 50 bytes, so the figure is a record of the measurement
  and not a constant to check against.
- **`McapRecovery`'s walk validates the opcode, not just the length, and that is what stops a zero
  tail being read as data.** A *truncation* leaves a partial record that does not fit the file, which
  the length check alone catches. A *power cut* on a filesystem with delayed allocation leaves
  something else entirely: the length was journalled while the last blocks never reached the disk, so
  the tail comes back as **zeros rather than short** — and zeros parse. Opcode `0x00`, length `0`,
  nine bytes consumed, repeat.
  Reproduced before fixing rather than reasoned about: a 4 kB zero tail had **one byte trimmed
  instead of 4096**, the walk having marched through 455 phantom nine-byte records and stamped the
  footer after all of them. An undefined `0x7F` opcode was accepted the same way.
  The accepted range is the spec's own `0x01`–`0x0F`, not the narrower set `McapWriter` emits: both
  fix the case, and the wider one cannot reject a record this app writes today or gains the ability
  to write tomorrow, which a hand-listed set would eventually do silently and destructively. Checked
  against a real interrupted recording from the dev phone — 1 540 716 bytes, trim 0 before and after,
  so the stricter rule costs a genuine orphan nothing.
- **The orphan sweep must never touch a file the drain owns**, and `Recorder.owned` is what stops it.
  `McapRecovery.finalise()` truncates to the last complete record and appends a footer; run against a
  live file that leaves the writer positioned past the new end, so everything it writes afterwards
  lands beyond the footer and a reader silently stops at it. `publish()` would then copy a partial
  file and delete it, and the run would carry on writing to an unlinked inode.
  It is a **set**, not the one live file, because a rotation hands the finished file to its own
  coroutine and opens the next immediately — so for the length of a 512 MB copy the drain owns two,
  and tracking only the live one would let a sweep race the rotation for the other. Claimed *before*
  the file is created, released only once its copy has been attempted.
  This hazard predates the launch sweep: `start()` has always launched the sweep alongside the
  drain's first `openSession()`, so the window existed and was microseconds wide. Making the sweep
  reachable from an Activity widened it to most of a run, which is what turned a latent race into one
  worth closing. `McapRecoveryTest` pins the destructive behaviour itself rather than the guard,
  since the guard needs a `Context` — the point being that skipping is a rule, not a courtesy.
- **Sweeps are serialised by a `Mutex`, and a second caller waits rather than skipping.** The Files
  route awaits a sweep before it lists, so a caller that returned early on finding one in flight
  would go on to read the folder mid-copy and answer with an authoritative-looking list that is
  missing the file — the exact failure the sweep exists to prevent, reintroduced one layer up.
- **`PublisherService.onDestroy()` closes the file, and must not be relied on to.** It reaches
  `RecordingSession.close()` through `stopPublishing()`, so an ordinary teardown finalises the
  recording properly. A shutdown kill does not call it, and even when it is called `Recorder.stop()`
  is deliberately fire-and-forget with a grace timeout — returning from it does not mean the file was
  closed. Anything that needs a *closed* file has to go through the orphan sweep instead.
- **Both tanks now act, and they act differently on purpose.** Storage *stops* a run —
  `openSession()` refuses below `MIN_FREE_BYTES` — because there is nowhere left to put the next
  file. The battery **secures** the run instead: at `CRITICAL_BATTERY_PCT` (10%), `watchBattery()`
  calls `Recorder.requestRotation()`, which closes and publishes the current file *while there is
  still power to copy it* and carries on into a new one. Stopping there would throw away whatever
  life the phone has left for no gain, since the tail is recoverable exactly as the whole run used
  to be; a full disk has no such option.
  **A percentage, not the runtime estimate.** `RuntimeEstimator` reports `Unknown` until it has
  measured a real drain, which can be most of a short run — and this is the one thing that must not
  be unavailable exactly when it is needed. The charge is always there.
  **Its own collector, not a hook in `runBattery()`.** That one is a subject publisher, and
  `supervise()` cancels it once every battery subject is switched off — so a safety behaviour hung
  off it would quietly vanish for anybody who turned the battery telemetry off. It polls on its own
  30 s clock, the same shape `watchConnection` uses.
  **Once per crossing, re-armed by charging.** A charge hovering on the threshold is read every
  thirty seconds, and acting on each reading would close and publish the file every thirty seconds.
  `batteryAction()` is pure and `BatteryWatchdogTest` pins the transitions, including that an absent
  reading neither acts nor *forgets* — a gauge going quiet must not re-arm a run and let it secure
  itself all over again.
  Verified on a Pixel 6 with `dumpsys battery unplug; set level 5`: fired once across three polls at
  5%, published a properly closed 2.2 MB file **with its summary section** 58 ms later, and the run
  carried on into a new file. Then, re-armed by the phone's real charger and dropped again, it fired
  a second time. Note `BatteryManager.isCharging` reads the hardware and does **not** follow
  `dumpsys battery set ac 1`, so the re-arm can only be exercised with an actual charger.
- **The low-battery alert is the only notification in this app that interrupts anybody**, and it
  needs its own channel to do it. The ongoing run notification is `IMPORTANCE_LOW` with
  `setOnlyAlertOnce(true)`, and from Android 8 the *channel's* importance decides whether anything
  alerts — so a `PRIORITY_HIGH` notification posted to that channel is silent. Hence
  `BATTERY_CHANNEL_ID` at `IMPORTANCE_HIGH`, posted once via `distinctUntilChanged` on
  `PublisherStatus.batteryCritical`. It earns the interruption because it is the one moment where
  doing something — plugging in — changes the outcome of a run in progress.
- **The recording carries no index, and that is a measured trade rather than an omission.** Readers
  scan the data section to open a file. Legal MCAP, and what happened before an index was tried.
  It *was* tried, and both findings are expensive enough to rediscover that they are kept here.
  **`ChunkIndex` without `MessageIndex` is not a smaller index but a broken one.** An empty
  `message_index_offsets` map sends a *seeking* reader down the index path with nothing to follow:
  measured against `mcap` 1.2.2, such a file returns **zero** messages from the default reader while a
  non-seeking reader reads all of them. Strictly worse than no index, which at least leaves the scan
  working. Anyone reaching for "just the chunk index, it is only a few bytes" needs to meet that first.
  **The full index costs 16.6 bytes per message**, which on a real recording here was **40.6% of the
  file** — 92 104 messages, 1.5 MB of `MessageIndex` against payloads averaging 41 bytes on disk. The
  ratio is the whole story: `MessageIndex` lives *outside* the chunks so it does not compress, while
  everything it indexes compresses about four times. Quote the per-message figure rather than the
  percentage, which is a property of how well a particular run's payloads compress — an earlier
  estimate of ~6% came from assuming a much larger message and was wrong by most of an order of
  magnitude.
  This app exists to record as much as a phone will hold, and computes days-of-recording from free
  space; 68% added to every file is permanent, where what it buys is seconds off opening one in
  Foxglove. If that trade ever looks wrong, it is the two records together or neither.
- **A Message's `data` is not length-prefixed; a Schema's is.** Getting that wrong produces a file that
  parses perfectly and whose every payload fails to decode, which is a genuinely nasty failure mode —
  it was the first bug in `McapWriter` and `McapWriterTest` now pins it. Validate format changes by
  reading a file back with the real `mcap` library, not by eyeballing bytes.
- **The MCAP schema bytes come from a build-time descriptor set.** `protobuf-javalite` strips
  descriptors — there is no `getDescriptor()` on a generated lite class — and adding `protobuf-java`
  alongside collides at dex time. So protoc emits `assets/keelson_payloads.desc` via
  `descriptorSetOptions`, git-ignored and generated, and nothing parses it at runtime. `includeImports`
  is mandatory or `google/protobuf/timestamp.proto` is missing and no reader can resolve anything.
  Restrict the option to the main variants — `all().configureEach` also covers the test proto tasks and
  they race for the same output file.
- **Unit tests return defaults for the Android stubs, and that was a deliberate narrow trade.**
  `testOptions { unitTests { isReturnDefaultValues = true } }` exists because `android.util.Log` throws
  "not mocked" in a JVM test, and `McapTrack` logs on exactly the path a truncated-file test must go
  down. It is safe only because nothing here *depends* on a stub throwing — the parsers that consume
  foreign input use `kotlinx-serialization` rather than `org.json` precisely so the tested code is the
  shipped code. Reaching for a stubbed Android API in a unit test is still the wrong move; this flag
  makes a log call survivable, not `android.jar` usable.
- **`subjectSchemaNames` in `record/McapSchemas.kt` must track `subjects.yaml`.** A wrong type there
  produces a file that opens and decodes to nonsense.
- **The file's figures reach the status flow on a throttle, and the last push is forced.** The drain
  used to call `_status.update` for every written sample — a lambda, a `copy()` and a `MutableStateFlow`
  CAS each, at a rate measured up to 800 a second, on the one coroutine that must not fall behind. It is
  the same anti-pattern the live store exists to avoid ("the live view pulls, it never gets pushed"),
  and nothing reads it that fast: the start screen polls at 1 Hz and the live view at 5. Capped at four
  a second now.
  Two things make the throttle safe rather than merely cheap. The push is **forced when the loop ends**,
  or the count on screen settles a fraction of a second short of the count in the file — and this app's
  whole claim about recording is that those two agree. And it is forced **after `session.close()`**, not
  before: `bytesWritten` reads through to the writer and closing is what emits the summary section and
  the footer, so pushing first reported a 1.51 MB file as 1.4 MB. Measured before and after; it now reads
  1.0 MB for a 1 067 765-byte file.
  The forced push is guarded on `messageCount > 0`, the same guard the `filesCompleted` line beside it
  uses and for the same reason: after a rotation the final session can be empty, and restating its
  zeroes would wipe a real file's figures off the card.
  Worth knowing when checking this by hand: the start card's **"N samples" is the publisher's count, not
  the recorder's**, so it legitimately differs from the message count in the file — the two run at
  different rates by design. A 58 974 against 63 265 is the record/publish split, not a lost sample.
- **`Recorder`'s channel is per *run*, not per Recorder — and that is load-bearing.** `stop()` closes
  the queue to end the drain loop, and a closed Kotlin `Channel` can never be reopened. When the channel
  was a single long-lived field, the *second* run in a process recorded **nothing**: `drain()` saw a
  closed queue, returned immediately, published a header-only 223-byte MCAP with no messages, and every
  sample after that was counted as dropped while the run itself published fine. Same shape as the Zenoh
  logger crash below — a bug only reachable by Stop then Start without killing the app, which is exactly
  what a normal session does. Verified fixed by two consecutive runs in one process producing 10 596 and
  9 703 messages, both read back with the `mcap` Python library.

- **The recorder is stopped *after* the collectors, not before, and that ordering is the whole of a
  bug that hid for months.** `stopInternal()` used to call `recorder.stop()` synchronously and cancel
  the collectors on a coroutine afterwards — so for the tens of milliseconds the cancel took, every
  sample published met a queue that had already been cleared, and `offer()` ignored it *silently*
  rather than counting it. Measured on a Pixel 6: 88 102 samples published, 88 090 written, twelve
  gone with nothing on screen admitting it. Now the cancel comes first and `recorder.stop(token)`
  follows it, and a run measures **94 443 published, 94 443 written, and 94 443 messages in the file**.
  The token is not decoration: the stop now lands on a coroutine, so a Stop immediately followed by a
  Start could otherwise close the *new* run's file — the same shape as the single-channel bug that
  once made the second run in a process record nothing.
- **Nothing that copies a file may run on the drain coroutine.** `publishOrphans()` learned this first
  — inline before the drain, a 212 MB orphan blocked the loop for the length of a copy and cost 20 000
  samples in the first half-minute of a run, with the file still showing zero messages. The **rotation**
  publish was the same call at the same place and was fixed later: `drain()` now hands `publish()` to
  the run's scope and opens the next session immediately. Measured with `DEFAULT_MAX_BYTES` temporarily
  at 3 MB on a Pixel 6 at 812 samples/s, the queue peaked at **65 inline against 28 off the drain** —
  nothing dropped at that size, but the depth is the mechanism and it scales with the file. Two traps.
  **Read `session.path` before the launch**, because `session` is reassigned on the next line and a
  lambda capturing the variable publishes whichever file it names by the time it runs — the new one,
  still being written. And **the final publish in the `finally` deliberately stays inline**: `stop()`
  joins the drain and *then* cancels the scope, so a final publish handed to that scope would be racing
  the cancellation that follows its own join. There is nothing left to stall there anyway — the queue is
  closed and empty by then. `DRAIN_GRACE_MILLIS` therefore no longer has to cover a copy at all;
  `cancelAndJoin` waits for one, untimed, and `publish` is blocking I/O that cancellation cannot
  interrupt part-way, so a truncated file in Downloads is not a risk.
- **Closing a `Channel` does not lose what is buffered in it, even under cancellation.** Worth knowing
  because the obvious diagnosis of the above was that `stop()` cancelled the drain too eagerly — it
  did cancel it, and that turned out to cost nothing: `receive()` only checks for cancellation when it
  has to *suspend*, and on a closed channel that still holds elements it never does. `RecorderStopTest`
  pins this so the next person does not fix the thing that was never broken. `stop()` still joins the
  drain rather than cancelling it, with a 5 s grace and a count of anything left over, because that is
  the honest shape — but it is belt and braces, not the fix.
- **`OutboxBuffer.evicted` is not a loss, and must never be shown as one.** The ring is full about two
  and a half minutes into any run, so from then on every add evicts something and a healthy hour evicts
  most of a million samples. What costs data is only the part of the *replay window* that overflowed,
  which `lostSince(addedMark)` computes from the add count the watchdog marks at each poll that saw a
  router: everything added since the mark is window, the ring holds `capacity` of them, the rest are
  gone. Zero for any outage shorter than the whole buffer, which is nearly all of them.
  `PublisherStatus.replayLost` carries the run total and is recomputed on every poll *including while
  the gap is open* — reporting it only after the reconnect would mean the one screen anybody looks at
  during an outage says nothing, and then says "Replayed 32 768 samples" as if it had caught up.
- **A settings profile carries everything except the five fields that identify the install.** `entityId`,
  `operatorId` and `platformRegistryOrigin` are each documented in `Settings` as generated once and never
  changed — that is exactly what makes copying them a bug — plus `platformRegistryVersion` (sync ordering)
  and `batteryExemptionAsked` (a device fact). They are **not in `SettingsProfile` at all**, so an
  import cannot touch them even by mistake; `SettingsProfileTest` asserts the round trip field by field
  rather than with `copy`, so a field added to `Settings` later fails the test until somebody decides
  which side it belongs on. Note the DTO is **hand-built with the runtime JSON API, not `@Serializable`**:
  there is no kotlinx-serialization compiler plugin on this project, so an annotated class compiles
  cleanly and then throws `SerializationException` on the first export.
- **`publish()` treats a vanished file as somebody else's success, not a failure.** `Recorder.stop()`
  is fire-and-forget, so a run's closing publish can still be in flight when the next run's
  `publishOrphans()` finds the same file — they race to copy it and the loser opens a file the winner
  has already deleted. That `FileNotFoundException` used to set `RecordingStatus.error`, which put
  **"Recording problem" on screen for a recording that had just saved perfectly well**, every time
  settings were saved mid-run. Caught separately from a real I/O failure, which still keeps the file
  and still reports.
- **`RecordingStatus` outlives the run, and the main screen now shows it.** `Recorder.stop()` flips
  `recording` to false and leaves every other field standing, which is what makes a post-run summary
  possible without new state — the card used to be gated on `recording` alone and took the file name,
  the count and the destination off screen at the moment somebody wanted them. Two traps in there.
  `filesCompleted` counts **successful copies to Downloads only** — it is the answer to "did it save?",
  and a failed copy leaves the file recoverable in app storage, so counting it would be how somebody
  comes to wipe the phone with the run still on it. It also counts the **last** file: it used to be
  incremented at rotation only, so an ordinary run that never reached 512 MB ended reporting zero files
  saved having saved one. And `fileName`/`messagesWritten`/`bytesWritten` are **per file, not per run** —
  they restart at every rotation, which is why the final `_status.update` deliberately touches nothing
  but the count: an empty final session would otherwise replace a real file's figures with zeroes.
  That per-file rule is also why the start screen's "Last run" line counts *files* rather than
  megabytes once `filesCompleted > 1` (`lastRecordingOf()`): a size beside a whole-run sample count
  would silently be describing the last file of several.
- **The Stop dialog describes the run that actually happened, not the one the code was written for.**
  `stopFigures()` and `stopNoteHint()` in `ui/MainScreen.kt` are pure and pinned by
  `StopDialogTextTest` because a run does two independent things and either can be off, so there are
  four combinations to be right about. Written when publishing was the only thing a run did, the dialog
  told a record-only run that its samples had been **published** and that a closing note would go **on
  the bus** — two statements about a run that had deliberately done neither.
  `totalSamplesPublished` counts samples reaching `SubjectSink.emit`, not puts, so with publishing off
  it means *samples produced*; the sentence therefore drops the verb rather than choosing a wrong one —
  `19 358 samples over 00:00:24, 485 kB recorded.` A closing note is a `log_message` through the same
  sink, so it too reaches the file and not the wire. And "The file keeps its name" is only said where
  there is a file, since only there could anybody have expected a note to rename one.
- **A run does two independent things, and the start row is where you choose which.** `PUB` and `REC`
  chips *before* a narrowed `START`, both on by default — the chips qualify the button and English reads
  left to right, so `PUB REC ▶ START` is the sentence and the other order is the same words shuffled.
  Selected, they take the **button's** fill rather than the chip default: they are not a setting sitting
  near a button, they are two thirds of what pressing it does, and three shared fills read as one
  control where two containers and a button read as three. That is a deliberate deviation from the
  `Configured | Maximum` selectors further up the same screen, which *are* a standalone setting and keep
  the default. The button used to carry the choice in its label
  — `START Publish & REC` — which made the label the only place it was visible and the *settings screen*
  the only place it could be changed. Neither chip on disables Start, with a line saying why: a run that
  neither publishes nor records is a foreground service holding a wake lock to achieve nothing.
  **A record-only run keeps its link.** `Settings.publishEnabled` gates one branch in `SubjectSink.emit`
  — the session still opens, the publishers are still declared and the liveliness tokens still stand, so
  the phone is present on the bus and simply says nothing. A phone that vanished from a fleet's
  liveliness while in fact running is worse to diagnose than one that is present and quiet. Three things
  follow from that branch and each is load-bearing: **the decimator is skipped**, because it is a
  *publish* rate limiter and thinning with nothing on the wire would make the counters describe a stream
  that does not exist; **the outbox is skipped**, because replay fills a gap in what a consumer received
  and there is no consumer; and **the sample is still counted**, because the counters drive the health
  checks and the live view, so a recording run whose every subject read `Stalled` would be a healthy run
  reporting itself broken. On such a run the counters mean *samples produced* rather than *published*,
  which is the honest reading — it is what reached the file.
  Three readouts had to stop claiming otherwise: the top bar's `PUB` lamp goes grey rather than taking
  its colour from the link (`RunState.publishing` is separate from `connection` for exactly this — the
  link is genuinely up), the status card reads **Recording only**, and an unreachable router stops being
  an error, since on a record-only run it is expected and very often the reason the run is record-only.
  Verified by counting the actual `session.publish` calls: ~758/s with PUB on, and frozen at zero with
  it off while the file kept growing.
- **The start screen's card is a session summary, and an icon on it means attention.** It says what
  the last run left behind, whether there is room for another, and where this one will connect — and
  deliberately **no longer says "Not publishing"**, which the `Idle` chip in the app bar and the
  `Start publishing` button pinned above the navigation bar were already saying. `StatusLine`, with
  its icon, is reserved for a warning or a failure; every calm fact is a label with its figure, since
  a run that saved perfectly announced with the same ⓘ as one that dropped samples teaches the eye to
  skip both. The row along the card's bottom edge names where a tap goes ("Endpoint & recording")
  rather than instructing ("Tap for endpoint and file details"), and while connected it can name the
  endpoint **only when exactly one is configured** — Zenoh reports routers' zids, not the locator a
  transport was opened on.
- **The outbox fills unconditionally, never on `Disconnected`.** `isConnectedToRouter()` reads Zenoh's
  transport table and is polled every 2 s, and Zenoh only empties that table once its keepalive gives
  up — so the state changes *seconds* after samples actually started going nowhere. A buffer gated on
  it would miss the head of every outage. For the same reason replay starts from the last poll that saw
  a router, which knowingly re-sends a couple of seconds: duplicates beat a hole.
- **Never flush the outbox unpaced.** Every QoS profile is `DROP` and `put` returns success regardless,
  so a burst that overruns the egress queue is discarded with no signal at all — the buffer empties, the
  counters climb, and the data is gone. `SensorPublisher.replay()` paces at ~400/s. `BLOCK` is not the
  alternative: it stalls the producer, and the sensor `callbackFlow` channels hold 64 samples, so about
  a second of stall starts dropping live data.
- **Publishers are Zenoh *advanced* publishers** with a sample cache and heartbeat, so a consumer using
  an `AdvancedSubscriber` can recover misses natively. Verified not to affect plain subscribers, which
  is what every keelson connector currently uses.
- **`keelson.Audio` allows only MP3 or WAV, and Android cannot encode MP3.** So `audio` goes on the bus
  as uncompressed 16-bit PCM behind a per-chunk 44-byte RIFF header — about 109 MB/h at the default
  16 kHz mono, against ~77 MB/h for every other subject combined. The message carries no rate, channel
  or bit-depth field, which is why the header is not optional: it is the only thing that makes a chunk
  self-describing. AAC and Opus both encode natively on Android and would cut that by an order of
  magnitude, but the enum has no value for either — that is an upstream conversation, and extending the
  enum is backward-compatible for `Alarm.proto`, which imports the same message.
- **The camera stays bound for the whole run, and that is the design.** Opening a camera costs a few
  hundred milliseconds and blinks the privacy indicator each time; at the default two-second time-lapse
  interval, open-per-frame would spend most of the duty cycle opening. So `CameraProvider` binds
  `ImageCapture` once against a private `LifecycleOwner` — the publisher is not one — and a ticker calls
  `takePicture` per interval. `awaitClose` unbinds **on the main thread** via a `Handler` post, because
  it cannot suspend and `LifecycleRegistry` is main-thread only; drop that post and the camera indicator
  stays lit until the process dies. Every `ImageProxy` must be closed on every path or the pipeline
  stalls after a handful of frames.
- **`image_compressed` is the one subject excluded from the outbox** (`PublishedSubject.bufferedForReplay`).
  `replay()` paces by *message count*, ~400/s, so a dozen buffered 150 kB frames is a multi-megabyte
  burst that the DROP-everywhere egress queue sheds silently — and takes the live navigation data queued
  behind it. A two-minute-old time-lapse frame is not worth that; the MCAP recording is the complete copy.
- **Video and the time-lapse cannot both run, and the reason is the camera HAL rather than taste.**
  Measured on a Pixel 6: binding CameraX's `Preview` (feeding the H.264 encoder) alongside
  `ImageCapture` produces `ERROR_CAMERA_DEVICE` within a second, the camera provider process dies and
  reinitialises in a loop, and no frame is ever produced — at **matching** resolutions as well as
  mismatched ones, so it is the combination itself. `Preview` alone is flawless. `Settings.offSubjects()`
  enforces it (`videoEnabled` wins) rather than the UI, so an imported profile with both set cannot
  reach a state the hardware refuses.
- **`MediaFormat.KEY_FRAME_RATE` is a bitrate hint, not a throttle.** The camera drives the encoder's
  input surface, so the encoder compresses whatever arrives: asking for 10 fps produced **29.9**,
  measured. The frame rate has to be asked of the *camera*, through
  `Camera2Interop.setCaptureRequestOption(CONTROL_AE_TARGET_FPS_RANGE, …)` — an experimental API opted
  into deliberately, because the alternative is a setting that silently does nothing. With it, the same
  request measures **10.0 fps and 131 MB/h against the 128 MB/h the settings screen promises**.
- **Every keyframe must carry its SPS, and `MediaCodec` sends it once.** `foxglove.CompressedVideo`
  requires Annex B framing — which is what `MediaCodec` emits natively, so AVCC would be the wrong
  guess — and that "each message containing a key frame (IDR) must also include a SPS NAL unit". The
  codec delivers SPS/PPS exactly once, in a `BUFFER_FLAG_CODEC_CONFIG` buffer before the first frame,
  so `withCodecConfig()` caches and prepends them. Without it the recording plays from the start and
  from nowhere else — which is what a subscriber joining mid-run, or the file after a 512 MB rotation,
  actually holds. `MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES` asks the encoder to do this instead
  and is deliberately unused: the platform documentation says a codec that does not support it **fails
  to configure**. Verified by cutting a recording at frame 1260 of 2499 and decoding it with `ffmpeg`.
- **The camera is the most expensive subject: ~158 MB/h at the 1280x720, 0.5 Hz default**, against
  ~109 MB/h for audio and ~77 MB/h for everything else combined. `cameraMegabytesPerHour()` in
  `ui/SettingsScreen.kt` is an *estimate* at 0.10 bytes per pixel — deliberately above the 0.04–0.07
  measured indoors on a Pixel 6, because outdoor detail compresses far worse and a low estimate is the
  one that costs somebody money. It is stated as a number in the UI, so changing the quality constant
  means changing this one and `FormatTest` with it.
- **Most phones cannot capture a small JPEG, so the frame is scaled after capture.** Measured: a Pixel 6
  offers **no JPEG stream below 1920x1080** on either lens (`dumpsys media.camera`, format 33), so a
  720p setting was silently publishing 2.25x the pixels it promised. `scaleIfOversized()` re-encodes to
  the requested width and logs once per run; a device with a smaller stream passes through untouched.
  Two consequences: the published frame costs a second JPEG generation, and end-to-end capture-to-write
  latency is ~375 ms (measured), which is part of why the interval floor is 500 ms.
- **CameraX's resolution selector needs an explicit aspect-ratio strategy.** Its default prefers 4:3 and
  filters the candidate list *before* the resolution strategy runs, so asking for 1280x720 got
  1920x1440. `aspectRatioStrategyFor()` picks the nearer of 4:3 and 16:9 — and note it compares ratio
  *distance*: a `w * 9 > h * 16` test is exactly false for 1280x720 and sends the commonest setting down
  the wrong branch.
- **Audio is off by default and must stay that way.** It records whatever is said near the phone. The
  `microphone` foreground-service type is added only when it is enabled *and* `RECORD_AUDIO` is already
  granted — on Android 14+ declaring the type without the permission throws — and `RECORD_AUDIO` is
  requested from `startupPermissions()` only when it is enabled, the same conditional shape as
  `ACCESS_LOCAL_NETWORK`. **The camera is off by default for the same reason and wired the same way** —
  `camera` type plus `CAMERA` in `startupPermissions()`, and the notification says `taking pictures`
  the way it says `recording audio`. Neither default should ever be flipped to on.
- **`ProcessCameraProvider.awaitInstance` is an extension on the companion**, in
  `androidx.camera.lifecycle` — it needs its own import and does not resolve as a plain static.
  `ContextCompat.getDisplayOrDefault` is likewise not available at this core version, so
  `CameraProvider` reads the rotation from `DisplayManager` directly. Both were compile errors first;
  treat an unresolved CameraX symbol as an API-shape question rather than a wrong version.
- **The time-left estimate reads the fuel gauge, not `CURRENT_NOW`, and it is unit-agnostic.**
  `RuntimeEstimator` divides fuel remaining by fuel consumed per hour, so the unit cancels: it is
  fed `BATTERY_PROPERTY_CHARGE_COUNTER` in microamp-hours where the device reports it (1 mAh steps on a
  Pixel 6) and percent where it does not (~46 mAh steps on the same phone). **The source is locked on
  the first reading of a run** in `runBattery()` — switching mid-run would look like the battery losing
  99.99% of itself in one tick, which is also why the estimator resets on any jump beyond ±50%.
  Least squares over a trailing 20-minute window, not first-minus-last over the run: the gauge quantises,
  and the drain changes when a subject like the camera is switched on. It reports `Unknown` rather than
  a number until it has measured a real drain, and never reports beyond four days. Charging is decided by
  the *caller*, not the estimator — see the next bullet for why it cannot live inside.
- **Two tanks empty a run, and the readout names the one that empties first.** The same
  `RuntimeEstimator` measures free space in `Recorder.trackFreeSpace()`, which is why it knows nothing
  about batteries or charging; `timeLeft()` in `publish/RuntimeEstimate.kt` picks the nearer end and the
  status card, the notification and `Detail("Space", …)` all read it. Two things are load-bearing there.
  The fuel is free space **above** the 256 MB `MIN_FREE_BYTES` floor, because that floor is where
  `openSession()` refuses to open the next file — that is the moment being predicted, not a full disk.
  And it is polled from `usableSpace` every 30 s rather than derived from `RecordingStatus.bytesWritten`,
  which restarts at every 512 MB rotation and would make the rate wrong for minutes afterwards; free
  space is also what actually ends the run when it is *another* app filling the volume. Rotation dips
  free space by up to a file's worth while `publish()` copies to Downloads before deleting — the trailing
  window is what absorbs that. A charging phone still fills its disk, so `Charging` does not suppress the
  storage half.
- **The Events tab reads note, buttons, history — and the history is last.** It led the screen for a
  while, on the argument that it is what somebody opens the tab for when they are not marking anything.
  Tried on a device, the other argument won: it is the only thing there that is *read* rather than
  pressed, and reading can be scrolled to where pressing has to be under the thumb already. It stays
  collapsible, and collapsed by default.
  **How many buttons sit across is a setting, 2, 3 or 4**, because it trades size against reach and
  which way to trade depends on the boat: two are big enough to hit without looking and put four of them
  a scroll away, four fit a long list on one screen and ask for more aim. The cells are weighted and
  square rather than a fixed side, so four across a narrow phone are simply smaller instead of
  overflowing, and a short last row keeps its cells the same size as the rest — stretching one across
  the gap would make it look more important than its neighbours.
  **The note section collapses like the history does**, for the phone whose operator never types one:
  hiding it gives the buttons and the list the whole page. Collapsed rather than switched off from
  somewhere else, which is what keeps the control reachable — the header and its chevron stay, so the
  way back is where the way out was, and no other screen has to hint that a hidden section exists.
  **Editing the buttons is a plain "Edit" on the Quick marks header**, beside the width chips. It read
  "Edit buttons" while it lived on the *Note* header, where the word was the only thing distinguishing
  the two; on the header of the section it edits, the position says it.
  A full-screen mode for the buttons was built and removed: with the note above and the history below,
  the buttons already have the middle of the screen, and the mode added a state to leave rather than
  room to press.
  **Expanded, the list is capped at a third of the screen and scrolls inside that.** A run makes a hundred
  marks and the page is one scrolling column, so an uncapped list pushed the note field and the buttons
  arbitrarily far down. The `heightIn` is also what makes the nested scroll *legal*: a scrollable
  measured inside another scrollable is handed an infinite maximum height and throws, so bounding it
  first is the fix rather than a nicety. The fraction is taken from `LocalConfiguration.screenHeightDp`
  rather than hand-tuned — `BoxWithConstraints` is no help inside a scrolling column, where the height
  it reports is infinite. A third rather than the half it started at: half showed nine rows on a Pixel 6
  and left the note field and the buttons only just on screen, where a third shows six and the whole
  screen still fits with sixteen marks expanded — which is what keeps the buttons reachable in *both*
  states rather than only the collapsed one.
  **A quick button is tap for an instant, hold for an interval.** Some of what gets marked on a boat —
  a manoeuvre, a leg, an engine run — is not a point in time, and recording it as one loses the half
  that matters. A long press arms a timer and the button's face counts; a tap closes it. The gesture was
  chosen over "the hold *is* the duration" because the finger is free while it runs, so the event's
  length is not bounded by how long somebody can hold a phone on a moving deck.
  **The Events tab carries a badge with the count while any timer runs**, because a timer armed by a
  long press is otherwise invisible from every other tab — somebody starts one, goes back to the chart,
  and finds out at the end of the run that the teardown closed it for them. The count rather than a bare
  dot: two running is a different situation from one and a dot cannot say so. It reads
  `RunState.runningTimers`, the same ambient the top bar's lamps use, for the same reason — the
  navigation bar is shared chrome and threading the publisher's state through every caller of it to
  reach one badge is what that `CompositionLocal` exists to avoid. The count is polled in `App()` rather
  than in the Events route, since the one screen that could read it cheaply is the only screen that does
  not need it.
  **Two marks go out, one at each end**, the second carrying the duration — so a reader sees the event's
  extent rather than a point at its end, and a run killed mid-timer still has the start on record. The
  timers live on `SensorPublisher` (`TimedMarks`, pure and testable) rather than the screen, for three
  reasons that all matter: one has to survive leaving the Events tab, it is run-scoped like the
  annotation log, and **the teardown is the only place that can close one somebody forgot** — which it
  does beside the closing note, marked `(run stopped)` so an interval the operator ended is
  distinguishable from one the teardown ended for them.
  Two traps. **A second start must not move the origin**: holding an already-running button would
  restart it silently and the duration would come out short, so `start()` refuses and the caller reads
  that as "no mark to publish". And **the timer is keyed on the button's label**, because that is what
  `mark()` already receives — keying on the whole `AnnotationButton` would have `publish` reach into
  `config` and invert the dependency. Two buttons sharing a label therefore share a timer.
  `formatElapsed()` moved from `ui/MainScreen.kt` into `publish/` for this: the screen prints it beside a
  running timer and the publisher puts it in the mark's message, and `publish` cannot reach into `ui`.
  One implementation rather than two, which is the rule this codebase keeps restating.
  **Editing the buttons is a text action on the Note section header, not an icon in the top bar and not
  a square in the row.** In the bar it was as far from the thing it edits as the screen allows and
  competed for the one place a glance goes for the run's status; as a square among the marks it took a
  slot in the grid and had to work to *not* look like one, which matters when a long press on a real one
  arms a timer. `SectionHeader`'s `action` slot already exists for a control that governs a section.
  It reads **"Edit buttons", not "Edit"** — it sits on the header of a section called *Note* while
  editing the quick marks further down, so the word is the only thing saying which of the two it means.
  **The quick buttons are last, which on a phone is nearest the thumb.** They are squares now rather
  than pills — a pill sized to its text is as small as its shortest label, and this is the control the
  screen exists for. The long press fires a haptic, which is not a nicety: the whole purpose is arming a
  timer without looking, and the snackbar confirms it a moment later than the finger needs. They were first on the argument
  that the moment being marked is passing while you look for them; that argument is right and the bottom
  of the screen serves it better than the top did. The note field is what you reach for deliberately.
  Two details. The collapsed header's **count carries the worst severity in the whole list**, not the
  newest mark's — a fault five marks ago is still the thing somebody must not miss, and collapsed the
  newest line is all they would otherwise see. And the compact form drops the `EmptyState` card for a
  single line: the explanation of what a mark *is* belongs on the expanded path, where there is room.
  The flag is hoisted in `MainActivity` with the live view's own preferences, for the reason stated
  there — a `remember` inside a route dies with the composable when it is popped.
- **`log_message` is the one subject with no collector, and the registry says so.** Annotations are
  published by `SensorPublisher.mark()` when a person presses a button, so the entry carries
  `eventDriven = true` and is deliberately **absent from `COLLECTOR_GROUPS`** — there is no listener for
  a switch to release, and `CollectorGroupsTest` asserts the absence rather than tolerating it. That
  flag also buys two things on screen: `subjectHealth()` skips the `Waiting`/`Stalled` checks, because
  silence is this subject's healthy state and without the exemption an unmarked run would show a red
  group heading all day; and `SubjectQosScreen` drops the whole sampling-rate section rather than
  offering a dial with nothing behind it. It is also the one place `Instant.now()` is the *correct*
  clock — the rule against it is about `SensorEvent.timestamp`, which is a boot clock, and a button
  press genuinely happens when it is recorded.
- **The annotation payload's fields are chosen for Foxglove's filters, not for tidiness.** `level` is
  the severity, `name` is the *category*, `message` is the text. Foxglove's Log panel filters on
  minimum severity and lists **one namespace toggle per distinct `name`** — so a category per button
  turns that filter into a legend, which is why the UI pushes a few shared categories. Two are the
  app's own: `note` for typed notes and `system` for the automatic mark at the head of every run. That
  automatic mark is load-bearing — MCAP channels are written on a subject's *first* sample, so a run
  nobody annotated would have no `log_message` channel at all and a saved Foxglove layout would find
  the topic missing. **A file rotation at 512 MB leaves the same gap** and is knowingly not solved: the
  next file gets the channel only at the next mark.
- **Editing the annotation buttons must not go through `saveSettings()`.** Like the per-subject
  switches, and for the same reason: that helper stops and restarts the service to redeclare
  publishers, which a list of button labels has no need of — it would drop the Zenoh session and close
  the MCAP file to rename a button. `MainActivity` calls `settingsRepository.update()` directly. The
  button list is also the one preference where **an absent key and an empty value differ**: absent means
  "never configured" and reads as the defaults, empty means the user deleted them all. If both fell back
  to the defaults, deleting the last button would put three back on the next launch.
- **The platform calibration is the only thing published under a different `entity_id`.** `entity_id` names
  the physical thing the data is *about*, and a platform's geometry is about the platform, not the phone that
  surveyed it. `Settings.entityFor(entry)` is the one place that resolves it — the counterpart to
  `sourceFor(entry)` — and `declareLiveliness()` therefore declares one token per **(entity, source)
  pair** rather than per source. Publishing the geometry under `pixel_6` would file a vessel's layout
  beside the phone's battery, where nobody looking for that vessel would find it.
- **`frame_transform` is stamped with the publish time, and that is the one deliberate exception to the
  observation-time rule.** Upstream's `platform-geometry2keelson.py` does the same, and Foxglove builds
  its transform tree against log time: a transform stamped with the survey date falls outside a
  recording's own time range and draws nothing at all. When each number was measured lives in the
  `calibration` block of `configuration_json` instead.
- **Two variants of the platform-geometry document, and the difference is load-bearing.**
  `../keelson/connectors/platform/config-schema.json` is `additionalProperties: false` at every level, so
  the *exported file* carries no provenance and can be fed to that connector unchanged; the *wire*
  document adds a `calibration` block with the zero point, the heading source, and each offset's capture
  method and accuracy. Upstream has nowhere to put uncertainty, and a calibration without it is half a
  measurement — `docs/calibration.md` proposes the block. Do not "tidy" the two variants into one.
- **All of a platform's transforms share one key**, with the sensor named by `child_frame_id` inside the
  message — upstream's shape, not an oversight. Zenoh's latest-value store therefore holds only the last
  transform of each round, which is exactly why `runCalibration()` republishes on a ten-second loop
  rather than publishing once at start-up. Its "rate" is that interval, the same reading `audio` gives
  its chunk length.
- **Four registry entries publish `location_fix`, and only one of them is the phone's position.** The
  fused fix on `{phone}/pubsub/location_fix/{locationSource}`, the platform's surveyed zero on
  `.../location_fix/calibration`, and the two **unfused solutions** beside them:
  `.../location_fix/gnss` from `LocationManager.GPS_PROVIDER` and `.../location_fix/network` from
  `NETWORK_PROVIDER`. Same subject, different source, which is keelson's own model — the source chunk
  names *which* producer, exactly as `cellular` and `wifi` do for the radio subjects, and `FixSources`
  sits beside `RadioSources` for the same reason.
  **The two unfused sources nest one level *under* the configured `locationSource`**, so the keys are
  `location_fix/{locationSource}`, `.../{locationSource}/gnss` and `.../{locationSource}/network`. The
  specification allows the extra level — "`source_id` may contain any number of additional levels (i.e.
  forward slashes), ei. camera/rbg/0" — and upstream's `parse_pubsub_key` returns `phone/gnss` as one
  `source_id`. `PublishedSubject.sourceSuffix` is what does it, *not* `fixedSourceId`: a fixed id
  replaces the configured one, so a phone whose `locationSource` was itself `gnss` would have published
  two different solutions on one key and interleaved them indistinguishably. Nesting removes that
  whatever anybody types in a free-text field, which is better than a list of reserved words to police.
  **A nested source is why `pubsubSubjectAndSource()` exists.** Two places read a topic by counting
  from the end — `McapTrack.isFixTopic` and `RecordingDetailScreen.subjectOf` — and both answered
  `gnss` for the *subject* the moment a source gained a level: the Files tab stopped recognising its own
  fix channel and the detail screen printed the wrong pair. That helper anchors on the verbatim `pubsub`
  chunk, which is the one position that cannot move, and `KeysTest` pins it against the specification's
  own three-level example. Never read a source as `chunks.last()`.
  **There is no wifi-only or cell-only fix and there cannot be.** Android exposes `gps`, `network`,
  `fused` and `passive`; `network` is wifi *and* cell with nothing saying which contributed. Verified
  against a Pixel 6's `dumpsys location`. Anyone asked for the three separately should be told two.
  **The fused entry must stay first among the four** — `forSubject()` answers with the earliest, and
  the derived subjects, the live map and a recording's track all read that answer. `SubjectRegistryTest`
  pins the order.
  **`McapTrack` must exclude the other three**, not match the fused one: that source is configurable, so
  the reader cannot know it — but it knows `calibration`, and it knows the two *suffixes* (`/gnss`,
  `/network`) whatever level they hang from. This
  is not theoretical — measured on a real indoor run, `location_fix/network` carried **17** messages
  against the fused stream's 16, so the wrong channel was the busiest, and `readMcapDetails` returns
  topics busiest-first. A track interpolated from cell towers drawn as the boat's is the same failure
  as the surveyed zero point, reached by another route.
  What this buys, and it worked first time: on a desk indoors the **gnss** stream produced nothing at
  all — no channel in the file, since a channel is written on first sample — while **network** and the
  **fused** fix agreed to five decimals. That is `location_fix_quality`'s red `No fix` shown to be
  telling the truth, from a recording rather than from an impression.
- **`location_fix` is published by two registry entries, and the difference is entity + source.** The
  phone's live fix is `{phone}/pubsub/location_fix/phone`; the platform's surveyed zero is
  `{platform}/pubsub/location_fix/calibration`. Three things stop the second being read as a live position,
  and none of them is optional: the key differs, the payload timestamp is the **survey** time (the
  opposite choice from `frame_transform` beside it, and for the opposite reason — here the age of the
  measurement is the point), and `labelOf()` names it "Zero point" rather than "Position", keyed on the
  *entry* because that is the only way to tell two entries of one subject apart. It stays silent until a
  position exists: `Settings.offSubjects()` gates it on `PlatformZero.hasPosition`, because a tape-measured
  platform and a heading-only zero both have geometry worth publishing and no position, and 0°N 0°E is the
  most confident possible way of being wrong.
- **The zero point has three sources, and the map one states no accuracy on purpose.** `Capture
  position` averages twenty seconds of fixes, `Pick on map` pans a chart under a fixed crosshair, and
  `Type` takes decimal degrees. `CaptureMethod.MAP` records which, and `zeroFromMap()` deliberately
  leaves `accuracyM`, `verticalAccuracyM` and `scatterM` null unless a figure is typed: the error in
  a picked point is the basemap's georeferencing plus how well somebody pointed, and the app knows
  neither. Deriving one from the zoom is the obvious move and is wrong — it measures the pointing,
  not the imagery, so it reads as sub-metre at exactly the zoom where imagery is most likely to be
  several metres out. It keeps the previous altitude and the heading, because a chart gives neither
  and a surveyed one is better than nothing.
  **A pick dragged to 0°N 0°E reads as no position at all.** `hasPosition` uses Null Island as the
  absent marker, which was safe while a zero could only be captured or typed and is now reachable by
  panning. Pinned in `ZeroFromMapTest` as a known cost rather than left to be found.
- **The forward axis can be taken off a chart, and it is often better than walking it.** A
  baseline's angular error is position error divided by baseline length. Walked, both ends carry
  independent GNSS error. On a chart the dominant error is the imagery's georeferencing, which is
  largely a *uniform local shift* — and a uniform shift **cancels** out of a bearing between two
  points on the same imagery, leaving only the pointing error. Measured on the phone: a 77 m map
  baseline reports 0.7° per metre of error, where the 2 m one somebody might walk on a small
  platform reports twenty-seven. `HeadingSource.MAP_BASELINE` records which, because a consumer
  told only "baseline" could not tell those apart.
  **`PlatformZero.headingBaselineM` is new provenance for both kinds**, walked included — the length
  was knowable and unrecorded, so nothing downstream could tell a 3 m baseline from a 30 m one. Null
  for compass and typed headings, which have no baseline, and **cleared when a bearing is typed**:
  keeping the length from the pan that preceded it would attach a baseline to a number that did not
  come from one.
  **The picker refuses a baseline under `MIN_BASELINE_M` (5 m), and that is a real bug it fixes
  rather than a nicety.** The map opens centred on the zero, so the crosshair starts *on* the anchor
  — and the first frame reported `324° true · 0 m from the zero · about 83.0°`, which is `atan2`
  answering from two identical points dressed as a measurement. Below the floor it shows the
  existing heading marked `· unchanged` and disables Use, so confirming an untouched screen cannot
  silently rewrite a compass heading as a typed one.
- **A sensor offset can be pointed at on a chart too, and it is a *relative* measurement — which is
  what makes it worth having.** The offset is the difference between the zero and the sensor, and a
  chart's dominant error is the imagery's georeferencing, which is largely a uniform local shift —
  and a uniform shift cancels out of a difference. The same argument as the map baseline, applied one
  level down. So on a large platform this can beat walking there with the phone, whose fix accuracy
  this app already warns is often larger than the offset being measured.
  It changes **neither height nor rotation**. A chart is flat, so `z` is kept from whatever was
  there; and a phone beside a radar can measure where the radar *is* but not where it is *looking*,
  which is why rotation is always typed and a map changes nothing about that.
  `enuFromBodyOffset` and `pointFromEnuOffset` are the two inverses this needed. The second uses the
  origin's latitude for the radii where the forward direction uses the mean of both ends, and the
  cost is **measured rather than claimed**: sub-millimetre within about a hundred metres, about three
  centimetres at five hundred. The first version of that comment said sub-millimetre "out to a few
  hundred metres" and `GeodesyInverseTest` disagreed, which is why the figure is a measurement.
- **A `MapView` swallows taps whatever its gesture settings say**, so a `clickable` on the
  `AndroidView`'s own modifier never fires. Both calibration cards had one and neither worked — found
  by tapping a preview on the phone and watching nothing happen. `PreviewMapButton` puts a
  transparent box over the map instead. Gestures stay off on the map as well: the overlay stops taps
  reaching it, not drags, and a preview that pans inside a scrolling column is the fight the
  full-screen pickers exist to avoid.
- **osmdroid's map centre comes back quantised, and it is not a measurement.** `setCenter` fires the
  scroll listener, and what `mapCenter` then reports is not the point that went in — measured on a
  Pixel 6 at about **11 cm at zoom 18**. Harmless as a view; not harmless as a measurement. Before
  `MOVED_THRESHOLD_M`, opening the sensor picker and confirming without touching anything moved an
  offset of 23.699 m to 23.81 m and recorded it as a fresh measurement. `PositionPickerMap` now
  reports nothing until the centre has genuinely left where it was put, after which it reports
  everything — including a pan back to the start, which is a decision where the opening report never
  was. Every picker seeds its own readout from what it already holds, so nothing is blank without it.
- **The picker is the first map in this app that is read as well as written**, and it is full screen
  for the reason `RecordingChart` records: an interactive map inside a `verticalScroll` loses every
  drag to the page. `PositionPickerMap` reports its centre through an `onCentre` lambda fed by
  osmdroid's `MapListener` — **both** `onScroll` and `onZoom`, since a zoom moves the ground under a
  fixed centre just as a scroll moves the centre over fixed ground, and listening to one leaves the
  readout stale after the other. The crosshair is a Compose `Box` over the `AndroidView`, not an
  overlay: it is fixed in screen space and needs no projection.
  The same composable draws the card's preview with `interactive = false` — gestures off rather than
  a transparent view over the top, because osmdroid would still receive the touches and a map that
  pans a little when you meant to scroll is worse than one that plainly does not.
- **A captured offset smaller than its own fix accuracy is noise, and the UI says so.** Averaging twenty
  seconds of fixes cuts *scatter*, not *bias* — GNSS multipath holds still for minutes — so
  `AveragedFix` reports both numbers and `SensorMount.accuracyExceedsOffset` drives a red line on the
  row. A phone is honest for platform-scale geometry; decimetre offsets on a small platform want a tape
  measure, which is why manual entry is the primary path rather than the fallback.
- **`connectedDebugAndroidTest` reinstalls the app, which wipes `filesDir` — and that takes the mTLS
  credentials with it.** Learned the hard way: after an instrumented run the next Start failed with
  "needs TLS but no root CA certificate has been imported", the settings were back to defaults, and the
  **entity id had regenerated from `phone` to `pixel_6`**, silently moving every key the phone
  publishes on. Nothing about the failure points at the test run. Before running instrumented tests on
  a phone that holds credentials, export a settings profile; afterwards, re-import it, re-import the
  three PEMs (or push them straight in with
  `adb shell "run-as se.rise.logline sh -c 'cat > files/tls/root_ca.pem'" < minica.pem`, likewise
  `client_cert.pem` and `client_key.pem`), and **set the entity id back by hand** — a profile
  deliberately does not carry it.
- **Emulators are useless here.** GNSS, IMU and the camera all need a physical device.

## TODO.md

The working checklist, and **the user prunes it, not you.**

- **Never delete an item.** Deciding a thing is done — and that it was done the way it was meant to be
  — is the user's call, and an item quietly removed is a claim they never got to check. This holds
  even when the work is finished, tested and committed.
- **Do tick it**: `- [ ]` becomes `- [x]`, with a short `Done in <sha>.` line at the end of the item.
  Nothing else about the wording changes. Without that the file stops describing reality — a still-open
  box means "nobody has looked at this" and "this shipped an hour ago" alike, which is a trap for the
  next person and for the next session.
- **Do add items** for anything found along the way: a bug noticed while working elsewhere, a
  verification that could not be done from here, a consequence of a decision. Those are findings, and
  losing them costs more than a slightly long file.
- The record of *what* was done and *why it was done that way* belongs in the commit message and in
  the gotchas here and in [README.md](README.md) — never in the checklist item, which is why a ticked
  one can be deleted without reading it.

## Related checkouts

Sibling repos on this machine, useful as references and already in the working-directory allowlist:

- `../keelson/` — protocol spec (`docs/protocol-specification.md`), `messages/subjects.yaml`,
  `messages/qos.yaml`, Python/JS SDKs, existing connectors. **Source of truth for the wire format.**
- `../keelson-router/` — docker-compose Zenoh router setups to test against.

## CI

`.github/workflows/build.yml` runs `testDebugUnitTest lintDebug :app:assembleRelease` on pushes to
`main` and on pull requests targeting it, plus manual dispatch. A cold run is around ten minutes, most
of it packaging the Zenoh natives.

**The branch filters are load-bearing.** `on: push:` carried no filter, so every pull request built
twice — once as the push, once as the `pull_request` — and every feature-branch commit burned a full
run. Filtering both to `main` means each commit that matters is built exactly once.

**The Gradle run is split in two, and that is a constraint rather than fussiness.** protoc writes the
MCAP descriptor set to `app/src/main/assets/keelson_payloads.desc` — a *source* directory — at the same
path for every variant, so a debug task and a release task in one task graph make Gradle refuse the
build: `mergeReleaseAssets` uses the output of `generateDebugProto` without declaring a dependency, and
lint's model task hits it in the other direction. It predates CI building the release variant, and
`./gradlew build` has always hit it; nothing anybody ran happened to span both variants, and it fails
loudly rather than silently, which is why it went unnoticed. One variant per invocation is what the
shared path requires. The real fix is getting that file out of `src/`, filed in TODO.md.

**`assembleRelease` replaced `assembleDebug` for coverage, not speed.** `testDebugUnitTest` already
compiles the debug variant and `lintDebug` already analyses it, so the only thing given up is
*packaging* a variant nobody ships — while the variant that does ship now goes through the release
block and the signing config on every run. Building both would add ten minutes to package 118 MB that
goes nowhere.

**Signing is gated on a push to `main` specifically, and that is a security boundary.** Not on the
secret being present: a pull request from a branch *in this repository* does receive secrets, and a
pull request may edit `app/build.gradle.kts`, so a PR build that can reach the keystore is a PR that
can print it into the log. PR builds come out unsigned, which is everything a compile check needs.

**Main runs are excluded from concurrency cancellation** (`cancel-in-progress` is false only for
`refs/heads/main`). A run killed part-way through the upload would leave the rolling release serving a
truncated 110 MB APK, and a stable download link that hands back a broken file is worse than no link.

Two runner-specific details worth knowing before editing it: there is no `local.properties` on CI, so
AGP resolves the SDK from `ANDROID_HOME` (verified locally by building with the file moved aside), and
`compileSdk 37` is new enough that the workflow installs the platform explicitly with `sdkmanager`.

**That install spells the package `platforms;android-37.0`, not `android-37`.** From API 36 onwards
Android publishes minor releases, so platform paths carry a minor version — `36`, `36.1`, `37.0`,
`37.1` — and the bare `android-37` is not a package at all. `sdkmanager` answers a wrong path with
`Warning: Failed to find package` and exit 1, which reads like a runner or channel problem rather than
a typo, and had the workflow red on every run it made. Both the published index and the SDK installed
here spell it `37.0`; `$ANDROID_HOME/platforms/android-37.0/package.xml` is the local proof.

## Releases

Two channels, and they are deliberately different things.

**`main-latest` is a rolling prerelease.** Every push to `main` replaces it with a freshly signed APK
under a fixed asset name, so
`releases/download/main-latest/Logline-main-latest.apk` is a URL a phone can bookmark. `prerelease:
true` and `make_latest: false` keep it from outranking a real version at `/releases/latest`. The tag
is `main-latest` rather than `main`, because a tag sharing a branch's name makes every bare
`git checkout main` ambiguous.

**The one piece of pure GitHub trivia here: `softprops/action-gh-release` does not move the tag of an
existing published release.** `target_commitish` is honoured at creation and for drafts only, so the
tag ref stays wherever it was first cut. `build.yml` therefore force-updates `refs/tags/main-latest`
itself *before* calling the action; without that step the release would go on naming the commit it was
first cut at while serving new APKs underneath. `name` and `body` are set on every run for the same
class of reason — the action retains an existing release's info for any key not supplied, so omitting
the body once leaves last month's commit described above this month's APK.

**`v*` tags are versions**, and `release.yml` gates them twice. The tag must equal `versionName` with
the `v` stripped, because a tag is a claim and `version.properties` is the fact — when they disagree,
the APK inside a release named `v1.1` reports `1.0` on every phone and nothing on the release page
says so. And `CHANGELOG.md` must carry a matching `## <version> — <date>` heading, which becomes the
release body; an empty extraction fails the run, since a forgotten changelog entry is only ever
noticed once the release is public. The extractor matches by **literal prefix rather than regex**,
because a version is full of dots and `^## 1.0` as a regex also matches `## 120 — …`.

`workflow_dispatch` is deliberately absent from `release.yml`: with no tag in `github.ref` there is
nothing to name the release after, which is what made the old dispatch path broken rather than merely
unused.

## Repository state

A git repository since 2026-08-18, on `main`, with `origin` at `RISE-Maritime/Logline`. The initial
commit is the whole app at `versionCode 1`; everything before it is unrecoverable, which is the reason
it exists. That `1` is history rather than a live number — codes are derived from the commit count now
and start above 10 000, so nothing will ever be built at 1 again.

Ignored and deliberately never committed: `local.properties`, `.claude/settings.local.json`, the mTLS
client credentials under `certificates/`, any `*.pem` / `*.jks` / `*.keystore`, the generated
`assets/keelson_payloads.desc`, and `.idea/deploymentTargetSelector.xml` — which holds the serial
numbers of whichever phones this machine has deployed to, and was tracked by accident until the
pre-publication audit. **Nothing machine-specific belongs in a tracked file any more**: this repository
is public, so absolute paths, device identifiers and deployment hostnames go in the ignored local half.
`.claude/settings.json` is tracked and deliberately carries only portable permissions.
