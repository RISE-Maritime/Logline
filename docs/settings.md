# Settings, and setting up a phone

What is configurable, what a settings profile does and deliberately does not carry, and the two
features that are configured rather than merely switched on.

- [Configure](#configure)
- [Setting up a second phone](#setting-up-a-second-phone)
- [Checklists](#checklists)
- [Platform calibration](#platform-calibration)

---

## Configure

The app has five tabs across the bottom — **Session**, **Live**, **Events**, **Files** and **Setup**.
Session is the dashboard and the start/stop control, Live is the map and the plots, Events is where a
moment is marked, Files is what has been recorded, and Setup holds everything that is configured
rather than operated: Settings, Platforms, the annotation buttons and (when enabled) Checklists.

**Settings → Recording → Recording folder** chooses where finished recordings are written. The default
is `Downloads/Logline`; any folder the system picker offers works, including a card or a synced one,
and the exports follow into a `config` subfolder inside it. It is the same folder the Files tab lists
from, and the same control appears there as **Change** — one destination, so the app cannot fill one
place while listing another.

Setup's last section is **About**, and its row states the installed build — `1.0 (1) · debug build` —
so a phone can be asked which APK it is holding without anyone going back to the machine that built
it. The screen behind it adds the install and update dates and the application id. The version is
bumped by hand in `version.properties`; nothing derives it, which is exactly why the phone has to be
able to say it.

Start and Stop sit pinned above the tab bar rather than in the scroll, because the page below them is
thirty-nine subjects long. The card at the top is the session summary — what the last run left behind
while nothing is running, what this one is doing while it is — with the endpoint and the file's own
figures one tap behind the row along its bottom edge.

Settings itself is five collapsible groups — General, Connection, Recording, Live camera,
Collaboration — with only General open to start with. Finding a router by multicast sits inside
**Connection**, between the endpoint list and the credentials, because a scan result's *Add* button
appends to that very list — the producer next to what it produces. The longer explanations sit behind the
ⓘ on a section heading rather than in the page.

Settings screen fields, all persisted to DataStore (`logline_settings`):

| Field | Default | Notes |
| --- | --- | --- |
| Realm | `rise` | First segment of every key |
| Entity ID | slugified `Build.MODEL` | Which physical thing is reporting |
| Router endpoints | `tls/router.example.com:443` | A list of Zenoh locators, `proto/host:port`, tried in order. A `tls/` or `quic/` scheme turns on TLS and needs the credentials above; `tcp/` does not. The last entry cannot be removed. |
| Scan multicast address | `224.0.0.224:7446` | Where **Scan for routers** looks. Only ever used for scanning, never for the session. |
| Location source ID | `phone` | Also used as the `frame_id` on `LocationFix` |
| IMU source ID | `phone` | Also used as the `frame_id` on the IMU messages |

Saving while publishing is running restarts the session with the new settings.

### Per-sensor settings

Each subject on the main screen carries a **gear icon** opening a screen for that sensor alone.

**Sampling rate** has a **Maximum** switch and, below it, a free numeric field in Hz with decimals
allowed — `0.2 Hz` is one GNSS fix every five seconds, which is the point on a long run.

Maximum asks for a zero delay (`SENSOR_DELAY_FASTEST`, and interval 0 for location) rather than filling
in a number, so the hardware is the only limit. That distinction matters: on this Pixel 6 the
gyroscope advertises 415.97 Hz, and Maximum resolves to its 2404 µs minimum delay yet delivers about
442 Hz — a hardcoded "max" number would have undershot. The screen states what the hardware can actually do, read
from the sensor itself (`Sensor.getMinDelay()`), e.g. *"This sensor supports up to 416 Hz (min delay
2404 µs)"*, along with the sensor's name and vendor and the rate currently being achieved. Asking for
more than the sensor supports is allowed and warned about rather than blocked — Android simply
delivers slower. Above 200 Hz there is a second warning: the IMU subjects at that rate is well over a thousand
messages a second, where the publish path and battery bite before the sensor does. The app declares
`HIGH_SAMPLING_RATE_SENSORS`, without which Android caps motion sensors at 200 Hz.

**Quality of service** follows on the same screen, where priority, congestion control, reliability and
express are each chosen individually — any combination, not just the named profiles from `qos.yaml`.

Every field opens pre-filled with what the subject currently publishes with, so changing one setting
does not mean reconstructing the other three. The screen says whether the subject is following
`qos.yaml` or has been overridden, spells out the upstream policy, and offers **Reset to qos.yaml
policy**; if a hand-picked combination happens to match a named profile, it says so.

Two things worth knowing before reaching for it. An override is a local divergence from a policy whose
whole point is that a subject behaves the same wherever it is published, so prefer the default. And
`BLOCK` congestion control applies back-pressure — if the egress queue fills, publishing waits rather
than shedding samples, which on a 55 Hz sensor path can stall the publisher. Every profile in
`qos.yaml` uses `DROP` for that reason; the screen warns when you pick `BLOCK`.

## Setting up a second phone

**Settings → Configuration.** *Export…* writes a JSON profile to `Downloads/Logline`; *Import…* reads
one back after showing what it will overwrite. *Show QR* and *Scan QR* carry the connection half
without a file at all, which is the part that is the same across a fleet and tedious to type.

**A profile configures a phone; it does not clone one.** Five fields never travel, and each breaks
something different if it does:

| Stays behind | Because |
| --- | --- |
| `entity_id` | Names *this hardware*. Two phones sharing one publish on byte-identical keys and their samples interleave with nothing to tell them apart. |
| `operator_id` | De-duplicates this phone's own presence heartbeat coming back on the wildcard subscription. |
| `platform_registry_origin` | The same job for the platform library: without a distinct origin a phone applies its own library back over itself on every reconnect. |
| `platform_registry_version` | Sync bookkeeping — an imported version would claim a place in the last-writer-wins ordering it has not earned. |
| `battery_exemption_asked` | A record that *this* device was asked; a new phone should still be asked. |

The operator's **name, role and site** do travel, with a tick on the import screen to leave them
behind — right for your own second phone, wrong for provisioning five.

The QR carries the realm, router endpoints, source ids and scout address only. Switched-off subjects,
rates, QoS overrides and annotation buttons need the file: a QR holds a few hundred bytes, and
squeezing more in produces a code that will not scan rather than one that carries less.

TLS credentials are in neither. They are files, imported per device, and the whole point of keeping
them out of Android's backups is that they should not travel casually.

## Checklists

Optional, off by default. Turn it on under **Checklists** in Settings and give the phone a name and a
site; a **Checklists** button then appears on the main screen, whether or not a run is going.

A checklist here is not a private to-do list. It is the *same* checklist the ROC stations are working,
shared over Keelson: tick an item on the phone and it appears in crowsnest's timeline with your name
and site against it, and an item ticked at a ROC turns green here. Notes, flags and flag resolutions
travel the same way, and a presence heartbeat shows who else is on the procedure.

It works offline. Everything applies locally first, progress is kept on the phone, and events that
could not be sent are replayed when the router comes back — the screen says "Working offline" rather
than leaving a tap ambiguous.

**Where the procedures come from.** The item text lives on the bus, under `checklist_procedure`, held
by the router's storage plugin — the phone reads the whole library with one query when it joins. If
nothing has ever been published there the screen says so and offers to publish a starter library
(crowsnest's own procedures, with the same item ids, which is what makes the two sides agree).

The router needs two storages for this; see
[`keelson-router/docker-compose.keelson-router-rise.yml`](../../keelson-router/docker-compose.keelson-router-rise.yml):

```
--cfg='plugins/storage_manager/storages/checklist_procedure/key_expr:"crowsnest/@v0/*/pubsub/checklist_procedure/*"'
--cfg='plugins/storage_manager/storages/checklist_snapshot/key_expr:"crowsnest/@v0/*/pubsub/checklist_state/*"'
```

**Reminders.** Any item can carry a reminder — "remind me in 20 minutes", optionally repeating. These
are **local to the phone and never published**: no checklist message carries a due time, so what the
other sites see is the completion when you tick it. They are inexact alarms (Android may slip them by a
few minutes rather than waking the device precisely), they survive a reboot, and completing the item
cancels the one attached to it.

Checklist traffic uses its own Zenoh session, open only while a checklist screen is, and its own realm
and entity (`crowsnest/@v0/checklist/...`) — not the ones this phone publishes sensor data under.
Checklist activity is **not** written to the MCAP recording.

## Platform calibration

Optional, and nothing publishes until a platform is described. **Setup → Platforms** (while stopped) records
where a sensor platform's zero point is and where each sensor sits relative to it: X forward, Y to
starboard, **Z down**, metres, with rotations in degrees applied yaw → pitch → roll.

Describing one platform is a five-step flow — **Platform**, **Zero**, **Forward**, **Sensors**, **Review** —
with the steps shown as a row of chips at the top. They are navigation rather than a sequence: any
step is reachable at any time, which is what makes correcting an existing platform as quick as it should
be, and **Save** stays available throughout, so a survey interrupted halfway is not lost. A step
carrying a ✓ has something in it. The wire details — the key the transforms go out on,
`frame_transform`, `configuration_json` — live on the Review step and behind the ⓘ, not above the
name field.

The phone holds a **library** of platforms, not one. A platform is keelson's own word for the thing
being measured — `entity_id` is the platform name — so the list is the counterpart to crowsnest's
own-ship selector: one platform is **active**
(the platform the phone is on), and any number of others can be switched on beside it, because a campaign
often wants every platform in the water logged and not only the one the phone is bolted to. Each publishing
platform gets its own publishers, its own keys and its own liveliness token, so changing the selection
restarts a run — unlike the per-subject switches, which do not.

Each platform can carry a **photograph**, picked from the phone's gallery on the first step of its
page and shown beside it in the library. It is there because a list of entity ids is hard to read —
`sealog-1` and `sealog-2` are one character apart, and a picture of the boat is not. The photo stays on
this phone: it is not part of the geometry document, so it is neither published nor written into an
export or a settings profile, and it does travel in an Android backup, being the one thing here that
nobody can rebuild without walking back down to the quay.

Offsets are either **typed** — a tape measure, and for a small platform the only honest option — or
**captured**, by standing the phone at the sensor and averaging twenty seconds of fixes. The screen
shows the fix accuracy behind every captured number and flags in red any offset smaller than the
accuracy that produced it, because that offset is GNSS noise rather than geometry.

**Rotations can be measured too** — lay the phone flat against the sensor's mounting face, screen up,
top edge the way it faces, and it reads its own attitude. Pitch and roll come from gravity and are as
good as anything aboard, provided the platform is level when you measure: heel and trim go straight
into the number and nothing can detect that afterwards. **Yaw is the one to distrust.** It comes from
the magnetometer, which is exactly what a radar, a steel mast or a motor pulls out of true, so the
screen shows the compass's own accuracy beside it and says so in red when that figure is too loose to
steer by. Measured indoors on a desk a Pixel 6 reported ±90°, which is the honest answer. Yaw is
relative to the platform's bow, so it needs the forward axis established first, and it needs the zero
point's position to correct magnetic north to true — without one the screen says the yaw is magnetic
rather than quietly leaving it wrong by the local declination, about 6° in western Sweden.

The result goes out under the **platform's** entity id, not the phone's — `entity_id` names the thing the
data is about — and exports as a file keelson's own `connectors/platform` reads unchanged:

```
rise/@v0/sealog/pubsub/frame_transform/calibration
rise/@v0/sealog/pubsub/configuration_json/calibration
rise/@v0/sealog/pubsub/location_fix/calibration
```

The phone's own sensors are unaffected by which platform is selected: a battery reading is about the phone
whichever platform it is bolted to, so everything it measures stays under the phone's entity id.

Four things line the library up with crowsnest's platform list, each its own control on the screen:
**Export all** writes the whole library in crowsnest's registry shape and **Import** reads it back (or
a single platform-geometry file, or an older `keelson-platforms` `config.json`); **Scan the bus** finds
platforms already publishing and offers them for adoption; the phone **answers `get_config`** for every
platform it holds while a platform screen is open; and an opt-in **shared library** publishes the whole list on a
deliberately non-keelson key, last-writer-wins, where other stations can read it. What each of those
does and does not carry — and why crowsnest's `get_config` key shape needs the phone to serve two — is
in [docs/calibration.md](calibration.md).

The third one is the platform's **zero point**, which anchors the transforms to the earth. It is stamped
with the time it was surveyed rather than the time it was published, sits on a different key from the
phone's own fix, and is called "Zero point" on screen — three separate reasons it cannot be read as
where the platform is *now*. It publishes only once a position has been captured or typed; a platform measured
entirely with a tape publishes its geometry and no position.

Full procedure, the frame conventions, a worked example and an honest account of what a phone fix can
and cannot measure: **[docs/calibration.md](calibration.md)**.

---

[← Back to the README](../README.md)
