# Logline — putting it on a phone

Internal distribution. No app store, no server to run.

There are three separate jobs here and it helps to keep them apart: **building** an APK,
**getting it onto a phone**, and **provisioning** that phone so it knows which bus it is on.
The third is the one people forget, and it is the one that decides whether a fresh install is
useful or just installed.

- [Build a release APK](#build-a-release-apk)
- [Getting it onto phones](#getting-it-onto-phones)
- [Provisioning a phone](#provisioning-a-phone)
- [Updating a phone later](#updating-a-phone-later)

---

## Build a release APK

```bash
./gradlew :app:assembleRelease
```

The version comes from `version.properties` at the repo root and is bumped by hand.

**Without a signing key this still works** — it warns and produces `app-release-unsigned.apk`,
which is fine for trying something out. For anything you hand to another person, sign it.

### Why the signing key matters more than it looks

Android identifies an app by its package name **and its signature**. Same signature, an update
installs straight over the old one and everything is kept. Different signature, Android refuses
the upgrade, and the only way forward is to uninstall first.

**Uninstalling wipes the app's private storage** — which is where the mTLS certificates live.
So an unsigned or re-signed build costs you, on every phone, every time:

- the three PEM certificates, re-imported
- the settings profile, re-imported
- the entity id, re-typed by hand

That is the whole argument for doing the next bit once.

### Make the key, once

```bash
keytool -genkeypair -v -keystore ~/logline-release.jks -alias logline \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then point the build at it through `local.properties` (git-ignored):

```properties
logline.keystore=/Users/you/logline-release.jks
logline.keystore.password=…
logline.key.alias=logline
logline.key.password=…
```

or the environment variables `LOGLINE_KEYSTORE`, `LOGLINE_KEYSTORE_PASSWORD`,
`LOGLINE_KEY_ALIAS`, `LOGLINE_KEY_PASSWORD`, which win over the file.

> **Back the keystore up somewhere that is not one laptop, and keep the passwords with it.**
> Lose it and this app id can never be updated in place again — every phone in the fleet needs
> an uninstall and a full re-provision, for good.

The keystore never goes in the repository. Neither do the passwords.

---

## Getting it onto phones

### The simple way: a GitHub Release

The repository already builds on every push. Tagging a version builds a signed APK and attaches
it to a Release, so a phone installs from a link and there is nothing to host and no file to
chase:

```bash
git tag v1.0
git push --tags
```

The signing key reaches CI as repository secrets — `LOGLINE_KEYSTORE_BASE64` (the `.jks`
base64-encoded), `LOGLINE_KEYSTORE_PASSWORD`, `LOGLINE_KEY_ALIAS`, `LOGLINE_KEY_PASSWORD` — so
it stays out of the repository even when that repository is public.

```bash
base64 -i ~/logline-release.jks | pbcopy    # paste into the secret
```

### The no-setup way: send the file

`app/build/outputs/apk/release/app-release.apk` is an ordinary file. Drive, email, a USB cable,
a memory stick — all fine. Nothing about the app cares how it arrived.

### On the phone

Android will ask to allow installing from wherever the file came from — the browser, Files,
Drive. That prompt is expected and only has to be answered once per source.

To install over a cable instead:

```bash
adb install -r app-release.apk
```

`-r` keeps the existing data. It fails with a signature mismatch if the APK was signed with a
different key than the one already installed — see above.

---

## Provisioning a phone

In this order. Steps 2 and 3 are what make the phone a member of the fleet rather than an app
that happens to be installed.

**1. Install and open it.**

**2. Import the certificates** — *Setup → This phone → Settings → **Router security***.
Three files: the root CA, the client certificate, the client key. Each row shows what is loaded,
with its `CN` and expiry, so you can see the import worked. Skip this and a `tls/` endpoint
refuses to start and names the file it wants.

**3. Import the settings** — *Setup → This phone → Settings → **Configuration** → Import…*
This carries the realm, endpoints, rates, per-subject switches, QoS overrides, tag vocabulary
and the MapTiler key. Export one from a phone that is already set up the way you want.

*Or* use **Show QR** / **Scan QR** between two phones, which carries the connection settings
only — realm, endpoints, source ids. Quicker in the field, less complete.

**4. Set the entity id by hand** — *Settings → **Identity** → Entity ID*.

This is deliberately **not** carried by a settings profile. The entity id is what names this
phone's data on the bus; if a profile copied it, two phones would publish onto the same keys and
overwrite each other. Give each phone something that identifies it — the vessel, or the phone.

**5. Start a run once.** Answer the permission prompts and the battery-optimisation question.
Then check the **Files** tab shows the recording. That is the whole system proven end to end.

---

## Updating a phone later

With the same signing key, an update is just installing the new APK over the old one. Settings,
certificates, recordings and the entity id all survive.

Two things to know:

- **A release APK cannot install over a debug build**, and vice versa — different keys. Moving
  a phone from a development build to a released one needs an uninstall, so provision it again
  afterwards.
- **Recordings already copied to `Downloads/Logline` survive an uninstall**, because they are in
  shared storage rather than the app's own. They may stop appearing *in the app's Files list*
  though: Android ties a file to the install that wrote it. The list offers to reach them again
  by granting access to the folder.
