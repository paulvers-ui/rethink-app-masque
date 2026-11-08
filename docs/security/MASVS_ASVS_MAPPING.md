# OWASP requirement mapping

ASVS is written for web applications. For the Android app we use its mobile
counterpart, **OWASP MASVS v2** (requirements) with **MASTG** (test
procedures). ASVS 5.0 applies to the download website.

Status: ✅ met · 🟡 partial · ❌ gap · n/a not applicable. "Evidence" is where a
reviewer can check it.

## App: OWASP MASVS v2

| Control | Requirement | Status | Evidence / action |
|---|---|---|---|
| STORAGE-1 | Sensitive data stored securely | ✅ | WireGuard configs via `EncryptedFileManager` (Keystore `MasterKey`); SECURITY-FIXES VULN-I |
| STORAGE-2 | No sensitive data leakage | 🟡 | `allowBackup=false`, `data_extraction_rules.xml`; PCAP path check (VULN-H). Action: keep Semgrep `aurora-log-sensitive` at zero true positives |
| CRYPTO-1 | Strong, current cryptography | ✅ | Crypto lives in vetted libs (Go stdlib, WireGuard, AndroidX Security). No homemade crypto (SECURE_CODING G4/K6) |
| CRYPTO-2 | Key management per best practice | 🟡 | App keys in Keystore. Release signing key generated in CI → see THREAT_MODEL T19 |
| AUTH-1 | Secure auth to remote endpoints | n/a | No user accounts or backend login. WARP/proxy credentials are device-generated and stored per STORAGE-1 |
| AUTH-2 | Local auth per platform best practice | ✅ | `AppLockActivity` uses BiometricPrompt / device credential |
| AUTH-3 | Step-up for sensitive operations | 🟡 | Action: require AppLock re-auth before exporting configs/backups and before disabling the firewall |
| NETWORK-1 | Secure traffic per best practice | ✅ | `network_security_config.xml`; DoH/DoT/DNSCrypt; Semgrep `aurora-trust-all-tls` |
| NETWORK-2 | Identity pinning where needed | 🟡 | DNSSEC now validates DNS data to the root anchor (dnssecv). Action: decide pinning policy for update/blocklist endpoints |
| PLATFORM-1 | IPC used securely | 🟡 | 2 exported components; PendingIntents set FLAG_IMMUTABLE (Lint `UnspecifiedImmutableFlag`). Action: MASTG-TEST for each exported component every release |
| PLATFORM-2 | WebViews used securely | ✅ | No JS bridge; Semgrep `aurora-webview-js-bridge` |
| PLATFORM-3 | UI used securely | 🟡 | HTML-rendered log strings now escaped (THREAT_MODEL T15). Action: FLAG_SECURE on screens that show keys/QR configs |
| CODE-1 | Up-to-date platform | 🟡 | `targetSdkVersion 35`, `minSdk 23`. Action: move to API 36 (Google Play raises the required target every August; check the current rule for the `play` flavour) |
| CODE-2 | Mechanism to enforce updates | 🟡 | Obtainium/F-Droid updates; no forced update (acceptable for FOSS) |
| CODE-3 | Only components without known vulns | 🟡 | Dependabot, dependency review, govulncheck (firestack). Action: Gradle dependency locking so OSV-Scanner sees the full tree |
| CODE-4 | Validates and sanitises all untrusted input | 🟡 | dnssecv allowlists names/types; Room bind params. Action: review the Semgrep SQL findings (migrations) and annotate |
| RESILIENCE-1..4 | Anti-tamper, anti-RE | n/a by choice | FOSS app; source is public. Integrity comes from signing + SHA-pinned `libusque.so` |
| PRIVACY-1 | Minimise data access | ✅ | No Firebase / Play Services in `fdroid` flavour |
| PRIVACY-2 | Prevent identification | 🟡 | dnssecv recursion exposes the device IP to authoritative servers for probe names only (THREAT_MODEL T7) |
| PRIVACY-3 | Transparent data collection | 🟡 | Action: document what each flavour sends where (incl. DNSSEC probes) in README / privacy page |
| PRIVACY-4 | User control over their data | ✅ | Log retention controls; clear-logs options |

## OWASP Mobile Top 10 (2024)

| Risk | Where we address it |
|---|---|
| M1 Improper Credential Usage | No hard-coded secrets (gitleaks over full history); keys in Keystore |
| M2 Inadequate Supply Chain Security | SHA-pinned Actions, Dependabot, dependency review, `libusque.so` SHA pin, vendored code with PATCHES.md |
| M3 Insecure Authentication/Authorization | AppLock; AUTH-3 action item |
| M4 Insufficient Input/Output Validation | SECURE_CODING K1–K3; dnssecv input allowlists |
| M5 Insecure Communication | Encrypted DNS, WireGuard/MASQUE, DNSSEC validation |
| M6 Inadequate Privacy Controls | PRIVACY rows above |
| M7 Insufficient Binary Protections | Accepted (FOSS); integrity via signing |
| M8 Security Misconfiguration | Manifest review, MobSF, network security config |
| M9 Insecure Data Storage | STORAGE rows above |
| M10 Insufficient Cryptography | Vetted libraries only |

## Website: OWASP ASVS 5.0 (Level 1)

The site is static (GitHub Pages), so most ASVS chapters (sessions,
authentication, APIs) do not apply. What does:

| Area | Check | How |
|---|---|---|
| Transport | HTTPS only, HSTS | GitHub Pages "Enforce HTTPS" on |
| Content integrity | Third-party scripts with Subresource Integrity, or none | review page source; ZAP rule 90003 |
| Security headers | CSP, `X-Content-Type-Options`, `Referrer-Policy` | GitHub Pages can't set headers; use `<meta http-equiv="Content-Security-Policy">`; ZAP reports |
| Download integrity | Publish SHA-256 for each APK beside the link | THREAT_MODEL T20 |
| Output encoding | No user-supplied content rendered | static site |

## OWASP Top 10 (web, 2021) quick check

Relevant only to the website and to any future backend: A01 access control
(n/a static), A02 crypto failures (HTTPS), A03 injection (no inputs), A05
misconfiguration (headers, above), A06 vulnerable components (no JS libs, or
pinned with SRI), A08 integrity failures (download hashes).
