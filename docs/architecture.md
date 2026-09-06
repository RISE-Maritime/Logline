# Architecture

How a sensor reading becomes a message on the Keelson bus, and which file owns each step.

## Data flow

```
Android SensorManager / FusedLocationProviderClient
        │  registerListener / requestLocationUpdates
        ▼
sensors/ImuProvider.kt, sensors/LocationProvider.kt, sensors/CameraProvider.kt
        │  callbackFlow → Flow<Vec3Sample | QuatSample | Location | CameraFrame>
        ▼
publish/SensorPublisher.kt
        │  build payload proto (LocationFix / Decomposed3DVector / TimestampedQuaternion /
        │                       CompressedImage)
        ▼
keelson/Envelopes.kt — enclose()
        │  core.Envelope { enclosed_at, payload } → ByteArray
        ▼
keelson/KeelsonSession.kt — publish()
        │  Publisher.put(ZBytes)
        ▼
Zenoh router (client mode, single configured endpoint)
```

The status counters flow back the other way: each successful `put` bumps a `SubjectStatus` inside
`SensorPublisher._status`, which both `MainScreen` and the service notification read.

## Ownership and process lifetime

```
LoglineApp (Application)
  ├── settingsRepository : SettingsRepository
  └── publisher          : SensorPublisher      ← one instance, whole process

MainActivity ── PublisherService.start/stop(context) ──> PublisherService ──> publisher.start/stop()
     └── renders publisher.status                            └── notification + partial wake lock
```

`SensorPublisher` is deliberately *not* held by Compose. A publishing run has to outlive the Activity,
so the Application owns it and both the UI and the service address the same instance. The UI never
calls `SensorPublisher.start()` — it sends an intent, and the service is the only caller.

### `publish/PublisherService.kt`

A started (never bound) foreground service. It does no sensor or Zenoh work; it exists to keep the
process alive and the CPU awake, which is the only way callbacks keep arriving with the screen off.

Order matters in `onStartCommand`: read settings (one blocking DataStore read — the type mask depends
on them), pick the service type from the *current* permission state, `startForeground` immediately
(the 5 s deadline), take the wake lock, and only then start the publisher. Opening the Zenoh session is
slow and must not sit in front of `startForeground`; a few milliseconds of preferences read is a
different order of magnitude and buys a type mask that is right on the first call.

| Location permission | `foregroundServiceType` | Subjects | Cap |
| --- | --- | --- | --- |
| Granted | `location` | all of them | none |
| Denied | `dataSync` | everything except the three GNSS subjects | ~6 h/24 h, then `onTimeout()` |

`microphone` and `camera` are **or**-ed onto whichever of those applies, each only when its subject is
enabled *and* its permission (`RECORD_AUDIO`, `CAMERA`) is already granted. On Android 14+ declaring a
type without its permission throws rather than warning, so a run started with audio or the camera
switched on but the permission denied must come back as a plain `location` run that publishes
everything else — which is exactly what a denied location permission does today.

`SensorPublisher.runLocation()` already skips itself when the permission is missing, so IMU-only mode
needs no publisher change — the mode only decides the service type and the notification wording.

`START_STICKY`, and a null intent (the system restarting us after a kill) is treated as a start, so an
unattended run resumes from persisted settings. The type is recomputed on every start, so a restart
after a permission revoke comes back as `dataSync` rather than crashing.

The service watches `publisher.status.map { it.error }.distinctUntilChanged()` — mapped, because the
raw status flow emits at the combined sample rate — and shuts itself down if the session failed to
open. A separate 5 s ticker refreshes the notification's sample count.

## Components

### `config/`

`Settings` is an immutable data class — five strings plus the per-subject `qosOverrides` map — with
the defaults as companion constants.
`SettingsRepository` wraps a DataStore preferences file named `logline_settings` and exposes
`Flow<Settings>` plus a suspending `update()`.

`entityId` has no constant default — it is derived per device by slugifying `Build.MODEL`
(lowercase, non-alphanumerics collapsed to `_`), falling back to `"android"`.

### `keelson/`

The protocol layer. Three small files, deliberately free of Android imports:

- **`Keys.kt`** — the `Subjects` constants, `pubsubKey()`, which builds
  `{realm}/@v0/{entity_id}/pubsub/{subject}/{source_id}`, and `livelinessKey()`, which builds
  `{realm}/@v0/{entity_id}/pubsub/*/{source_id}`. This is the only place the key layout is encoded.
  `@v0` is a **verbatim** chunk — no wildcard, `**` included, will match it — so a subscriber must
  write it out; `rise/**` silently matches nothing. That is what isolates major protocol versions.
  The `*` in the liveliness key is **literal**, per protocol specification §5.1 — presence is declared
  per source, not per subject.
- **`Qos.kt`** — the five QoS profiles transcribed from `keelson/messages/qos.yaml`.
  `policyQosForSubject()` is what upstream assigns; `qosForSubject(subject, overrides)` applies a
  per-subject user override on top, falling back to policy when there is none. Publishers are declared with the profile, so the transport
  stance is policy rather than accident. Only `location_fix` (`elevated`, `DATA_HIGH`) differs from
  Zenoh's defaults; the IMU subjects are unlisted upstream and inherit `default`, which `qos.yaml`
  documents as identical to those defaults. Verified on the wire — a subscriber sees `DATA_HIGH` on
  `location_fix` and `DATA` on the other three.

  Overrides live in `Settings.qosOverrides` (subject → profile; absent means follow policy) and are
  persisted one DataStore key per subject, `qos_<subject>`. Choosing Auto *removes* the key rather than
  storing a sentinel, so a subject with no opinion stays that way even if upstream policy later
  changes. An unrecognised stored value — a profile renamed or dropped upstream — is read as Auto
  rather than crashing.
