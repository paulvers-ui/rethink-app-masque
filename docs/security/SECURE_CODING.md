# Secure coding rules

Distilled from OWASP Secure Coding Practices, the SEI CERT Oracle Coding
Standard for Java (which carries over to Kotlin on the JVM), MASVS, and Go's
security guidance (there is no CERT standard for Go). Rule IDs are referenced
by Semgrep rules in `.semgrep/` and by reviewers.

## Principles (apply everywhere)

- **P1 Treat all input as hostile:** DNS answers, blocklists, configs (incl. QR
  codes and imports), intents from other apps, files, and anything from Go.
- **P2 Least privilege:** no new permission, exported component, or socket
  bypass without a THREAT_MODEL entry.
- **P3 Secure defaults, fail closed:** when a security check can't decide, the
  result is "not protected", never "protected". Example: dnssecv returns
  `Unknown`, never `Secure`, on any error.
- **P4 No silent downgrade:** if protection degrades (tunnel down, DNSSEC
  unreachable, plain DNS fallback), the UI says so.

## Kotlin / Android

| ID | Rule | CERT / MASVS |
|---|---|---|
| K1 | **SQL:** Room `@Query` with bind parameters only. Raw `execSQL`/`rawQuery` may interpolate compile-time constants (table names) only; mark the line `// nosemgrep: aurora-sql-string-interpolation -- constant`. | IDS00-J · CODE-4 |
| K2 | **Intents:** validate every extra from an exported component (type, range, allowlist). Explicit intents internally; `FLAG_IMMUTABLE` PendingIntents unless mutability is required and justified. | IDS01-J · PLATFORM-1 |
| K3 | **Output encoding:** text passed to `htmlToSpannedText` / `Html.fromHtml` must be resource strings, with every dynamic argument wrapped in `TextUtils.htmlEncode()`. Prefer plain `setText`. WebViews: no JS bridge, no remote content. | — · PLATFORM-2 |
| K4 | **Secrets at rest:** keys and tokens only via `EncryptedFileManager` / Keystore. Never SharedPreferences in plaintext, never SQLite columns. | MSC03-J · STORAGE-1 |
| K5 | **Logging:** never log keys, tokens, passwords, PSKs, full configs or user URLs at INFO and above. Log booleans ("token present? true"), not values. | FIO13-J · STORAGE-2 |
| K6 | **Crypto:** no homemade crypto. `SecureRandom` for anything unguessable. If a password/PIN must ever be stored, hash with Argon2id (or bcrypt) with a per-user salt; today AppLock delegates to the OS, which is preferred. | MSC02-J · CRYPTO-1 |
| K7 | **TLS:** never override `TrustManager`/`HostnameVerifier`. Debug-only exceptions go in `network_security_config` `<debug-overrides>`. | — · NETWORK-1 |
| K8 | **Files:** app-private storage only; canonicalise paths before checks (see VULN-H fix). | FIO00-J · STORAGE-2 |
| K9 | **Errors:** catch narrowly; show users a generic message, log details at DEBUG. Never swallow an error that means "protection is off". | ERR00-J |
| K10 | **Concurrency:** shared mutable state uses concurrent collections or locks (see VULN-D). | VNA02-J |
| K11 | **Go boundary:** keep a strong Kotlin field for every Go-backed object you hand to Go or keep using (gomobile refnum GC, see `app/build.gradle`). Wrap every call into Go in `try/catch(Exception)`; gomobile turns Go errors into exceptions. | — |

## Go (firestack, dnssecv)

| ID | Rule |
|---|---|
| G1 | **Recover at goroutine roots** that touch untrusted packets or alpha libraries; convert a panic into an error result (dnssecv `Validate` does this). |
| G2 | **Bound everything** derived from the network: concurrency (semaphore), time (`context.WithTimeout`), memory (capped caches), recursion/loops (query caps). |
| G3 | **Protected sockets:** any socket opened from inside the VPN process must go through `protect.MakeNsDialer` (or an equivalent `Control` func), or it loops into the TUN. |
| G4 | **Crypto:** stdlib `crypto/*` and `golang.org/x/crypto` only; `crypto/rand` for randomness. |
| G5 | **Never trust the DNS AD bit** from an upstream; validate locally (dnssecv) or treat as unvalidated. |
| G6 | **Vendored code:** every local change carries a `FIRESTACK PATCH` marker and a PATCHES.md entry; CI checks the markers. |
| G7 | **No network I/O in `init()`.** Libraries must not touch the network before the app has installed its dialer. |
| G8 | `go vet`, `gosec`, `govulncheck` clean on changed packages; tests run with `-race`. |

## Pull-request checklist (reviewer)

- [ ] New input sources validated (P1) and outputs encoded (K3)?
- [ ] New permission / exported component / network path → THREAT_MODEL updated?
- [ ] Secrets: none in code, logs, or test fixtures (gitleaks green)?
- [ ] Failure path is fail-closed and visible to the user (P3, P4)?
- [ ] Go: bounded (G2), protected sockets (G3), panics contained (G1)?
- [ ] Tests cover the security-relevant branch, not just the happy path?
- [ ] CI security jobs green; new code-scanning alerts triaged?
