# Logline

Android (Kotlin + Compose) publisher that puts a phone's GNSS, IMU, barometer and battery on a
Keelson/Zenoh bus.
Single Gradle module `:app`, package `se.rise.logline`, ~3000 lines of Kotlin.
Read [README.md](README.md) for what it does; this file is how to work on it.

## Build environment — read this first

There is **no `java` on the PATH**. Every Gradle invocation needs `JAVA_HOME` pointed at a JDK 25
(the daemon toolchain is pinned in `gradle/gradle-daemon-jvm.properties`):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

`.claude/settings.json` sets `JAVA_HOME` and `ANDROID_HOME` for this project, so Bash calls here
already have them. If a Gradle command fails with `Unable to locate a Java Runtime`, that env was
lost — re-export it inline rather than debugging Gradle.

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

`ChecklistSync` shares nothing with `SensorPublisher` — different session, different realm, different
lifetime — because a checklist is worked through with logging stopped. See "Checklists" below.

`SensorPublisher` is the only stateful thing in the app. It exposes `StateFlow<PublisherStatus>`
holding a per-subject sample count and last-publish timestamp; the UI is otherwise stateless.

**Nothing that outlives a screen may be held by Compose.** The publisher lives on `LoglineApp`
precisely so the service and the Activity see the same instance and the same status flow. Start and
stop go through `PublisherService.start(context)` / `.stop(context)` — never call
`SensorPublisher.start()` from the UI.

Full walkthrough: [docs/architecture.md](docs/architecture.md).

## Conventions

- **Key expressions only via `pubsubKey()`** in `keelson/Keys.kt`. Never concatenate a key inline —
  the `{realm}/@v0/{entity_id}/pubsub/{subject}/{source_id}` layout is protocol, not formatting.
  **`@v0` is verbatim: no wildcard crosses it**, so any subscription or tooling must spell it out —
  `rise/**` matches nothing and fails silently, `rise/@v0/**` works. `KeysTest` pins the chunk position.
- **QoS comes from `qos.yaml`, via `qosForSubject()`** in `keelson/Qos.kt`. Those profiles are a
  transcription of `../keelson/messages/qos.yaml` — a copy, like the vendored protos. Never hand-tune a
  publisher's priority or reliability here; if a profile is wrong it is wrong upstream first. Only the
  three GNSS subjects (`elevated`) differ from Zenoh's defaults today; everything else is unlisted
  upstream and inherits `default` on purpose, so the same subject travels identically from every
  connector. The Settings screen exposes a per-subject override on top of that — `policyQosForSubject()`
  is upstream policy, `qosForSubject(subject, overrides)` is what actually gets used. Overrides are an
  escape hatch, not the norm: default them to Auto and keep the policy path the one that works.
- **Liveliness keys only via `livelinessKey()`**, same file, same reason. The `*` in
  `{realm}/@v0/{entity_id}/pubsub/*/{source_id}` is literal — one token per *source*, not per subject
  (protocol specification §5.1). Declaring one token per subject is a misreading of the spec — the
  count follows the configured source ids, not the subject count.
- **Subject names only via `Subjects`** in `keelson/Keys.kt`, and the subject *set* only via
  `PublishedSubject` in `keelson/SubjectRegistry.kt`. A new subject means a constant plus a registry
  entry, and the name must already exist in `keelson/messages/subjects.yaml` upstream. **Check `dev`,
  not just the checked-out branch** — the `radio_*` subjects live on `dev` and are not yet on `main`, so
  a `git show dev:messages/subjects.yaml` is the honest lookup.
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
- **Compose screens take data and lambdas**, never a repository or a `Context`. There are no
  exceptions left — `MainScreen` takes a `PublisherStatus`, and anything needing a `Context`
  (`sensorCapabilities()`, the file picker, the osmdroid `MapView`) is resolved in `App()` in
  `MainActivity` and handed down. `LiveScreen` takes its map as a `@Composable (Modifier) -> Unit`
  for exactly this reason.
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
| `Primitives.proto`, `Decomposed3DVector.proto`, `Audio.proto` | `../keelson/messages/payloads/` |
| `Checklist{Event,State,Presence,Procedure}.proto` | `../keelson/messages/payloads/` |
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

- **The protocol was not upstream.** `keelson.ChecklistEvent` / `ChecklistState` / `ChecklistPresence`
  existed only as generated JS in the git-ignored `../keelson/sdks/js/dist/`, built from `.proto` files
  nobody committed — crowsnest works because it depends on `file:../keelson/sdks/js`. They have been
  reconstructed from those generated encoders and added to `../keelson/messages/payloads/`, along with a
  new `ChecklistProcedure.proto`. `ChecklistWireTest` pins them against golden bytes produced by
  crowsnest's own bindings; that test is the only thing standing between a field-number slip and a
  message that decodes cleanly into the wrong fields.
