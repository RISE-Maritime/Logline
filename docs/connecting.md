# Connecting to a router

Which router the phone talks to, the credentials the shared bus demands, what happens when the
link drops, and how to prove from a laptop that any of it is working.

- [Router endpoints, and finding one](#router-endpoints-and-finding-one)
- [Router security](#router-security)
- [Filling in a dropped link](#filling-in-a-dropped-link)
- [Verify it is working](#verify-it-is-working)

---

## Router endpoints, and finding one

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

A shared fleet bus is typically fronted by a private CA (`minica`) and **demands a client
certificate** — mutual TLS, not just server TLS. Three PEM files are therefore needed before a
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

## Verify it is working

From a machine with a Zenoh client, subscribe to everything the phone emits (locators use Zenoh's
`proto/host:port` form, not a URL). Against a shared `tls/` bus you are a client of the same bus, and
need the same three credentials the phone does:

```bash
z_sub -e tls/<router-host>:443 -k 'rise/@v0/<entity_id>/pubsub/**' \
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

---

[← Back to the README](../README.md)