- **`Envelopes.kt`** — `enclose(payload)` wraps arbitrary payload bytes in a `core.Envelope` stamped
  with `enclosed_at`; `protoTimestamp()` produces the payload-level `timestamp` fields. The two
  timestamps mean different things: payload `timestamp` is *when the value was observed*,
  `enclosed_at` is *when it was put on the bus*. There are two overloads — an `Instant` one, and an
  epoch-nanos one that floors, so a pre-epoch value still yields the non-negative `nanos` protobuf
  requires.
- **`KeelsonSession.kt`** — thin wrapper over the Zenoh Kotlin session. Opens in `client` mode with
  multicast scouting disabled, so the app never joins a bus it wasn't told about. It is given the
  *list* of configured endpoints: Zenoh tries them in order and attaches to whichever answers first,
  which makes the list failover rather than a fan-out — there is one session and one router at a time.
  A dead entry is cheap only when it is actively refused; a blackholed one costs up to the 10 s
  transport-open timeout before the next is tried, because they are tried sequentially.
  `declarePublisher(key, profile)` — the QoS profile is required, so a publisher cannot be declared
  without a stance — `publish(publisher, bytes)` returning a `Result`, `declareLivelinessToken(key)`
  and `isConnectedToRouter()` are the whole surface. `isLocalEndpoint(locator)` lives here too: it
  decides from the locator whether a run needs `ACCESS_LOCAL_NETWORK` (see `Scout.kt`).
- **`Scout.kt`** — the router scan, and **it speaks Zenoh's scouting exchange itself rather than
  calling `Zenoh.scout`**. The binding delivers a Hello from one of Zenoh's own threads and its native
  side builds the `ZenohId` argument with `FindClass`, which on a natively-attached thread resolves
  against the system class loader and cannot see app classes: the process dies with
  `JNI DETECTED ERROR IN APPLICATION … ClassNotFoundException: io.zenoh.jni.config.ZenohId` the moment
  any router answers. Verified on zenoh-kotlin 1.10.0; no app-side callback avoids it.
  **Scout is only the first of four.** The same fault fires for a query reply (`EntityGlobalId`) and for
  any subscribed sample carrying a `Timestamp` or `SourceInfo` — and a Zenoh router timestamps every
  sample it forwards, so no subscription is safe. `ZenohBinding.SUBSCRIPTIONS_SAFE` gates what that
  breaks; upstream is eclipse-zenoh/zenoh-flat-jni#49. The exchange is
  three bytes out (`encodeScout`) and one datagram back (`decodeHello`), both pinned by `ScoutWireTest`
  against captures from a real Zenoh node — including a cross-check that the zid string matches what
  zenoh-python prints for the same live router. Two Android details are load-bearing: the socket is
  bound to the active `Network` and its multicast interface set from `ConnectivityManager`, and
  `ACCESS_LOCAL_NETWORK` must be granted or the `sendto` fails with `EPERM`, which looks exactly like
  an empty network. The scan only ever *offers* what it found; nothing is connected to until it is in
  the endpoint list and saved.

### `calibrate/`

The platform geometry, and the only part of the app that is *about something other than the phone*. Free of
Android except for `CalibrationCapture`, which is the two sensor reads the rest of it is built from:

- **`Calibration.kt`** — `PlatformCalibration`, `PlatformZero` and `SensorMount` as plain value types, plus the
  slug helpers that turn `Sealog` into `sealog` and `sealog-frame-ccrp`. The enums spell upstream's
  `platform_type` and `sensor_type` vocabularies verbatim.
- **`Geodesy.kt`** — WGS84 radii of curvature, a local tangent-plane offset between two fixes, the
  great-circle bearing the two-point baseline uses, and the ENU → platform-frame rotation. The Z sign flip
  lives here: ENU is up-positive and the platform frame is down-positive, which is the whole difference
  between a mast and a keel. Also `averageFix`, which reports the platform's accuracy and the samples'
  own scatter as two separate numbers, because they routinely disagree by a factor of ten.
- **`Rotations.kt`** — yaw/pitch/roll to quaternion, intrinsic Z-Y-X, matching what `squaternion`
  produces inside `platform-geometry2keelson.py`. Pinned by `RotationsTest` against a rotation matrix
  built the long way.
- **`PlatformGeometryJson.kt`** — the document, hand-written the way `clientConfigJson()` hand-writes
  Zenoh's config. Two variants: strict for the export, provenance-carrying for the wire.
- **`CalibrationExport.kt`** — the export, through `record/Downloads.kt`, which is the MediaStore write
  the MCAP recorder also uses.

`SensorPublisher.runCalibration()` publishes it, on a ten-second loop, under the entity
`Settings.entityFor()` resolves — the platform's, not the phone's.

### `sensors/`

Both providers turn a callback-based Android API into a cold `Flow` via `callbackFlow`, registering
the listener on collection and unregistering in `awaitClose`. Nothing else in the app talks to
`SensorManager` or Play Services location.

- **`ImuProvider`** — `linearAcceleration()` (`TYPE_LINEAR_ACCELERATION`), `angularVelocity()`
  (`TYPE_GYROSCOPE`), `orientation()` (`TYPE_ROTATION_VECTOR`, converted to a quaternion with
  `SensorManager.getQuaternionFromVector`, which returns `[w, x, y, z]` — note the reordering when
  building the sample). The rate comes from settings — 50 Hz by default, which is what
  `SENSOR_DELAY_GAME` used to hard-code. A missing sensor closes the flow rather than throwing.
