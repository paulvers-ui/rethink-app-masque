# DNSSEC on the home-screen DNS card

## What the user sees

A line under the region on the DNS card (only while DNS mode is active):

| Text | Meaning |
|---|---|
| DNSSEC ✓ validated | A signed zone validated to the IANA root keys **and** the deliberately broken `dnssec-failed.org` was rejected |
| DNSSEC: checking… | Self-test running |
| DNSSEC: partial | Rejection proven but no signed zone validated (slow/lossy network, or device clock wrong) |
| DNSSEC: blocked on this network | No answers at all; port 53 to the internet is blocked here |
| DNSSEC ✗ check failed | The validator **accepted** forged data. Logged as a critical event. Never green |
| DNSSEC: tap to check | Not run yet, or an error occurred |

Tapping the line re-runs the test. Otherwise it runs at most every 10 minutes,
and again after a network change.

## What it does and doesn't mean

It proves the app's own validator works end to end on the current network.
It does **not yet** validate the answers other apps receive through your DoH
upstream; that is phase 2 (THREAT_MODEL §6).

## Pieces

- Go: firestack `intra/dnssecv/` (validator, self-test, cache, vendored
  `nsmithuk/resolver` + root anchors) and `intra/dnssec.go` (gomobile API).
- Kotlin: `service/DnssecManager.kt` (singleton, rate limit, LiveData),
  `HomeScreenFragment.observeDnssec()` / `renderDnssec()`, layout view
  `fhs_card_dnssec_status`, strings `dnssec_card_*`, and a call to
  `DnssecManager.onNetworkChanged()` in `BraveVPNService.onNetworkChange`.

## Privacy note

Self-test queries leave the device in plaintext on port 53, from the real IP,
outside DoH/WARP, to root/TLD/authoritative servers for `.`, `isc.org`,
`ietf.org` and `dnssec-failed.org` only. No user domains are ever sent this way.