- **Procedure *definitions* travel on the bus; nothing else knew that.** An event names an item by id
  and carries no text, so a client with no definition can say something was completed but not what it
  said. Crowsnest seeds its library from a hardcoded constant per browser. `checklist_procedure` fixes
  that — one key per procedure, held by the router's `storage_manager`, read with a single `get` on
  join. `STARTER_PROCEDURES` is crowsnest's library transcribed **with its ids**, offered for publishing
  only when the query comes back empty; the ids are what make the two sides agree, so changing one does
  not rename anything, it makes them stop agreeing silently.
- **The router needs storages, and the ones it had were for something else.**
  `../keelson-router/docker-compose.keelson-router-rise.yml` now covers
  `crowsnest/@v0/*/pubsub/checklist_procedure/*` and `.../checklist_state/*`. The pre-existing
  `rise/@v0/*/pubsub/checklist_{state,controller}` storages match nothing any client publishes.
  Note that `command:` is a folded YAML scalar — a `#` line inside it is an argument, not a comment.
- **`ChecklistSync` owns its own session**, opened when a checklist screen is up and closed when it is
  not, keyed on `route.startsWith("checklist")` so stepping between the list and a procedure does not
  cycle it. Two Zenoh sessions in one process are fine; `initZenohLogOnce()` already guards the one
  thing that may only happen once.
- **Its store is pushed, not pulled — the opposite of `LiveSampleStore` and `AnnotationLog`.** That rule
  is about the *publish path*: ~217 sensor samples a second must not drive recomposition. Nothing on the
  publish path feeds `ChecklistStore`. It is fed by taps, at both ends of the link, and a ticker there
  would only add latency to a tap. Updates still go through `MutableStateFlow.update`, because a Zenoh
  thread, a heartbeat coroutine and the UI all write it.
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

## Gotchas

- **The launcher icon is generated, not hand-written.** `art/logline_icon.svg` is the source;
  `python3 art/svg_to_adaptive_icon.py` regenerates `drawable/ic_launcher_{background,foreground}.xml`.
  Never put an `.svg` under `res/` — AAPT will fail the build. The foreground is deliberately scaled to
  0.80 so a circular launcher mask cannot clip the mark.
- **Release builds are unsigned by design when no key is configured.** `assembleRelease` warns and
  emits `app-release-unsigned.apk` rather than failing, so a developer without the keystore is not
  blocked. Credentials come from env vars or `local.properties`; see the README. Version lives in
  `version.properties` and is bumped by hand — there is no git repository to derive it from.
- **`local.properties` is git-ignored** and holds `sdk.dir`. A fresh clone won't build without it.
- **Configuration cache is on.** Build-logic edits are picked up; if a change seems ignored, add
  `--rerun-tasks` before assuming the code is wrong.
- **AGP 9.3.1 / compileSdk 37** is a leading-edge combination. Treat unfamiliar AGP DSL errors as a
  version-specific API question, not as broken code — `compileSdk { version = release(37) }` and the
  `optimization { }` release block are AGP 9 syntax and are correct as written.
- **Endpoints are a list, and the default is the cloud router, `tls/router.example.com:443`.** Stored
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

- **The compass is derived, not a sensor, and three of its four subjects are conditional.**
  `heading_magnetic_deg` is `getOrientation`'s azimuth off `TYPE_ROTATION_VECTOR` — the direction of the
  phone's **+Y axis**, which is meaningless when +Y points at the sky. `heading_true_north_deg` and
  `magnetic_variation_deg` need a position for `GeomagneticField`, so they publish **nothing** until the
  first fix rather than referencing north wrongly; `heading_accuracy_deg` is absent on a device that
  reports no estimate. All four ride another subject's samples (`rateOwner`), so they have no rate of
  their own — and the three heading ones therefore run at the rotation vector's rate, which at the 50 Hz
  default is ~150 extra messages a second. Do not confuse heading with `course_over_ground_deg`: one is
  where the phone points, the other where it is going.

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
- **osmdroid needs a user agent or it silently shows nothing.** OSM's tile servers answer the library
  default with `403`, and the failure looks like a blank grid rather than an error. `TrackMap`
  sets `Configuration.userAgentValue` to the package name and calls `MapView.onResume()` — `AndroidView`
  does not forward lifecycle, and osmdroid starts its tile threads there. Both were needed before a
  single tile appeared.
