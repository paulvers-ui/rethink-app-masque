<div align="center">
  <img src=".github/assets/logo.png" alt="Rethink Dns Fork" width="120">

  # Rethink Dns Firewall Free Vpn Fork

  Encrypted DNS client, firewall, and WireGuard/WARP proxy for Android.

  [![Download APK](https://img.shields.io/github/v/release/paulvers-ui/rethink-app-masque?label=download&style=for-the-badge)](https://github.com/paulvers-ui/rethink-app-masque/releases/latest)
  [![Website](https://img.shields.io/badge/website-paulvers--ui.github.io%2Frethink-1f6f5c?style=for-the-badge)](https://paulvers-ui.github.io/rethink/)
</div>

---

This is a fork of [Rethink DNS + Firewall + VPN](https://github.com/celzero/rethink-app) with help of arcadesignpro, creatoreprints, diniboy1123, with these changes from the original:

- **Own package ID** (`com.creatore.rethinkfork`) — installs alongside the original Rethink on the same phone, no conflict.
- **WARP over MASQUE**, using [usque](https://github.com/paulvers-ui/usque) instead of Cloudflare's official WireGuard client.
- **Single theme** (Dark Plus) — every other option was removed from the picker.
- **Network status sound alerts**: tunnel down, unhealthy proxy, interface switch — debounced so a burst of related events doesn't fire repeatedly.
- **No Firebase or Google Play Services** in the `fdroid` build variant.
- **Own network backend**: the firestack this fork uses comes from [paulvers-ui/firestack](https://github.com/paulvers-ui/firestack), with 600+ commits on top of the last point where its API still matched this app — upstream's `n2` branch changed that API and broke compatibility here.
- Own sponsor link, and Cloudflare instead of Rethink as the default fallback DNS.

## Install

- **[Obtainium](https://github.com/ImranR98/Obtainium)** (recommended): add this repository as a source and get automatic updates.
- **[Download page](https://paulvers-ui.github.io/rethink/)**: direct APK, with a QR code for your phone.
- **[GitHub Releases](https://github.com/paulvers-ui/rethink-app-masque/releases)**: manual download.

## License

Same as the original project — see [LICENSE](LICENSE).
