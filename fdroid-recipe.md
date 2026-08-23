# F-Droid Build Recipe — `paulvers-ui/rethink-app`

> **Type:** Android APK (Kotlin + Gradle)  
> **Package ID:** `com.creatore.rethinkfork`  
> **Min SDK:** 26 (Android 8.0) | **Target/Compile SDK:** 35  
> **JDK:** 17  
> **ABI version codes:** armeabi-v7a=2, arm64-v8a=3, x86=8, x86_64=9  

---

## Metadata file: `metadata/com.creatore.rethinkfork.yml`

```yaml
Categories:
  - Internet
  - Security

License: Apache-2.0

WebSite: https://github.com/paulvers-ui/rethink-app
SourceCode: https://github.com/paulvers-ui/rethink-app
IssueTracker: https://github.com/paulvers-ui/rethink-app/issues

AutoName: Rethink Fork

Summary: DNS-based firewall and VPN with WireGuard and WARP support
Description: |-
  Rethink Fork is a fork of RethinkDNS with additional features including
  WireGuard tunnelling, Cloudflare WARP integration (via usque/libusque.so),
  per-app DNS and firewall rules, and DNS-over-HTTPS/TLS. Uses the firestack
  Go engine compiled via gomobile as an AAR dependency.

RepoType: git
Repo: https://github.com/paulvers-ui/rethink-app
Branch: main

Builds:
  - versionName: '@@gitVersion@@'
    versionCode: @@versionCode@@
    commit: @@commit@@        # pin to a specific tag or SHA
    subdir: .

    sudo:
      - apt-get install -y openjdk-17-jdk git wget unzip

    gradle:
      - fdroidRelease    # use the fdroid product flavor + release build type

    prebuild:
      # Resolve firestackCommit — default is '0.5.5' from build.gradle
      # Override at build time if using a custom firestack AAR:
      #   ./gradlew -PfirestackCommit=<SHA> assemblefdroidRelease
      - echo "Using firestackCommit from build.gradle (JitPack)"

    build:
      - ./gradlew
          -PfirestackCommit=$$FIRESTACK_COMMIT$$
          assemblefdroidRelease
          --no-daemon
          --stacktrace

    # Output APK path (per-ABI splits are generated)
    # F-Droid picks the arm64-v8a variant by default
    output: app/build/outputs/apk/fdroid/release/app-fdroid-arm64-v8a-release.apk

    # Do NOT sign here — F-Droid signs with its own key
    ndk: r26b

AutoUpdateMode: Version
UpdateCheckMode: Tags
# versionCode formula from build.gradle:
#   baseAbiVersionCode * 10000000 + variant.versionCode
#   arm64-v8a (code 3) => 3 * 10000000 + versionCode
CurrentVersion: '@@gitVersion@@'
CurrentVersionCode: @@arm64_versionCode@@
```

---

## Manual build steps

### Prerequisites

```bash
# Java 17
java -version   # must be 17

# Android SDK (API 35)
# Android NDK r26b
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.3.11579264
```

### Clone and build

```bash
git clone https://github.com/paulvers-ui/rethink-app
cd rethink-app

# Standard fdroid release (uses JitPack to resolve firestack AAR)
./gradlew assemblefdroidRelease --no-daemon

# With a custom firestack commit (JitPack must have built it):
./gradlew -PfirestackCommit=bd748e4839cd0169d9bedf56304415412d5ef688 \
  assemblefdroidRelease --no-daemon
```

Output APKs are in:
```
app/build/outputs/apk/fdroid/release/
  app-fdroid-arm64-v8a-release.apk   ← main target
  app-fdroid-armeabi-v7a-release.apk
  app-fdroid-x86_64-release.apk
  app-fdroid-x86-release.apk
```

### With a locally-built firestack AAR (fully offline / reproducible)

```bash
# 1. Build firestack.aar from source (see fdroid-recipe-firestack.md)
cp /path/to/firestack/build/firestack.aar app/libs/firestack.aar

# 2. In app/build.gradle, comment out JitPack dependency and use local file:
#    implementation fileTree(dir: 'libs', include: ['*.aar'])

# 3. Build
./gradlew assemblefdroidRelease --no-daemon
```

---

## Product flavors

The app uses two flavor dimensions (`releaseChannel` × `releaseType`):

| Flavor | Purpose |
|--------|---------|
| `fdroid` + `Release` | F-Droid distribution build — **use this** |
| `play` + `Release` | Google Play variant |
| `*` + `Debug` | Debug builds (not for distribution) |

Always build the `fdroid` channel for F-Droid submission.

---

## ABI version code formula

F-Droid needs a unique, monotonically increasing `versionCode` per ABI:

```
versionCode = abiCode × 10_000_000 + baseVersionCode
```

| ABI | abiCode | Example versionCode (base=42) |
|-----|---------|-------------------------------|
| armeabi-v7a | 2 | 20_000_042 |
| arm64-v8a   | 3 | 30_000_042 ← F-Droid preferred |
| x86         | 8 | 80_000_042 |
| x86_64      | 9 | 90_000_042 |

---

## firestack dependency

The AAR is resolved from JitPack using the commit SHA defined in `build.gradle`:

```groovy
def firestackCommit = project.findProperty("firestackCommit") ?: "0.5.5"
// resolves to: com.github.celzero:firestack:<firestackCommit>@aar
```

To use `paulvers-ui/firestack` on JitPack instead of the upstream:

```groovy
maven { url 'https://jitpack.io' }
// then set firestackRepo=paulvers-ui in gradle.properties
// or pass -PfirestackCommit=<SHA> -PfirestackRepo=paulvers-ui at build time
```

---

## F-Droid submission checklist

- [ ] Reproducible build verified (`diffoscope` output clean)
- [ ] `fdroid` flavor used (not `play`)
- [ ] No proprietary dependencies (firestack AAR built from source)
- [ ] `libusque.so` built from `paulvers-ui/usque` source (see `fdroid-recipe-usque.md`)
- [ ] Signing done by F-Droid infrastructure only
- [ ] `AntiFeature` declared if WARP registration contacts Cloudflare servers
