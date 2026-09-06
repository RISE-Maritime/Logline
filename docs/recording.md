# Recording, and running unattended

The MCAP file the phone writes beside — or instead of — what it publishes, and what keeps a run
alive with the screen off.

- [Local recording (MCAP)](#local-recording-mcap)
- [Background logging](#background-logging)

---

## Local recording (MCAP)

Every published sample is also written to an **MCAP** file, on by default and switchable under
Settings. Finished files land in **Downloads/Logline** on the phone, so they can be copied off over
USB, Drive or anything else — no adb, and no storage permission (an app always owns the media it
creates, and `WRITE_EXTERNAL_STORAGE` is a no-op at minSdk 30).

**The recording is the complete log; the bus is best-effort.** A Zenoh `put` succeeds even when the
router is gone — measured here, ~9000 successful puts landed on an empty bus during a 26 s outage — so
recording is deliberately *not* gated on publish success. The bytes go to disk when they are built.

### What the files contain

The format matches what keelson's own `keelson2mcap` recorder produces, so its `mcap2keelson` replayer
and Foxglove both read them:

| | |
| --- | --- |
| Channel topic | the full Zenoh key, e.g. `rise/@v0/pixel_6/pubsub/location_fix/phone` |
| Message data | the **unwrapped payload** — not the envelope |
| `publish_time` | the envelope's `enclosed_at` |
| `log_time` | when the recorder wrote it |
| Schema | the payload's protobuf type, with a `FileDescriptorSet` embedded |

The unwrapped-payload part is not a detail: the replayer re-wraps with
`keelson.enclose(payload=message.data, enclosed_at=message.publish_time)`, so a file containing whole
envelopes would replay as doubly-wrapped messages that decode to nothing.

Messages are buffered into **zstd-compressed chunks**, as the Python recorder does; schemas, channels
and the summary stay outside them, so a reader gets the statistics without decompressing anything. Two
differences from the Python recorder remain, neither affecting readability: no chunk *index* is written,
so a reader scans rather than seeks, and schemas are deduplicated **per protobuf type** rather than per
subject, so 28 subjects produce 8 schema records rather than 28.

### Replaying one

The channels carry *this phone's* entity and source ids, so `mcap2keelson` refuses to load a recording
under the same identity — it would republish onto the keys it is reading. Use a different
`--entity-id`/`--source-id`, or pass `--replay-key-tag` to append `/replay` to every topic.

### Size, rotation and interruption

Roughly **241 MB per hour** at the defaults, rolling to a new file at 512 MB. Two changes moved that
figure in opposite directions and it is worth knowing both: recording now defaults to each sensor's
*maximum* rate, which took an uncompressed run to about 720 MB/h, and zstd then won roughly three
quarters of that back. Measured on a Pixel 6: 153 s wrote 10.2 MB holding 14.2 MB of payload — stored
smaller than the data it contains, where before compression the same payload cost 2.2× its own size in
framing. Recording stops rather than filling the disk if free space drops below 256 MB.

Files are written to app-private storage first and moved to Downloads when closed, so a crash cannot
lose one to a half-finished MediaStore entry. **A recording interrupted by a kill is repaired the next time the app is opened**: the app trims it to the last complete record and appends a footer, because a file
without one has all its messages present and none of them reachable — readers seek to the footer
first. The repaired file has no statistics, so readers scan it; the messages are intact.

**If the phone's battery goes flat mid-run, the recording is not lost.** Charge the phone and open
the app: the interrupted recording is repaired and moved to Downloads on launch, and appears in Files
labelled "incomplete, never closed". It opens and replays normally; only its summary figures are
missing, so a reader scans it rather than seeking. At most a few seconds at the end are gone — what
was still in memory when the power went — and on a real one measured here nothing was: the last
chunk had already been written, and the repair added 50 bytes without removing any.

Nothing stops a run because the battery is low. The Session screen warns under half an hour and the
notification carries the same figure, but neither ends the recording the way running out of disk
does, so a run left going will be ended by the phone rather than by the app.

Compression costs something here, and it is bounded deliberately. A killed process loses whatever is
still buffered in the open chunk, where before it lost only a partial message — so chunks are flushed
at 256 kB **or after two seconds, whichever comes first**, and each one is then synced to disk rather
than left to the operating system's own schedule, which costs 1.65 ms about every 1.4 seconds and
makes a pulled battery no worse than a crash. The time bound is the important half: it
makes the worst case a property of the clock rather than of how fast the sensors happen to be running.
Verified by killing the app 25 s into a run: **87 193 messages covering 24.7 s came back**, so under a
second was lost with the in-flight chunk.

If the queue to the writer ever overflows, the main screen shows a **DROPPED** count. It is never
hidden — a recording with an unreported hole is worse than one that admits to it.

**The summary stays on screen after Stop.** `Recording saved · 41 203 messages · 38.4 MB · 1 file in
Downloads/Logline`, with the file name, the folder and `Ran for 01:23:45` behind the tap. It used to
vanish the instant a run ended, which left "did it actually save?" to be answered with a file manager.

Two things about that line are worth knowing. **`Saved` counts only copies that reached Downloads** —
a failed copy leaves the file in app storage, recoverable with `adb`, and is reported as a problem
rather than counted as a save. And the message count and size are **for the last file, not the run**:
they restart at each 512 MB rotation, which is what the file count is there to complete.

### Getting recordings off the phone

The **Files** tab. It lists everything the app has put
in `Downloads/Logline` — recordings and the platform calibration's platform-geometry export — newest first,
with size, message count and duration, and offers a share sheet and a delete.

The count and duration come out of each file's own MCAP `Statistics` record, read through the footer:
two seeks and about forty bytes, so a 74 MB recording costs what a small one does and nothing is
scanned. A file that says **`no summary`** is not broken — it is a recording rescued from a killed
process, where `McapRecovery` rebuilt the footer with no statistics section. Every message is there;
the file simply does not carry a count any more.

The file being written right now is deliberately not in the list: it stays in app-private storage until
it is closed, and the status card already reports it live.

> Android ties a `Downloads` entry to the app that wrote it, so if this list is ever empty when you know
> there are files, look in `Downloads/Logline` with a file manager before concluding anything is lost.

## Background logging

Publishing is owned by `PublisherService`, a foreground service — not by the UI. Once started it keeps
running with the app backgrounded, the screen off, and the task swiped out of recents, and it holds a
partial wake lock so the non-wakeup IMU sensors keep delivering while the CPU would otherwise suspend.
An ongoing notification shows `entity → endpoint` — or a count, when several endpoints are configured,
since which one is live cannot be told — plus a running sample count, and carries a **Stop**
action. Stopping from the notification and stopping from the app are the same path.

### How much longer it can run

The status card and the notification carry an estimate of how much logging is left in the run —
`about 5 h 20 min of logging left on this battery`, and a warning under half an hour.

It is measured from the fuel gauge's own **drain while logging**, over a trailing twenty-minute window.
Not from `CURRENT_NOW`: instantaneous draw on a phone swings by an order of magnitude between screen-on
and screen-off, so a number derived from it jumps around and means nothing. Not over the whole run
either, because the thing being measured changes — switching the camera on roughly doubles the drain,
and an average that included the hour before that would keep promising time the phone no longer has.
The window is what makes the number follow the run.

Where the device reports `BATTERY_PROPERTY_CHARGE_COUNTER` the estimate uses it: on a Pixel 6 it moves
in 1 mAh steps against the 1% steps of the level, which is about 46 mAh on the same phone — so a trend
is measurable in minutes rather than the best part of an hour. Devices that do not report it fall back
to percent, and the arithmetic is identical either way.

It says nothing until it has actually measured a drain, says `on external power` while charging rather
than pretending, and refuses to report anything beyond four days — a gauge that has barely moved is a
stalled gauge, not four days of logging.

**Free storage is measured the same way and the readout shows whichever runs out first**, saying which:
`about 40 min of logging left before storage fills`, and `Storage running out` under half an hour. While
recording, the free space on the volume is sampled every thirty seconds and put through the same
trailing-window fit — so it is the *measured* fill rate, whether this app filled the disk or another one
did, rather than an assumed bytes-per-hour. The tank is the space above the 256 MB floor at which the
recorder refuses to open the next file, because that is the moment the recording actually stops. A phone
on a charger still fills its disk, so the storage estimate carries on while the battery half says
`on external power`. Tap the status card for a `Space` line showing the disk figure on its own, which is
what you want on a run where the battery is the limit that binds.

The service runs in one of two modes, chosen at start from the permission state:

| Location permission | Service type | Subjects | Runtime cap |
| --- | --- | --- | --- |
| Granted | `location` | all of them | none |
| Denied | `dataSync` | everything except the three GNSS subjects | ~6 h per 24 h, then Android stops it |

Denying location is therefore degraded, not fatal — you still get IMU. The mode is fixed for the run:
granting the permission afterwards needs a Stop and Start before GNSS appears. Denying the notification
permission does not stop publishing; it only makes the notification invisible.

**When there is no fix, the rows say why.** Four subjects come off the one GNSS callback, and a silent
row is the same shape whether the phone is waiting for satellites, the permission was refused, or
location is switched off at the system level — so the two causes a person can act on are named on all
four rows instead of left to look like a slow first fix:

| What the rows say | What happened |
| --- | --- |
| `Waiting for the first sample` | Normal. Nothing is wrong yet — a cold fix indoors takes a while. |
| `Failed — Location is switched off in Android settings` | The master switch. Caught at the start of a run *and* the moment it is toggled mid-run, and cleared as soon as it is switched back on rather than after the next fix. |
| `Failed — Location permission was not granted for this run` | An IMU-only run. Stop, grant, Start. |
| `Failed — The location provider refused the request: …` | Play Services rejected it, which is how a device without them presents. |

A phone merely indoors is deliberately *not* one of these. The fused provider reports itself unavailable
under a roof and then recovers, and calling that a failure is the kind of false alarm that teaches
people to stop reading the row.

`ACCESS_BACKGROUND_LOCATION` is deliberately not requested — the service is always started from a
visible Activity, which is the exemption that makes it unnecessary.

**The battery-optimisation exemption is asked for once, at the first Start.** A foreground service and
a partial wake lock are enough on a Pixel and are not enough everywhere: several manufacturers' battery
managers stop an app that has been in the background for hours, which is the shape of every logging
run. The prompt comes at the first Start rather than at first launch, because nothing is running when
the app opens and a question about background execution has no context to be understood in there. It is
asked once whatever the answer — a prompt on every Start is how people learn to dismiss prompts — and
**Settings → Background running** shows the current state and offers the dialog again, which is the way
back for anyone who dismissed it. The state is read from `PowerManager` on every resume, because
nothing announces a change to it.

> The `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission behind that dialog is restricted by Play policy
> to a short list of eligible app types. This is an in-house tool that is never published to a store;
> anyone considering publishing it should expect to drop the direct dialog and send users to the system
> list instead.

### Starting again after a reboot

Off by default; **Settings → Background running → Start on boot**. `START_STICKY` already brings a run
back when the process is killed, and nothing brought it back after a restart — which for a phone wired
into a platform is the difference between an unattended install and one somebody has to go and visit.

A boot start is narrower than one you press Start for, and both limits are the platform's:

- **It needs the location permission.** Without it the run would be a `dataSync` service, and Android
  15+ refuses that type from a `BOOT_COMPLETED` broadcast. The receiver checks and declines rather than
  letting `startForeground` throw; `adb logcat -s BootReceiver:V` says which happened.
- **It never brings audio or the camera**, whatever the settings say — `microphone` and `camera` are
  refused from that broadcast for the same reason. They are dropped from the settings the run is given,
  so the service's type mask and the collectors agree; switch them back on by hand when you next want
  them. Given both record people, a run that starts itself is arguably the last place they belong.

A run stopped by hand stays stopped: this is about surviving a restart, not about refusing to be
switched off.

---

[← Back to the README](../README.md)