- **`LocationProvider`** — fused provider, `PRIORITY_HIGH_ACCURACY`, 1 s interval, callbacks on the
  main looper.
- **`SensorRate`** — a configured rate is either `Hz(n)` or `Max`. `Max` converts to a zero delay
  (`SENSOR_DELAY_FASTEST`; interval 0 for location) rather than to the advertised maximum as a number,
  because the advertised maximum is not a ceiling: the gyroscope here reports 415.97 Hz and delivers
  ~442 Hz. Plus pure conversions between Hz and the delay units Android wants
  (`hzToRateUs`, `hzToIntervalMillis`), input parsing, and `achievedHz()`, which divides samples by the
  span between the first and last publish. Note it uses *n−1* intervals for *n* samples; counting *n*
  would overstate the rate on a short run.
- **`SensorCapabilities`** — the device's own answer for what a sensor can do, from
  `Sensor.getMinDelay()`. Null where the platform publishes no limit, including `location_fix`, whose
  fused provider has no equivalent query.
- **`AudioProvider`** — `AudioRecord` read into fixed-length chunks and emitted from a `flow {}` on
  `Dispatchers.IO` rather than a `callbackFlow`, because `AudioRecord` is a blocking read rather than a
  listener; the recorder is stopped and released in the `finally`, which is the same guarantee
  `awaitClose` gives the others. The chunk's start time comes from the *sample clock* — an anchor taken
  at the first read plus `frames / rate` — not from the wall clock at each read, so a scheduling hiccup
  shifts nothing and consecutive chunks stay exactly one duration apart. Source preference is
  `UNPROCESSED` (when `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` says so) then `VOICE_RECOGNITION`
  then `MIC`; the first two leave AGC and noise suppression off, and a log wants the signal the
  microphone heard. `supports(rate, channels)` asks `getMinBufferSize`, so the settings screen greys out
  a rate the device will not give rather than failing when a run starts.
- **`CameraProvider`** — CameraX `ImageCapture` bound to a private `LifecycleOwner` (the publisher is
  not one), driven by a ticker that calls `takePicture` once per interval; `awaitClose` unbinds on the
  main thread, which is what releases the camera and its indicator. The camera stays bound for the whole
  run — opening one costs a few hundred milliseconds and blinks the indicator, which at a two-second
  interval would be most of the duty cycle. `ImageProxy.planes[0]` is already JPEG, so nothing converts
  pixel formats on the happy path; a device that hands back `JPEG_R` or YUV is re-encoded rather than
  published as something `format` would misdescribe. The resolution selector states its aspect-ratio
  strategy explicitly (the default prefers 4:3 and filters candidates before the resolution strategy
  sees them), and `scaleIfOversized()` re-encodes to the requested width on the many devices whose
  smallest JPEG stream is larger than anything this app offers. Every `ImageProxy` is closed — a leaked one stalls
  the pipeline after a few frames. Five consecutive capture failures fail the subject rather than
  logging forever.
- **`JpegScale`** — `scaleJpeg(jpeg, width, quality)`: a sampled (`inSampleSize`) decode followed by an
  exact scale, so the full-size bitmap is never materialised and the result is the width asked for
  rather than a power-of-two step. Used twice — for the live view's ~320 px thumbnail at quality 60, and
  by `CameraProvider` to bring an oversized capture down to the configured frame size.
- **`WavChunk`** — the 44-byte canonical RIFF/WAVE header put in front of each chunk's PCM, and
  `levelDbfs`, the RMS level the live view plots. Pure and no Android types, which is what makes the
  framing testable: `keelson.Audio` carries no rate, channel or bit-depth field, so the header is the
  only thing that makes a chunk self-describing.
- **`SampleHold`** — `Flow<T>.heldAt(intervalMillis)`, which republishes the last value on a ticker for
  the on-change sensors (`illuminance_lux`) that otherwise report nothing for minutes.
- **`BatteryRuntimeEstimator`** (in `publish/`) — least squares over a trailing window of (time, fuel)
  readings, giving time-to-empty. Pure Kotlin and unit-agnostic: microamp-hours or percent, whichever
  the device's gauge offers, since the unit cancels in `fuel / fuel-per-hour`. Fed from `runBattery()`,
  surfaced on `PublisherStatus.batteryRuntime`, and shown on the status card and in the notification.
- **`SensorClock`** — converts a `SensorEvent.timestamp` (nanoseconds on the boot clock) to epoch
  nanoseconds. The pure arithmetic is separated from the clock reads so it can be unit tested.

### Where a payload timestamp comes from

Each subject is stamped with the time the value was *observed*, never the time the coroutine got round
to publishing it:

| Subject | Source of `timestamp` |
| --- | --- |
| the `SensorManager` subjects | `SensorEvent.timestamp` via `SensorClock.epochNanosNow()` |
| `location_fix` | `Location.time`, the provider's UTC fix time (falling back to now if it is 0) |
| `audio` | the instant the chunk's *first frame* was captured, from `AudioProvider`'s sample clock |
| `image_compressed` | `ImageProxy.imageInfo.timestamp`, the camera's exposure time, via `SensorClock` |

`location_fix` additionally carries `position_covariance` — see `keelson/Covariance.kt`, which builds
the row-major ENU diagonal from Android's 1σ accuracies and mirrors the fleet's mavlink connector. Two
deliberate constraints: it is emitted only when *both* accuracies are present (a `0.0` in an unknown
slot would read as a perfectly known axis), and `altitude` has no way to say "unknown" — a proto3
`double` has no presence, so an unset altitude and a 0.0 altitude are the same bytes. Fixing that would
mean `optional double altitude` in the upstream message definition.

