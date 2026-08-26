# Keys and liveliness

Where a sample lands in the key space, and how a consumer knows this phone is there at all.

- [Key expressions](#key-expressions)
- [Liveliness](#liveliness)

---

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

---

[← Back to the README](../README.md)
