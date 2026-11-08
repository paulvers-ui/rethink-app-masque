# OWASP SAMM v2 self-assessment

Scale per practice: 0 (not done) to 3 (optimised). **These are initial
estimates from a repository review on 2026-11-07, not an audited score.**
Re-score yearly with the official SAMM toolbox (owaspsamm.org) and record the
date. Targets are for 12 months out and sized for a small FOSS team.

| Function | Practice | Now | Target | Evidence now → step to target |
|---|---|---|---|---|
| Governance | Strategy & Metrics | 0.5 | 1 | No written goals → this folder + track open security issues per month |
| Governance | Policy & Compliance | 1 | 1.5 | SECURITY.md, MASVS mapping → review mapping each release |
| Governance | Education & Guidance | 0.5 | 1 | SECURE_CODING.md, AGENTS.md → contributors read it via PR template |
| Design | Threat Assessment | 1 | 2 | THREAT_MODEL.md exists → update per design change; DREAD drives backlog order |
| Design | Security Requirements | 1 | 1.5 | MASVS rows → each 🟡 row becomes an issue with acceptance criteria |
| Design | Security Architecture | 1 | 1.5 | Fail-closed DNSSEC, socket protection, encrypted storage → document reusable patterns in SECURE_CODING |
| Implementation | Secure Build | 1.5 | 2 | SHA-pinned Actions, pinned firestack/usque → dependency locking, SLSA attestations |
| Implementation | Secure Deployment | 1 | 2 | Signed APKs → offline signing key (T19), published hashes |
| Implementation | Defect Management | 1 | 1.5 | SECURITY-FIXES.md → `security` label, CVSS scores, time-to-fix metric |
| Verification | Architecture Assessment | 0.5 | 1 | Two pentest rounds → yearly review of trust boundaries |
| Verification | Requirements-driven Testing | 1 | 1.5 | dnssecv safety tests (fail-closed, Broken state) → regression test per SECURITY-FIXES item |
| Verification | Security Testing | 1.5 | 2 | CodeQL, Semgrep, gosec, MobSF, ZAP → fuzz netstack input; MASTG manual checks per release |
| Operations | Incident Management | 0.5 | 1 | Disclosure policy → written runbook: key compromise, malicious release, broken DNSSEC |
| Operations | Environment Management | 1 | 1.5 | Dependabot, govulncheck → monthly patch triage |
| Operations | Operational Management | 0.5 | 1 | Log retention controls → data inventory per flavour (PRIVACY-3) |

**Overall (mean): ≈ 0.9 now → ≈ 1.5 target.**

Highest-value moves, in order: offline signing key (Secure Deployment),
dependency locking + attestations (Secure Build), incident runbook (Incident
Management), DNSSEC phase 2 per the threat model (Security Architecture).
