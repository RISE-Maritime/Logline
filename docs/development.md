# Working on the code

What to install, what to run, where things live, and what this build knowingly does not do.

- [Requirements](#requirements)
- [Build](#build)
- [Release build](#release-build)
- [Run on a device](#run-on-a-device)
- [Project layout](#project-layout)
- [Protobuf definitions](#protobuf-definitions)
- [Known limitations](#known-limitations)
- [Related repositories](#related-repositories)

---

## Requirements

- **JDK 25** — the Gradle daemon toolchain is pinned to 25 in `gradle/gradle-daemon-jvm.properties`.
  Android Studio's bundled JBR satisfies this; there is no system `java` on the PATH of the
  development machine.
- **Android SDK** with API 37 platform, path in `local.properties` (`sdk.dir`, git-ignored).
- **A physical device**, API 30+. GNSS and IMU are not usefully emulated.
- **A reachable Zenoh router**, e.g. from
  [keelson-router](https://github.com/RISE-Maritime/keelson-router). The app connects in `client`
  mode with multicast scouting disabled, so it will only ever talk to an endpoint you configured —
  scanning for routers offers what it finds, it never joins anything by itself.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew :app:assembleDebug      # debug APK
./gradlew :app:testDebugUnitTest  # JVM unit tests
./gradlew :app:lintDebug          # Android lint
```

Those three tasks are exactly what CI runs (`.github/workflows/build.yml`), so a green local run and a
green pipeline mean the same thing.

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. It is ~102 MB because the Zenoh
Kotlin binding ships native `.so` libraries for every ABI; that is expected, not a packaging bug.

## Release build

```bash
./gradlew :app:assembleRelease
```

`versionName` comes from `version.properties` at the repo root and is bumped by hand — it is an
editorial claim about what changed, and nothing should derive it.

`versionCode` **is** derived: `versionCodeBase` from that file plus `git rev-list --count HEAD`. That
is not the thing the old rule warned about. "A version that changes as a side effect of building makes
'is this the same build?' unanswerable" is exactly right, and the commit count is the *answer* to it
rather than a violation — the same checkout produces the same number on every machine, and a different
number means a different commit. Hand-bumping is what left it at `1` for 187 commits, so every APK
ever built reported the same version and none of them could upgrade another.

**A shallow clone fails the build rather than falling back.** `git rev-list --count HEAD` does not
error on a truncated history; it succeeds and returns the clone depth. `version.properties` explains
what that would cost.

**Signing is optional and off by default.** With no credentials configured the build succeeds, warns,
and produces `app-release-unsigned.apk` — most developers here never need the key. To produce a signed
APK, create a keystore:

```bash
keytool -genkeypair -v -keystore ~/logline-release.jks -alias logline \
  -keyalg RSA -keysize 2048 -validity 10000
```

and give the build its location, either through `local.properties` (git-ignored, already holds
`sdk.dir`):

```properties
logline.keystore=/Users/you/logline-release.jks
logline.keystore.password=…
logline.key.alias=logline
logline.key.password=…
```

or the equivalent environment variables `LOGLINE_KEYSTORE`, `LOGLINE_KEYSTORE_PASSWORD`,
`LOGLINE_KEY_ALIAS`, `LOGLINE_KEY_PASSWORD`, which take precedence. Keep the keystore and its passwords
out of the repository; losing the key means the app id can never be upgraded in place again.

> A release APK is signed with a different key than the debug build, so it **cannot be installed over
> it** — `adb install` fails with a signature mismatch until you uninstall the debug build first, which
> also wipes its settings.

Minification is deliberately off. This is an in-house tool that is never published to a store, so there
is no size ceiling and no reason to obfuscate — readable stack traces are worth more. Enabling R8 would
also require hand-written keeps for protobuf-lite (which ships none and resolves generated fields
reflectively) and for the Zenoh JNI classes, and both of those fail at runtime rather than at build
time. The APK is mostly native libraries R8 cannot touch in any case.

## Run on a device

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n se.rise.logline/.MainActivity
adb logcat -s SensorPublisher:V
```

Out of the box the endpoint is loopback, `tcp/127.0.0.1:7447`, which connects to nothing until you
either forward a port to the phone (`adb reverse tcp:7447 tcp:7447`) or point it somewhere else. A
shared `tls/` bus needs a network path of its own — Wi-Fi, or cellular on a device with a SIM — plus
the credentials in [Router security](connecting.md#router-security).

Pointing at a local router instead is one entry in the endpoint list — typed in, or picked from
**Scan for routers**. If that router runs on your laptop, use the laptop's LAN address:
`tcp/127.0.0.1:7447` points at the *phone's* loopback, and is only useful alongside
`adb reverse tcp:7447 tcp:7447`.

## Project layout

```
app/src/main/java/se/rise/logline/
  LoglineApp.kt            Application — process-scoped owner of the publisher and settings
  MainActivity.kt          Compose entry point + NavHost (main / live / annotations / calibration / qos / settings)
  config/                  Settings data class + DataStore repository
  keelson/                 Key expression builder, Envelope helper, Zenoh session wrapper
  sensors/                 ImuProvider and LocationProvider — callbackFlow over Android APIs
  publish/SensorPublisher.kt  Owns the session, the publishers, and the per-subject status
  publish/PublisherService.kt Foreground service — notification, wake lock, run lifetime
  checklist/               Shared checklists — own session, reducer, reminders (see Checklists)
  calibrate/               Platform geometry — model, geodesy, quaternions, platform-geometry JSON
  ui/                      MainScreen, LiveScreen, AnnotationScreen, SettingsScreen, theme
app/src/main/proto/        Vendored copies of Keelson protobuf definitions
art/                       Source artwork + the launcher-icon generator
```

`art/logline_icon.svg` is the source of the launcher icon. Android cannot use SVG, so
`art/svg_to_adaptive_icon.py` converts it into the two adaptive-icon VectorDrawables
(`ic_launcher_background.xml`, `ic_launcher_foreground.xml`) — edit the SVG and re-run the script
rather than hand-editing the XML. `art/logline_icon.png` is the same artwork rendered for this
file, since markdown cannot size an SVG reliably; regenerate it alongside:

```bash
python3 -c "import cairosvg; cairosvg.svg2png(url='art/logline_icon.svg', \
  write_to='art/logline_icon.png', output_width=512, output_height=512)"
```

`docs/screen-shots/` holds the screens in the README. They are the app as shipped, not mock-ups, so a
screen that changes shape wants its picture retaken rather than its caption reworded.

See [docs/architecture.md](architecture.md) for how these fit together, and
[CLAUDE.md](../CLAUDE.md) for the working conventions.

## Protobuf definitions

`app/src/main/proto/` holds **verbatim copies** of files from the Keelson repo — they are vendored,
not authored here. Java/Kotlin lite bindings are generated at build time by the
`com.google.protobuf` Gradle plugin.

| Local path | Upstream source |
| --- | --- |
| `Envelope.proto` | `keelson/messages/Envelope.proto` |
| `Primitives.proto`, `Decomposed3DVector.proto` | `keelson/messages/payloads/` |
| `Checklist{Event,State,Presence,Procedure,Evidence}.proto` | `keelson/messages/payloads/` |
| `Audio.proto`, `LocationFixQuality.proto`, `WHEPProxy.proto` | `keelson/messages/payloads/` |
| `ErrorResponse.proto` | `keelson/interfaces/` — the only one not from `payloads/` |
| `foxglove/*.proto` | `keelson/messages/payloads/foxglove/` |

To pull in upstream changes, copy the files across and rebuild — never hand-edit them here.

## Known limitations

[TODO.md](../TODO.md) tracks these alongside the rest of the pending work, prioritised. They are
deliberate omissions in the current state, not hidden bugs:

- **No RPC interface liveliness.** The app answers crowsnest's `get_config` probe but does not declare
  an interface-level token, because §3.6's full-interface rule would commit it to serving all of
  `configurable/v1`.
- **No instrumented tests.** 818 JVM tests cover the wire format, the registry, the units and the
  formatting; `app/src/androidTest` is empty, so nothing covers a screen.
- **`entity_health` is not published**, deliberately — upstream forbids a connector computing its own.
  The subject-level liveliness above is what lets an aggregator compute it instead.
- **Emulators are not usable.** GNSS, IMU and the camera all need a physical device.
- **No Zenoh subscription works on Android**, so **shared checklists** and **reading other platforms'
  geometry** are gated off behind `ZenohBinding.SUBSCRIPTIONS_SAFE`. The binding builds callback
  arguments with `FindClass` on one of Zenoh's own threads, where JNI cannot see app classes, and a
  router timestamps every sample it forwards — so every subscribed sample trips it, on every realm.
  Filed as [eclipse-zenoh/zenoh-flat-jni#49](https://github.com/eclipse-zenoh/zenoh-flat-jni/issues/49);
  one boolean restores both features when it lands. Publishing, queryables and liveliness declaration
  are unaffected, so nothing the phone *says* or records is limited by it.

## Related repositories

Sibling checkouts under `~/Documents/CODE/`, referenced throughout the docs:

- `keelson/` — protocol spec, `messages/subjects.yaml`, Python/JS SDKs, connectors
- `keelson-router/` — Zenoh router deployment (docker compose, TLS certs)

---

[← Back to the README](../README.md)
