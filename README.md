<div align="center">
  <img src=".github/assets/logo.png" alt="Rethink Dns Fork" width="120">

  # Rethink Dns Firewall Free Vpn Fork

  Encrypted DNS client, firewall, and WireGuard/WARP proxy for Android.

  [![Download APK](https://img.shields.io/github/v/release/paulvers-ui/rethink-app-masque?label=download&style=for-the-badge)](https://github.com/paulvers-ui/rethink-app-masque/releases/latest)
  [![Website](https://img.shields.io/badge/website-paulvers--ui.github.io%2Frethink-1f6f5c?style=for-the-badge)](https://paulvers-ui.github.io/rethink/)

  [English](#rethink-dns-fork) | [简体中文](#rethink-dns-fork-简体中文)
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

---

<div align="center">

# Rethink Dns Fork (简体中文)

面向 Android 的加密 DNS 客户端、防火墙及 WireGuard/WARP 代理。

</div>

本项目 fork 自 [Rethink DNS + Firewall + VPN](https://github.com/celzero/rethink-app)，相较原版做了以下改动：

- **独立的包名**（`com.creatore.rethinkfork`）——可与原版 Rethink 在同一部手机上共存安装，互不冲突。
- **通过 MASQUE 使用 WARP**，采用 [usque](https://github.com/paulvers-ui/usque) 替代 Cloudflare 官方的 WireGuard 客户端。
- **单一主题**（Dark Plus）——选择器中的其他主题选项均已移除。
- **网络状态语音提示**：隧道断开、代理异常、接口切换等——已做防抖处理，避免同类事件短时间内反复提示。
- `fdroid` 构建版本**不包含 Firebase 或 Google Play 服务**。
- **独立的网络后端**：本 fork 使用的 firestack 来自 [paulvers-ui/firestack](https://github.com/paulvers-ui/firestack)，在其 API 与本应用仍兼容的最后一个节点之上又新增了 600 多次提交——上游的 `n2` 分支之后修改了该 API，导致与本项目不再兼容。
- 独立的赞赏链接，并以 Cloudflare 取代 Rethink 作为默认的备用 DNS。

## 安装

- **[Obtainium](https://github.com/ImranR98/Obtainium)**（推荐）：将本仓库添加为源，即可自动获取更新。
- **[下载页面](https://paulvers-ui.github.io/rethink/)**：直接下载 APK，并提供手机扫码二维码。
- **[GitHub Releases](https://github.com/paulvers-ui/rethink-app-masque/releases)**：手动下载。

## 许可证

与原项目相同——详见 [LICENSE](LICENSE)。
