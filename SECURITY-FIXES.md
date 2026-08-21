# Security fixes — pentest round 2

This branch addresses the deep-architecture pentest report (Round 2). It fixes
everything **except** the three issues that require upstream Go / `firestack`
changes (VULN-A, VULN-E, VULN-J). Those are tracked at the bottom of this file.

## Fixed in this PR

### VULN-B — ICMP firewall bypass (data exfil channel)
- New persistent setting `block_icmp` defaulting to **true** in
  `PersistentState.kt`.
- The Go bridge MUST consult this flag and drop ICMP frames at the TUN
  before they reach the gVisor stack. (Kotlin-side scaffolding only — the
  matching `firestack` change is required for the runtime block to take
  effect; see TODO note in `PersistentState.kt`.)

### VULN-C — UID reuse / tombstone rule inheritance
- `FirewallManager.purgeTombstoneIfDifferentPackage(uid, pkg)` deletes any
  tombstoned `AppInfo` whose `|uid|` collides with a freshly-installed UID
  but whose package name differs.
- Wired into `RefreshDatabase.maybeInsertApp()` so it runs on every new app
  install / UID assignment, before the new row is persisted.
- An `AUDIT (VULN-C)` log line is emitted whenever a stale tombstone is
  purged.

### VULN-D — `volatile HashSet` foreground tracking is not thread-safe
- `FirewallManager.GlobalVariable.foregroundUids` is now backed by
  `Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())`. All
  `add` / `remove` / `contains` calls are now safe under concurrent access
  on weakly-ordered ARM devices.

### VULN-F — `BYPASS_DNS_FIREWALL` silently disables logging and rules
- `FirewallManager.updateFirewallStatus()` now writes a high-visibility
  `AUDIT: BYPASS_DNS_FIREWALL granted to uid=… pkgs=[…]` warning every
  time the status is granted or changed, so an operator reviewing logs can
  detect a malicious or social-engineered grant even though the bypassed
  app itself produces no connection-log entries.

### VULN-G — Evil-twin SSID kills the WireGuard tunnel
- `BraveVPNService.pauseSsidEnabledWireGuardOnNoNw()` now requires the
  current Wi-Fi BSSID to match a TOFU-pinned BSSID for the SSID before it
  will pause any SSID-bound WireGuard tunnel.
- Pin map is stored in `SharedPreferences("ssid_bssid_pin")`.
- New persistent setting `require_bssid_match_ssid` (default **true**)
  lets advanced users opt out.
- Fails open and logs when the BSSID is unreadable
  (e.g. `02:00:00:00:00:00` MAC anonymization on Android 9+).

### VULN-H — PCAP file written to world-readable storage
- `AppConfig.setPcap()` now refuses any `EXTERNAL_FILE` path that is not
  inside `context.filesDir` or one of `context.getExternalFilesDirs(null)`.
- Path is canonicalized first so symlinks / `..` cannot defeat the check.
- Rejected paths fall back to `PcapMode.NONE` instead of leaving a
  half-configured "write-to-file" state with an empty path.

### VULN-I — WireGuard private keys at rest
- WireGuard configurations are already written through
  `EncryptedFileManager` (Android Keystore-backed `EncryptedFile` /
  `MasterKey`); only the file path is stored in SQLite, not the key
  material itself. **No code change required** — added a SECURITY note in
  this document so future contributors know not to add a plaintext
  fallback.

### VULN-K — DNS cache TTL / size bounds
- New persistent settings in `PersistentState.kt`:
  - `max_dns_cache_ttl_sec` — default **3600**
  - `dns_cache_max_entries` — default **10000**
- Like VULN-B, the matching enforcement lives in the `firestack` DNS
  cache. Kotlin-side defaults + plumbing are in place; the Go side must
  clamp accepted TTLs and evict on size overflow.

## Not fixed here (need upstream Go work in `firestack`)

- **VULN-A** — Go runtime panic in `gVisor` `dispatchLoop` kills
  `BraveVPNService` and leaks traffic. Requires: wrap goroutines in
  `recover()`, enforce Android `VPN_LOCKDOWN`, packet fuzzing on the
  gVisor input path.
- **VULN-E** — Double-free / UAF at tunnel teardown across the gomobile
  JNI boundary. Requires: audit `tunnel.Tunnel.disconnect` lock order and
  the gomobile reference-count race; safer teardown sequencing.
- **VULN-J** — gVisor IP fragment reassembly bugs when `useMaxMtu` is
  enabled. Requires: rebase pinned `firestack` / gVisor to a release that
  carries the relevant fragment-reassembly fixes.

## Verifying the fixes

