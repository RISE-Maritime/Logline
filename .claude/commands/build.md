---
description: Compile the app and report any errors, with the right JAVA_HOME
allowed-tools: Bash(./gradlew:*), Read, Edit, Grep
---

Build the app and fix or report what breaks.

1. Run `./gradlew :app:compileDebugKotlin` first — it is much faster than a full assemble and
   catches everything except packaging problems. If `$ARGUMENTS` names a different task, run that
   instead.
2. If it fails with `Unable to locate a Java Runtime`, the environment lost `JAVA_HOME`. Re-run as:
   `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew ...`
3. On a compile error, read the offending file and fix it. Remember AGP 9 / compileSdk 37 is
   leading-edge — an unfamiliar DSL error is more likely a version-specific API than broken code, so
   check the AGP 9 syntax before rewriting a build file.
4. Never edit anything under `app/src/main/proto/` to make a build pass — those files are vendored
   verbatim from the `keelson` repo.
5. When it compiles, say so plainly and report the task and its wall time. Only run
   `:app:assembleDebug` if an installable APK was actually asked for — packaging the Zenoh native
   libraries is slow.
