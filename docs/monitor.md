# The Monitor tab

The Monitor tab watches **another entity** on the bus, not this phone. It shows that entity's data as
a board of cards you choose, in the spirit of panels in Foxglove Studio. The first catalogue ports
the panels from `foxglove-custom-panels`, plus a generic Plot card and a Value card.

It is for keeping an eye on a vessel from shore or from another deck, and for debugging a
replay. The default entity is `case`, which is what the debugging replay publishes as.

## Where the data comes from

The data comes from the router's **REST plugin, as Server-Sent Events**. It does not come from a
Zenoh subscriber.

The app cannot hold a Zenoh subscriber. On the Zenoh binding this app uses, every sample the router
timestamps aborts the process in native code. Routers timestamp every sample they forward by
default. `keelson/ZenohBinding.kt` has the diagnosis, and upstream tracks it as
eclipse-zenoh/zenoh-flat-jni#49.

The REST plugin serves the same key expressions over HTTP. A `GET` with
`Accept: text/event-stream` on `{realm}/@v0/{entity}/pubsub/**` is a subscription, delivered one
event per sample:

```
event: PUT
data: {"key":"rise/@v0/case/pubsub/heading_true_north_deg/gnss","value":"<base64 envelope>","encoding":"zenoh/bytes","timestamp":"…"}
```

`value` is base64 of the usual `core.Envelope`. The app unwraps it and decodes the payload by the
subject's declared type in `subjects.yaml`, transcribed to `monitor/RemoteSubjectTypes.kt`. A subject
whose type the app has not vendored is listed as *seen*, never guessed at.

**The router's address is derived, not asked for.** If the Router REST URL setting is blank, the app
uses the host of the first configured router endpoint on port 8000. The rise router already
publishes that port (`--rest-http-port=8000`). Set the URL explicitly when the REST plugin sits
somewhere else.

**The stream is plain HTTP and unauthenticated.** The mTLS credentials protect the Zenoh link only.
Anyone on the path can read what the tab is reading. The app permits cleartext HTTP for this reason
(`res/xml/network_security_config.xml`). Putting TLS in front of `:8000` is an open item.

A router on a LAN address needs Android 17's `ACCESS_LOCAL_NETWORK` permission. The app asks for it
when the tab opens.

The stream runs **only while the tab is on screen**. It is one long-lived GET carrying everything the
entity publishes, and holding it from another tab would spend radio on cards nobody can see. What has
arrived stays in memory across tab switches for the same entity and is cleared when the entity
changes. Watching never touches a run: the settings go through `update()`, not `saveSettings()`, so no
change here restarts publishing or recording.

## Cards

Each card has a gear that opens its settings. The same sheet also moves the card up or down, or
removes it. Settings are drafted in the sheet and saved when it closes. The subject pickers offer
what the entity has actually published, and they stay editable, so you can set a card up before a
vessel starts sending.

| Card | Reads | Settings |
| --- | --- | --- |
| Plot | 1–3 numeric subjects | subject and source per series, window (60 s), y-min/y-max (auto) |
| Value | one numeric subject | subject, source, decimals |
| Heading | `heading_true_north_deg`, COG, `yaw_rate_degps`, `yaw_deg` as fallback | heading-up/course-up, angle smoothing, ROT smoothing |
| Rudder | `rudder_angle_deg` | unit (slot), max angle |
| Engine | `engine_rate_rpm`, `propeller_pitch_pct` | unit, bar shows pitch/RPM, max RPM |
| Bow thruster | a configurable subject | subject, unit |
| Wind | apparent wind angle and speed | trail length |
| Pitch & roll | `pitch_deg`, `roll_deg` | window, pitch and roll advice |
| Ship velocity | heading, COG, SOG, `sway_velocity_mps`, yaw rate | LOA, prediction |
| IMU | `linear_acceleration_mpss`, `angular_velocity_radps` | gravity mode, mounting, peak window, sparkline, G-G |
| Chart | `location_fix`, heading, COG, SOG | follow, track length |

Every card also has a **stale after** time, 5 s by default. A reading older than that is dimmed and
shows its age. A subject that has never arrived reads `—`, never zero.

**Inputs resolve by subject.** A source left on *Auto* reads the first source publishing the subject,
in sorted order. On a per-unit card, the slot picks the source whose last chunk names that unit: a
number, or `port`/`ps`/`babord` for 0 and `starboard`/`stbd`/`sb`/`styrbord` for 1. That is the
Foxglove panels' own rule. A **pinned source that is not on the bus stays the answer**. Reading a
different source while the pinned one is quiet would put another sensor's numbers under the name
you chose.

Five things differ from the Foxglove panels, on purpose:

- **Bow thruster**: the panel reads `thruster_power_pct`, which is not in keelson's `subjects.yaml`,
  so no conforming publisher will ever match it. The card's subject is a setting for that reason, and
  the card says so when it points at an undeclared name.
- **Chart**: this is the Live tab's own chart. Its course vector is a fixed length, and it states a
  direction, not a prediction, so the panel's *vector minutes* setting is left out rather than
  ignored. There are no AIS targets yet.
- **Plot**: series are binned into pixel columns *by time*, because they can arrive at different
  rates. A pause longer than four sample intervals, and at least 2 s, is drawn as a gap.
- **Ship velocity**: bow and stern sway are always derived. With no sway subject they are estimated
  from drift and rate of turn, and the card says which.
- **IMU**: gravity is detected in `auto` from the mean magnitude over the last two seconds. The
  gravity vector is that two-second mean, where the panel uses a two-second low-pass filter.

## Code

| File | Contents |
| --- | --- |
| `monitor/MonitorWire.kt` | the SSE parser, the REST event decoder, the URL and key expression |
| `monitor/MonitorSync.kt` | the stream's lifetime and link state; `MonitorSource` for a future Zenoh one |
| `monitor/MonitorStore.kt` | the samples, held in the Live store's own rings and pulled at 5 Hz, never pushed |
| `monitor/MonitorCards.kt` | card kinds and their settings, declared as `ParamSpec` data |
| `monitor/MonitorInputs.kt`, `MonitorResolve.kt` | what each card reads, and how an input finds a topic |
| `monitor/ShipMotion.kt`, `ImuMath.kt`, `MonitorPlotMath.kt` | the derivations, pure and unit-tested |
| `ui/monitor/` | the screen, the settings sheet and the card bodies |

The cards persist one JSON document per DataStore key (`monitor_card_{i}`), the same way the platform
library does. A settings profile carries the entity, realm, URL and cards.