The boot→epoch offset is re-read for every sample, so the stream follows UTC across a clock
correction. Two consequences follow from that and are expected, not defects: `System.currentTimeMillis()`
is millisecond-resolution, so IMU timestamps carry ~1 ms of quantisation; and a backwards clock
correction moves samples backwards with it, so the stream is not guaranteed monotonic.

Because most devices — but not all — put `SensorEvent.timestamp` on the `elapsedRealtime` clock rather
than uptime, `SensorPublisher` checks the first sample of each IMU stream and logs a warning if its
implied age is negative or over 5 s.

### `publish/SensorPublisher.kt`

The orchestrator and the only long-lived mutable state.

`start(settings)` opens the Zenoh session on `Dispatchers.IO`, declares the four publishers, then
launches one collector coroutine per subject inside a `SupervisorJob` scope. A failure during setup
is caught, surfaced as `PublisherStatus.error`, and triggers `stopInternal()`.

`stop()` cancels the scope and closes the session. Because the scope is a `SupervisorJob`, one
failing subject collector does not take the others down with it.

`PublisherStatus` is a snapshot: `running`, an optional `error`, and four `SubjectStatus` values
(sample count + last publish epoch millis). It is owned by `PublisherStatusStore`, not by
`SensorPublisher` directly.

**`mark()` is the one publish that no collector produces.** An operator annotation is published on
demand rather than sampled, so it has no `runX()`, no entry in `COLLECTOR_GROUPS` and no sensor to
release — but it goes through the same `SubjectSink.emit()` as everything else, so it records to MCAP,
buffers for replay and counts identically, and the per-subject switch gates it in exactly one place
like every other subject. It returns `false` rather than silently doing nothing when there is no run or
the subject is switched off: a mark that went nowhere is the one failure a person pressing a button
would never notice. The instant is taken synchronously at the call, not inside the coroutine, because
the press *is* the observation.

`AnnotationLog` holds the marks made during a run for the screen to show back — bounded, `synchronized`
for cross-thread visibility, and **pulled on a ticker rather than pushed**, the same rule
`LiveSampleStore` follows.

### `publish/PublisherStatusStore.kt`

Every collector increments into the same value from `Dispatchers.Default`, so every counter update
goes through `MutableStateFlow.update` — a compare-and-set loop. A plain
`_status.value = _status.value.copy(...)` loses increments whenever two collectors read the same
snapshot before either writes; measured under a contended unit test, that pattern lost roughly 60–70%
of them. `started()` and `failed()` are whole-value replacements, so they stay plain assignments.

Keeping the counters in their own class is also what makes them testable: `SensorPublisher` needs a
`Context` and real sensor hardware, the store needs neither.

## Liveliness

`start()` declares one liveliness token per distinct source id — `setOf(locationSource, imuSource)`,
so one in the default configuration — right after the publishers are up. A failed declaration is
logged and ignored: liveliness is discovery, not the data path, and it must never reach
`setupFailed()`, which would stop the foreground service.

The tokens are undeclared in the teardown coroutine *before* the session closes. Closing the session
would drop them anyway; undeclaring first gives consumers a leave event immediately rather than one
that waits on transport teardown. A killed process produces the same leave event, which is the
property that makes liveliness worth having.

## Failure taxonomy

Three distinct things can go wrong, and conflating them is how a logger ends up lying to you:

| What happened | Where it shows | Does the run end? |
| --- | --- | --- |
| Session failed to open, or a publisher failed to declare | `PublisherStatus.error` | Yes — the service stops itself |
| The router link dropped mid-run | `PublisherStatus.connection` = `Disconnected` | No — Zenoh reconnects on its own |
| One subject's publish failed, or its collector died | `SubjectStatus.failure` | No — the other three continue |

The middle row is the one that needed measuring. **A Zenoh `put` on a session whose router has gone
still returns success**: during a deliberate 26 s outage on a Pixel 6, roughly 9000 puts succeeded
while the bus received nothing at all. Publish results therefore cannot detect a lost link, and the
sample counters kept climbing throughout — which is exactly the false "healthy" reading this design
exists to remove.

So `SensorPublisher` runs a watchdog coroutine polling `KeelsonSession.isConnectedToRouter()`
(`Session.info().routersZid()`, empty when there is no router) every 2 s. That, not the publish result,
drives `ConnectionState`.

**The top row's error belongs to one run, and clearing it is load-bearing.** `PublisherService` learns
about a setup failure by collecting `PublisherStatus.error`, and a `StateFlow` hands a fresh collector
the current value the instant it subscribes. So a failed run left its error in place, the next run's
watcher read it as its own, and the service stopped itself before the session had even opened — every
start after one failure died that way, until the process was force-stopped. `SensorPublisher.start()`
therefore calls `PublisherStatusStore.clearError()` synchronously before it launches anything, and the
service attaches its watcher only *after* `start()` has returned. Clearing rather than trying to tell
the runs apart by message is the point: retrying an unreachable router produces byte-identical text
every time, so any comparison would swallow the second failure.

Recovery needs no code: in the same experiment, restoring the link brought the stream back within
seconds with no restart and no user action, so the Zenoh config is left alone rather than carrying
retry settings it does not need.

Per-subject failures come from `SubjectSink` in `SensorPublisher`, which wraps each collector. Without
that wrapper a throwing collector is swallowed by the `SupervisorJob` and the subject simply stops,
invisibly. `CancellationException` is rethrown, or `stop()` would be reported as a failure. Logging is
once per subject — at 55 Hz, a line per failed publish is a flood.

### `checklist/`

**Gated off in this build** — `ZenohBinding.SUBSCRIPTIONS_SAFE` is false, so none of the below runs.
See the note under `Scout.kt` above and `eclipse-zenoh/zenoh-flat-jni#49`.

