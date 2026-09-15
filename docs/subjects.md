# What Logline publishes

Every subject the phone puts on the bus, the Android API behind it, and — for the ones where
the obvious reading is wrong — what the number actually means.

- [Audio](#audio)
- [Camera](#camera)
- [Annotations](#annotations)
- [Which way is +X](#which-way-is-x)
- [The raw sentences](#the-raw-sentences)
- [How good is the fix](#how-good-is-the-fix)
- [Which altitude](#which-altitude)
- [Roll, pitch and yaw](#roll-pitch-and-yaw)
- [IMU temperature, and the sensor Android has no name for](#imu-temperature-and-the-sensor-android-has-no-name-for)
- [The compass](#the-compass)
- [The radio subjects are keyed by link, not by name](#the-radio-subjects-are-keyed-by-link-not-by-name)
- [Serving cell identity, and why it is the one part that needs location](#serving-cell-identity-and-why-it-is-the-one-part-that-needs-location)
- [Uptime, and why a recording carries it](#uptime-and-why-a-recording-carries-it)
- [Speed and course are always published](#speed-and-course-are-always-published)

---

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

## Audio

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

## Camera

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

## Annotations

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

### In Foxglove

The [Log panel](https://docs.foxglove.dev/docs/visualization/panels/log) accepts `foxglove.Log`
directly. Point it at the `log_message` topic, live through `keelson2foxglove` or from a recording, and
the marks arrive as rows with the severity filter and one namespace checkbox per category. No connector
change was needed for this: `foxglove-liveview` resolves a subject's schema from `subjects.yaml`, and
`log_message` has been there all along.

Foxglove's **Events** — the coloured marks above the playback bar — are a different mechanism. They
belong to a device and a time range and are created in the app or through the REST API; nothing ingests
a topic into them. Turning these annotations into Events therefore needs a post-processing step against
a finished recording, which this app does not do.

## Which way is +X

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

## The raw sentences

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

## How good is the fix

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

## Which altitude

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

## Roll, pitch and yaw

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

## IMU temperature, and the sensor Android has no name for

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

## The compass

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

## The radio subjects are keyed by link, not by name

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

**The poll rate is not the measurement rate.** The cellular quality and identity subjects are read
from the modem's cache, which it refreshes when it chooses — on one 5 h 53 min run at sea,
`SignalStrength` refreshed only 166 times, about once every two minutes. Each poll republishes the
cached value **with the modem's own report time**, converted once per report, so consecutive messages
with the same payload timestamp mean "no new report". Deduplicate on that timestamp to get the real
measurements, and expect a held value to be minutes old.

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

## Serving cell identity, and why it is the one part that needs location

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

## Uptime, and why a recording carries it

`device_uptime_duration` is `SystemClock.elapsedRealtime()` on the battery poll — the phone's time
since boot, deep sleep included, because the phone was up and merely asleep.

It earns its place months later. A gap in a recording has two explanations that are indistinguishable
from the data — the app was stopped and started, or the phone went down and came back — and for an
unattended platform they mean very different things. Uptime resetting across the gap says which.

## Speed and course are always published

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

---

[← Back to the README](../README.md)
