# Rig calibration

How to describe a sensor rig with this phone, what goes on the Keelson bus when you do, and — the part
that matters most — what a phone-measured calibration is actually worth.

A calibration here is two things: **where the rig's zero point is**, and **where each sensor sits
relative to it**. Nothing else. It is what lets a consumer of the rig's radar, lidar and GNSS relate
them to each other and to a common reference point, instead of treating each as if it were mounted at
the centre of the world.

## The contract

Nothing in this is invented locally. Both subjects and the document shape come from keelson, and there
is a reference publisher to match:

| | |
| --- | --- |
| `frame_transform` | `foxglove.FrameTransform` — one message per sensor |
| `configuration_json` | `keelson.TimestampedString` — the whole geometry as one document |
| `location_fix` | `foxglove.LocationFix` — the rig's zero point, where it was surveyed |
| Document shape | [`connectors/platform/config-schema.json`](../../keelson/connectors/platform/config-schema.json) |
| Reference publisher | `connectors/platform/bin/platform-geometry2keelson.py` |

Keys, with the **rig** as the entity — not the phone:

```
{realm}/@v0/{rig_entity_id}/pubsub/frame_transform/{calibration_source}
{realm}/@v0/{rig_entity_id}/pubsub/configuration_json/{calibration_source}
{realm}/@v0/{rig_entity_id}/pubsub/location_fix/{calibration_source}     ← the surveyed zero
```

`entity_id` names the physical thing the data is *about*, and a rig's geometry belongs to the rig even
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

1. **Name the rig.** The entity id and the parent frame id follow the name (`SSRS18` → `ssrs18`,
   `ssrs18-frame-ccrp`) until you edit one of them by hand.
2. **Set the zero point.** Stand at the rig's reference point and *Capture position* — twenty seconds
   of fixes, averaged — or *Type position* from a chart or a survey. A rig you intend to measure
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

   **Rotation is always typed.** A phone held against a radar can measure where the radar is; it
   cannot measure where the radar is looking, and a capture button for it would be inventing a
   measurement.
5. **Save.** That restarts publishing so the new geometry goes out. **Export** writes the
   platform-geometry file to `Downloads/Logline`.

## What a phone fix is worth

This is the part to read before trusting any of it.

A fused fix on a phone is metre-class. Averaging twenty seconds of it reduces **scatter** — how far the
samples fall from their own mean — and does almost nothing about **bias**: GNSS multipath sits still for
minutes at a time, so a capture can report 0.3 m of scatter and still be three metres from the truth.
The app shows both numbers side by side for exactly that reason, and they routinely disagree.

The consequence: **an offset smaller than the fix accuracy that produced it is noise**, and the app says
so in the error colour rather than showing six confident decimal places. A rig 1.8 m long has sensor
offsets of a few decimetres; those must be measured with a tape and typed. Capture is honest for
platform-scale geometry — masts, containers, a ship's bridge to its bow — or with an RTK-corrected
receiver, where the numbers are centimetres and this whole caveat goes away.

Two more limits worth stating plainly:

- **The phone's own antenna is not the sensor.** Holding a phone against a radar puts its GNSS antenna
  some tens of centimetres from the thing being measured, and nothing corrects for that.
- **Altitude is the worst axis.** GNSS vertical error is roughly twice the horizontal, and mast heights
  are exactly where that hurts. Type `z` whenever you can.

## What goes on the wire

With one lidar on `SSRS18` at 0.22 m forward, 0.35 m up and yawed 90° to starboard:

**`frame_transform`**, one message per sensor, republished every ten seconds:

```
timestamp { seconds: 1787085413 nanos: 965459000 }
parent_frame_id: "ssrs18-frame-ccrp"
child_frame_id: "ssrs18-frame-ouster-os-lidar"
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
  "name": "SSRS18",
  "ccrp_m": { "x": 0, "y": 0, "z": 0 },
  "frame_transforms": [
    {
      "parent_frame_id": "ssrs18-frame-ccrp",
      "child_frame_id": "ssrs18-frame-ouster-os-lidar",
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
      { "child_frame_id": "ssrs18-frame-ouster-os-lidar", "capture": "manual" }
    ],
    "updated_at_ms": 1700000001000
  }
}
```

## The zero point on the bus

The transforms are relative geometry: they say a lidar is 0.22 m ahead of the rig's reference point,
and nothing about where that point is on the earth. The zero is what anchors them, so it is published
too — as `foxglove.LocationFix`, under the rig's entity and the `calibration` source:

```
rise/@v0/ssrs18/pubsub/location_fix/calibration
```

Three things keep it from being mistaken for the rig's live position, and all three matter:

1. **It is a different key.** The phone's own fix is `rise/@v0/pixel_6/pubsub/location_fix/phone` —
   different entity, different source. Nothing ever overwrites the other, and a consumer that wants the
   live position of a moving rig subscribes to whatever GNSS that rig actually carries.
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

It publishes only once a position has actually been captured or typed. A rig measured entirely with a
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
  surveys the rig once, and a connector on the vessel publishes the result from then on.
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
  --entity-id ssrs18 \
  --source-id platform \
  --config ssrs18-platform-geometry.json \
  --interval 10
```

Use `--entity-id` matching the rig entity the phone published under, or the fleet ends up with the same
rig's geometry under two names.

## Verifying a calibration

- The recording is the complete copy: every transform published is written to the run's MCAP, on the
  same key. Read it back with the `mcap` Python library and the channel decodes as `foxglove.FrameTransform`.
- Open the MCAP in Foxglove and add a 3D panel: the sensors appear at their offsets from the rig frame.
  A sensor below the waterline means a sign error on `z`; a sensor abeam when it should be ahead means
  the forward axis is wrong, not the offset.
- `jsonschema` the exported file against `connectors/platform/config-schema.json` before handing it on.

## What this deliberately does not do

- **No publishing outside a run.** The calibration goes out with the next Start rather than opening a
  second Zenoh session for a one-off put.
- **No import** of an existing platform-geometry file. Export is one-way for now.
- **No rotation capture**, for the reason given above.
- **One calibration at a time**, not a library of rigs.
- **The exported file carries no position.** `config-schema.json` has no field for one, so the zero
  travels on `location_fix` and in the wire document's `calibration` block, and the exported file stays
  strictly upstream's shape.