`ChecklistSync` owns a `KeelsonSession` of its own, opened while a checklist screen is up and closed
when it is not — a checklist is worked through with logging stopped, so it cannot borrow
`SensorPublisher`'s session, whose lifetime is a run. It publishes this operator's events and a
presence heartbeat every 5 s, and takes everything else by subscription.

**It issues no Zenoh query**, deliberately: the bootstrap `get` it used to run is what first exposed
the binding fault. Run snapshots arrive by subscription instead, which works because every station
holding an active run republishes one every 30 s, and the item text comes from this phone's own
store, which `ChecklistRepository` persists and `STARTER_PROCEDURES` seeds with crowsnest's own ids.

**It does now publish state snapshots, and only for runs it created.** That reversed an earlier
decision, and the reason it reversed is the run re-key: the phone can create a run, and
`checklist_state` exists solely so a late joiner can bootstrap — so a run this phone started and
never snapshotted is invisible to every station that was not listening at the time, because nothing
else will ever write that key. The old objection was right about the shape of the risk and wrong
about the fix: a phone writing a *procedure*-keyed record onto a run-keyed storage key really did put
a differently-shaped entry into a shared safety log, and keying on the run is what removes that,
rather than staying silent. Restricting the writer set by creator is what §7.4 asks for in place of
the per-writer keys it has not got, and it is the partition crowsnest already uses.

Progress is keyed on the **run**, not the procedure — §7.3 makes `checklist_state/{run_id}` one key
per run, forever. A publisher predating the run model sends none, and `runIdOf()` files such a record
under the procedure id rather than dropping it, which is also why the re-key needed no migration.

The layering is the point:

| File | Depends on |
| --- | --- |
| `ChecklistModel.kt`, `ChecklistReducer.kt` | nothing — pure Kotlin, no Android, no protobuf |
| `ChecklistCodec.kt` | protobuf, `enclose()` |
| `ChecklistKeys.kt` | `pubsubKey()` |
| `ChecklistStore.kt` | coroutines |
| `ChecklistSync.kt` | all of the above, plus Zenoh and a `Context` |
| `ChecklistRepository.kt` | DataStore — and `ChecklistCodec`, because it stores the wire bytes |
| `ChecklistEvidenceStore.kt` | a `Context`, for `filesDir` — the photographs this phone has attached |

`applyEvent` and `applySnapshot` are pure functions, which is what makes the conflict rules testable
without a bus — and since protocol-specification.md §7 they are testable against a *published
specification* rather than against this app's own reading of some proto comments. §7 names
`ChecklistSync.kt` as one of its two as-built reference implementations, and `ChecklistMergeTest` is
a test per §7.2 rule: earliest completion wins as a min-register over values, status is monotone,
notes and flags and evidence union by id, a flag's resolution is absorbing, terminal beats
non-terminal with `ABANDONED` winning, and the archive is re-emitted once seen. Every event is
de-duplicated by id, because a publisher's sample cache re-delivers.

There is deliberately **no `event_count` guard**. It reads like one is missing; §7.2 forbids it. The
count is a scalar, so two sites that each applied a different twelve events both hold 12 and each
rejects the other as stale. Convergence comes from the field rules, which hold regardless of arrival
order — `merging three snapshots converges in every order` is the test that checks the claim rather
than an instance of it.

`ChecklistStore` is a `StateFlow` — the opposite of `LiveSampleStore`, and for a reason that does not
generalise: that rule protects the UI from the *publish path* at 217 samples/s, and nothing on the
publish path touches this. It is fed by people tapping.

Reminders (`ChecklistReminders`, `ChecklistReminderReceiver`) are the only part with no wire presence at
all — `AlarmManager` plus a notification channel, re-armed from DataStore on boot.

### `ui/`

Every screen is built from the same small set of pieces in **`ui/components/Screen.kt`**, so
navigation and actions sit in the same place on all of them:

- `ScreenScaffold(title, onBack, titleIcon, bottomBar, snackbarHost)` — the top bar with a real back arrow. Screens
  used to print their own headline into the scroll and put "Back" or "Cancel" at the *bottom* of it, so
  leaving one meant scrolling to the end first. The bar uses `enterAlwaysScrollBehavior`, so it leaves
  with the content rather than parking a title over every list, and returns on the first upward flick —
  `exitUntilCollapsed` would have kept a stub pinned, and `pinned` is what it replaced. The Scaffold is
  wired to it with `Modifier.nestedScroll`; without that the scroll happens inside the content and the
  bar never hears about it.
- `FormActions` — Save and Cancel pinned in the bottom bar. Reaching Save on the settings form
  previously took six swipes, on every edit.
- `LoglineNavBar` and `TopLevel` — the five tabs (Session, Live, Events, Files, Setup) and the bar that draws
  them. It shares `bottomBar` with `FormActions` rather than adding a second `Scaffold`, because a
  nested one applies the status-bar inset twice; the slot is safe to share since a screen is either a
  tab or a form and never both.
- `ConnectionChip(running, connection)` — **Idle / Connecting / Running / No router**, the whole
  vocabulary for *global* state. It was written twice before this and the two drifted: the start
  screen said `Idle` and the live view said `IDLE`, from unrelated implementations.
- `EmptyState(title, body, primary, secondary)` — one shape for "nothing here yet", where there had
  been three (a `StatusLine` on the platform list, a centred column on the recordings list, a bare line of
  body text on the annotation screen).
- `SectionHeader`, `StatusLine` (icon + colour + text, never colour alone) and `ConfirmDialog`.
- `Modifier.readAsOneItem(description)` — collapses a row into one TalkBack node, because a subject
  row is five `Text`s that only mean anything together.
