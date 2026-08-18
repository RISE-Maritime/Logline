---
description: Add a new Keelson subject publisher end to end
allowed-tools: Read, Edit, Write, Grep, Glob, Bash(./gradlew:*), Bash(grep:*)
---

Add publishing for the subject named in `$ARGUMENTS`, following the existing four subjects as the
template.

**Validate first — do not skip this.** Look up the subject in
`~/Documents/CODE/keelson/messages/subjects.yaml` and note its payload type. If it is not there,
stop: subject names are protocol, defined in the `keelson` repo, and inventing one here produces
messages nobody can consume. Report that and ask whether to add it upstream first.

Note its QoS too: if `keelson/messages/qos.yaml` lists it under a profile other than `default`, it
needs a branch in `policyQosForSubject()`. Unlisted means it inherits `default`, which is correct and
needs no edit.

`PublishedSubject` in `keelson/SubjectRegistry.kt` is the single source of truth. Status keys, rate
defaults, sensor capabilities, persistence and the UI rows all derive from it — so this is short now.
Do not add a parallel list anywhere; the duplication this replaced failed silently.

Then:

1. If the payload `.proto` is not yet vendored, copy it from `keelson/messages/payloads/` (or
   `payloads/foxglove/`) into `app/src/main/proto/`, verbatim. Most primitives are already there —
   check before copying.
2. Add the subject constant to `Subjects` in `app/src/main/java/se/rise/logline/keelson/Keys.kt`, and
   an entry to `PublishedSubject`: `defaultRate`, `source`, `sensorType` if it comes off
   `SensorManager`, and `rateOwner` if it rides another subject's samples. Add the name to the expected
   set in `SubjectRegistryTest`.
3. If no sensor source exists, add a flow to `sensors/` using `callbackFlow` — register the Android
   listener on collection, unregister in `awaitClose`. Follow `ImuProvider` or `ScalarSensorProvider`.
4. **Check the units.** If the subject name's unit is not what Android reports, add the conversion to
   `sensors/Units.kt` with a test against a known constant. This is the most common silent bug here.
5. In `publish/SensorPublisher.kt` add a `runX()` collector — build the payload, `enclose()`, publish
   through a `SubjectSink` — and `launch` it in `start()`. The publisher is already declared from the
   registry. Stamp the *observation* time (`SensorClock.epochNanosNow`), never `Instant.now()`. If a
   field can be absent, skip it rather than publishing a proto3 zero.
6. If a new Android permission is needed, add it to `AndroidManifest.xml` *and* wire the runtime
   request — copy the location pattern (request in `MainScreen`, defensive re-check in the
   `SensorPublisher` collector). A `uses-feature` should be `required="false"`.
7. `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin` and report the resulting key expression,
   e.g. `rise/@v0/{entity}/pubsub/{subject}/{source}`.
