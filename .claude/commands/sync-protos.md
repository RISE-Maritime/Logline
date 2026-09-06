---
description: Re-copy the vendored protobuf files from the keelson repo and report any drift
allowed-tools: Bash(diff:*), Bash(cp:*), Bash(ls:*), Bash(./gradlew:*), Read
---

The files in `app/src/main/proto/` are verbatim copies from `~/Documents/CODE/keelson`. Resync them.

**Take them from a tag, not from the worktree.** That checkout is a working repository and is
routinely parked on a feature branch that predates whatever you are syncing — this has already
happened: a branch with no `Checklist*.proto` at all made five vendored files look invented here.
`git -C ../keelson tag --list '0.6.0*' | sort -V | tail -1` names the newest, and
`git -C ../keelson archive <tag> messages | tar -x -C <scratch>` gets a clean copy to diff against.
Check the lineage too — a newer tag is not automatically a superset, so
`git merge-base --is-ancestor <last-verified> <newest>` before trusting "it is not there any more".

Mapping:

| Local | Upstream |
| --- | --- |
| `app/src/main/proto/Envelope.proto` | `keelson/messages/Envelope.proto` |
| `app/src/main/proto/Primitives.proto` | `keelson/messages/payloads/Primitives.proto` |
| `app/src/main/proto/Decomposed3DVector.proto` | `keelson/messages/payloads/Decomposed3DVector.proto` |
| `app/src/main/proto/foxglove/*.proto` | `keelson/messages/payloads/foxglove/*.proto` |
| `app/src/main/proto/Audio.proto`, `LocationFixQuality.proto`, `WHEPProxy.proto` | `keelson/messages/payloads/` |
| `app/src/main/proto/Checklist{Event,State,Presence,Procedure,Evidence}.proto` | `keelson/messages/payloads/Checklist*.proto` |
| `app/src/main/proto/ErrorResponse.proto` | `keelson/interfaces/ErrorResponse.proto` — **not** `payloads/`, the only one from that directory |

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
