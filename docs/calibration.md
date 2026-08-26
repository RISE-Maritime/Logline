# Platform calibration

How to describe a sensor platform with this phone, what goes on the Keelson bus when you do, and — the part
that matters most — what a phone-measured calibration is actually worth.

A calibration here is two things: **where the platform's zero point is**, and **where each sensor sits
relative to it**. Nothing else. It is what lets a consumer of the platform's radar, lidar and GNSS relate
them to each other and to a common reference point, instead of treating each as if it were mounted at
the centre of the world.

The phone holds a **library** of them. `entity_id` is, in the protocol specification's words, "normally
the platform name", so the library is a platform list, the direct counterpart to crowsnest's own-ship
selector. One platform is **active** (the platform the phone is on) and any
number of others can be switched on alongside it, because a campaign often wants the geometry of every
platform in the water logged rather than only the one the phone is bolted to.

## The contract

Nothing in this is invented locally. Both subjects and the document shape come from keelson, and there
is a reference publisher to match:

| | |
| --- | --- |
| `frame_transform` | `foxglove.FrameTransform` — one message per sensor |
| `configuration_json` | `keelson.TimestampedString` — the whole geometry as one document |
| `location_fix` | `foxglove.LocationFix` — the platform's zero point, where it was surveyed |
| Document shape | [`connectors/platform/config-schema.json`](../../keelson/connectors/platform/config-schema.json) |
| Reference publisher | `connectors/platform/bin/platform-geometry2keelson.py` |

Keys, with the **platform** as the entity — not the phone:

```
{realm}/@v0/{platform_entity_id}/pubsub/frame_transform/{calibration_source}
{realm}/@v0/{platform_entity_id}/pubsub/configuration_json/{calibration_source}
{realm}/@v0/{platform_entity_id}/pubsub/location_fix/{calibration_source}     ← the surveyed zero
```

`entity_id` names the physical thing the data is *about*, and a platform's geometry belongs to the platform even
though a phone measured it. The source id defaults to `calibration`, because this source is a survey
rather than a piece of hardware. Both are editable on the calibration screen.

## The frame

**X forward, Y to starboard, Z DOWN**, metres. Maritime convention, from
`connectors/platform/README.md` — not the ROS one. A sensor three metres up the mast has `z = -3`.

Rotations are degrees, applied **yaw → pitch → roll** (intrinsic Z-Y-X). Yaw is positive swinging to
starboard. On the wire they become a quaternion, and `RotationsTest` pins that conversion against the
same composition `squaternion` performs in the Python connector — a quaternion built in a different
Euler order is a perfectly valid rotation that puts the sensor somewhere else, and nothing downstream
can tell that from a mounting error.

The **zero point** is where every offset is measured from. keelson's CCRP — the Consistent Common
Reference Point — sits at that origin unless `ccrp_m` says otherwise.

## The procedure

1. **Name the platform.** The entity id and the parent frame id follow the name (`Sealog` → `sealog`,
   `sealog-frame-ccrp`) until you edit one of them by hand.
2. **Set the zero point.** Stand at the platform's reference point and *Capture position* — twenty seconds
   of fixes, averaged — or *Type position* from a chart or a survey. A platform you intend to measure
   entirely with a tape needs no zero point at all.
3. **Establish the forward axis.** Three ways, and the calibration records which was used:
   - **Baseline** — capture the zero, walk forward along the centreline, capture again. The geodesic
     bearing between the two is the heading. The longer the baseline the better: over 20 m a metre of
     GNSS error is under three degrees, over 2 m it is thirty.
   - **Compass** — hold the phone **flat, screen up, top edge pointing forward**, and read the fused
     rotation vector for four seconds. The angle is the direction of the phone's +Y axis, which is why
     how you hold it is part of the instruction. Needs a position first, because true north is magnetic
     north plus a declination that depends on where you are; without one the app reports the magnetic
     reading and refuses to pass it off as true.
   - **Type** — from a drawing or a surveyed heading.