Audit log lines added by this PR:

```
AUDIT (VULN-C): purging tombstoned rules for <pkg> (uid=<u>) because UID … is being reused by <pkg2>
AUDIT: BYPASS_DNS_FIREWALL granted to uid=<u> pkgs=[…]
AUDIT (VULN-G): refusing SSID-based wg pause; BSSID for '<ssid>' did not match the trusted (TOFU) BSSID
TOFU: pinned BSSID <bssid> for SSID '<ssid>'
Refusing pcap path outside app-private storage: <path>
```

Each fix is also commented inline with a `SECURITY (VULN-x)` marker so it can
be located via `grep -rn "SECURITY (VULN-"`.

---

## Round 3 (April 2026 audit, branch `tests`)

This round addresses four additional findings raised in the round-3 OWASP-style
review. All marked inline with `SECURITY (VULN-...)` comments matching the
ranks in the audit report.

### #1 — Insecure Deserialization on backup restore (CWE-502, CRITICAL)

**Where:** `backup/BackupAgent.kt`, `backup/RestoreAgent.kt`

**Was:** Shared-preferences inside a `.rbk` archive were serialised with
`java.io.ObjectOutputStream` and read back with `java.io.ObjectInputStream`
on a user-supplied file. That is a classic Java deserialization ACE
primitive — any gadget chain available on the Android classpath (framework,
Glide, Gson, OkHttp, Koin, ...) can be triggered by a crafted `.rbk`,
executing attacker-controlled code in the Rethink process which holds VPN,
network-visibility, and EncryptedFile master keys.

**Fix:**
- Backup writes a strict JSON envelope prefixed with the magic header
  `RTHK_PREFS_V2_JSON\n`. Only scalar pref types (Bool/Int/Long/Float/String/
  Set<String>) are exported.
- Restore parses with `org.json` (no class instantiation), explicitly
  rejects the legacy `0xACED` ObjectStream magic and any non-matching
  magic header, and caps prefs blob at 8 MiB.
- minSdk=23 means `ObjectInputFilter` is unavailable; we drop legacy
  binary backups entirely. Users must take a fresh backup with this build.

### #2 — Zip Slip on backup restore (CWE-22, HIGH)

Already documented above (round-1/2). Round-3 audit confirmed the fix in
`BackupHelper.unzip()` is correct: canonical-path validation, name
sanitization, zip-bomb size caps, and AUDIT logging.

### #5 — ICMP not enforced at the firewall callbacks (VULN-B, MEDIUM)

**Where:** `service/BraveVPNService.kt` `flow()` and `inflow()`

**Was:** `persistentState.blockIcmp` was only consulted inside firestack /
gVisor at the TUN layer. If a future firestack revision (or a fork that
patches gVisor) starts dispatching ICMP through the Kotlin `flow()` /
`inflow()` callbacks, ICMP traffic would slip the user-visible "block ICMP"
toggle and become a covert exfiltration channel.

**Fix:** Defense-in-depth: both `flow()` and `inflow()` now early-return
`Backend.Block` for protocol 1 (ICMP) and 58 (ICMPv6) when
`persistentState.blockIcmp` is true, log an `AUDIT (VULN-B)` line, and
persist a synthetic ConnTrackerMetaData so the block surfaces in the UI
log just like other firewall rules.

### #6 — UAF / panic during VPN teardown (VULN-A/E, HIGH availability)

**Where:** `service/BraveVPNService.kt` `signalStopService()` / `stopVpnAdapter()`,
`net/go/GoVpnAdapter.kt` `closeTun()`

**Was:** Two concurrent triggers (user toggle, network-callback driven
restart, system stop, firestack error path) could race into
`tunnel.disconnect()` on the same go-side handle. Production crash logs
showed SIGSEGV / Go panics. A crash here drops the lockdown VPN — the
worst possible failure mode for this app.

**Fix:**
- `BraveVPNService` adds an `AtomicBoolean teardownInFlight` and ignores
  duplicate `signalStopService` calls. It snapshots and nulls
  `vpnAdapter` *before* calling into JNI so a racing coroutine sees a
  null adapter.
- `GoVpnAdapter.closeTun()` adds an `AtomicBoolean closeTunInFlight`
  guard (single-shot per adapter instance) and widens the catch from
  `Exception` to `Throwable` so JNI-surfaced `Error`s
  (UnsatisfiedLinkError, OOM, NoSuchMethodError, InternalError) cannot
  propagate and crash the Service.
- Surrounding `eventLogger`/`stopSelf`/`logEvent` calls are individually
  wrapped so a logging failure cannot prevent shutdown.

