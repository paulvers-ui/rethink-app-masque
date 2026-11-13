# Security policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

Report privately through GitHub: **Security → Report a vulnerability** on this
repository (private vulnerability reporting). Include the app version
(Settings → About), Android version, device, and steps to reproduce. A proof
of concept helps; logs must not contain your WireGuard/WARP keys.

| Step | Target |
|---|---|
| Acknowledge report | 7 days |
| Initial assessment and severity (CVSS v4) | 14 days |
| Fix or mitigation for High/Critical | 30 days |
| Public advisory | after a fixed release, at most 90 days after the report |

Reporters are credited in the advisory unless they prefer otherwise.

## Scope

In scope: this app (all build flavours), the `firestack` fork it bundles
(`paulvers-ui2/firestack`), the bundled `libusque.so`, release signing and CI,
and the download website.

Out of scope: third-party DNS resolvers and WireGuard servers you configure,
upstream Rethink DNS services, and issues needing a rooted or already
compromised device.

## Supported versions

Only the latest release receives security fixes. Obtainium users get updates
automatically.

## How we work

See [`docs/security/`](docs/security/README.md): threat model, secure-coding
rules, and how the project maps to OWASP MASVS, NIST SSDF and OWASP SAMM.
Past fixes are listed in [`SECURITY-FIXES.md`](SECURITY-FIXES.md).