4. **Add each sensor.** Name it, pick its type, then either type the offsets or *Capture from where I
   am standing* — stand the phone at the sensor and the offset falls out of the two positions and the
   heading.

   **Rotation can be measured or typed.** *Measure with the phone* lays it flat against the sensor's
   mounting face, screen up, top edge the way the sensor faces, and reads its attitude for four
   seconds. This used to say a rotation was always typed, on the argument that a phone cannot measure
   where a radar is looking — which is **half right, and the half it gets wrong is worth having**:
   pitch and roll come from gravity, and a phone measures those as well as anything on the boat. Only
   yaw is the magnetometer's, and only yaw is ruined by the steel it is usually bolted to. So all
   three are measured, the compass's own accuracy is shown beside them, and a figure too loose to
   steer by is called out in red.

   Two conditions, both stated on the screen rather than assumed. The platform must be **level** when
   you measure, because heel and trim go into pitch and roll and nothing downstream can tell them from
   the sensor's own tilt. And the **forward axis must exist**, since yaw is relative to the bow — plus
   the zero point's position, without which magnetic north cannot be corrected to true and the screen
   says the yaw is magnetic instead of silently being wrong by the declination.
5. **Save.** That restarts publishing so the new geometry goes out. **Export** writes the
   platform-geometry file to `Downloads/Logline`.

## What a phone fix is worth

This is the part to read before trusting any of it.

A fused fix on a phone is metre-class. Averaging twenty seconds of it reduces **scatter** — how far the
samples fall from their own mean — and does almost nothing about **bias**: GNSS multipath sits still for
minutes at a time, so a capture can report 0.3 m of scatter and still be three metres from the truth.
The app shows both numbers side by side for exactly that reason, and they routinely disagree.

The consequence: **an offset smaller than the fix accuracy that produced it is noise**, and the app says
so in the error colour rather than showing six confident decimal places. A platform 1.8 m long has sensor
offsets of a few decimetres; those must be measured with a tape and typed. Capture is honest for
platform-scale geometry — masts, containers, a ship's bridge to its bow — or with an RTK-corrected
receiver, where the numbers are centimetres and this whole caveat goes away.

Two more limits worth stating plainly:

- **The phone's own antenna is not the sensor.** Holding a phone against a radar puts its GNSS antenna
  some tens of centimetres from the thing being measured, and nothing corrects for that.
- **Altitude is the worst axis.** GNSS vertical error is roughly twice the horizontal, and mast heights
  are exactly where that hurts. Type `z` whenever you can.

## What goes on the wire

With one lidar on `Sealog` at 0.22 m forward, 0.35 m up and yawed 90° to starboard:

**`frame_transform`**, one message per sensor, republished every ten seconds:

```
timestamp { seconds: 1787085413 nanos: 965459000 }
parent_frame_id: "sealog-frame-ccrp"
child_frame_id: "sealog-frame-ouster-os-lidar"
translation { x: 0.22 z: -0.35 }
rotation { z: 0.7071067811865476 w: 0.7071067811865476 }
```

Two things there are deliberate:

- **All sensors share one key**, and the sensor is named by `child_frame_id` inside the message rather
  than by the source id. That is upstream's shape. It follows that Zenoh's latest-value store keeps only
  the last transform of each round, which is why the loop repeats rather than publishing once.
- **The timestamp is the publish time, not the survey time** — the one place this app deliberately
  breaks its own observation-time rule. Foxglove builds its transform tree against log time: a transform
  stamped with the day of the survey falls outside the recording's own time range and draws nothing.
  When each number was actually measured is in the document instead.

**`configuration_json`**, the whole geometry as one document, on the same ten-second loop:

```json
{
  "name": "Sealog",
  "ccrp_m": { "x": 0, "y": 0, "z": 0 },
  "frame_transforms": [
    {
      "parent_frame_id": "sealog-frame-ccrp",
      "child_frame_id": "sealog-frame-ouster-os-lidar",
      "sensor_type": "lidar",
      "sensor_description": "Ouster OS lidar",
      "translation_m": { "x": 0.22, "y": 0, "z": -0.35 },
      "rotation_deg": { "yaw": 90, "pitch": 0, "roll": 0 }
    }
  ],
  "calibration": {
    "frame": "x-forward, y-starboard, z-down; rotations yaw-pitch-roll",
    "zero": {
      "latitude": 57.708912345,
      "longitude": 11.97456,
      "altitude_m": 12.5,
      "accuracy_m": 3.4,
      "vertical_accuracy_m": 6.1,
      "scatter_m": 0.42,
      "heading_deg": 35,
      "heading_source": "baseline",
      "capture": "gnss_average",
      "samples": 60,
      "captured_at_ms": 1700000000000
    },
    "sensors": [
      { "child_frame_id": "sealog-frame-ouster-os-lidar", "capture": "manual" }
    ],
    "updated_at_ms": 1700000001000
  }
}
```

## The zero point on the bus

