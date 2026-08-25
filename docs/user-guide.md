# Logline — using it

For whoever is holding the phone. Nothing here needs a laptop.

Logline turns a phone into a sensor package: it puts position, motion, pressure and radio
readings onto the vessel's data bus, and writes the same readings to a file on the phone.
Those two halves are independent — either can be switched off.

- [Before you leave the quay](#before-you-leave-the-quay)
- [Starting and stopping a run](#starting-and-stopping-a-run)
- [Reading the Live view](#reading-the-live-view)
- [Marking what happens](#marking-what-happens)
- [Finding the files afterwards](#finding-the-files-afterwards)
- [When something looks wrong](#when-something-looks-wrong)

---

## Before you leave the quay

Five minutes alongside is worth an hour of wondering later.

**1. Open the app and read the card on the Session tab.** It should say **Ready to publish**,
with a line like `50 streams ready` and another like `89.1 GB free · about 164 days of
recording`. If either line is missing or says something else, that is the app telling you
something before you ask.

**2. Check where it will connect.** Tap **Endpoint & recording** along the bottom of that
card. If the endpoint starts with `tls/` the phone needs its three certificates imported
already — *Setup → This phone → Settings → Router security* shows what is loaded. Without
them a run will refuse to start and say which file is missing.

**3. Do a test run.** Press **START**, let it run for half a minute, press **Stop**. Then
open the **Files** tab and confirm the recording is there with a message count. A phone that
records on the quay records at sea; one that does not, does not.

**4. Answer the prompts.** The first Start asks about battery optimisation — say yes, or
Android may stop the run when the screen is off. Location and notification permissions are
asked the same way, once.

**5. Decide what the run is.** On the Session tab, **Tags** describe the whole recording —
`quay trial`, `engine run`. They travel inside the file, so a recording can still say what it
was months later. Set them before you start.

---

## Starting and stopping a run

The start row reads **PUB · REC · ▶ START**, and the two chips are part of the button, not
settings near it.

| Chip | On | Off |
| --- | --- | --- |
| **PUB** | readings go onto the bus, live | the phone stays connected but says nothing |
| **REC** | readings are written to a file | nothing is saved |

Both are on by default. Turn **PUB** off for a trial where nobody is watching; turn **REC**
off if you only want the live picture. With both off, START is disabled — a run that neither
publishes nor records would hold the phone awake to achieve nothing.

**A run keeps going with the screen off**, and with the app in the background. The
notification shows it is alive and can stop it.

**Stopping** asks you to confirm, and states what the run did — something like
`19 358 samples over 00:00:24, 485 kB recorded.` You can attach a closing note in the same
dialog. The file keeps its name.

---

## Reading the Live view

The top bar carries the run's state on every screen: two lamps, **PUB** and **REC**. Only REC
blinks, and only while a file is actually being written.

Down the Live tab:

**The chart.** Your position, the accuracy circle, and — if the marks are switched on — the
track behind you, a heading line and a course vector. The layer button changes the base map;
the expand button gives it the whole screen.

**Position and accuracy**, attached under the chart: `57.4359°N · 12.0328°E` and `±8 m`. If
the fix has gone stale it says so in words above the numbers — `Last known position · 12 s
ago` — because a coordinate with a `±` beside it reads as current whatever its age.

**The three navigation values**: **SOG** (speed over ground, knots), **COG** (course over
ground) and **HDG T** (heading, true). Bearings are zero-padded — `009°`, `090°` — so they
do not jump about as the boat turns.

**The vitals line**: `GNSS · SAT · CELL · BAT`. Only the abnormal is coloured.

**GROUP HEALTH**, a row of chips — GNSS, IMU, Device, Cell, Wi-Fi. Tap one to show only that
group's plots. A healthy chip is plain; one wanting attention gets a coloured dot **and** a
word.

### What the colours mean

The same everywhere in the app:

| | |
| --- | --- |
| **green** | fine — including *recording*, which is a healthy state, not a warning |
| **amber** | worth knowing about |
| **red** | something is wrong |
| **grey** | switched off |

Colour never carries a state on its own; there is always a word beside it.

### Two things that look alike and are not

**`No fix` in the vitals row** means the GNSS receiver is not solving. A position may still be
arriving — Android derives one from wifi and cell — so you can have a perfectly current
position *and* `No fix`. Indoors this is normal.

**A stale position** is the opposite: the receiver solved, and then stopped. That is the
`Last known position` line above the coordinates.

---

## Marking what happens

The **Events** tab is for saying what happened, while it happens.

**Quick marks** are the buttons at the bottom, nearest your thumb.

- **Tap** one to mark an instant.
- **Hold** one to start a timer — a manoeuvre, a leg, an engine run. The button counts while
  it runs; **tap it again to close it.** Your finger is free in between.
- While any timer is running, the Events tab carries a badge with how many. If you forget one,
  stopping the run closes it for you and marks it `(run stopped)`.

**Notes** are for anything no button covers. Type it, pick **Info**, **Warning** or **Error**,
and send. Severity is what lets a reader filter later.

**Edit** on the *Quick marks* header changes the buttons, and the 2/3/4 chips beside it change
how many sit across — bigger buttons are easier to hit without looking.

Marks are recorded whether or not the run is publishing. With PUB off they go to the file only.

---

## Finding the files afterwards

The **Files** tab lists everything the app has saved, newest first.

A row reads `logline-2026-08-24T100724.mcap` and `1 MB · 51 322 messages over 00:01:02`, plus
any tags. The small picture on the left is the run's own track — shape and scale, not place.

- **Search by date, name or tag.** Punctuation is ignored, so `2026-08-24`, `20260824` and
  `0824` all find the same day.
- **Details** opens the recording: its track on a chart, what it contains, and its figures.
- **Share…** copies it off the phone — that is how a recording reaches a laptop.

Finished recordings are copied to **`Downloads/Logline`**, so any file manager or a USB cable
can reach them without the app.

**`incomplete, never closed`** on a row means the app was killed mid-run — the phone ran out
of battery, or Android stopped it. Everything captured up to that moment is still in the file
and still readable; only the closing summary was never written.

**A run in progress is not in the list.** It lives in private storage until it closes.

---

## When something looks wrong

**PUB is grey.** The run is recording only. That is a choice, not a fault — the link may be
perfectly good.

**`No router`, or `Publishing, no router` in the top bar.** The router is unreachable. The run
continues, the file keeps being written, and the app refills the gap from a buffer when the link
returns. Nothing is lost unless the outage is long enough to overflow that buffer — a couple of
minutes' worth. This is not a reason to stop and restart a run.

**A row says `Not on this device`.** The phone has no such sensor. Nothing to fix.

**A row says `Waiting`.** No sample yet. GNSS can take tens of seconds from cold, and longer
under a roof.

**The chart is blank.** Either there is no network for map tiles, or the MapTiler key is not
being accepted — the app says which, under the chart and in the layer menu.

**Nothing is being written.** Check REC is on, and check the free-space line on the Session
card.

### Not available in this build

**Shared checklists** and **reading other platforms' geometry** are switched off. The Zenoh
Android library aborts the app on any subscription
([zenoh-flat-jni#49](https://github.com/eclipse-zenoh/zenoh-flat-jni/issues/49)); until that is
fixed these two features cannot work, and switching them on would crash rather than fail.

Everything the phone **publishes** and **records** is unaffected — the logger, the live view,
marks, tags, files, and surveying a platform of your own all work normally.