- `AppMark` — the launcher icon drawn the way the launcher draws it, foreground vector over background
  vector, clipped round. It sits beside the title on the start screen. Both layers come from the
  generated drawables, so `art/logline_icon.svg` stays the single source and the in-app mark cannot
  drift from the home-screen one.

**The colour scheme is the app's own, and dynamic colour is off.** `LoglineTheme` used to default to
`dynamicColor = true`, which derives everything from the user's wallpaper — on the test device that
produced a lavender app whose colour had nothing to do with Logline and changed when the wallpaper did.
The launcher icon is a near-white mark on a `#05377A`–`#012E69` navy, so `ui/theme/Color.kt` carries a
blue scheme taken from it, for light and dark. Two things there are deliberate: every `surfaceContainer*`
role is spelled out, because those defaults are Material's baseline *purple* neutrals and a `Card` draws
on them — leaving them would have produced a blue app with lavender cards; and `tertiary` is a warm
amber, because it is what `StatusTone.Warning` paints and a cool tertiary sat close enough to primary
that a warning read as just another blue label. Passing `dynamicColor = true` hands the scheme back to
the wallpaper.

**Stopping is two steps, and the closing note is published by the publisher rather than the caller.**
Stop sits alone under the thumb in the pinned bar and ends something that cannot be resumed — the
recording is closed and copied, and starting again opens a new file — so it opens a dialog carrying the
run's figures and an optional note. One interruption rather than two: the moment somebody decides to
stop is exactly when they know what the run was.

The note is a *note*, not a rename. It goes out as a `log_message` mark, so it lands on the bus and in
the recording beside every other mark of the run; nothing is renamed, because a long run may have
rotated through several files and copied some of them to Downloads already.

**`SensorPublisher.stop()` takes the note and publishes it itself, and that is not a convenience.**
`mark()` launches on the run scope, and stopping cancels that scope — a note marked from the UI and
then followed by a stop would race the teardown and usually lose. `stopInternal` publishes it on
`closeScope` *before* cancelling the collectors, and since the recorder is stopped after that cancel,
the note reaches the file as well as the wire. Same ordering discipline as the recorder-after-collectors
rule beside it. Verified: the typed line appears in the saved `.mcap`.

**Start and Stop carry a glyph, and REC is a lamp rather than a line of text.** The stop square is
*drawn* — a `Box` with a rounded clip — because `material-icons-core` has no stop glyph and
`material-icons-extended` is tens of megabytes of vectors to buy one shape. The recording lamp sits in
the pinned action surface rather than beside the connection chip in the app bar, and that placement is
the whole point of it: the bar uses `enterAlwaysScrollBehavior` and leaves the screen on the first
downward scroll, and thirty-nine subject rows is a page people scroll — so an indicator up there
answers "is it still recording" only until somebody looks at anything. It blinks via a `graphicsLayer`
block so the animated value is read in the draw phase and nothing recomposes for it, and it is shown
only when a file is actually being written: publishing and recording are two facts, and only one of
them leaves something to take off the phone afterwards.

**Navigation is four tabs, and everything else is pushed on top of one of them.** Home used to carry
six full-width buttons under its status card — Settings, Live view, Mark event, Platforms, Recordings,
Checklists — which made the first screen a dashboard and a table of contents at once, and made `Start
publishing` compete with five controls that do nothing during a run. Those four configuration
destinations moved to a **Setup** hub (`ui/SetupScreen.kt`), whose rows carry as a subtitle the state
their old button labels smuggled into themselves (`Platforms · Sealog` became a row reading *Platforms* over
*Sealog*). What is left on Home is one filled button when stopped, and `Live view` / `Mark event` /
`Stop` when running — observe, annotate, stop, in the order a run is worked in. The first two are tab
switches duplicated out of the bar deliberately, because the moment being marked is passing while
somebody hunts for the control.

Two things about this are load-bearing and easy to break. The bar lives in `ScreenScaffold`'s existing
`bottomBar` slot, so there is still exactly one `Scaffold` handling insets. And the checklist and
platform sessions are scoped by route *prefix*, so moving Platforms and Checklists under Setup left them
working — but a route rename would break them silently, since the screen still opens and simply finds
nothing.

**`MainScreen` is an operational display, not a status page for the plumbing.** Its rows show the
*measurement* — `Speed over ground  0.2 kn`, `Position  57.4359°N 12.0326°E` — with the rate and the
age as small print beneath. The earlier version led with sample counts, which answer "is telemetry
flowing" but never "is the boat doing three knots", so a run could look perfectly healthy while
publishing nonsense.

Three pieces support that:

- **`ui/SubjectLabels.kt`** turns `speed_over_ground_knots` into "Speed over ground" plus a `kn` unit,
  and formats each subject's value at the precision it actually means — a tenth of a knot, a whole
  dBm, `219M` rather than nine digits of bit rate. The wire name is not lost: it is shown on the
  subject's own screen, which is where someone needs to spell it for a subscriber.
- **`LiveSampleStore.latest()`** returns one value per subject rather than the whole window.
  `snapshot()` would hand the main screen a quarter of a million floats a second to display
  twenty-nine numbers; `latest()` and `TrackRing.latest()` read the newest slot under the same lock.
  It is still **pulled on a 1 Hz ticker**, never pushed — the rule about the publish path not driving
  recomposition applies here exactly as it does to the live view.