The transforms are relative geometry: they say a lidar is 0.22 m ahead of the platform's reference point,
and nothing about where that point is on the earth. The zero is what anchors them, so it is published
too — as `foxglove.LocationFix`, under the platform's entity and the `calibration` source:

```
rise/@v0/sealog/pubsub/location_fix/calibration
```

Three things keep it from being mistaken for the platform's live position, and all three matter:

1. **It is a different key.** The phone's own fix is `rise/@v0/pixel_6/pubsub/location_fix/phone` —
   different entity, different source. Nothing ever overwrites the other, and a consumer that wants the
   live position of a moving platform subscribes to whatever GNSS that platform actually carries.
2. **Its payload timestamp is the survey time, not the publish time.** This is the opposite choice from
   `frame_transform` above, and deliberately so: a transform's timestamp is machinery for building a
   frame tree, while this one is the *age of a measurement*. A consumer that checks it gets a straight
   answer — this fix is from Tuesday — which is the one defence against reading a surveyed anchor as a
   live position.
3. **It is named "Zero point" on screen**, not "Position", because two rows reading the same thing is
   exactly how the mistake would get made in the first place.

The fix carries a covariance built from the capture's own accuracies — horizontal on east and north,
vertical on up, tagged `APPROXIMATED` because a phone reports a radius and not an error ellipse. As
everywhere else in this app it is **both or nothing**: a typed position has no accuracy to state, so it
states none rather than putting a zero in the up slot and claiming the altitude was known perfectly.

It publishes only once a position has actually been captured or typed. A platform measured entirely with a
tape has sensors worth publishing and no position at all, and a heading typed before any capture is
stored as a zero with no position — in both cases `location_fix` stays silent rather than putting
0°N 0°E on the bus, which would be the most confident possible way of being wrong.

Why `foxglove.LocationFix` rather than `keelson.Coordinate`: the protocol specification's test is
whether the position carries measurement context a consumer could act on. This one has an accuracy, a
frame id and a survey time, so it is an observation, not a referent.

## The `calibration` block, and why the export does not have it

`config-schema.json` is `additionalProperties: false` at every level: one unknown key and the platform
connector refuses to start. So there are two variants of the same document, and the difference is not
cosmetic:

- **The exported file** — strictly upstream's schema, nothing else. It can be handed to
  `platform-geometry2keelson.py --config <file>` unchanged, which is the point of exporting: the phone
  surveys the platform once, and a connector on the vessel publishes the result from then on.
- **`configuration_json` on the wire** — the same document plus the `calibration` block above, carrying
  the zero point, the heading and its provenance, and how each sensor's offset was arrived at.

That split exists because **upstream's schema has nowhere to put uncertainty**, and a calibration
without a stated uncertainty is half a measurement: a consumer cannot tell a tape-measured 0.22 m from a
GNSS-averaged one with ±3.4 m behind it. Proposing that block upstream is the honest fix — it is the
same kind of gap as `keelson.Audio` having no AAC encoding, and it is recorded here for the same reason.

## Feeding keelson's platform connector

```bash
uv run connectors/platform/bin/platform-geometry2keelson.py \
  --realm rise \
  --entity-id sealog \
  --source-id platform \
  --config sealog-platform-geometry.json \
  --interval 10
```

Use `--entity-id` matching the platform entity the phone published under, or the fleet ends up with the same
platform's geometry under two names.

## Verifying a calibration

- The recording is the complete copy: every transform published is written to the run's MCAP, on the
  same key. Read it back with the `mcap` Python library and the channel decodes as `foxglove.FrameTransform`.
- Open the MCAP in Foxglove and add a 3D panel: the sensors appear at their offsets from the platform frame.
  A sensor below the waterline means a sign error on `z`; a sensor abeam when it should be ahead means
  the forward axis is wrong, not the offset.
- `jsonschema` the exported file against `connectors/platform/config-schema.json` before handing it on.

## The library, and crowsnest

Crowsnest keeps a platform list of its own, and it is worth being precise about what it is before
trying to sync with it. It is **local config, not a bus object**: a `src/DB/platform_registry.json`
shipped with the build plus a per-browser localStorage overlay, not shared between stations. Its live
`get_config` RPC only *enriches* a platform already in that list; it discovers nothing. And **keelson
has no wire-level list of platforms at all** — no subject, no interface, no well-known key. The only
bus-derived enumeration is Zenoh liveliness, which yields entity ids and presence and no metadata.

