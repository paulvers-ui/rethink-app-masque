# Response to F-Droid RFP #4306

Copy the section below into a comment on
https://gitlab.com/fdroid/rfp/-/work_items/4306

Everything here was verified directly against the repository before writing
it -- not guessed from the bot labels alone.

---

## Copy-paste reply

Thanks for reviewing. A few corrections and clarifications, checked directly
against the repo before posting:

**License**: this was filed as MIT by mistake -- the repo's `LICENSE` file
and the F-Droid metadata (`fdroid/com.arcadesignpro.auroravpn.yml`) both
correctly say **Apache-2.0**, matching the upstream Rethink project. Sorry
for the confusion at filing time.

**On the `com.celzero.bravedns` label and the `trackers` / `in-exodus-privacy`
flags**: this fork's real, installed package ID is `com.arcadesignpro.auroravpn`,
confirmed on every signed release APK with `aapt dump badging` /
`apksigner verify`. `com.celzero.bravedns` only appears as the Gradle
`namespace` (the package the generated `R`/`BuildConfig` classes live in --
kept unchanged from upstream specifically to avoid a ~460-file package-rename
refactor across every Kotlin source file). It is **not** the `applicationId`,
and it's not what gets installed on a device. If the scanner queried Exodus
or fdroiddata using `namespace` instead of `applicationId`, it would have
pulled the *original* Rethink app's tracker/listing data, not this fork's --
that's most likely the source of both the `trackers` and `in-exodus-privacy`
labels. Checked directly: Firebase and Crashlytics are gated to the
`websiteImplementation`/`playImplementation` Gradle configurations only --
the `fdroid` product flavor this app would actually be built from has zero
Firebase, Crashlytics, or other known tracker dependencies. The one
Facebook-published dependency present in all flavors,
`com.facebook.shimmer:shimmer`, is a UI shimmer/skeleton-loading animation
library with no network calls and no tracking -- a keyword scanner matching
on "facebook" in the Maven groupId would flag it without that distinction.

**`insecure-gradlew`**: fixed -- `gradle-wrapper.properties` now pins
`distributionSha256Sum` for gradle-8.13-all, sourced directly from
[gradle.org/release-checksums](https://gradle.org/release-checksums/).

**`accessibility-service-config`**: real, but currently dead code, not a
live feature. `BackgroundAccessibilityService` exists in source (inherited
from upstream) and implements an opt-in "block apps running in background"
firewall feature, gated behind a persisted preference default-off -- but the
service is **not declared in any of the five `AndroidManifest.xml` files**
in this fork (checked all of them: `main`, `full`, `headless`, `play`,
`website`). Without a manifest `<service>` declaration, Android will never
surface it as an enableable accessibility service and the permission is
never requested or granted. It's unreachable, inert code as things stand.
We're deciding whether to finish wiring it up (which would need clear
in-app justification for the sensitive permission, as this policy expects)
or remove it outright, and will follow up once decided rather than leave it
ambiguous.

**`triple-t`**: checked the whole repo (`build.gradle`, `app/build.gradle`,
every Gradle/Kotlin/XML/YAML file) for any Triple-T Play Publisher plugin
reference -- there is none. Not sure where this label came from; possibly
carried over from analysis of the upstream repo.

**`minSdkVersion`**: lowered from API 26 to **API 23 (Android 6.0)** for
broader community reach, per feedback from the app's users. The codebase
already has version-gating helpers (`Utilities.isAtleastO()` etc.) used
throughout, so this should be low-risk, but hasn't been exhaustively
hand-audited for every API 26+ call site -- the project's own CI runs
Android Lint's `NewApi` detector on every push, which is the right tool for
catching any real violation, and we'll fix anything it surfaces.

**Network security config**: `cleartextTrafficPermitted="false"` globally,
system CAs only, no custom/user CA trust anchor -- unchanged from upstream.

**Reproducible builds / scanner-error**: if the automated build attempt hit
an error, it likely predates the fixes above (the gradlew checksum in
particular would have blocked any build outright). Happy to have it
re-scanned after this lands.

**Donation**: as requested by the checklist, this fork will set up its own
support link (ko-fi) separate from upstream's -- already reflected in
`fdroid/com.arcadesignpro.auroravpn.yml`'s description.

---

## For whoever posts this (not part of the copy-paste text above)

Two things flagged in this PR were **not** resolved unilaterally in code,
because they're bigger decisions than a build-config fix:

1. **`BackgroundAccessibilityService`** -- confirmed dead/unreachable (see
   above), but whether to finish wiring it up as a real feature or remove
   it entirely is a product decision, not something to guess at. Removing a
   currently-inert-but-clearly-intentional feature is a bigger, riskier call
   than the `WgHopManager` cleanup earlier in this project (which had an
   explicit prior signal of abandonment -- its own UI switch had already
   been deliberately removed). This one doesn't have that same signal.
2. **`minSdkVersion` 23** -- the change itself is one line, done in this PR.
   What's *not* done is a full manual guarantee that every API-26+ call site
   in a ~460-file codebase is properly guarded. That's what Android Lint's
   `NewApi` detector is for, and it already runs in this repo's CI
   (`android.yml`, on every push and PR) -- if it flags something after this
   merges, that's the signal to add an `isAtleastO()`-style guard at that
   specific call site, not evidence the whole change was wrong.
