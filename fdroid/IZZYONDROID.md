# Submitting this app to IzzyOnDroid

## Correction before anything else

`fdroid/GUIA-FDROID.md` (an earlier document in this repo) describes IzzyOnDroid's
submission process as a GitLab merge request against `gitlab.com/IzzySoft/fdroiddata`.
**That's stale.** IzzyOnDroid moved its whole infrastructure to Codeberg. The real
process below was re-verified from IzzyOnDroid's current docs, not carried over
from that earlier assumption.

## How IzzyOnDroid actually works (and why it's easier than official F-Droid)

IzzyOnDroid does **not** build this app from source. It distributes the exact APK
you already publish via `release-apk.yml`, pinned to your signing key. That means:

- No `srclibs`, no `prebuild:`, no `firestackRepo=local` workaround, no NDK
  fight on someone else's buildserver.
- Once accepted, their update checker watches this repo's GitHub Releases and
  picks up new tagged versions **within 24 hours of the APK being attached** --
  exactly what `release-apk.yml` already does on every `v*` tag push. Nothing
  further to automate here.
- They do still attempt a **reproducible build** later, as a trust signal shown
  next to the app (not a blocker for initial inclusion). If it doesn't reproduce
  bit-for-bit, the app stays listed -- it's just not marked "Reproducible".

## Two real blockers found while preparing this -- fix both before submitting

### 1. Hard 30MB per-APK size limit

IzzyOnDroid's own contributor guide: *"We cannot currently accept apk files over
30MB."* This isn't a soft guideline -- submissions over it are rejected outright.

**This already happened to the original Rethink app.** There's an existing,
still-open request for it at
[`IzzyOnDroid/repodata#45`](https://codeberg.org/IzzyOnDroid/repodata/issues/45),
rejected because its APK is 89.1MB. Worth reading before submitting this fork --
it's not a duplicate (different `applicationId`), but the same size problem could
easily apply here too, since this fork bundles firestack's native library and
usque's binary on top of everything upstream already has.

**Check your actual size before doing anything else:**

```bash
# after release-apk.yml has produced at least one real release:
curl -sL "https://github.com/paulvers-ui/rethink-app-masque/releases/latest" \
  -o /dev/null -w '%{size_download}\n' # rough check via redirect, or just:
ls -lh AuroraVPN-v*.apk AuroraVPN-v*-arm64-v8a.apk AuroraVPN-v*-armeabi-v7a.apk
```

Or simplest: open the release on GitHub and look at each asset's listed size.

**If the universal APK is over 30MB** (likely, given `libusque.so` alone is 13MB
plus `firestack.aar`'s native libraries), IzzyOnDroid's own documented remediation
is switching to a per-ABI submission instead of universal. `release-apk.yml`
already produces `AuroraVPN-*-arm64-v8a.apk` and
`AuroraVPN-*-armeabi-v7a.apk` as separate, smaller assets for exactly this
reason -- submit one of those instead of the universal one if it's under the
limit and the universal isn't.

### 2. Fastlane metadata still describes the original app, not this fork

IzzyOnDroid pulls `fastlane/metadata/android/*/` directly from this repo and shows
it to end users. Checked the current tree before writing this:

```
fastlane/metadata/android/en-US/title.txt            -> "Rethink: DNS + Firewall + VPN"
fastlane/metadata/android/en-US/short_description.txt -> upstream's original text
```

This is 100% inherited from `celzero/rethink-app`, untouched since the fork was
created. Every locale (35+ languages) has the same problem. Submitting as-is
means IzzyOnDroid would list this fork under the original app's name and
description -- confusing at best, and likely to draw a rejection or a request to
fix it first.

**Minimum fix before submitting:** update at least
`fastlane/metadata/android/en-US/title.txt` and `short_description.txt` to name
this fork correctly. Full translation of all 35+ locales isn't required for
submission, but the `en-US` one is what reviewers will actually look at.

```bash
echo "AuroraVPN: DNS + Firewall + VPN" > fastlane/metadata/android/en-US/title.txt
echo "Firewall, WARP over MASQUE, DNS + WireGuard proxy -- a Rethink fork." \
  > fastlane/metadata/android/en-US/short_description.txt
```

(Screenshots and `full_description.txt` describing the original app's features are
fine to leave as-is if the underlying functionality is still accurate -- it's the
*name* mismatch that's the immediate problem.)

## Step by step

### 1. Fix the two blockers above first

Confirm the APK you're about to submit is under 30MB, and that
`fastlane/metadata/android/en-US/title.txt` names this fork, not the original.

### 2. Get the signing key fingerprint

IzzyOnDroid pins your app's signing certificate the first time it's accepted
(`AllowedAPKSigningKeys`), so future updates signed with a different key get
rejected automatically -- protects users from a compromised release. You need
this fingerprint to include in the submission.

```bash
# against the actual APK you plan to submit, downloaded from your Release:
apksigner verify --print-certs AuroraVPN-v1.0.0.apk | grep SHA-256
```

Note the lowercase hex value -- that's what goes in the issue/metadata.

### 3. Notify the original Rethink authors

IzzyOnDroid's inclusion policy expects the original developer to be aware and
not opposed, same expectation as official F-Droid. Open an issue at
[`celzero/rethink-app`](https://github.com/celzero/rethink-app/issues) describing
this fork before submitting to IzzyOnDroid, and reference it in the submission.

### 4. File the inclusion request

Use the exact template IzzyOnDroid provides -- don't write a freeform issue:

**https://codeberg.org/IzzyOnDroid/repodata/issues/new?template=.forgejo%2fissue_template%2fapp-inclusion-request.yaml**

You'll need a Codeberg account (free, same idea as needing a GitLab account for
official F-Droid -- the code stays on GitHub either way).

Fields the template will ask for, based on the guidelines confirmed from an
existing accepted submission:

- Confirm you're the developer of this fork (you are -- `paulvers-ui`)
- Confirm the app complies with the
  [App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/)
- Confirm it isn't already listed (search first -- the original Rethink is
  listed as a *pending request*, #45 above, not yet included; this fork has a
  different `applicationId` regardless)
- Confirm the Fastlane folder is present (it is, once step 1's fix lands)
- Link to the GitHub repo: `https://github.com/paulvers-ui/rethink-app-masque`
- Link to the latest Release with the APK attached
- The `AllowedAPKSigningKeys` fingerprint from step 2
- What this fork changes from the original (same content as `Description:` in
  `fdroid/com.arcadesignpro.auroravpn.yml` -- own package ID, WARP over MASQUE via
  usque, single Dark Plus theme, network status sound alerts, no Firebase in
  this variant, own sponsor link, Cloudflare fallback DNS)
- Link to the notification issue from step 3

### 5. Wait for review

No fixed SLA -- reviewed by a person. If the APK is confirmed under 30MB and the
Fastlane metadata is fixed, the two concrete blockers found here are cleared;
what's left is ordinary review time.

### 6. After acceptance -- nothing to automate

Once listed, IzzyOnDroid's update checker watches this repo's GitHub Releases on
its own schedule and picks up new tagged versions with an APK attached within
24 hours. `release-apk.yml` already produces exactly that on every `v*` tag push.
No new workflow, no new step, no change to how releases get published --
this document only covers the one-time submission.