*(2026-08-25: the first half of that has moved. Crowsnest now has a platform **library sync** —
`services/platformLibrarySync.js` and a hook mounted in `BasePage.jsx` — which publishes its overlay to
the same `platform_registry/library/latest` key this app uses, with a byte-compatible encoder;
`PlatformLibraryInteropTest` pins that the two really meet. It is written but **untracked** in that
repo, so it is not shipped. The rest below still holds: keelson still has no wire-level platform list,
and this app cannot currently receive one anyway, since subscribing aborts the process — see
`ZenohBinding.SUBSCRIPTIONS_SAFE`.)*

So there is nothing to subscribe to for "the platforms". Four things bridge the gap instead, and each
is a separate control on the platform list:

**Same shape, keyed the same way.** A platform's entity id is the registry key on both sides, and a
crowsnest entry is upstream's `config-schema.json` plus `realm` — which is what this app already
writes. **Export all** produces that object-of-platforms; **Import** reads it, or a single
platform-geometry file, or one of the older `keelson-platforms` `config.json` documents whose
transforms are `translation: [x, y, z]` arrays. What an import does *not* keep is stated on the screen
rather than discovered afterwards: MMSI, call sign, `data_streams`, `queryables` and camera
calibrations are not read and are not written back out, because this app models geometry and not a
fleet's stream inventory.

**Scan the bus.** Two probes, because neither alone is enough. Liveliness answers which entities are
alive and nothing else. The documents come from *listening* to `configuration_json` for a little over
ten seconds — every platform connector republishes on that interval precisely so a late joiner need
not ask, which is what makes a passive listen sufficient. A router `get` is deliberately not used: no
storage covers `configuration_json`, so a query returns an empty list that looks exactly like an empty
bus. A discovered platform is offered for adoption and never added silently.

**Answering `get_config`.** While a platform screen is open the phone serves the configuration of every platform
in its library, on two keys per platform:

```
{realm}/@v0/{platform}/@rpc/configurable/v1/get_config/{calibration_source}   ← the specification's shape
{realm}/@v0/{platform}/@rpc/get_config/connector_platform                    ← what crowsnest actually probes
```

The second is a **pre-interface layout** with no `{interface}/{version}` chunks. Crowsnest builds it in
`src/apps/os_config/index.jsx` and declares it in every registry entry, and nothing a current keelson
connector serves answers it — so the phone serves both, to be useful today and correct later. Drop the
legacy one once crowsnest moves. (Its declared `get_data_streams` and `get_queryables` queryables do
not exist in keelson at all.) The reply is **raw JSON, not an envelope** — that is what
`keelson.scaffolding.configurable` does and what crowsnest's worker expects, and it is the one place in
this app where "everything on the wire is wrapped" does not apply.

**The shared library**, off by default. It publishes the whole library as raw JSON on

```
{realm}/@v0/platforms/pubsub/platform_registry/library/latest
```

`platform_registry` is **deliberately not a keelson subject**, which is exactly what makes a consumer's
decode fall through to raw JSON rather than failing to unwrap an envelope — the same trick crowsnest
already plays for `dataflow_config`, `route` and `voyage`. Last-writer-wins by `version`, with an
`origin` field dropping this phone's own echoes; the resolution rules are transcribed from crowsnest's
`shouldApplyRemote` so the two sides settle a disagreement the same way. Two rules are ours, and both
are deliberate:

- **A remote library replaces documents and never local policy.** Which platform is active and which platforms
  publish stay this phone's own. Without that, one operator's save would silently start every phone in
  the fleet publishing geometry under entity ids nobody told them about.
- **A platform this phone is publishing is never deleted by a remote update**, a knowing deviation from
  crowsnest's whole-map replace: taking a platform out from under a live publisher is the one case where
  last-writer-wins is not acceptable.

Two things outside this repo are needed for the last one to be worth much. The router needs a storage
for that key or a station joining late sees nothing — added to
`../keelson-router/docker-compose.keelson-router-rise.yml`, the same lesson `checklist_procedure`
taught. And crowsnest does not publish its own overlay today, so until it does this is one-way: the
phone shares, and nothing answers.

## What this deliberately does not do

- **No publishing outside a run.** The calibration goes out with the next Start rather than opening a
  second Zenoh session for a one-off put.
- **No rotation capture**, for the reason given above.
- **The exported file carries no position.** `config-schema.json` has no field for one, so the zero
  travels on `location_fix` and in the wire document's `calibration` block, and the exported file stays
  strictly upstream's shape.
