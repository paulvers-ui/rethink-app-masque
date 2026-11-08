# Instructions for AI coding agents

Applies to Claude Code, GitHub Copilot, Cursor, Codex and similar tools working
in this repository.

## Security rules (always)

1. Follow [`docs/security/SECURE_CODING.md`](docs/security/SECURE_CODING.md).
   Cite the rule ID (K1–K11, G1–G8) in your PR description when relevant.
2. Also load the **Project CodeGuard** core rules (OWASP/CWE-based security
   rules for AI agents, CC BY 4.0): download the release for your agent from
   https://github.com/project-codeguard/rules/releases and place it where your
   agent reads rules (e.g. `.cursor/rules/`, `.github/instructions/`,
   `.claude/`). Pin the release version in your PR.
3. Never add: hard-coded secrets, TrustManager/HostnameVerifier overrides,
   WebView JavaScript bridges, `MODE_WORLD_*` files, SQL built from strings,
   logging of keys or tokens, or network I/O in Go `init()`.
4. Never weaken a fail-closed path. In particular the DNSSEC code
   (`DnssecManager`, firestack `intra/dnssecv`) must never report
   "validating"/`Secure` on error, timeout or exception.
5. Any new permission, exported component, network destination or socket that
   bypasses the VPN requires a matching row in
   [`docs/security/THREAT_MODEL.md`](docs/security/THREAT_MODEL.md) in the same PR.
6. Don't edit `.github/workflows/` permissions, pinned action SHAs, the
   firestack commit pin or `libusque.so` pins unless the task is explicitly
   about them.
7. Before finishing: run unit tests and `semgrep --metrics=off --config .semgrep/ app/src`
   on changed files; report any finding you could not fix.

## Project facts agents get wrong

- The Go backend is a gomobile `.aar` (`com.celzero.firestack.*`). Add Go APIs
  to the existing bound packages (`intra`, `intra/backend`, `intra/settings`);
  a second gomobile `.aar` in the APK will not work.
- Release builds run with R8 disabled on purpose; don't "fix" it.
- Keep a Kotlin field for every Go-backed object (SECURE_CODING K11).