- **MCAP records the unwrapped payload, never the envelope.** keelson's replayer re-wraps with
  `enclose(payload=message.data, enclosed_at=message.publish_time)`, so writing envelopes produces
  doubly-wrapped messages that decode to garbage everywhere downstream. The channel topic is the full
  Zenoh key, because the replayer republishes it verbatim. `connectors/mcap/bin/keelson2mcap.py` is the
  reference — read it rather than the connector README, which says "records envelopes" and means the
  opposite.
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
- **`subjectSchemaNames` in `record/McapSchemas.kt` must track `subjects.yaml`.** A wrong type there
  produces a file that opens and decodes to nonsense.
- **`Recorder`'s channel is per *run*, not per Recorder — and that is load-bearing.** `stop()` closes
  the queue to end the drain loop, and a closed Kotlin `Channel` can never be reopened. When the channel
  was a single long-lived field, the *second* run in a process recorded **nothing**: `drain()` saw a
  closed queue, returned immediately, published a header-only 223-byte MCAP with no messages, and every
  sample after that was counted as dropped while the run itself published fine. Same shape as the Zenoh
  logger crash below — a bug only reachable by Stop then Start without killing the app, which is exactly
  what a normal session does. Verified fixed by two consecutive runs in one process producing 10 596 and
  9 703 messages, both read back with the `mcap` Python library.

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
- **The rig calibration is the only thing published under a different `entity_id`.** `entity_id` names
  the physical thing the data is *about*, and a rig's geometry is about the rig, not the phone that
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
- **All of a rig's transforms share one key**, with the sensor named by `child_frame_id` inside the
  message — upstream's shape, not an oversight. Zenoh's latest-value store therefore holds only the last
  transform of each round, which is exactly why `runCalibration()` republishes on a ten-second loop
  rather than publishing once at start-up. Its "rate" is that interval, the same reading `audio` gives
  its chunk length.
- **`location_fix` is published by two registry entries, and the difference is entity + source.** The
  phone's live fix is `{phone}/pubsub/location_fix/phone`; the rig's surveyed zero is
  `{rig}/pubsub/location_fix/calibration`. Three things stop the second being read as a live position,
  and none of them is optional: the key differs, the payload timestamp is the **survey** time (the
  opposite choice from `frame_transform` beside it, and for the opposite reason — here the age of the
  measurement is the point), and `labelOf()` names it "Zero point" rather than "Position", keyed on the
  *entry* because that is the only way to tell two entries of one subject apart. It stays silent until a
  position exists: `Settings.offSubjects()` gates it on `RigZero.hasPosition`, because a tape-measured
  rig and a heading-only zero both have geometry worth publishing and no position, and 0°N 0°E is the
  most confident possible way of being wrong.
- **A captured offset smaller than its own fix accuracy is noise, and the UI says so.** Averaging twenty
  seconds of fixes cuts *scatter*, not *bias* — GNSS multipath holds still for minutes — so
  `AveragedFix` reports both numbers and `SensorMount.accuracyExceedsOffset` drives a red line on the
  row. A phone is honest for platform-scale geometry; decimetre offsets on a small rig want a tape
  measure, which is why manual entry is the primary path rather than the fallback.
- **Emulators are useless here.** GNSS, IMU and the camera all need a physical device.

## Related checkouts

Sibling repos on this machine, useful as references and already in the working-directory allowlist:

- `../keelson/` — protocol spec (`docs/protocol-specification.md`), `messages/subjects.yaml`,
  `messages/qos.yaml`, Python/JS SDKs, existing connectors. **Source of truth for the wire format.**
- `../keelson-router/` — docker-compose Zenoh router setups to test against.

## CI

`.github/workflows/build.yml` runs `testDebugUnitTest lintDebug assembleDebug` on push, pull request,
and manual dispatch. It runs as of the initial commit; nothing has exercised it yet, because
there is no remote to push to.

Two runner-specific details worth knowing before editing it: there is no `local.properties` on CI, so
AGP resolves the SDK from `ANDROID_HOME` (verified locally by building with the file moved aside), and
`compileSdk 37` is new enough that the workflow installs the platform explicitly with `sdkmanager`.

## Repository state

A git repository since 2026-08-18, on `main`, with no remote configured yet — pushing needs one
created first, and that is a decision for whoever owns the org. The initial commit is the whole app at
`versionCode 1`; everything before it is unrecoverable, which is the reason it exists.

Ignored and deliberately never committed: `local.properties`, `.claude/settings.local.json`, the mTLS
client credentials under `certificates/`, any `*.pem` / `*.jks` / `*.keystore`, and the generated
`assets/keelson_payloads.desc`. Note `.claude/settings.json` **is** tracked and holds this machine's
absolute `JAVA_HOME` and `ANDROID_HOME` — fine while this is a one-machine project, worth revisiting
the moment it is not.
