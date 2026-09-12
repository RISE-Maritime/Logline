# Logline — installing and setting up

From an empty phone to one that is publishing and recording. Nothing here needs the source code or a
build: the app arrives as a file, and everything after that is done on the phone.

If you were handed a phone that somebody has already set up, you want
[using it](user-guide.md) instead. If you are building and releasing the app, that is
[building and releasing](deploying.md).

- [What you need](#what-you-need)
- [Get the APK](#get-the-apk)
- [Install it](#install-it)
- [First open](#first-open)
- [Point it at a router](#point-it-at-a-router)
  - [A router of your own](#a-router-of-your-own)
  - [A shared fleet bus](#a-shared-fleet-bus)
- [Give the phone an identity](#give-the-phone-an-identity)
- [The quick way: a profile or a QR](#the-quick-way-a-profile-or-a-qr)
- [Prove it works](#prove-it-works)
- [Worth setting up next](#worth-setting-up-next)
- [Updating later](#updating-later)
- [When it does not work](#when-it-does-not-work)

---

## What you need

| | |
| --- | --- |
| A phone | Android 11 or newer. GNSS, IMU and the camera are real hardware, so an emulator will not do. |
| About 250 MB free | The APK is around 100 MB and the app unpacks native libraries beside it. Recordings need much more — see [recording](recording.md). |
| A Zenoh router | Either one you run yourself, or a shared bus somebody gives you an address and certificates for. Both are covered below. |

A phone with no router still works: it records to a file and publishes nothing. That is a supported
way to use it, not a broken setup — see the `PUB` and `REC` chips on the Session tab.

## Get the APK

Three ways in, and the first is the one to use unless somebody told you otherwise.

**The current build of `main`** — one link that always has the newest APK:

```
https://github.com/RISE-Maritime/Logline/releases/download/main-latest/Logline-main-latest.apk
```

It is replaced on every change and signed with the same key a release is, so it installs straight over
one. Open that link in the phone's browser.

**A numbered version** — the [Releases](https://github.com/RISE-Maritime/Logline/releases) page. Each
carries `Logline-<version>.apk` and the changelog for what is in it. Use one of these when you need to
know exactly which build a fleet is on.

**A file somebody sent you.** Drive, email, a cable, a memory stick — nothing about the app cares how
it arrived.

## Install it

Tap the downloaded file. Android will ask whether to allow installing apps from wherever it came from
— the browser, Files, Drive. That prompt is expected and is answered once per source, not once per
install.

Over a cable instead:

```bash
adb install -r Logline-main-latest.apk
```

`-r` keeps existing data. If it fails with a signature mismatch, the phone is holding a build signed
with a different key — most often a debug build. That needs an uninstall first, which erases the app's
private storage including any certificates. Read [updating later](#updating-later) before doing it.

## First open

The app opens on the **Session** tab with five tabs along the bottom: Session, Live, Events, Files and
Setup. The top bar carries two lamps, `PUB` and `REC`, which say whether this phone is publishing and
whether it is writing a file.

**It will not connect to anything yet, and that is correct.** The shipped default endpoint is
`tcp/127.0.0.1:7447`, the phone's own loopback, which deliberately names nobody's infrastructure. Until
you point it somewhere, `PUB` stays grey.

Everything configured rather than operated lives under **Setup**.

## Point it at a router

Pick one of the two. The first needs nothing from anybody.

### A router of your own

No credentials, no certificates. On a machine on the same network as the phone:

```bash
docker run --rm --network host eclipse/zenoh:1.9.0 \
  --listen tcp/[::]:7447 --cfg='mode:"router"'
```

Then find that machine's address on the network — `ipconfig getifaddr en0` on macOS,
`hostname -I` on Linux — and on the phone go to **Setup → This phone → Settings → Router endpoints**
and add:

```
tcp/192.168.1.42:7447
```

with your own address. Or tap **Scan for routers**, which sends a multicast scout and lists what
answers, and add the one you recognise.

Two things about local networks specifically:

- **Android will ask for permission to find and connect to nearby devices.** Grant it. Without it the
  scan finds nothing and a LAN endpoint silently never connects, because Android rejects the packets
  before Zenoh sees a network at all.
- **`tcp/127.0.0.1:7447` is the phone's own loopback, not your laptop's.** To reach a router on the
  machine the phone is cabled to, use its LAN address, or forward the port with
  `adb reverse tcp:7447 tcp:7447`.
- **A router inside Docker without `--network host` usually cannot be found by the scan**, because it
  advertises container addresses the phone cannot reach.

### A shared fleet bus

A `tls/` or `quic/` endpoint means mutual TLS, and the phone needs three files before it will connect
at all. Whoever runs the bus issues them per device.

Go to **Setup → This phone → Settings → Router security** and import:

| | |
| --- | --- |
| Root CA certificate | The private CA the bus is fronted by. The system trust store cannot verify it. |
| Client certificate | This device's own, e.g. `CN=phone-2-pixel-6` |
| Client key | The matching private key. This is what authenticates the phone to the fleet. |

Each row then shows what is loaded, with its `CN` and expiry, so an import that did not work is
visible rather than turning up later as an opaque handshake failure. Add the endpoint under **Router
endpoints** in the usual `tls/host:port` form.

Skip the certificates and a `tls/` endpoint refuses to start and names the file it wants — it does not
fail quietly.

Give each device its own certificate rather than copying one identity around: a certificate is how the
bus tells devices apart, and a per-device one can be revoked without re-issuing everyone else's. More
detail in [connecting to a router](connecting.md).

## Give the phone an identity

**Setup → This phone → Settings → Identity → Entity ID.**

This names the phone's data on the bus — it is the `{entity_id}` chunk of every key it publishes on.
Give each phone something that identifies it: the vessel, or the phone itself.

**Do this even if you imported everything else.** A settings profile deliberately does not carry the
entity id, and that is the whole reason this is its own step: two phones sharing one publish onto
byte-identical keys, and their samples interleave on the bus with nothing to tell them apart.

**Source IDs** in the same screen name *which producer* on this phone a reading came from. The defaults
are fine for a single phone; change them only if you have a reason.

## The quick way: a profile or a QR

Setting up a second phone the same way as the first does not mean retyping any of it.

**A settings profile** — *Settings → Configuration → Export…* on the phone that is already right, then
*Import…* on the new one. It carries the realm, endpoints, rates, per-subject switches, QoS overrides
and the tag vocabulary. It does **not** carry the entity id, and by default it does not carry the
MapTiler key or the operator identity either — those are a tick at export time, because the file lands
in shared storage and is the one people forward.

**A QR code** — *Settings → Configuration → Show QR* on one phone, *Scan QR* on the other. Carries the
connection settings alone: realm, endpoints, source ids. Quicker in the field, less complete.

Neither carries the certificates. Those are per device and are always imported by hand.

## Prove it works

Press **Start** on the Session tab, with both the `PUB` and `REC` chips on.

Answer the permission prompts as they come. Location is the one that matters — granted, the run is a
`location` foreground service and everything works backgrounded; denied, the run still happens but
publishes only the IMU subjects. You will also be asked once about battery optimisation, which is
worth allowing for a long unattended run.

Then check, in order:

1. **The top bar** — `PUB` goes green when the session is attached to a router, and `REC` blinks while
   a file is being written.
2. **The Session card** — a live sample count, the file name, and where it will be saved.
3. **The Live tab** — the chart, and a position once the phone has a fix. Indoors it may say `No fix`
   while still showing a position, which is honest: that is the GNSS receiver saying it is not solving
   while a fused position from wifi and cell keeps arriving.
4. **Stop, then the Files tab** — the recording should be listed with its size and duration.

That last step is the whole system proven end to end. If you want to confirm the data is really on the
bus rather than only in the file, [connecting to a router](connecting.md#verify-it-is-working) has a
`z_sub` command that subscribes to everything the phone emits.

## Worth setting up next

None of these is required, and all of them are under **Setup**.

| | |
| --- | --- |
| **Recording folder** | *Settings → Recording*. Default is `Downloads/Logline`; any folder the picker offers works, including a card or a synced one. |
| **Satellite imagery** | *Settings → Satellite imagery*. Without a key the chart uses Esri, which covers the globe but coarsens away from cities. A free MapTiler key gets finer imagery and one zoom level more. |
| **Offline maps** | *Settings → Offline map*. Import an `.mbtiles` archive for water with no signal. |
| **A platform** | *Setup → Platforms*. Where the sensors are on the vessel, so the geometry goes on the bus with the data. Its own guide: [calibration](calibration.md). |
| **Start on boot** | *Settings → Background running*. For a phone left aboard. |
| **Checklists** | *Settings*, then the Collaboration section on Setup. Shared with other stations. |

## Updating later

With the same signing key — which every build from the link above has — an update is just installing
the new APK over the old one. Settings, certificates, recordings and the entity id all survive.

Two things to know:

- **A release APK cannot install over a debug build**, and the reverse. Different keys. Moving a phone
  between them needs an uninstall, and **uninstalling erases the app's private storage** — the
  certificates go with it and have to be imported again. Export a settings profile first.
- **Recordings already copied to `Downloads/Logline` survive an uninstall**, because they are in
  shared storage. They may stop appearing *in the app's own Files list*, because Android ties a file
  to the install that wrote it; the list offers to reach them again by granting access to the folder.

## When it does not work

| What you see | What it usually is |
| --- | --- |
| `PUB` stays grey | No router. The default endpoint is loopback — until you add a real one, there is nothing to attach to. |
| A `tls/` endpoint never starts, naming a file | The certificates are not imported. *Settings → Router security*. |
| **Scan for routers** finds nothing | The nearby-devices permission was denied, or the router is in Docker without `--network host`. |
| A LAN endpoint never connects, no error | Same permission. `appops get se.rise.logline` showing `ACCESS_LOCAL_NETWORK` rejections is the honest signal. |
| The map is a blank grid | No network for tiles, and no offline archive imported. |
| `No fix` while a position shows | Not a fault. The receiver is not solving; the position is fused from wifi and cell. |
| Four GNSS rows all say `Waiting` | Location permission denied, or location switched off for the whole phone. The row says which. |
| The newest recording is missing from Files | A run in progress is not listed until it closes. One interrupted by a flat battery is rescued when the app is next opened, and arrives labelled "incomplete, never closed". |
| The install fails on a signature mismatch | The phone holds a build signed with a different key. See [updating later](#updating-later). |

Deeper detail on any of this: [connecting to a router](connecting.md) for endpoints and credentials,
[settings](settings.md) for every control, [using it](user-guide.md) for the run itself.
