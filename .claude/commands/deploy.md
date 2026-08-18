---
description: Build, install on the connected device, launch, and tail the publisher log
allowed-tools: Bash(./gradlew:*), Bash($ANDROID_HOME/platform-tools/adb:*), Read
---

Get the current code running on a physical device.

1. `$ANDROID_HOME/platform-tools/adb devices` — stop and tell the user if no device is attached.
   Emulators cannot produce useful GNSS or IMU data; if only an emulator is listed, say so and ask
   before continuing.
2. `./gradlew :app:assembleDebug`
3. `$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`
   — the APK is ~119 MB, so this takes a while. That size is the bundled Zenoh native libraries,
   not a packaging fault.
4. `$ANDROID_HOME/platform-tools/adb shell am start -n se.rise.logline/.MainActivity`
5. Tail the log briefly with a timeout so it cannot hang:
   `$ANDROID_HOME/platform-tools/adb logcat -d -s SensorPublisher:V AndroidRuntime:E`
6. Report what happened. If the session failed to open, the likely cause is the router endpoint:
   `tcp/127.0.0.1:7447` resolves to the *phone's* loopback, so a router on the dev machine needs the
   LAN address or `adb reverse tcp:7447 tcp:7447`.

`$ARGUMENTS` may name a specific device serial — pass it through as `adb -s <serial>`.
