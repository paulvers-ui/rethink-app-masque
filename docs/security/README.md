# Security program

How AuroraVPN applies each framework, and where the evidence lives.

| Framework | What it gives us | Our document | Automated by |
|---|---|---|---|
| Threat modelling: STRIDE, DREAD, PASTA | What can go wrong, ranked | [THREAT_MODEL.md](THREAT_MODEL.md) | review on every design change |
| OWASP MASVS / MASTG (the mobile ASVS) | Verifiable requirements for the app | [MASVS_ASVS_MAPPING.md](MASVS_ASVS_MAPPING.md) | MobSF, CodeQL, Semgrep |
| OWASP ASVS 5.0 | Requirements for the website | [MASVS_ASVS_MAPPING.md](MASVS_ASVS_MAPPING.md#website-owasp-asvs-50-level-1) | ZAP baseline |
| OWASP Top 10 / Mobile Top 10 (2024) | Awareness checklist | [MASVS_ASVS_MAPPING.md](MASVS_ASVS_MAPPING.md#owasp-mobile-top-10-2024) | — |
| OWASP Secure Coding Practices, SEI CERT (Java) | Coding rules | [SECURE_CODING.md](SECURE_CODING.md) | Semgrep `.semgrep/`, detekt, gosec |
| NIST SSDF (SP 800-218) | Process tasks: prepare, protect, produce, respond | [SSDF.md](SSDF.md) | CI workflows |
| OWASP SAMM v2 | Maturity score and roadmap | [SAMM.md](SAMM.md) | yearly self-assessment |
| Project CodeGuard | Security rules for AI coding agents | [`/AGENTS.md`](../../AGENTS.md) | agent context |

## CI security gates

| Workflow | Checks | Blocks merge? |
|---|---|---|
| `ci-security.yml` | unit tests, CodeQL (Kotlin/Java, Actions) | tests yes; alerts via code-scanning rules |
| `sa.yml` | detekt (new warnings only, baseline) | yes |
| `mobsf.yml` | MobSF static mobile scan | report |
| `security-extra.yml` | gitleaks (history), Semgrep, dependency review, OSV | gitleaks + dependency review yes |
| `dast-zap.yml` | ZAP passive scan of the website | monthly report |
| firestack `go-security.yml` | Go tests (race), gosec, govulncheck, CodeQL Go, live DNSSEC self-test | tests + govulncheck yes |

Recommended repository settings (Settings → Code security): enable private
vulnerability reporting, secret scanning with push protection, Dependabot
alerts, and a branch-protection rule on `main` that requires the jobs above
plus one review.

## Cadence

| When | What |
|---|---|
| Every PR | CI gates above; reviewer uses the checklist in SECURE_CODING.md |
| Design change (new protocol, new permission, new network path) | update THREAT_MODEL.md in the same PR |
| Monthly | triage code-scanning and Dependabot alerts; ZAP report |
| Yearly | SAMM self-assessment; review DNSSEC probe zones and root anchors; external pentest when budget allows |
