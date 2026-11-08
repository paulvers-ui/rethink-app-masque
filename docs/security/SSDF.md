# NIST SSDF (SP 800-218 v1.1) mapping

SSDF groups secure-development tasks into four practice areas. For each, what
this project does today and the next concrete action. Owner for all items:
the maintainer(s) listed in `CODEOWNERS` (create one if missing).

## PO — Prepare the Organization

| Practice | Today | Next action |
|---|---|---|
| PO.1 Define security requirements | MASVS mapping, threat model, SECURE_CODING.md | Treat MASVS ❌/🟡 rows as backlog issues with the `security` label |
| PO.2 Roles and responsibilities | Single-maintainer project | Add `CODEOWNERS` covering `.github/`, `app/build.gradle`, `firestack` pins, `intra/dnssecv/` |
| PO.3 Supporting toolchains | CodeQL, detekt, MobSF, Semgrep, gitleaks, gosec, govulncheck, ZAP | Keep tool versions pinned; review pins quarterly |
| PO.4 Security check criteria | CI gates in `docs/security/README.md` | Branch protection on `main` requiring those jobs |
| PO.5 Secure dev environments | GitHub-hosted runners, least-privilege `permissions:` | Enable MFA (hardware key or passkey) for every account with write access, and on the signing-key holder's machine |

## PS — Protect the Software

| Practice | Today | Next action |
|---|---|---|
| PS.1 Protect all code from tampering | Actions pinned to commit SHAs; `persist-credentials: false` | Require signed commits on `main`; enable secret scanning push protection |
| PS.2 Verify release integrity | APK signing; `libusque.so` SHA-256 pin | Publish SHA-256 of each APK in the release notes and on the website; add GitHub artifact attestations (SLSA provenance) to `release-signed.yml` |
| PS.3 Archive and protect each release | GitHub Releases; firestack commit pinned (`FIRESTACK_COMMIT`) | **Enable Gradle dependency locking**: add `dependencyLocking { lockAllConfigurations() }` to `app/build.gradle`, run `./gradlew dependencies --write-locks`, commit the `*.lockfile`s. OSV-Scanner then covers the full tree |

## PW — Produce Well-Secured Software

| Practice | Today | Next action |
|---|---|---|
| PW.1 Design to meet requirements, mitigate risks | THREAT_MODEL.md (STRIDE/DREAD/PASTA) | Update it in any PR adding a network path or permission |
| PW.2 Review the design | — | Second reviewer for any change touching THREAT_MODEL trust boundaries |
| PW.4 Reuse well-secured software | Vendored resolver with documented patches; dropped `google/uuid` | Re-vendor checklist in `intra/dnssecv/PATCHES.md` |
| PW.5 Follow secure coding practices | SECURE_CODING.md; AGENTS.md for AI agents | Reviewer checklist on every PR |
| PW.6 Secure build configuration | Go built with `-trimpath -s -w`; release R8 is **disabled** on purpose (gomobile refnum crash, see comment in `app/build.gradle`) | Keep it off until a reproduction exists; add a reproducible-build check against F-Droid |
| PW.7 Review and analyse code | CodeQL, detekt, Semgrep, gosec | Triage Semgrep SQL findings once, then add `nosemgrep` notes |
| PW.8 Test executable code | Unit tests; dnssecv offline tests with `-race`; live self-test in CI; MobSF | Add fuzzing for the netstack packet path (VULN-A) with Go native fuzzing |
| PW.9 Secure defaults | `block_icmp=true`, `require_bssid_match_ssid=true`, DNSSEC fail-closed | Default new installs to an encrypted DNS upstream |

## RV — Respond to Vulnerabilities

| Practice | Today | Next action |
|---|---|---|
| RV.1 Identify vulnerabilities continuously | Dependabot, CodeQL weekly, govulncheck, ZAP monthly, SECURITY.md intake | Enable private vulnerability reporting in repo settings |
| RV.2 Assess, prioritise, remediate | SECURITY-FIXES.md records fixes | Score with CVSS v4; target times in SECURITY.md |
| RV.3 Root-cause analysis | Pentest rounds documented | For each fixed vuln, add a regression test and, if possible, a Semgrep/CodeQL rule so the class can't return |
