# Logline — building and releasing

Producing an APK and getting it to where somebody can install it. No app store, no server to run.

**Installing and setting a phone up is [its own guide](install.md)** — the download link, the
certificates, the entity id and the first run. This page stops at the point an APK exists and is
reachable; that one starts there. They were one page once, and it meant whoever was provisioning a
phone had to read past a keystore they would never touch.

- [Build a release APK](#build-a-release-apk)
- [Getting it onto phones](#getting-it-onto-phones)

---

## Build a release APK

```bash
./gradlew :app:assembleRelease
```

`versionName` comes from `version.properties` at the repo root and is bumped by hand.
`versionCode` is **derived**: `versionCodeBase` from that file plus the number of commits, so it is
the same number on a laptop and in CI, always increases, and identifies the commit an APK was built
from. A shallow clone fails the build rather than guessing — see the comments in `version.properties`
for why that has to be loud.

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

### The newest build of main

Every push to `main` replaces one rolling prerelease with a freshly signed APK, so there is always a
current build behind a link that does not change:

```
https://github.com/RISE-Maritime/Logline/releases/download/main-latest/Logline-main-latest.apk
```

It is signed with the same production key a release is, so it installs straight over a tagged
release, and over an earlier rolling build, keeping the settings, the certificates and the
recordings. It is marked as a prerelease and never shows up as "Latest" — the Releases list is for
versions.

One consequence worth knowing before it surprises somebody: the rolling build's `versionCode` is the
commit count, so it is **higher than any earlier tagged release**. A phone on the rolling build
cannot step back to an older version without an uninstall, and an uninstall wipes the certificates
and the entity id.

### A version: tag it

Tagging builds a signed APK, attaches it to a Release, and uses the matching `CHANGELOG.md` section
as the release notes. It is three steps rather than one, and both of the extra ones are checked by
CI rather than trusted:

```bash
# 1. bump versionName in version.properties
# 2. add a `## 1.1 — YYYY-MM-DD` section to CHANGELOG.md
git commit -am "Release 1.1"
git push
git tag v1.1 && git push origin v1.1
```

**The tag must equal `versionName` with the `v` stripped.** A tag is a claim and the file is the
fact; when they disagree, the APK inside a release named `v1.1` reports `1.0` on every phone that
installs it and nothing on the release page says so. The workflow fails in ten seconds rather than
publishing that.

**`CHANGELOG.md` must have a matching `## <version> — <date>` heading.** An empty extraction fails
the release too, because a forgotten changelog entry is the kind of thing nobody notices until the
release is public.

The signing key reaches CI as repository secrets — `LOGLINE_KEYSTORE_BASE64` (the `.jks`
base64-encoded), `LOGLINE_KEYSTORE_PASSWORD`, `LOGLINE_KEY_ALIAS`, `LOGLINE_KEY_PASSWORD` — so
it stays out of the repository even when that repository is public.

```bash
base64 -i ~/logline-release.jks | pbcopy    # paste into the secret
```

### The no-setup way: send the file

`app/build/outputs/apk/release/app-release.apk` is an ordinary file. Drive, email, a USB cable,
a memory stick — all fine. Nothing about the app cares how it arrived. CI renames it to
`Logline-<version>.apk` on a release, so what somebody finds in a Downloads folder six months later
says which one it is.

---

From here on it is the phone's turn: installing the file, importing certificates, naming the phone and
proving a run works end to end. That is all in **[installing and setting up](install.md)**.
