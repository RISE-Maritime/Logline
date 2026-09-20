# Changelog

What changed between builds, so "which one is on that phone?" has an answer that `versionName`
alone cannot give. `versionName` comes from `version.properties` and is bumped by hand; `versionCode`
is derived from the commit count.

**The heading format is a contract, not a style preference.** Each release gets one
`## <versionName> — <YYYY-MM-DD>` heading: the version exactly as `version.properties` spells it, no
`v` prefix, an em dash, the date. `.github/workflows/release.yml` takes everything between that
heading and the next `## ` and makes it the GitHub Release body, matching by literal prefix — so a
`v` on the front, an en dash, or a version that does not match `version.properties` all mean the
workflow finds nothing, and it fails the release rather than publishing one with no notes.

## 1.1 — 2026-09-20

The Monitor tab, a Minimum logging mode, and keelson `0.6.0-pre.18`.

### Monitor

A new tab that watches **another** entity on the bus and draws what it publishes as cards you
choose — value, plot, heading, chart and the rest — the Foxglove panels on a phone. Cards resolve
their inputs by subject, so one board follows whichever source is publishing.

It reads the router's REST plugin as Server-Sent Events rather than subscribing, because a
subscribed sample aborts this Zenoh binding; [docs/monitor.md](docs/monitor.md) explains the
mechanism. The Zenoh subscriber is written and ships dormant behind
`ZenohBinding.SUBSCRIPTIONS_SAFE`: it has been proven on a phone against a patched binding, and a
fix is proposed upstream (milyin/prebindgen#758, eclipse-zenoh/zenoh-flat-jni#49). Watching another
boat never restarts a run.

### Minimum logging

A mode for a phone carried only to mark events: eight channels instead of thirty-nine, position
capped at 0.2 Hz, IMU and media off. Measured over two hours on a Pixel 6 — **0.361 MB/h against
240.2 MB/h**, and about 25 hours of battery against 11. It narrows the tuned profile rather than
editing it, so Full comes back untouched.

### Subjects and protocol

Re-synced to keelson `0.6.0-pre.18`: all nineteen vendored protobuf files byte-identical, QoS
profiles re-verified. Six host-telemetry subjects adopted — free and used disk, memory, swap, host
name and boot time — with the disk ones carrying their mountpoint in the source id.
`cpu_load_pct` and `cpu_temperature_celsius` are unobtainable under an app's own uid and are
documented as such. `set_config` now refuses with `UNSUPPORTED`, which `pre.18` added for exactly
this case.

### Fixes

- A held radio reading's timestamp is converted once per reading, not per poll: ~166 real
  measurements had been publishing as 18 766 distinct instants.
- The MCAP schema descriptor is generated into `build/` instead of `src/`, which had made
  `./gradlew build` refuse to run at all.
- CI stopped installing an SDK package Google withdrew.

### Docs

An install and setup guide, a Monitor page, and `deploying.md` narrowed to one audience.

## 1.0 — 2026-08-25

First release. Internal use; see [docs/install.md](docs/install.md) for getting it onto a
phone and [docs/user-guide.md](docs/user-guide.md) for using it.

### What it does

- **Publishes** a phone's GNSS, IMU, barometer, light, battery and radio readings to a
  Keelson/Zenoh bus — 50 subjects, each with its own record and publish rate, per-subject
  switches, and QoS transcribed from upstream's `qos.yaml`.
- **Records** the same readings to MCAP, zstd-chunked, copied to `Downloads/Logline` when a run
  ends. The file and the wire are independent: either half can be switched off, and a run that
  only records still holds its place on the bus.
- **Live view** — chart with track, heading and course, position and accuracy, the three
  navigation values, radio and battery vitals, and per-group health.
- **Marks** — quick buttons for an instant or a held interval, typed notes with a severity, and
  run-wide tags that travel inside the recording.
- **Files** — search, sort and filter recordings, with each row's own track drawn from the file,
  and a detail view with the full track and figures.
- **Platform survey** — measure a vessel's geometry with the phone, publish it as
  `configuration_json` and `frame_transform`, and serve `configurable/v1`'s `get_config`.
- **Runs in the background** with a foreground service, survives a reboot if asked, and replays
  what a dropped link missed.

### Not available in this build

**Shared checklists** and **reading other platforms' geometry** are gated off. The Zenoh Android
binding aborts the process on any subscription — a `FindClass` on one of Zenoh's own threads
cannot see app classes, and a router timestamps every sample it forwards, so every subscribed
sample trips it. Filed upstream as
[eclipse-zenoh/zenoh-flat-jni#49](https://github.com/eclipse-zenoh/zenoh-flat-jni/issues/49);
`ZenohBinding.SUBSCRIPTIONS_SAFE` is the single boolean that restores both when it lands.

Everything the phone publishes or records is unaffected — publishing, queryables and liveliness
declaration all use different paths.

### Known limitations

- Live camera over WHEP is unfinished, blocked by the same binding fault.
- Two runs of one checklist procedure would share a row, were checklists enabled.
- The Files list shows recordings written by the current install; the folder can be granted to
  see the rest.
