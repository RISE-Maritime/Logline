---
description: Re-copy the vendored protobuf files from the keelson repo and report any drift
allowed-tools: Bash(diff:*), Bash(cp:*), Bash(ls:*), Bash(./gradlew:*), Read
---

The files in `app/src/main/proto/` are verbatim copies from `~/Documents/CODE/keelson`. Resync them.

Mapping:

| Local | Upstream |
| --- | --- |
| `app/src/main/proto/Envelope.proto` | `keelson/messages/Envelope.proto` |
| `app/src/main/proto/Primitives.proto` | `keelson/messages/payloads/Primitives.proto` |
| `app/src/main/proto/Decomposed3DVector.proto` | `keelson/messages/payloads/Decomposed3DVector.proto` |
| `app/src/main/proto/foxglove/*.proto` | `keelson/messages/payloads/foxglove/*.proto` |
| `app/src/main/proto/Audio.proto` | `keelson/messages/payloads/Audio.proto` |
| `app/src/main/proto/Checklist{Event,State,Presence,Procedure}.proto` | `keelson/messages/payloads/Checklist*.proto` |

Steps:

1. `diff` each pair and report exactly which files differ, with the substantive changes — field
   additions, renumbering, type changes. Field-number changes are wire-breaking; call those out
   loudly.
2. Copy the upstream versions over the local ones. Direction is always upstream → here. If local
   files contain edits that are *not* upstream, stop and report them instead of overwriting — that
   means the protocol was forked here by mistake and the change belongs in the `keelson` repo.
3. `./gradlew :app:compileDebugKotlin` to confirm the generated bindings still satisfy the Kotlin
   code. Renamed or renumbered fields show up as compile errors in `SensorPublisher` or `Envelopes`.
4. Summarise: files changed, wire-compatibility impact, whether the app still compiles.
