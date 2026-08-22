# Logline

An Android app that turns a phone into a [Keelson](https://github.com/RISE-Maritime/keelson) sensor
connector: it reads GNSS and IMU sensors and publishes them to a Zenoh router as protobuf messages
wrapped in a Keelson `Envelope`.

Written in Kotlin with Jetpack Compose. Single module (`:app`), no backend of its own — it is a
publisher on someone else's bus.

## What it publishes

Every sample is serialised as its payload type, wrapped in `core.Envelope` (which stamps
`enclosed_at`), and `put` on a Zenoh publisher.

| Subject | Payload type | Android source | Approx. rate |
| --- | --- | --- | --- |
| `location_fix` | `foxglove.LocationFix` | Fused location provider, `PRIORITY_HIGH_ACCURACY` | 1 Hz by default |
| `speed_over_ground_knots` | `keelson.TimestampedFloat` | `Location.getSpeed()` | with `location_fix` |
| `course_over_ground_deg` | `keelson.TimestampedFloat` | `Location.getBearing()` | with `location_fix` |
| `linear_acceleration_mpss` | `keelson.Decomposed3DVector` | `TYPE_LINEAR_ACCELERATION` | 50 Hz by default |
| `angular_velocity_radps` | `keelson.Decomposed3DVector` | `TYPE_GYROSCOPE` | 50 Hz by default |
| `orientation_quaternion` | `keelson.TimestampedQuaternion` | `TYPE_ROTATION_VECTOR` | 50 Hz by default |
| `magnetic_field_gauss` | `keelson.Decomposed3DVector` | `TYPE_MAGNETIC_FIELD` | 50 Hz by default |
| `heading_magnetic_deg` | `keelson.TimestampedFloat` | `TYPE_ROTATION_VECTOR`, via `getOrientation` | with `orientation_quaternion` |
| `heading_true_north_deg` | `keelson.TimestampedFloat` | the above plus `GeomagneticField` | with `orientation_quaternion` |
| `heading_accuracy_deg` | `keelson.TimestampedFloat` | the rotation vector's 5th component | with `orientation_quaternion` |
| `magnetic_variation_deg` | `keelson.TimestampedFloat` | `GeomagneticField(lat, lon, alt).declination` | with `location_fix` |
| `air_pressure_pa` | `keelson.TimestampedFloat` | `TYPE_PRESSURE` | 1 Hz by default |
| `illuminance_lux` | `keelson.TimestampedFloat` | `TYPE_LIGHT` | 1 Hz by default, held between reports |
| `battery_state_of_charge_pct` | `keelson.TimestampedFloat` | `ACTION_BATTERY_CHANGED` | 0.2 Hz by default |
| `battery_voltage_v` | `keelson.TimestampedFloat` | `ACTION_BATTERY_CHANGED` | with state of charge |
| `battery_current_a` | `keelson.TimestampedFloat` | `BATTERY_PROPERTY_CURRENT_NOW` | with state of charge |
| `battery_temperature_celsius` | `keelson.TimestampedFloat` | `ACTION_BATTERY_CHANGED` | with state of charge |
| `battery_is_charging` | `keelson.TimestampedBool` | `BatteryManager.isCharging()` | with state of charge |
| `radio_rsrp_dbm` | `keelson.TimestampedFloat` | LTE `getRsrp()` | 1 Hz by default |
| `radio_rsrq_db` | `keelson.TimestampedFloat` | LTE `getRsrq()` | with RSRP |
| `radio_sinr_db` | `keelson.TimestampedFloat` | LTE `getRssnr()` | with RSRP |
| `radio_rssi_dbm` | `keelson.TimestampedFloat` | LTE `getRssi()` **and** `WifiInfo.getRssi()` | with RSRP |
| `radio_access_technology` | `keelson.TimestampedString` | which `CellSignalStrength` reports | with RSRP |
| `radio_downlink_bitrate_bps` | `keelson.TimestampedFloat` | `WifiInfo.getRxLinkSpeedMbps()` | with RSRP |
| `radio_uplink_bitrate_bps` | `keelson.TimestampedFloat` | `WifiInfo.getTxLinkSpeedMbps()` | with RSRP |
| `radio_cell_id` | `keelson.TimestampedInt64` | `CellIdentityLte.getCi()` / `CellIdentityNr.getNci()` | with RSRP |
| `radio_physical_cell_id` | `keelson.TimestampedInt` | `getPci()` | with RSRP |
| `radio_earfcn` | `keelson.TimestampedInt` | `getEarfcn()` / `getNrarfcn()` | with RSRP |
| `radio_band` | `keelson.TimestampedString` | `getBands()` | with RSRP |
| `radio_downlink_bandwidth_mhz` | `keelson.TimestampedFloat` | `CellIdentityLte.getBandwidth()` | with RSRP; LTE only |
| `audio` | `keelson.Audio` | `AudioRecord`, WAV-framed PCM | off by default; 1 chunk/s when on |
| `image_compressed` | `foxglove.CompressedImage` | CameraX `ImageCapture`, JPEG | off by default; 1 frame/2 s at 720p when on |
| `video_compressed` | `foxglove.CompressedVideo` | `MediaCodec` H.264, Annex B | off by default; 10 fps at 640x480 when on, replaces the time-lapse |
| `log_message` | `foxglove.Log` | an operator pressing a button | only when marked |
| `frame_transform` | `foxglove.FrameTransform` | a platform calibration, one message per sensor | every 10 s, only when a platform is calibrated |
| `configuration_json` | `keelson.TimestampedString` | the same calibration as one document | with `frame_transform` |
| `location_fix` | `foxglove.LocationFix` | the platform's surveyed zero point, under the platform's entity | with `frame_transform`, once a zero is set |

**The units on the wire are keelson's, not Android's**, and they differ for most of these: gauss rather
than microtesla, pascals rather than hectopascals, knots rather than metres per second, volts rather
than millivolts, amps rather than microamps, degrees Celsius rather than tenths. The conversions live
in `sensors/Units.kt` with tests against known constants, because a missed one produces a plausible
number that is simply wrong by a constant factor.

**Every subject has a switch on the main screen**, and each section heading has a master switch that
moves the whole group in one tap. Switching one off stops it completely: nothing
goes on the bus, nothing is written to the recording — a subject switched off for a whole run has no
channel in the MCAP file at all rather than an empty one — and nothing is plotted in the live view. The
switches take effect **on a run already in progress**, without dropping the Zenoh session, so you can
turn the IMU off partway through a passage and leave GNSS publishing. Once every subject on one sensor
is off, that sensor's listener is released as well, which is where the battery saving comes from: the
IMU group alone is about 165 messages a second at the 50 Hz default, and the compass another 150.

A heading's master switch reads as on while anything in its group is on, so one tap silences a mixed
group; the badge beside it carries what a two-state switch cannot say — `3/3 ✓ · 2 off`, or just `off`
once the whole group is.

Audio and the camera are the two exceptions. Their switches restart the run, because the microphone and
camera foreground-service types and their permissions are fixed when the service starts — the row says
so. **A master switch never moves those two**: nobody tapping "Device" expects the microphone and the
camera to come on with the barometer, so they keep their own row switch and their own deliberate tap.
Switch settings persist across runs and restarts.

Some subjects come off a single reading and therefore share one *recording* rate: speed and course are
read from the same `Location` object as `location_fix`, the battery scalars from one poll, and the radio
subjects from another. There is one listener behind each group, so there is nothing per-subject to ask
the sensor for, and their settings screen says so.

On the **wire** they are independent. Each of those subjects has its own publish rate, which is pure
decimation of samples that have already arrived — so a declination that moves over a day's sailing can
go out once every twenty seconds while the fix beside it stays at 1 Hz. The limit is that a subject
cannot be published faster than the one it is derived from: its page offers *Follow Position* or a rate
of its own, and says which one is binding. Raising it past the cap means raising the owner first, which
is one tap from the same page. Note the consequence for anyone consuming the bus — a group can now be
internally inconsistent, with speed arriving every second and course every five, off the same fix. The
recording is unaffected either way; that is what the file is for.

Those groupings are also what a switch releases: switching off one of the four subjects that ride the
GNSS fix leaves the other three publishing and the receiver on.

**Video replaces the time-lapse; the camera will not serve both.** Binding a `Preview` that feeds the
H.264 encoder alongside `ImageCapture` kills the camera HAL on a Pixel 6 — `ERROR_CAMERA_DEVICE` within
a second, at matching resolutions as well as mismatched — so switching video on switches the stills off.
Which to pick is a question of what the run is for: the time-lapse is 158 MB/h for one frame every two
seconds, video is **131 MB/h measured for ten frames a second** at the 640x480, 300 kbps default. Video
is cheaper per hour *and* twenty times the frames at that setting; at 720p and 2 Mbps it is 858 MB/h and
turns ten days of recording into under two, which is why the settings screen prints the figure.

The frame rate is asked of the camera, not the encoder — `MediaFormat.KEY_FRAME_RATE` only tells the
encoder how to spend its bitrate, and a request for 10 fps encoded 29.9 until the camera itself was
asked. The bitrate is honoured closely: 131 MB/h against 128 predicted, the difference being envelope
overhead.

Each message holds one frame in Annex B framing, and every keyframe carries its own parameter sets, so
a subscriber joining mid-run can start decoding at the next keyframe — at most two seconds. Verified by
cutting a recording at its 1 260th frame of 2 499 and decoding the remainder with `ffmpeg`.

**`illuminance_lux` is held between readings, and that is deliberate.** The ambient light sensor is
*on-change*: it reports when the light changes and not otherwise — measured on a Pixel 6, twenty
minutes passed with no event in a steady room. Published raw that is a series full of holes,
indistinguishable from a dead sensor. So the collector repeats the last reading at the configured rate
while **keeping its original observation timestamp**, exactly as the radio subjects do. Consecutive
messages sharing one timestamp are therefore the signal that the sensor has not reported since; a
consumer wanting only genuine readings should deduplicate on the payload timestamp, not the arrival
time. Nothing is published before the first reading, so a device without the sensor stays silent rather
than reporting a plausible zero.

### Audio

**Off by default, and it stays off until someone turns it on in Settings.** A phone logging in a
wheelhouse records the conversations held around it; Android's microphone indicator is visible for the
whole run and the notification says `recording audio` alongside the sample count.

When it is on, each publish carries one chunk of **uncompressed 16-bit PCM inside a 44-byte RIFF/WAVE
header**, so every chunk is self-describing and plays on its own. WAV is not a preference:
`keelson.Audio.Encoding` offers only `MP3` and `WAV`, and Android has an MP3 decoder but no encoder.
The per-subject rate control sets the *chunk length* rather than a sample rate — 1 Hz is one-second
chunks, 0.5 Hz is two-second ones — and the sample rate and channel count are their own settings:

| | data rate |
| --- | --- |
| 8 kHz mono | ≈ 55 MB/h |
| 16 kHz mono *(default)* | ≈ 109 MB/h |
| 44.1 kHz mono | ≈ 302 MB/h |
| 48 kHz mono | ≈ 329 MB/h |
| stereo | double the above |

For scale, every other subject combined is about 77 MB/h. Rates the device's microphone does not offer
are shown as unavailable rather than failing when a run starts.

The capture source is **`UNPROCESSED` where the device advertises it, otherwise `VOICE_RECOGNITION`**,
never plain `MIC`: automatic gain control and noise suppression would rewrite the very thing a log is
supposed to preserve. On the bus `audio` is `transient` — best-effort, per keelson's `qos.yaml` — so a
dropped chunk is a hole in the sound that nothing retransmits. The MCAP file is the complete copy.

To listen to a recording, concatenate the chunks' `data` fields and open the result with any WAV
reader; each chunk carries its own header, so a single chunk is already a playable file.

### Camera

**Off by default, and it stays off until someone turns it on in Settings**, for the same reason audio
does: a phone logging on a bridge photographs whoever walks in front of it. Android's camera indicator
is visible for the whole run and the notification says `taking pictures` alongside the sample count.

When it is on, each publish carries one **JPEG at quality 80** in a `foxglove.CompressedImage`, with
`format` set to `jpeg` and `frame_id` naming the lens (`camera_rear` or `camera_front`). The
per-subject rate control is the *time-lapse interval* rather than a frame rate — 0.5 Hz is a frame
every two seconds, 0.1 Hz one every ten — and the frame size is its own setting:

| at 1 frame / 2 s *(default)* | data rate |
| --- | --- |
| 640x480 | ≈ 52 MB/h |
| 1280x720 *(default)* | ≈ 158 MB/h |
| 1920x1080 | ≈ 355 MB/h |

Those are estimates, and generous ones: a JPEG's size depends entirely on what is in front of the lens.
Measured on a Pixel 6, an indoor scene at 720p came out around 40–90 kB a frame; a coastline under a
broken sky will run higher. **It is still the most expensive subject in the app** — every other subject
combined is about 77 MB/h, and audio at its default is 109 MB/h — so the settings screen prints the
figure next to the switch, and dropping the rate is the dial that matters most.

**The frame size is a request, and on most phones it is not one the camera can grant.** A Pixel 6
offers no JPEG capture stream below 1920x1080 on either lens, so a run configured for 720p is captured
at 1080p and **scaled down before publishing** — otherwise the setting would be quietly ignored and the
data rate would be more than double the figure quoted for it. A device that does offer a smaller stream
publishes what it captured, untouched.

The camera is **bound for the whole run** rather than opened per frame: opening one costs a few hundred
milliseconds and blinks the indicator each time, which at a two-second interval would be most of the
duty cycle. The cost is that the camera stays powered while a run is going.

On the bus `image_compressed` is `transient` — best-effort, per keelson's `qos.yaml` — so a frame that
will not fit down a congested link is dropped rather than delaying navigation data. It is also the one
subject **excluded from the dropped-link buffer**: replay is paced by message count, so a handful of
150 kB frames would go out as a burst the egress queue sheds silently, taking live data queued behind
it with it. The MCAP file is the complete copy either way.

To turn a recording into a video, read the `image_compressed` channel out of the MCAP, write each
message's `data` field as a numbered `.jpg`, and hand the directory to `ffmpeg`.

### Annotations

Everything else here is a sensor reading. `log_message` is the one subject a **person** produces: a
marker pressed on the **Mark event** screen to say *this is the bit that matters*, so the moment can be
found again without scrubbing a six-hour recording.

It carries `foxglove.Log`, which is the only well-known keelson payload with free text and a severity.
Buttons are configured in the app — a **label**, a **severity** and a **category** — and there is a
free-text note field beside them for anything unforeseen. The three fields are not arbitrary; each one
lands on something a reader can filter by:

| Field on the wire | Carries | What reads it |
| --- | --- | --- |
| `timestamp` | the instant of the press | the observation time, as with any other subject |
| `level` | the button's severity | Foxglove's minimum-severity filter |
| `name` | the button's category | Foxglove's namespace toggles — one per distinct value |
| `message` | the label, or the typed note | what is displayed, and what text search matches |

`file` and `line` are left empty: they mean a source location and there is not one.

**Categories are meant to be shared between buttons.** Foxglove lists one toggle per distinct `name`,
so a unique category per button grows that list until it is a legend rather than a filter. A handful —
`navigation`, `machinery`, `incident` — is what makes it useful.

Two categories are produced by the app rather than configured. A typed note goes out as `note`, and
every run opens with one automatic `Recording started` mark under `system`. That mark is not
housekeeping: MCAP channels are written on a subject's *first* sample, so a run nobody annotated would
contain no `log_message` channel at all and a saved Foxglove layout pointing at the topic would find
nothing there. Its own category means a reader wanting only the operator's own marks switches one
namespace off.

Marking needs a run in progress — the buttons are disabled otherwise, and say so. There is no session
and no open recording for a mark to join, and a button that silently did nothing would be worse than
one that admits it. Editing the buttons, by contrast, works at any time and **does not restart the
run**: unlike every other setting, the button list is not something the publisher is started with.

`log_message` is `background` upstream — the lowest priority on the bus, so an annotation can never
delay live navigation data, but reliable, because it is the one message somebody deliberately made.

#### In Foxglove

The [Log panel](https://docs.foxglove.dev/docs/visualization/panels/log) accepts `foxglove.Log`
directly. Point it at the `log_message` topic, live through `keelson2foxglove` or from a recording, and
the marks arrive as rows with the severity filter and one namespace checkbox per category. No connector
change was needed for this: `foxglove-liveview` resolves a subject's schema from `subjects.yaml`, and
`log_message` has been there all along.

Foxglove's **Events** — the coloured marks above the playback bar — are a different mechanism. They
belong to a device and a time range and are created in the app or through the REST API; nothing ingests
a topic into them. Turning these annotations into Events therefore needs a post-processing step against
a finished recording, which this app does not do.

### Which way is +X

The vector subjects — `linear_acceleration_mpss`, `angular_velocity_radps`, `magnetic_field_gauss` —
are in **Android's sensor coordinate system**, fixed to the phone's body in its natural (portrait)
orientation:

| Axis | Points |
| --- | --- |
| +X | out of the **right** edge |
| +Y | out of the **top** edge |
| +Z | out of the **screen**, towards you |

Rotation is right-handed: positive is counter-clockwise seen from the positive end of the axis.

**These axes do not rotate with the screen.** Turning the phone to landscape does not swap X and Y in
the published data — the app never calls `remapCoordinateSystem`, so what goes on the wire is the
device frame as the platform reports it. Note also that `linear_acceleration_mpss` comes from
`TYPE_LINEAR_ACCELERATION`, which has gravity already subtracted, so a phone at rest reads about zero
on all three axes rather than 9.81 on one of them.

`orientation_quaternion` is the exception: it is the rotation **from** those device axes **to** the
world frame — x east, y north, z up — so it is what converts the readings above into earth-referenced
ones. Everything else is direction-free: the scalars carry no axis, `location_fix` is WGS-84, and
`course_over_ground_deg` is degrees clockwise from true north.

### The raw sentences

`raw_nmea0183` carries what the GNSS chip actually said, sentence by sentence, exactly as it said it.
Everything else this app publishes about position comes from Android's **fused** provider — GNSS
blended with wifi and cell, handed back as a `Location` with fix quality, DOP, satellite count and
constellation already discarded. The sentences carry all of it, in the form the rest of the fleet
already speaks, which makes the phone a plain NMEA source like any other box on the boat.

Several arrive per fix — a typical receiver emits GGA, RMC, GSA, VTG and a handful of GSV each second
— so at the 1 Hz default this is the busiest GNSS subject by message count and among the smallest by
bytes. Upstream puts it on the `background` QoS profile: reliable, so a sentence is not shed, and
`DATA_LOW`, so a talkative receiver never crowds out live navigation data.

Two things follow from how Android exposes it. **Nothing here starts the GNSS engine** — the listener
only hears one that is already running, which is the location collector's own request. So this subject
shares that collector: switch every other GNSS subject off and leave this one on, and the request stays
alive and the receiver keeps talking. Switch *all* of them off and the sentences stop a few seconds
later, which is honest — there is no receiver running to quote.

And the callback's timestamp is **checked, not trusted**. It is documented as epoch milliseconds and
that is what is used when it looks like one; a value below 2001-09-09 is read as the boot clock the
older `GpsStatus.NmeaListener` supplied and converted, because publishing that raw would date the whole
stream to 1970 — decodable, plausibly paced, and off by decades.

### How good is the fix

Three subjects answer that, all from `GnssStatus` and all on the same 1 Hz callback:
`location_fix_satellites_visible`, `location_fix_satellites_used`, and `location_fix_quality`
(`keelson.LocationFixQuality`).

The gap between visible and used is the reading. Twenty satellites in view and none of them used is a
phone under a steel deck — and from every other subject that looks exactly like a good fix, because
Android's fused provider hands back a position either way, derived from wifi and cell if it has to.

`location_fix_accuracy_horizontal_m` and `location_fix_accuracy_vertical_m` come off the fix itself and
say how far off it might be. Both numbers are already inside `location_fix`'s covariance matrix, where
nothing can read them without decoding nine doubles and knowing which three matter — as their own
subjects they are a line on a chart beside the track. They are Android's own figures, which are **68%
confidence radii rather than bounds**: a horizontal accuracy of 5 m means about two thirds of fixes
land within five metres, not that this one did.

**They are skipped, never zeroed, when the platform does not report them** — the opposite of speed and
course, and deliberately. A missing speed published as `0.0` says the phone is stationary, which is
usually true; a missing accuracy published as `0.0` says the fix is exact, which is never true.

That is also why **`FIX_NO` can appear while a position is being published, and is not a
contradiction**: the fix exists, and it is not a GNSS fix. `FIX_2D` and `FIX_3D` are told apart by
whether the fix carried an altitude. `pos_type` is `POS_TYPE_SINGLE` when the receiver is solving and
`POS_TYPE_NO_SOLUTION` when it is not — a phone does single-point positioning and nothing it can
prove, since no Android API exposes SBAS, RTK or PPP. `rtk_status` and `integrity` are left unset for
the same reason: their zero values mean "not reported", which is the truth.

### Which altitude

`location_fix.altitude` is `Location.getAltitude()`, which Android defines as height above the **WGS84
reference ellipsoid**. Foxglove's proto says only "Altitude in meters", so nothing on the wire says
which surface it is measured from — and in Sweden the two answers are **30-35 m apart**, which reads
as a broken sensor rather than as a different reference.

Two subjects settle it. `altitude_above_msl_m` is height above mean sea level, the number anybody
means by "altitude". `location_fix_undulation_m` is the geoid separation, **N = h − H** — ellipsoidal
minus mean-sea-level, the standard geodetic sign — so a consumer can convert between the two rather
than guessing which they were given. It is positive across northern Europe and negative over much of
the Indian Ocean, which is why it is published rather than assumed.

**Reported when the fix carries it, derived when it does not.** `Location.getMslAltitudeMeters()`
arrived in API 34 and is an *optional* property of a fix that Android's fused provider commonly omits,
so a strictly-reported version would be a subject that never publishes on most phones. Where it is
missing, `AltitudeConverter` fills it in from the same ellipsoidal height. The cost is that a series
can change provenance between samples — reported on one fix, derived on the next — with no field to
say which; that is accepted because both describe the same quantity to within the geoid model's own
accuracy, and the alternative is no series at all.

Below API 34 both rows read **"Not on this device"** rather than waiting for a sample that can never
arrive. Both are skipped, never zeroed, when there is no value — and that matters more here than
almost anywhere else in this app, because on a vessel `0.0 m` above sea level is *plausible*, so a
defaulted zero would not look wrong to anybody reading it later.

### Roll, pitch and yaw

`orientation_quaternion` carries the phone's attitude exactly and unreadably — nobody looks at a plot
of `w` and knows how much the boat was moving. `roll_deg`, `pitch_deg` and `yaw_deg` are the same
information in the form a person reads, and they cost nothing to produce: `getOrientation` was already
computing all three to derive the heading, and two of them were being thrown away. `yaw_deg` is the
heading in signed ±180° form, which is how an Euler triple is read; `heading_magnetic_deg` is the same
measurement as a 0-360° compass bearing.

`roll_rate_degps`, `pitch_rate_degps` and `yaw_rate_degps` are the gyro's three axes named and
converted — `angular_velocity_radps` already carries the same vector in rad/s, but a scalar can be
plotted and alarmed on where a vector component cannot. **They are body rates, not the derivatives of
the three angles**: the two agree only near level and diverge exactly where the motion is interesting.

Two things to know before reading any of them:

- **They describe the phone, not the vessel.** Pitch turns about the device's +X axis, roll about +Y,
  yaw about +Z — Android's own convention, the same one the heading uses. What that means for the boat
  the phone is strapped to is the platform calibration's `frame_transform`. The subject's own screen says so.
- **They run at 10 Hz by default, not the IMU's 50.** Their own dial, on their own sensor
  registration, because riding the rotation vector would have put ~150 messages a second on the bus for
  three subjects describing motion with a period of seconds. Six subjects at 10 Hz is ~60/s and about
  13 MB/h.

### IMU temperature, and the sensor Android has no name for

`imu_temperature_celsius` is the temperature of the IMU chip itself — what explains gyro bias drift on
a phone that has been sitting in the sun. It is the *chip's*, not the air's, and the difference is the
point: the die runs hotter than what is around it.

Getting it is not what you would expect. `Sensor.TYPE_TEMPERATURE` is deprecated,
`TYPE_AMBIENT_TEMPERATURE` measures the air, and a **Pixel 6 has neither** — `dumpsys sensorservice`
lists no `android.sensor.temperature` and no `android.sensor.ambient_temperature` at all. What it does
list is `com.google.sensor.gyro_temperature`: the LSM6DSR's own sensor, continuous, 1.62–52 Hz, no
permission. So the sensor is found by **string type**, since a vendor sensor's numeric type is assigned
by the vendor and means nothing on another phone.

A device without that exact sensor publishes nothing and the row reads "Not on this device". There is
deliberately **no fallback to the ambient sensor**: publishing air temperature under a subject that
names the IMU would be a plausible wrong number, and the whole reason to log this one is that the two
differ. The first reading is checked against the chip's own −40…125 °C range and logged if it falls
outside — a vendor sensor's scaling is not guaranteed by any contract, and a raw count would arrive
looking like a temperature.

### The compass

`heading_magnetic_deg` is the same rotation vector read as one angle: **degrees clockwise from magnetic
north, of the phone's +Y axis** — its top edge. That is Android's own definition of azimuth, and it
degenerates when +Y points at the sky, so a phone standing upright has no meaningful heading; lying flat
or mounted face-up is the case it answers. `orientation_quaternion` carries the full attitude for anyone
who needs another convention.

**Heading is not course.** `course_over_ground_deg` is where the phone is *travelling*, from GNSS;
heading is where it *points*. With leeway, current or a phone that is not aligned with the hull, the two
differ — which is the reason to log both rather than a redundancy.

`heading_true_north_deg` is the magnetic heading plus the local declination, and
`magnetic_variation_deg` is that declination on its own — computed from `GeomagneticField`, so both
**need a position**. Until the first fix arrives neither publishes at all, rather than referencing a
heading to the wrong north. (Measured at Onsala: +5.45°, which matches the published world magnetic
model for 57.44 N, 12.03 E.) Declination is a property of *where you are*, so it rides `location_fix` at
1 Hz rather than the compass at 50.

`heading_accuracy_deg` is the platform's own 1-sigma estimate, straight off the rotation vector's fifth
component. It is published beside the heading rather than used to gate it — a heading with a stated 60°
uncertainty is information; a heading silently withheld is not. **Sixty degrees is what an uncalibrated
magnetometer reports**, and the fix is the usual figure-of-eight. The other tell is
`magnetic_field_gauss` magnitude straying from the 0.25–0.65 G Earth range, which means iron or
electronics nearby.

The three heading subjects ride the rotation vector's samples, so at the default 50 Hz they add about
150 messages a second between them. Lower `orientation_quaternion`'s rate if that matters — they follow
it, and their settings screens say so.

The app shows this as a labelled diagram in the live view and on each affected sensor's settings
screen, so nobody has to go looking for it while reading numbers.

### The radio subjects are keyed by link, not by name

`radio_rssi_dbm` is published **twice**, once per radio, distinguished by `source_id` — which is how
`subjects.yaml` models it upstream ("use `source_id` to distinguish links"):

```
rise/@v0/pixel_6/pubsub/radio_rssi_dbm/cellular    -65 dBm
rise/@v0/pixel_6/pubsub/radio_rssi_dbm/wifi        -43 dBm
```

A subscriber wanting only one must include the source: `.../radio_rssi_dbm/cellular`. Subscribing to
`.../radio_rssi_dbm/**` gets both interleaved, which is usually not what you want.

**A link that is down publishes nothing rather than a placeholder.** Turn WiFi off and the three `wifi`
subjects stop; the `cellular` ones continue. This matters because the platform's "unavailable" values
are not neutral — telephony reports `2147483647`, WiFi reports `-127` dBm and `-1` Mbps — and `-140`
dBm is a *real* RSRP meaning "barely alive". Letting a sentinel through would not add noise to an
averaged series, it would destroy it, so absent values are dropped at the boundary.

**On 5G NSA the metrics follow the leg the technology names.** Non-standalone 5G keeps an LTE anchor
alongside the NR carrier and the modem reports both at once — measured here, `rsrp` was `-91` on the
LTE leg and `-83` on the NR leg simultaneously. `radio_rsrp_dbm`, `radio_rsrq_db` and `radio_sinr_db`
are taken from NR whenever NR is reporting, so they always describe the same radio as
`radio_access_technology`; publishing `"NR"` next to the anchor's numbers would be quietly wrong.
`radio_rssi_dbm` is the exception — NR has no RSSI equivalent, so it stays the LTE anchor's
measurement, and on standalone NR it is simply absent.

**The two bitrate subjects are link speed, not traffic.** `radio_downlink_bitrate_bps` and
`radio_uplink_bitrate_bps` come from `WifiInfo.getRxLinkSpeedMbps()` / `getTxLinkSpeedMbps()`, which is
the rate the radio has *negotiated* — a Wi-Fi 6E link reports a couple of gigabits whether or not a
single packet is moving. The app shows them as "Link speed, down/up" so the screen does not imply the
phone is pushing 2.16 Gbit/s. Measuring real throughput would need traffic counters and a subject of
its own.

**The signal figures are held between modem reports.** Android's `SignalStrength` is a cached value the
modem refreshes only when its own hysteresis thresholds are crossed — on a stationary phone, twelve
reads over 30 s returned identical values. The subjects publish at their configured rate regardless, so
the payload carries the modem's own measurement time rather than the publish time: identical timestamps
across several samples mean the radio has not reported since, not that the link is flapping.

### Serving cell identity, and why it is the one part that needs location

The four identity subjects exist for one reason, stated in `subjects.yaml` itself: *"A change in any of
these means the link handed over, and measurements either side of it are not comparable."* A step in
RSRP is noise until you know whether the cell changed underneath it.

**These four require `ACCESS_FINE_LOCATION`** — `getAllCellInfo()` is annotated with it, since a cell id
plus a public tower database yields an approximate position. Consequences worth knowing:

- An **IMU-only run publishes no identity**, because it publishes no location either. The signal
  *quality* subjects are unaffected and keep going; only identity stops.
- The same happens if the location app-op is suppressed while backgrounded. Verified by forcing it:
  the four identity subjects stopped dead while all eight quality subjects continued, then returned
  when it was restored. The platform signals this as an **empty cell list**, not an error, so "no
  identity" and "no cells in range" look identical from the API — both are published as nothing.
- It keeps working with the screen off because the foreground service runs with the `location` type,
  which grants `PROCESS_CAPABILITY_FOREGROUND_LOCATION`. `ACCESS_BACKGROUND_LOCATION` is still not
  requested.

**On 5G NSA the identity is the LTE anchor's, even when the technology reads `"NR"`.** `getAllCellInfo()`
reports only the anchor on this hardware — no `CellIdentityNr` appears at all — while the quality
metrics come from the NR leg. So `radio_cell_id` is *not* the 5G cell id on NSA. It still does its job,
since an anchor handover does invalidate comparisons, but do not read it as identifying the NR carrier.
On standalone NR the NR identity appears and the mismatch disappears.

**`radio_downlink_bandwidth_mhz` is LTE-only, and its uplink counterpart is not published at all.**
Android reports a cell's bandwidth in only one place an ordinary app can reach —
`CellIdentityLte.getBandwidth()`, in kHz — and that field does not exist on `CellIdentityNr`, so the
subject falls silent on 5G rather than publishing a zero. Note also what the platform does and does not
promise: the documentation says "Cell bandwidth in kHz" and names no direction. It is published as the
downlink because that is what the LTE cell identity's bandwidth is, which is a reading rather than
something the API states.

The uplink is genuinely different on an asymmetric carrier, and there is no way to get it: it lives on
`PhysicalChannelConfig`, whose listener requires `READ_PRECISE_PHONE_STATE` — protection level
`signature|privileged`, so no app outside the system image can hold it. `radio_tx_power_dbm` is absent
for the same kind of reason: `requestModemActivityInfo()` reports time spent in power buckets, not dBm.

**Identity is stamped with the cell list's own report time**, not the publish time, because
`getAllCellInfo()` returns a cache. Consecutive samples that share a timestamp are the *same* reading
republished, not a new measurement; observed here, the timestamp held constant across roughly six 1 Hz
publishes and then stepped by 12 s. (A sub-millisecond spread between them is the boot→epoch offset
being re-derived per sample, not a changing measurement.)

**No permission is needed for the signal-quality subjects.** `ACCESS_NETWORK_STATE` is normal and install-time.
Signal strength is deliberately exempt from Android's telephony permissions, and WiFi RSSI read through
`NetworkCapabilities.getTransportInfo()` needs no location grant — it is SSID and BSSID that are
location-gated, and those are not published. The radio technology is inferred from which
`CellSignalStrength` subclass reports real values, specifically to avoid `getDataNetworkType()` and the
`READ_PHONE_STATE` it requires.

### Uptime, and why a recording carries it

`device_uptime_duration` is `SystemClock.elapsedRealtime()` on the battery poll — the phone's time
since boot, deep sleep included, because the phone was up and merely asleep.

It earns its place months later. A gap in a recording has two explanations that are indistinguishable
from the data — the app was stopped and started, or the phone went down and came back — and for an
unattended platform they mean very different things. Uptime resetting across the gap says which.

### Speed and course are always published

`speed_over_ground_knots` and `course_over_ground_deg` go out on **every** fix. When the platform
reports no value they are published as `0.0` rather than omitted.

Know what that costs on the consuming side: proto3 cannot distinguish an absent float from a measured
one, so a `course_over_ground_deg` of `0.0` from this publisher means *either* "heading due north" *or*
"no bearing available", and nothing on the wire tells them apart. Bearing is genuinely absent whenever
the phone is not moving — on a stationary Pixel 6, 34 of 36 fixes carried none. Speed is far less
affected: it was present on 35 of 36, and a stationary phone really is doing zero knots.

A device without a sensor simply publishes fewer subjects: the flow closes and that subject stays at
zero samples. Nothing here needs a permission beyond the location one already requested — the
magnetometer, barometer and battery need none.

Rates are per-subject and configurable — see below. They are *requests*: Android treats the derived
delay as a hint, so what arrives depends on the hardware. Every subject row therefore carries all
three numbers — `1.0 Hz · set 1.0 · max ~1.0`, meaning achieved, requested and the fastest the source
can go — and a tap opens the subject's own page, which spells them out as **Hardware**, **Setting**
and **Actual** with a line on each saying what kind of number it is. A ceiling can be the sensor's own
advertised figure, a floor this app holds a poll loop to, or (for GNSS, which answers no such query)
an estimate, printed with a `~`.

Many subjects have no rate of their own: speed and course come off the same `Location` callback as the
fix, and the battery scalars off one poll. Their page says `Inherited from Position` and offers a
`Rate is set on Position ›` row that opens the subject that owns it.

Subject names and payload types follow `messages/subjects.yaml` in the Keelson repo. They are not
free-form — a subject only means something if a consumer agrees on its type.

How each subject travels comes from the companion `messages/qos.yaml`: `location_fix`,
`speed_over_ground_knots` and `course_over_ground_deg` are `elevated` (priority `DATA_HIGH`) — upstream
groups them as live conning state — and everything else here is unlisted upstream so it inherits
`default` (`DATA`), which that file documents as identical to Zenoh's own defaults. Publishers are
declared with the profile rather than left to chance, so adding a subject picks up the right stance.

`location_fix` also carries a position covariance: a 3×3 row-major ENU matrix in m², diagonal only,
derived from Android's horizontal and vertical accuracies (which are 68% confidence radii, so the
variance is their square) and tagged `APPROXIMATED`. East and north share the horizontal variance
because the phone reports one radius rather than an error ellipse. It is omitted entirely — leaving
`position_covariance_type` at `UNKNOWN` — unless the phone reports *both* accuracies, since a zero in
an unknown slot would claim that axis is perfectly known. This mirrors the mavlink connector, so the
shape is the same as from any other Keelson source.

Note that `altitude` cannot express "unknown": it is a plain proto3 `double`, so an absent altitude and
a literal `0.0` are byte-identical on the wire and no consumer can tell them apart.

Each payload's own `timestamp` is the **observation** time — the sensor event's clock for the IMU
subjects, the provider's UTC fix time for `location_fix` — while the envelope's `enclosed_at` is when it
went on the bus. The difference between the two is the sensor-to-bus latency.

## Key expressions

Keys follow the Keelson v0 pub/sub layout:

```
{realm}/@v0/{entity_id}/pubsub/{subject}/{source_id}
```

With default settings on a Pixel 8 that produces:

```
rise/@v0/pixel_8/pubsub/location_fix/phone
rise/@v0/pixel_8/pubsub/linear_acceleration_mpss/phone
rise/@v0/pixel_8/pubsub/magnetic_field_gauss/phone
rise/@v0/pixel_8/pubsub/air_pressure_pa/phone
rise/@v0/pixel_8/pubsub/battery_voltage_v/phone
...and one for each of the other subjects above
```

`entity_id` defaults to a slugified `Build.MODEL`; the realm, entity and the three source ids are all
editable in the Settings screen and persisted with DataStore.

### `@v0` is verbatim — wildcards do not cross it

A key chunk beginning with `@` is **verbatim** in Zenoh: it matches only an identical literal chunk, and
no wildcard crosses it — `**` included. So

```
rise/**            # matches NOTHING this app publishes
rise/@v0/**        # matches everything
```

The failure mode is what makes this worth knowing: there is no error. A subscriber on `rise/**`
declares successfully and then receives zero samples, which looks exactly like a publisher that is not
running. Measured here: `keelson/**` returned 0 messages over the same window in which
`keelson/@v0/**` returned thousands.

The protocol does this deliberately — the verbatim chunk isolates major versions, so a `@v0` consumer
can never accidentally receive `@v1` traffic (protocol specification §5.8).

## Liveliness

While publishing, the app declares Zenoh liveliness tokens so consumers can discover the phone before
its first sample and get a leave event when it goes away — including when the process is killed, since
Zenoh drops the tokens with the session. The protocol
([specification §5](https://github.com/RISE-Maritime/keelson)) structures these into **three tiers**;
the phone declares two of them, and a transitional third.

**Source tier** — one token per producing `(entity_id, source_id)` identity, saying the process is
present without saying what it publishes. The `*` is literal and sits in the *category* slot:

```
rise/@v0/pixel_6/*/phone
rise/@v0/pixel_6/*/cellular
rise/@v0/pixel_6/*/wifi
```

`cellular` and `wifi` are not configurable — they name which radio measured the value, which is a
hardware fact rather than a preference. Configuring distinct location, IMU and device source ids yields
one token each on top, and each publishing platform adds one under the platform's own entity id.

**Subject tier** — one token per subject the phone claims, on exactly the key that subject publishes on:

```
rise/@v0/pixel_6/pubsub/location_fix/phone
rise/@v0/pixel_6/pubsub/angular_velocity_radps/phone
… ~50 more
```

This is the tier a health monitor actually needs. Upstream's `entity_health` connector reads a source
that declares only a coarse token as advertising *nothing* and drops every subject it was watching as
`NOT_ADVERTISED` — treated as a fault in the monitor's own configuration — so without these tokens a
perfectly healthy phone contributes nothing to a vessel's health score.

A token is a claim of **capability, not activity**, and the specification forbids withdrawing one
because data has stopped. `heading_true_north_deg` keeps its token while it waits for the first fix,
and `log_message` keeps one through a run nobody annotates. Two things do remove a token: hardware the
device does not have, and **a subject switched off in Settings** — a configuration change rather than
silence. That is the one place a per-subject switch is visible beyond the phone: switching a subject
off now withdraws the claim, so a monitor sees it retracted rather than waiting for samples that are
never coming.

**RPC interface tier** — while a platform screen is open, each platform also advertises the `configurable/v1`
interface it answers on:

```
rise/@v0/{platform}/@rpc/configurable/v1/*/calibration
```

No wildcard crosses `@rpc` any more than it crosses `@v0`, so this token is invisible to every pattern
that finds the others — a discovery client needs a second subscription spelling `@rpc` out. Holding the
token obliges the app to answer *every* procedure in the interface, so `get_config` returns the
platform document and **`set_config` returns a typed refusal** — a serialised
`keelson.interfaces.ErrorResponse` with `PERMISSION_DENIED` and a description saying the refusal is
permanent. A platform's geometry is edited on the phone or taken from a shared library under rules that
protect a platform this phone is publishing; a remote write would bypass them. The token is declared only
while `PlatformSync` has a session, which is while a platform screen is up — a phone that is merely logging
advertises no RPC, which is what the specification asks for and worth knowing before you go looking.

**Legacy coarse token** — the pre-3-tier shape, still declared beside the source tier:

```
rise/@v0/pixel_6/pubsub/*/phone
```

The specification asks aggregators to read both shapes during the transition window, so this stays
until the consumers of interest have migrated. It is a fallback, not a substitute: on its own it leaves
every subject unadvertised.

```python
# What this phone claims to publish:
replies = session.liveliness().get("rise/@v0/pixel_6/pubsub/**")
# Presence of every producer on the bus, any category:
session.liveliness().declare_subscriber("rise/@v0/*/*/**", callback)
```

Note two Zenoh matching facts the specification calls out. A `*` matches exactly one chunk, so patterns
end in `**` wherever a multi-chunk `source_id` may follow; and wildcards never cross a verbatim chunk,
so `rise/**` matches nothing at all and RPC-tier tokens need a subscription spelling out `@rpc`. A
subscriber on `.../pubsub/*/**` also receives the source-level and legacy tokens, whose own wildcards
intersect `pubsub` — which is why a consumer classifies a token by its literal chunks rather than by
counting them.

## Requirements

- **JDK 25** — the Gradle daemon toolchain is pinned to 25 in `gradle/gradle-daemon-jvm.properties`.
  Android Studio's bundled JBR satisfies this; there is no system `java` on the PATH of the
  development machine.
- **Android SDK** with API 37 platform, path in `local.properties` (`sdk.dir`, git-ignored).
- **A physical device**, API 30+. GNSS and IMU are not usefully emulated.
- **A reachable Zenoh router**, e.g. from
  [keelson-router](https://github.com/RISE-Maritime/keelson-router). The app connects in `client`
  mode with multicast scouting disabled, so it will only ever talk to an endpoint you configured —
  scanning for routers offers what it finds, it never joins anything by itself.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

./gradlew :app:assembleDebug      # debug APK
./gradlew :app:testDebugUnitTest  # JVM unit tests
./gradlew :app:lintDebug          # Android lint
```

Those three tasks are exactly what CI runs (`.github/workflows/build.yml`), so a green local run and a
green pipeline mean the same thing.

The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`. It is ~102 MB because the Zenoh
Kotlin binding ships native `.so` libraries for every ABI; that is expected, not a packaging bug.

## Release build

```bash
./gradlew :app:assembleRelease
```

Version comes from `version.properties` at the repo root — `versionCode` and `versionName`, bumped by
hand. The repository has no tags and no remote to derive them from, and a version that changes as a
side effect of building makes "is this the same build?" unanswerable. A non-integer `versionCode`
fails the build rather than silently defaulting.

**Signing is optional and off by default.** With no credentials configured the build succeeds, warns,
and produces `app-release-unsigned.apk` — most developers here never need the key. To produce a signed
APK, create a keystore:

```bash
keytool -genkeypair -v -keystore ~/logline-release.jks -alias logline \
  -keyalg RSA -keysize 2048 -validity 10000
```

and give the build its location, either through `local.properties` (git-ignored, already holds
`sdk.dir`):

```properties
logline.keystore=/Users/you/logline-release.jks
logline.keystore.password=…
logline.key.alias=logline
logline.key.password=…
```

or the equivalent environment variables `LOGLINE_KEYSTORE`, `LOGLINE_KEYSTORE_PASSWORD`,
`LOGLINE_KEY_ALIAS`, `LOGLINE_KEY_PASSWORD`, which take precedence. Keep the keystore and its passwords
out of the repository; losing the key means the app id can never be upgraded in place again.

> A release APK is signed with a different key than the debug build, so it **cannot be installed over
> it** — `adb install` fails with a signature mismatch until you uninstall the debug build first, which
> also wipes its settings.

Minification is deliberately off. This is an in-house tool that is never published to a store, so there
is no size ceiling and no reason to obfuscate — readable stack traces are worth more. Enabling R8 would
also require hand-written keeps for protobuf-lite (which ships none and resolves generated fields
reflectively) and for the Zenoh JNI classes, and both of those fail at runtime rather than at build
time. The APK is mostly native libraries R8 cannot touch in any case.

## Run on a device

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n se.rise.logline/.MainActivity
adb logcat -s SensorPublisher:V
```

Out of the box the app connects to the **cloud router** at `tls/router.example.com:443`, so all the phone
needs is a network path of its own — Wi-Fi, or cellular on a device with a SIM. No `adb reverse`, no
router on your laptop. See [Router security](#router-security) for the credentials that endpoint
requires.

Pointing at a local router instead is one entry in the endpoint list — typed in, or picked from
**Scan for routers**. If that router runs on your laptop, use the laptop's LAN address:
`tcp/127.0.0.1:7447` points at the *phone's* loopback, and is only useful alongside
`adb reverse tcp:7447 tcp:7447`.

### Router endpoints and finding one

The endpoint list is **failover, not fan-out**: Zenoh tries the entries in order and the session
attaches to whichever answers first, so keep the list short and put the likeliest first. A dead entry
that is actively refused costs nothing, but one that silently drops packets costs up to ten seconds
before the next is tried.

**Scan for routers** sends a Zenoh scout on `224.0.0.224:7446` — configurable, because deployments move
it; one of the keelson routers uses `:7448`, and a scan on the wrong address is indistinguishable from
an empty network. Each answer is listed with its role, its Zenoh id and its locators; tapping **Add**
appends a locator to the list, and nothing is connected to until you save. Multicast has `ttl: 1`, so
this only ever finds routers on the same network segment.

A dockerised router usually cannot answer: on a Docker bridge network the scout port is not published,
and the locators it advertises are container addresses like `172.19.0.2` that the phone cannot reach.
Run the router with `network_mode: host` (or natively) for discovery to work.

**Android 17 gates the local network.** Local Network Protections make any LAN address — and the
multicast scan — a runtime permission, `ACCESS_LOCAL_NETWORK` ("find and connect to nearby devices").
The app asks for it on the first scan, and when a run starts with a LAN endpoint in the list; a
cloud-only setup never asks. Denied, the scan finds nothing and a LAN endpoint never connects, because
Android rejects the packets with `EPERM` before Zenoh sees a network at all.

## Router security

`router.example.com` is the shared fleet bus. It is fronted by a private CA (`minica`), and it **demands a
client certificate** — mutual TLS, not just server TLS. Three PEM files are therefore needed before a
`tls/` endpoint will connect:

| Credential | What it is |
| --- | --- |
| Root CA certificate | `minica.pem` — the private CA. The system trust store cannot verify this server. |
| Client certificate | This device's own certificate, e.g. `CN=phone-2-pixel-6` |
| Client key | The matching private key. This is what authenticates the phone to the fleet. |

Issue a per-device certificate with [`minica`](https://github.com/jsha/minica), from the directory
holding `minica-key.pem`:

```bash
minica --domains phone-2-pixel-6
```

Give each device its own, rather than copying one identity around: a certificate is how the bus tells
devices apart, and a per-device one can be dropped without re-issuing everyone else's.

Import all three under **Settings → Router security**. Each row shows what is loaded — `CN` and expiry
for the certificates — so an expired client certificate can be seen plainly instead of arriving later
as an opaque handshake failure.

**The credentials are never in the APK.** They are imported at runtime into app-private storage
(`filesDir/tls/`, mode `0600`), because a debug build gets passed around and this key opens the shared
bus. For the same reason `certificates/`, `*.pem`, `*.jks` and `*.keystore` are git-ignored — a key
that reaches the fleet must not reach the history.

**And they are excluded from Android's backups.** Auto-backup takes all of `filesDir` unless told
otherwise, so without a rule the client key would sit in a Google Drive backup and ride a
phone-to-phone transfer onto a device nobody enrolled. `res/xml/backup_rules.xml` (API 30) and
`res/xml/data_extraction_rules.xml` (API 31+, cloud backup *and* device transfer) exclude
`filesDir/tls` — along with `recordings` and the map tile cache, which are large enough to fail the
whole backup against its 25 MB quota. The settings themselves are still backed up on purpose. One
consequence worth knowing: **a restored or transferred phone has no credentials and must import its
own**, which is the intended shape — enrolment is per device — and it says so plainly on the first
start rather than failing obscurely.

Starting a `tls/` endpoint with a credential missing fails immediately and says which file is missing,
rather than hanging or failing obscurely later.

## Configure

The app has five tabs across the bottom — **Session**, **Live**, **Events**, **Files** and **Setup**.
Session is the dashboard and the start/stop control, Live is the map and the plots, Events is where a
moment is marked, Files is what has been recorded, and Setup holds everything that is configured
rather than operated: Settings, Platforms, the annotation buttons and (when enabled) Checklists.

Start and Stop sit pinned above the tab bar rather than in the scroll, because the page below them is
thirty-nine subjects long. The card at the top is the session summary — what the last run left behind
while nothing is running, what this one is doing while it is — with the endpoint and the file's own
figures one tap behind the row along its bottom edge.

Settings itself is six collapsible groups — General, Connection, Recording, Sensors & media,
Collaboration, Advanced — with only General open to start with. The longer explanations sit behind the
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

## Live view

The **Live** tab shows what is actually going on the bus, as opposed to how fast it is going: the last
GNSS fix on an OpenStreetMap background with the recent track drawn over it, and a sparkline per
subject for the last couple of minutes.

Under the position sits a one-line summary — fix kind, satellites, cellular SINR, charge — so the
question "is this run healthy" does not cost a scroll into three groups. The chips below it filter the
plots to one group when tapped. And a **Basic / All** control decides how much is plotted: Basic is
the eight subjects an operator watches (the fix, speed, course, true heading, horizontal accuracy, fix
quality, air pressure, charge) and All is every published series, with the number being held back
stated beside the control. Nothing is switched off by choosing Basic — every subject still publishes
and is still recorded.

It shows **only what was published** — it deliberately does not read the sensors itself. A second
location stream would double GNSS power draw and could disagree with the bus, which is the one thing
this view exists to rule out. With publishing stopped it shows whatever the last run left behind and
says so.

Some details that are load-bearing rather than cosmetic:

- **Vector subjects are plotted as magnitude.** Three overlaid axes are unreadable at card size, and
  magnitude is what answers "is this sensor sane". The full vector still goes on the wire untouched.
- **An absent bearing reads as "no bearing", not north.** The wire carries `0.0` when the fix has no
  bearing, which on a stationary phone is almost every fix; the view keeps the raw value so the map
  never draws a heading arrow that was never measured.
- **Sparklines are stride-sampled, not averaged**, when a window is wider than the canvas. Averaging
  smooths away exactly the spikes that make a sensor look wrong.
- **The map needs tiles.** osmdroid caches them under `filesDir`, so an area you have already looked at
  keeps rendering with no network; somewhere new with no signal renders the track on a blank grid.

The window is bounded in *samples*, not seconds (8192 per subject, ~2.7 minutes at 50 Hz), because
`Maximum` rate is a legal setting and a seconds-based window would not be bounded at 442 Hz.

Nothing about this touches the publish path beyond one lock and two array writes: the collectors append
to a ring, and the screen pulls a snapshot at 5 Hz. Measured with the view open, the IMU subjects still
publish at 55.3 Hz and 0.29% of frames were janky.

### The chart, and its layers

The live view opens on a 400dp chart with an **Expand** control that gives it the screen — reading a
chart and reading numbers are different jobs and neither wants half a display. The layer button offers:

| Layer | Source | Notes |
| --- | --- | --- |
| **Map** | OpenStreetMap standard | The default. |
| **Satellite** | Esri World Imagery | Global, no key. osmdroid's own `USGS_SAT` is the United States only and draws nothing over Sweden. |
| **Sea marks** | OpenSeaMap | An *overlay*, not a base layer — buoys, lights and seamarks drawn over whichever of the above is showing. |

**Attribution is drawn, and until now it was not.** `CopyrightOverlay` has to be added explicitly;
osmdroid does not draw the notice on its own, and this map never added one. It reads the current
source's notice, so it follows the layer — `© OpenStreetMap contributors` or `Esri, Maxar, Earthstar
Geographics`. Both licences require it.

> Esri's World Imagery is used without a key, as most open-source apps do, with the attribution their
> terms ask for. Esri's terms nominally expect an ArcGIS account for use in an application, so treat
> this as a pragmatic default rather than a settled licence — it is one constant in `TrackMap` to
> change if RISE would rather point at Lantmäteriet or its own imagery.

Note the Esri URL is `/tile/{z}/{y}/{x}` — **row before column**, unlike the `{z}/{x}/{y}` that
`XYTileSource` builds — which is why it is a custom source. Swap them and every tile still loads, from
the wrong place.

### Offline maps

The live view's map draws from OpenStreetMap over the network, which at sea is a blank grid. **Settings
→ Offline map** imports a tile archive — `.mbtiles`, `.gemf`, `.zip` or `.sqlite` — and the map draws
from it wherever it covers, with online tiles filling in the rest. **Offline tiles only** turns the
network off for the map entirely: out of coverage the downloader otherwise queues every tile the
archive does not cover and waits for each to time out.

**The app cannot fetch an area for you, and that is not an omission.** OpenStreetMap's tile usage
policy forbids bulk downloading, and osmdroid enforces it in code: `TileSourceFactory.MAPNIK` carries
`FLAG_NO_BULK`, so every `CacheManager` constructor throws `TileSourcePolicyException` for OSM tiles.
Prepare an archive ashore instead — MOBAC, QGIS or `tilemaker` all produce one — from a source that
permits it or from your own tile server.

An archive is also the better artefact than a warmed cache. osmdroid's tile cache is an LRU it trims at
600 MB, so tiles browsed into it can evaporate; an archive is a file and stays until it is removed. It
lives in app-private storage, is excluded from Android's backups like the recordings are, and competes
with them for the same volume — a 400 MB archive is 400 MB fewer of recording, which the main screen's
capacity line will show.

### Setting up a second phone

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
[`keelson-router/docker-compose.keelson-router-rise.yml`](../keelson-router/docker-compose.keelson-router-rise.yml):

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
`ssrs18` and `ssrs19` are one character apart, and a picture of the boat is not. The photo stays on
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
rise/@v0/ssrs18/pubsub/frame_transform/calibration
rise/@v0/ssrs18/pubsub/configuration_json/calibration
rise/@v0/ssrs18/pubsub/location_fix/calibration
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
in [docs/calibration.md](docs/calibration.md).

The third one is the platform's **zero point**, which anchors the transforms to the earth. It is stamped
with the time it was surveyed rather than the time it was published, sits on a different key from the
phone's own fix, and is called "Zero point" on screen — three separate reasons it cannot be read as
where the platform is *now*. It publishes only once a position has been captured or typed; a platform measured
entirely with a tape publishes its geometry and no position.

Full procedure, the frame conventions, a worked example and an honest account of what a phone fix can
and cannot measure: **[docs/calibration.md](docs/calibration.md)**.

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
lose one to a half-finished MediaStore entry. **A recording interrupted by a kill is repaired on the
next start**: the app trims it to the last complete record and appends a footer, because a file without
one has all its messages present and none of them reachable — readers seek to the footer first. The
repaired file has no statistics, so readers scan it; the messages are intact.

Compression costs something here, and it is bounded deliberately. A killed process loses whatever is
still buffered in the open chunk, where before it lost only a partial message — so chunks are flushed
at 256 kB **or after two seconds, whichever comes first**. The time bound is the important half: it
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

## Filling in a dropped link

A Zenoh `put` succeeds when there is no router — ~9000 of them landed on an empty bus during a measured
26 s outage — so an outage is invisible from the publish path. The app therefore holds the last couple
of minutes of samples and replays them when the link returns. On by default, switchable in Settings.

Measured on a real 40-second airplane-mode outage: a **44.4 s gap** in arrivals, then **11001 buffered
samples replayed at 377/s**, filling it completely. For one 12.5 Hz subject, 1398 samples were delivered
across a 110 s window in which ~1375 were expected — the hole closed, with a small overlap.

Three things a consumer should know:

- **Replayed samples keep their original `enclosed_at` but arrive after live data.** Anything ordering
  by arrival will see time jump backwards during a flush. The keelson MCAP replayer behaves the same
  way, so this is not a new shape on the bus, but it is worth designing for.
- **Expect a few seconds of duplicates.** Replay starts from the last poll that *saw* a router, not from
  when the drop was noticed — the two differ by up to a poll interval plus however long Zenoh took to
  tear the transport down. Overlapping is deliberate: a couple of seconds of duplicates beats losing the
  head of every outage, which is what any connection-gated buffer would do.
- **The flush is paced at roughly twice the production rate.** Every QoS profile is `DROP` and `put`
  reports success regardless, so an unpaced burst would be shed by the egress queue with no signal at
  all. Live traffic keeps flowing throughout.

The buffer holds 32768 entries — about 2.5 minutes at default rates, bounded in *entries* rather than
seconds because `Maximum` rate is legal and the gyroscope has been measured at 442 Hz. A longer outage
than that is still complete in the MCAP recording, but the bus cannot be filled in past the buffer, and
the main screen says so **while the outage is still going**: *Longer outage than the buffer holds — N
samples cannot be replayed*, with the same count as `Not replayed` beside `Replayed` in the details.
Without it a twenty-minute hole ended in "Replayed 32768 samples", which reads exactly like a run that
caught up.

Note this is not the same as the buffer's eviction count, which is ordinary turnover: the ring is full a
couple of minutes into every run and evicts on every sample from then on. What is reported is the part
of the *replay window* — the samples taken since the link was last known good — that no longer fits,
which stays at zero for any outage shorter than the whole buffer.

### The native path, for consumers that want it

Publishers are declared as Zenoh **advanced publishers** with a 4096-sample cache and a heartbeat, so a
consumer using an `AdvancedSubscriber` with `RecoveryConfig` can fetch what it missed directly, with no
republishing and therefore no duplicates or reordering. Nothing in keelson uses advanced subscribers
today — every connector calls plain `declare_subscriber` — so this benefits nobody yet, but it costs
almost nothing and is the correct mechanism when a consumer opts in. Plain subscribers are unaffected,
which was verified: all 25 channels at unchanged rates against advanced publishers.

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

## Verify it is working

From a machine with a Zenoh client, subscribe to everything the phone emits (locators use Zenoh's
`proto/host:port` form, not a URL). Against the cloud router you are a client of the same bus, and need
the same three credentials the phone does:

```bash
z_sub -e tls/router.example.com:443 -k 'rise/@v0/<entity_id>/pubsub/**' \
  --cfg='transport/link/tls/root_ca_certificate:"minica.pem"' \
  --cfg='transport/link/tls/enable_mtls:true' \
  --cfg='transport/link/tls/connect_certificate:"cert.pem"' \
  --cfg='transport/link/tls/connect_private_key:"key.pem"'
```

Those are the same four config keys `keelson-router` passes in its compose file, and the same ones the
app builds internally. Against a plain local router it is just `-e tcp/<router-host>:7447` with none of
them.

**Seeing nothing?** Check the key first. `rise/**` matches nothing — `@v0` is verbatim and no wildcard
crosses it, so the subscription must include it literally: `rise/@v0/**`. An empty subscriber is the
usual symptom, and it is indistinguishable from a publisher that is not running.

The app's main screen also shows a per-subject sample count and time since last publish, which is
the fastest way to tell "sensor not delivering" apart from "router not reachable".

It shows the router link explicitly, because the sample counters alone cannot: a Zenoh `put` on a
session that has lost its router still succeeds, so the counters keep climbing while nothing is being
delivered. The main screen therefore reports **Connected** or **Disconnected — samples are being
dropped**, and the ongoing notification appends `router unreachable`. Losing the link does not stop the
run: publishing continues, and Zenoh reconnects by itself once the router is reachable again, typically
within a few seconds. Samples produced during the outage are held and replayed once it returns — see
[Filling in a dropped link](#filling-in-a-dropped-link).

## Project layout

```
app/src/main/java/se/rise/logline/
  LoglineApp.kt            Application — process-scoped owner of the publisher and settings
  MainActivity.kt          Compose entry point + NavHost (main / live / annotations / calibration / qos / settings)
  config/                  Settings data class + DataStore repository
  keelson/                 Key expression builder, Envelope helper, Zenoh session wrapper
  sensors/                 ImuProvider and LocationProvider — callbackFlow over Android APIs
  publish/SensorPublisher.kt  Owns the session, the publishers, and the per-subject status
  publish/PublisherService.kt Foreground service — notification, wake lock, run lifetime
  checklist/               Shared checklists — own session, reducer, reminders (see Checklists)
  calibrate/               Platform geometry — model, geodesy, quaternions, platform-geometry JSON
  ui/                      MainScreen, LiveScreen, AnnotationScreen, SettingsScreen, theme
app/src/main/proto/        Vendored copies of Keelson protobuf definitions
art/                       Source artwork + the launcher-icon generator
```

`art/logline_icon.svg` is the source of the launcher icon. Android cannot use SVG, so
`art/svg_to_adaptive_icon.py` converts it into the two adaptive-icon VectorDrawables
(`ic_launcher_background.xml`, `ic_launcher_foreground.xml`) — edit the SVG and re-run the script
rather than hand-editing the XML.

See [docs/architecture.md](docs/architecture.md) for how these fit together, and
[CLAUDE.md](CLAUDE.md) for the working conventions.

## Protobuf definitions

`app/src/main/proto/` holds **verbatim copies** of files from the Keelson repo — they are vendored,
not authored here. Java/Kotlin lite bindings are generated at build time by the
`com.google.protobuf` Gradle plugin.

| Local path | Upstream source |
| --- | --- |
| `Envelope.proto` | `keelson/messages/Envelope.proto` |
| `Primitives.proto`, `Decomposed3DVector.proto` | `keelson/messages/payloads/` |
| `Checklist{Event,State,Presence,Procedure}.proto` | `keelson/messages/payloads/` |
| `foxglove/*.proto` | `keelson/messages/payloads/foxglove/` |

To pull in upstream changes, copy the files across and rebuild — never hand-edit them here.

## Known limitations

[TODO.md](TODO.md) tracks these alongside the rest of the pending work, prioritised. They are
deliberate omissions in the current state, not hidden bugs:

- **No RPC interface liveliness.** The app answers crowsnest's `get_config` probe but does not declare
  an interface-level token, because §3.6's full-interface rule would commit it to serving all of
  `configurable/v1`.
- **No instrumented tests.** 533 JVM tests cover the wire format, the registry, the units and the
  formatting; `app/src/androidTest` is empty, so nothing covers a screen.
- **`entity_health` is not published**, deliberately — upstream forbids a connector computing its own.
  The subject-level liveliness above is what lets an aggregator compute it instead.
- **Emulators are not usable.** GNSS, IMU and the camera all need a physical device.

## Related repositories

Sibling checkouts under `~/Documents/CODE/`, referenced throughout the docs:

- `keelson/` — protocol spec, `messages/subjects.yaml`, Python/JS SDKs, connectors
- `keelson-router/` — Zenoh router deployment (docker compose, TLS certs)