- **`ui/SubjectGroups.kt`** groups by `SourceKind` (radio split by link) into collapsible sections
  whose heading carries the verdict: `4/4 ✓` when everything is live, `1 stalled` in the error colour
  when it is not. `subjectHealth()` distinguishes *live*, *stalled* (nothing for five of that
  subject's own intervals), *waiting*, *failed* and *unavailable* — the last from
  `unavailableSubjects(context)`, so a phone with no barometer says so instead of showing a row that
  silently never publishes.

Colour is **exception-based**: a healthy screen has none in its rows at all, so anything coloured is a
problem. The status card is two lines — publishing and recording — with endpoint, file name and folder
behind a tap, and the action row leads with **Live view** while a run is going, because starting is a
one-off and watching is the job. `MainScreen` still takes only data and lambdas — no `Context`, no
publisher.

`MainActivity` owns the permission requests (`ACCESS_FINE_LOCATION` plus `POST_NOTIFICATIONS` on API
33+, and `ACCESS_LOCAL_NETWORK` when the endpoint list has a LAN address) and starts the service
regardless of the outcome: a denied location means IMU-only, a denied notification only means an
invisible notification. `locationGranted` is re-read in `onResume`, which covers both the permission
dialog and a trip to system settings. A second, request-only launcher backs the **Grant location
permission** button on the IMU-only warning, so the warning offers the fix rather than describing it.

**`SettingsScreen`** is still a pure form — local `remember` state, `onSave`/`onCancel` lambdas, no
repository access — with Save pinned and per-field helper text. Its twelve flat sections are now
**six collapsible groups** (General, Connection, Recording, Sensors & media, Collaboration, Advanced),
only General open by default, following the same rule the start screen's subject groups follow: the
default is closed and the heading carries the summary. The old section headings survive *inside* each
group, because they carry the per-section counts (`2/3 imported`) worth reading. The four longest
explanatory paragraphs moved behind ⓘ via `InfoDialog`; the one-line `SettingSwitch` descriptions
stayed inline, and the TLS clear-confirm and the battery-optimisation explanation stayed visible
because both describe things that are hard to undo. Before this the screen was 811 lines and about
twenty-three paragraphs deep, and it read as a configuration file; it now opens as one screenful. It compares its edited
`Settings` against `initial` to know whether it is dirty, and a `BackHandler` turns a system-back with
unsaved edits into *Discard changes?* instead of a silent loss. `validateEndpoint()` checks a typed
locator as it is typed, and clearing a TLS credential is confirmed, naming what stops working.
`MainActivity` handles the save: stop if running, persist, pop the back stack, restart with the new
settings.

**`SubjectQosScreen`** keeps the sampling rate open and puts the four QoS controls behind an
*"How this travels"* disclosure — expanded automatically when the subject is already overridden, so an
existing override is never hidden.
It drops the sampling-rate section entirely for an `eventDriven` subject: `log_message` has no stream
to sample, and a rate control there would be a dial with nothing behind it.

**`AnnotationScreen` is built for one tap with one hand on a moving boat.** The buttons already
published immediately — `onMark` goes straight to `SensorPublisher.mark()` — but that call returns a
`Boolean` saying whether the mark had anywhere to land, and the screen used to discard it. It now
shows the answer as a snackbar, because the only other evidence was a row appearing a second later in
a list below the fold, which is no feedback at all at the moment the button is pressed. Successive
marks dismiss the previous confirmation rather than queueing: several in quick succession is the
normal case, and a backlog of stale ones would still be arriving after the moment had passed. The buttons come first and
they are large, because the moment being marked is passing while you look for them; the note field and
the list of what has been marked sit below. That list is not a second copy of the recording — it is the
answer to "did that register", which is the only question left by a button that gives no other
feedback. Severity is the one thing coloured, and only when it is not routine, so colour still means
"look at this" the way it does on the main screen.

**Platform calibration is a five-step flow, and the rail is navigation rather than a gate.** The editor was
one long form that exposed the whole data model at once — identity, zero point, forward axis, sensors,
and the wire keys — with `frame_transform` and `configuration_json` in the first paragraph above the
name field. It is now Platform / Zero / Forward / Sensors / Review, with every step reachable at any time
in any order: a wizard that made somebody walk five screens to fix a typo would be worse than the form
it replaced. `Next` exists for a first survey and never blocks.

Three details are load-bearing. The step index is hoisted into `App()` beside the draft, because
editing a sensor pushes another destination and pops back — a `remember` in the screen would return
you to step 1 having just added a sensor on step 4. Save stays pinned across all five steps rather
than becoming a step-5 action: `saveEnabled` needs only a name and a valid entity id while
`isPublishable` additionally needs a sensor, so a half-surveyed platform is legitimately saveable and a
wizard that only saved at the end would throw away a survey somebody was interrupted in. And the rail
is disabled while a capture is running, because a twenty-second position capture has somebody standing
still at a point. The screen also gained the `BackHandler` every other form already had — it was the
one that discarded unsaved edits to a system-back silently.

**`AnnotationButtonsScreen` is deliberately not part of Settings.** Saving Settings restarts the
publisher so publishers are redeclared with a new key or QoS; none of that applies to a list of button
labels, and somebody adding a button halfway through a passage must not lose the run to do it. It is a
plain form otherwise, with the same `mutableStateListOf` / dirty / `FormActions` shape as
`SettingsScreen`.

**`LiveScreen` is a dashboard first and a plot list second.** The first screenful answers where the
phone is, whether the data is healthy and what it is doing: the map with a position marker, accuracy
circle and *two* vectors — course over ground from the fix and heading from the compass, which differ
by the leeway — then the position line, then speed, course and heading as large readings, then one
health chip per group. Below that a 30 s / 2 min / 10 min window and a **Pause**, then a **Basic / All** control, and below
that the plots, folded into the same sections as the main screen.

Three additions there. `VitalsLine` puts satellites, fix kind, cellular SINR and charge on one line
under the position, so "is this run healthy" no longer costs a scroll into three groups — each part is
skipped rather than dashed when the platform has not reported it. The health chips became a *filter*:
they were a read-only readout occupying a row just above the plots, and a tap now narrows to one
group. And **Basic** plots only `PublishedSubject.featured`, with the count of what it is holding back
stated beside it so it cannot read as data having gone missing — thirty-nine plots is a page nobody
scrolls during a run. Card footers carry the unit as well as the range, since the footer is read on
its own scanning down a column and a bare `12 – 18` says nothing about what it is 12 of.

Four things there are less obvious than they look:

- **`ui/LiveSignals.kt` holds the maths**, all of it pure and tested. `unwrapAngles()` is the reason a
  heading crossing north draws a two-degree step instead of a full-height cliff, and it **must run
  before `decimate()`** — a slow turn survives either order, but decimating first leaves gaps wider
  than half a circle on a fast one, and across such a gap a big turn one way is indistinguishable from
  a small turn the other, so the trace aliases backwards.
- **The plot is binned, not sampled, and its axis has a floor.** Two separate faults made steady
  sensors look unstable. First, the y-axis scaled to the data's own range with no lower bound: a
  stationary barometer varying 2 Pa in 100 kPa — 0.002% — was stretched to the full height of the card,
  so a rock-steady reading drew as a seismograph. `plotBounds()` refuses to scale tighter than 1% of the
  value, and the footer keeps showing the true min and max because that is a measurement while the axis
  is a drawing decision. Second, stride sampling took one sample in twenty-two from a 55 Hz window and
  *which* one shifted every frame, so the line danced. `envelope()` bins instead: every sample
  contributes, each pixel column keeps its own minimum, maximum and mean, and the card draws the band
  with the mean through it. The band is the noise, the line is the trend, and a spike is always
  somebody's maximum — which is what the old stride-sampling comment was trying to protect and did not.
- **`windowedTo()` binary-searches** the cut point. `timesMillis` is sorted because the ring is
  append-only, and a linear scan across 30 subjects × 8192 samples five times a second would be a
  million comparisons for nothing.
- **The window is bounded by memory, not by the control.** `LiveSampleStore.DEFAULT_CAPACITY` is 8192
  samples, so "10 min" is everything held for a 1 Hz subject and about 2.5 minutes for a 50 Hz one.
  The ⓘ says so.
- **Pause stops the pull, not the publisher.** The snapshot ticker and the clock both stop, so the
  whole view freezes while sensors keep publishing and recording underneath — verified by comparing
  screenshots 25 s apart while 29 subjects kept arriving at the router.

Documentation that used to sit between the map and the plots — the magnitude note and the entire axis
reference — now lives behind ⓘ buttons via `InfoDialog`, and the collapsed sections, chosen window,
pause and follow-fix are hoisted into `App()` so leaving the screen and returning does not reset them.

## Threading model

- A partial wake lock (`logline:publishing`) is held for the whole run. The IMU sensors are
  non-wakeup, so without it delivery stops when the CPU suspends — the wake lock is what makes
  screen-off logging work, not the foreground service on its own. Every exit path releases it:
  `stopPublishing()`, `onTimeout()`, and `onDestroy()`.
- Zenoh session open runs on `Dispatchers.IO`.
- **Session close runs on `Dispatchers.IO` too, on a `closeScope` that outlives a run** — the run scope
  is the thing being cancelled, so it cannot host its own teardown. `stop()` is fire-and-forget: the
  caller's thread only nulls the fields and flips the status, so the UI reacts on the tap. Every caller
  (`PublisherService.stopPublishing()` from `onStartCommand`, `onTimeout`, `onDestroy`) is on the main
  thread, and `Session.close()` is an unbounded JNI call — measured at about 1 ms on a Pixel 6 whether
  connected or not, so this is correctness rather than a rescue.
- The close coroutine **joins the cancelled run scope before closing the session**. Cancellation is
  asynchronous, so without the join a publish can still be in flight inside JNI while the session is
  torn down under it. The join costs 14–30 ms in practice, which is the collectors actually winding
  down — work that previously did not happen at all before the close.
- Sensor collectors run on `Dispatchers.Default` (the publisher scope).
- Android sensor and location callbacks arrive on the main looper and are handed to the flow via
  `trySend`, which drops on a full buffer rather than blocking the callback.
- `publisher.put()` is called from the collector coroutine, i.e. off the main thread.

## Permissions

Two runtime permissions, neither of them blocking. `ACCESS_FINE_LOCATION` is requested in
`MainActivity`, re-checked in `PublisherService` to pick the service type, and re-checked again in
`SensorPublisher.runLocation()`, which skips the location publisher rather than crashing if it was
revoked mid-run. `POST_NOTIFICATIONS` (API 33+) affects only whether the ongoing notification is
visible. IMU sensors need no permission at this sampling rate.

Manifest permissions added for the service: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`,
`FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`. `ACCESS_BACKGROUND_LOCATION` is
not needed, because the service is always started from a visible Activity.

## What isn't here

Deliberate gaps, listed so nobody goes looking for them:

- No QoS profile derivation from `keelson/messages/qos.yaml`; publishers use Zenoh defaults.
- No liveliness token declaration, so consumers can't discover this publisher via Zenoh liveliness.
- A dropped connection is filled in afterwards: the last ~2.5 minutes of samples are held and replayed
  when the router returns, and everything is written to MCAP regardless.
- No ViewModel layer. `SensorPublisher` hangs off the `Application` instead, which is enough for a
  single-screen app and is what lets the service and the UI share one instance.
